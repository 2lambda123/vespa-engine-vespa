// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include "match_thread.h"
#include "match_tools.h"
#include "handlerecorder.h"
#include "i_match_loop_communicator.h"
#include "matching_stats.h"
#include "document_scorer.h"
#include <memory>
#include <vespa/searchlib/common/featureset.h>
#include <vespa/searchlib/common/resultset.h>
#include <vespa/searchlib/fef/fef.h>
#include <vespa/searchlib/query/base.h>
#include <vespa/searchlib/queryeval/hitcollector.h>
#include <vespa/searchlib/queryeval/searchiterator.h>
#include <vespa/searchlib/queryeval/multibitvectoriterator.h>
#include <vespa/searchlib/queryeval/andnotsearch.h>
#include <vespa/vespalib/util/clock.h>
#include <vespa/vespalib/util/closure.h>
#include <vespa/vespalib/util/thread_bundle.h>
#include <vespa/log/log.h>
#include <cmath>

LOG_SETUP(".proton.matching.match_thread");

namespace proton {
namespace matching {

namespace {

using search::fef::FeatureHandle;
using search::fef::IllegalHandle;
using search::fef::RankProgram;

const double *get_score_feature(const RankProgram &rankProgram) {
    std::vector<vespalib::string> featureNames;
    std::vector<FeatureHandle> featureHandles;
    rankProgram.get_seed_handles(featureNames, featureHandles);
    assert(featureNames.size() == 1);
    assert(featureHandles.size() == 1);
    return rankProgram.match_data().resolveFeature(featureHandles.front());
}

}

using vespalib::Doom;
using search::queryeval::OptimizedAndNotForBlackListing;

template <typename IteratorT, bool do_rank, bool do_limit, bool do_share_work>
void
MatchThread::match_loop(MatchTools &matchTools, IteratorT search,
                        RankProgram &ranking, HitCollector &hits)
{
    const Doom &doom = matchTools.doom();
    const double *score_feature = do_rank ? get_score_feature(ranking) : nullptr;
    uint32_t matches = 0;
    uint32_t matches_limit = (do_limit) ? matchTools.match_limiter().sample_hits_per_thread(num_threads) : 0;
    IdleObserver idle_observer = scheduler.make_idle_observer();
    for (DocidRange docid_range = scheduler.first_range(thread_id);
         !docid_range.empty();
         docid_range = scheduler.next_range(thread_id))
    {
        search->initRange(docid_range.begin, docid_range.end);
        uint32_t docId = search->seekFirst(docid_range.begin);
        while ((docId < docid_range.end) && !doom.doom()) {
            if (do_rank) {
                search->unpack(docId);
                ranking.run(docId);
                double score = *score_feature;
                // convert NaN and Inf scores to -Inf
                if (__builtin_expect(std::isnan(score) || std::isinf(score), false)) {
                    score = -HUGE_VAL;
                }
                // invert test since default drop limit is -NaN (keep all hits)
                if (!(score <= matchParams.rankDropLimit)) {
                    hits.addHit(docId, score);
                }
            } else {
                hits.addHit(docId, 0.0);
            }
            ++matches;
            if (do_limit && matches == matches_limit) {
                const size_t local_todo = (docid_range.end - docId - 1);
                const size_t searchedSoFar = (scheduler.total_size(thread_id) - local_todo);
                IMatchLoopCommunicator::Matches my_matches(matches, searchedSoFar);
                WaitTimer count_matches_timer(wait_time_s);
                double match_freq = communicator.estimate_match_frequency(my_matches);
                const size_t global_todo = scheduler.unassigned_size();
                count_matches_timer.done();
                search.reset(matchTools.match_limiter().maybe_limit(SearchIterator::UP(search.release()),
                                match_freq,
                                matchParams.numDocs).release());
                matchTools.match_limiter().updateDocIdSpaceEstimate(searchedSoFar, local_todo + (global_todo / num_threads));
                LOG(debug, "Limit=%d has been reached at docid=%d which is after %zu docs.",
                    matches, docId, searchedSoFar);
                LOG(debug, "SearchIterator after limiter: %s", search->asString().c_str());
                docId = search->seekFirst(docId + 1);
            } else if (do_share_work && (idle_observer.get() > 0)) {
                DocidRange todo(docId + 1, docid_range.end);
                DocidRange my_work = scheduler.share_range(thread_id, todo);
                if (my_work.end < todo.end) {
                    docid_range = my_work;
                    search->initRange(docid_range.begin, docid_range.end);
                    docId = search->seekFirst(docid_range.begin);
                } else {
                    docId = search->seekNext(docId + 1);
                }
            } else {
                docId = search->seekNext(docId + 1);
            }
        }
    }
    if (do_limit && matches < matches_limit) {
        IMatchLoopCommunicator::Matches my_matches(matches, scheduler.total_size(thread_id));
        WaitTimer count_matches_timer(wait_time_s);
        LOG(debug, "Limit not reached (had %d) at docid=%d which is after %zu docs.",
            matches, scheduler.total_span(thread_id).end, scheduler.total_size(thread_id));
        communicator.estimate_match_frequency(my_matches); // for other threads
        matchTools.match_limiter().updateDocIdSpaceEstimate(scheduler.total_size(thread_id), 0);
        count_matches_timer.done();
    }
    thread_stats.docsMatched(matches);
    if (do_rank) {
        thread_stats.docsRanked(matches);
    }
}

template <typename IteratorT, bool do_rank, bool do_limit>
void
MatchThread::match_loop_helper_2(MatchTools &matchTools, IteratorT search,
                                 RankProgram &ranking, HitCollector &hits)
{
    if (scheduler.make_idle_observer().is_always_zero()) {
        match_loop<IteratorT, do_rank, do_limit, false>(matchTools, std::move(search), ranking, hits);
    } else {
        match_loop<IteratorT, do_rank, do_limit, true>(matchTools, std::move(search), ranking, hits);
    }
}

template <typename IteratorT, bool do_rank>
void
MatchThread::match_loop_helper(MatchTools &matchTools, IteratorT search,
                               RankProgram &ranking, HitCollector &hits)
{
    if (matchTools.match_limiter().is_enabled()) {
        match_loop_helper_2<IteratorT, do_rank, true>(matchTools, std::move(search), ranking, hits);
    } else {
        match_loop_helper_2<IteratorT, do_rank, false>(matchTools, std::move(search), ranking, hits);
    }
}

using search::fef::TermFieldHandle;
using search::fef::MatchData;

class FastSeekWrapper
{
private:
    typedef search::queryeval::SearchIterator SearchIterator;
public:
    FastSeekWrapper(SearchIterator::UP iterator)
    {
        reset(iterator.release());
    }
    void initRange(uint32_t begin_id, uint32_t end_id) {
        _search->initRange(begin_id, end_id);
    }
    uint32_t seekFirst(uint32_t docId) {
        return _search->seekFirst(docId);
    }
    uint32_t seekNext(uint32_t docId) {
        return _search->seekFast(docId);
    }
    vespalib::string asString() const {
        return _search->asString();
    }
    void unpack(uint32_t docId) {
        _search->unpack(docId);
    }
    void reset(SearchIterator * search) {
        _search.reset(&dynamic_cast<OptimizedAndNotForBlackListing &>(*search));
    }
    OptimizedAndNotForBlackListing * release() {
        return _search.release();
    }
    FastSeekWrapper * operator ->() { return this; }
private:
    std::unique_ptr<OptimizedAndNotForBlackListing> _search;
};

MatchThread::MatchThread(size_t thread_id_in,
                         size_t num_threads_in,
                         const MatchParams &mp,
                         const MatchToolsFactory &mtf,
                         IMatchLoopCommunicator &com,
                         DocidRangeScheduler &sched,
                         ResultProcessor &rp,
                         vespalib::DualMergeDirector &md,
                         uint32_t distributionKey) :
    thread_id(thread_id_in),
    num_threads(num_threads_in),
    matchParams(mp),
    matchToolsFactory(mtf),
    communicator(com),
    scheduler(sched),
    _distributionKey(distributionKey),
    resultProcessor(rp),
    mergeDirector(md),
    resultContext(),
    thread_stats(),
    total_time_s(0.0),
    match_time_s(0.0),
    wait_time_s(0.0)
{
}

search::ResultSet::UP
MatchThread::findMatches(MatchTools &matchTools)
{
    const Doom &doom = matchTools.doom();
    RankProgram::UP ranking = matchTools.first_phase_program();
    SearchIterator::UP search = matchTools.createSearch(ranking->match_data());
    LOG(debug, "SearchIterator: %s", search->asString().c_str());
    search = search::queryeval::MultiBitVectorIteratorBase::optimize(std::move(search));
    LOG(debug, "SearchIterator after MultiBitVectorIteratorBase::optimize(): %s", search->asString().c_str());
    HitCollector hits(matchParams.numDocs, matchParams.arraySize, matchParams.heapSize);
    if (matchTools.has_first_phase_rank() && ((matchParams.arraySize + matchParams.heapSize) != 0)) {
        match_loop_helper<SearchIterator::UP, true>(matchTools, std::move(search), *ranking, hits);
    } else {
        if ((dynamic_cast<const OptimizedAndNotForBlackListing *>(search.get()) != 0) &&
            ! matchTools.match_limiter().is_enabled())  // We cannot replace the iterator if we inline the loop.
        {
            match_loop_helper<FastSeekWrapper, false>(matchTools, FastSeekWrapper(std::move(search)), *ranking, hits);
        } else {
            match_loop_helper<SearchIterator::UP, false>(matchTools, std::move(search), *ranking, hits);
        }
    }
    if (matchTools.has_second_phase_rank()) {
        { // 2nd phase ranking
            ranking = matchTools.second_phase_program();
            search = matchTools.createSearch(ranking->match_data());
            DocidRange docid_range = scheduler.total_span(thread_id);
            search->initRange(docid_range.begin, docid_range.end);
            auto sorted_scores = hits.getSortedHeapScores();
            WaitTimer select_best_timer(wait_time_s);
            size_t useHits = communicator.selectBest(sorted_scores);
            select_best_timer.done();
            DocumentScorer scorer(*ranking, *search);
            uint32_t reRanked = hits.reRank(scorer, doom.doom() ? 0 : useHits);
            thread_stats.docsReRanked(reRanked);
        }
        { // rank scaling
            auto my_ranges = hits.getRanges();
            WaitTimer range_cover_timer(wait_time_s);
            auto ranges = communicator.rangeCover(my_ranges);
            range_cover_timer.done();
            hits.setRanges(ranges);
        }
    }
    return hits.getResultSet();
}

void
MatchThread::processResult(const Doom & doom, 
                           search::ResultSet::UP result,
                           ResultProcessor::Context &context)
{
    if (doom.doom()) return;
    bool hasGrouping = (context.grouping.get() != 0);
    if (context.sort->hasSortData() || hasGrouping) {
        result->mergeWithBitOverflow();
    }
    if (doom.doom()) return;
    size_t             totalHits = result->getNumHits();
    search::RankedHit *hits      = result->getArray();
    size_t             numHits   = result->getArrayUsed();
    search::BitVector *bits  = result->getBitOverflow();
    if (bits != nullptr && hits != nullptr) {
        bits->andNotWithT(search::RankedHitIterator(hits, numHits));
    }
    if (doom.doom()) return;
    if (hasGrouping) {
        search::grouping::GroupingManager man(*context.grouping);
        man.groupUnordered(hits, numHits, bits);
    }
    if (doom.doom()) return;
    size_t sortLimit = hasGrouping ? numHits : context.result->maxSize();
    context.sort->sorter->sortResults(hits, numHits, sortLimit);
    if (doom.doom()) return;
    if (hasGrouping) {
        search::grouping::GroupingManager man(*context.grouping);
        man.groupInRelevanceOrder(hits, numHits);
    }
    if (doom.doom()) return;
    PartialResult &pr = *context.result;
    pr.totalHits(totalHits);
    size_t maxHits = std::min(numHits, pr.maxSize());
    if (pr.hasSortData()) {
        FastS_SortSpec &spec = context.sort->sortSpec;
        for (size_t i = 0; i < maxHits; ++i) {
            pr.add(hits[i], spec.getSortRef(i));
        }
    } else {
        for (size_t i = 0; i < maxHits; ++i) {
            pr.add(hits[i]);
        }
        if ((bits != nullptr) && (pr.size() < pr.maxSize())) {
            for (unsigned int bitId = bits->getFirstTrueBit();
                 (bitId < bits->size()) && (pr.size() < pr.maxSize());
                 bitId = bits->getNextTrueBit(bitId + 1))
            {
                pr.add(search::RankedHit(bitId));
            }
        }
    }
    if (hasGrouping) {
        context.grouping->setDistributionKey(_distributionKey);
    }
}

void
MatchThread::run()
{
    fastos::StopWatch total_time;
    fastos::StopWatch match_time;
    total_time.start();
    match_time.start();
    MatchTools::UP matchTools = matchToolsFactory.createMatchTools();
    search::ResultSet::UP result = findMatches(*matchTools);
    match_time.stop();
    match_time_s = match_time.elapsed().sec();
    resultContext = resultProcessor.createThreadContext(matchTools->doom(), thread_id);
    {
        WaitTimer get_token_timer(wait_time_s);
        QueryLimiter::Token::UP processToken(
                matchTools->getQueryLimiter().getToken(matchTools->doom(),
                        scheduler.total_size(thread_id),
                        result->getNumHits(),
                        resultContext->sort->hasSortData(),
                        resultContext->grouping.get() != 0));
        get_token_timer.done();
        processResult(matchTools->doom(), std::move(result), *resultContext);
    }
    total_time.stop();
    total_time_s = total_time.elapsed().sec();
    thread_stats.active_time(total_time_s - wait_time_s).wait_time(wait_time_s);
    mergeDirector.dualMerge(thread_id, *resultContext->result, resultContext->groupingSource);
}

} // namespace proton::matching
} // namespace proton
