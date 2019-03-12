// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "match_master.h"
#include "docid_range_scheduler.h"
#include "match_loop_communicator.h"
#include "match_thread.h"
#include <vespa/searchlib/attribute/attribute_operation.h>
#include <vespa/searchlib/engine/trace.h>
#include <vespa/searchlib/common/featureset.h>
#include <vespa/vespalib/util/thread_bundle.h>
#include <vespa/vespalib/data/slime/inserter.h>
#include <vespa/vespalib/data/slime/inject.h>
#include <vespa/vespalib/data/slime/cursor.h>

#include <vespa/log/log.h>
LOG_SETUP(".proton.matching.match_master");

namespace proton::matching {

using namespace search::fef;
using search::queryeval::SearchIterator;
using search::FeatureSet;
using search::attribute::AttributeOperation;

namespace {

struct TimedMatchLoopCommunicator : IMatchLoopCommunicator {
    IMatchLoopCommunicator &communicator;
    fastos::StopWatch rerank_time;
    TimedMatchLoopCommunicator(IMatchLoopCommunicator &com) : communicator(com) {}
    double estimate_match_frequency(const Matches &matches) override {
        return communicator.estimate_match_frequency(matches);
    }
    Hits selectBest(SortedHitSequence sortedHits) override {
        auto result = communicator.selectBest(sortedHits);
        rerank_time.start();
        return result;
    }
    RangePair rangeCover(const RangePair &ranges) override {
        RangePair result = communicator.rangeCover(ranges);
        rerank_time.stop();
        return result;
    }
};

DocidRangeScheduler::UP
createScheduler(uint32_t numThreads, uint32_t numSearchPartitions, uint32_t numDocs)
{
    if (numSearchPartitions == 0) {
        return std::make_unique<AdaptiveDocidRangeScheduler>(numThreads, 1, numDocs);
    }
    if (numSearchPartitions <= numThreads) {
        return std::make_unique<PartitionDocidRangeScheduler>(numThreads, numDocs);
    }
    return std::make_unique<TaskDocidRangeScheduler>(numThreads, numSearchPartitions, numDocs);
}

} // namespace proton::matching::<unnamed>

ResultProcessor::Result::UP
MatchMaster::match(search::engine::Trace & trace,
                   const MatchParams &params,
                   vespalib::ThreadBundle &threadBundle,
                   const MatchToolsFactory &mtf,
                   ResultProcessor &resultProcessor,
                   uint32_t distributionKey,
                   uint32_t numSearchPartitions)
{
    fastos::StopWatch query_latency_time;
    query_latency_time.start();
    vespalib::DualMergeDirector mergeDirector(threadBundle.size());
    MatchLoopCommunicator communicator(threadBundle.size(), params.heapSize, mtf.createDiversifier(params.heapSize));
    TimedMatchLoopCommunicator timedCommunicator(communicator);
    DocidRangeScheduler::UP scheduler = createScheduler(threadBundle.size(), numSearchPartitions, params.numDocs);

    std::vector<MatchThread::UP> threadState;
    std::vector<vespalib::Runnable*> targets;
    for (size_t i = 0; i < threadBundle.size(); ++i) {
        IMatchLoopCommunicator &com = (i == 0)
                ? static_cast<IMatchLoopCommunicator&>(timedCommunicator)
                : static_cast<IMatchLoopCommunicator&>(communicator);
        threadState.emplace_back(std::make_unique<MatchThread>(i, threadBundle.size(), params, mtf, com, *scheduler,
                                                               resultProcessor, mergeDirector, distributionKey,
                                                               trace.getRelativeTime(), trace.getLevel()));
        targets.push_back(threadState.back().get());
    }
    resultProcessor.prepareThreadContextCreation(threadBundle.size());
    threadBundle.run(targets);
    ResultProcessor::Result::UP reply = resultProcessor.makeReply(threadState[0]->extract_result());
    query_latency_time.stop();
    double query_time_s = query_latency_time.elapsed().sec();
    double rerank_time_s = timedCommunicator.rerank_time.elapsed().sec();
    double match_time_s = 0.0;
    std::unique_ptr<vespalib::slime::Inserter> inserter;
    if (trace.shouldTrace(4)) {
        inserter = std::make_unique<vespalib::slime::ArrayInserter>(trace.createCursor("match_threads").setArray("threads"));
    }
    for (size_t i = 0; i < threadState.size(); ++i) {
        const MatchThread & matchThread = *threadState[i];
        match_time_s = std::max(match_time_s, matchThread.get_match_time());
        _stats.merge_partition(matchThread.get_thread_stats(), i);
        if (inserter && matchThread.getTrace()) {
            vespalib::slime::inject(matchThread.getTrace()->getRoot(), *inserter);
        }
    }
    _stats.queryLatency(query_time_s);
    _stats.matchTime(match_time_s - rerank_time_s);
    _stats.rerankTime(rerank_time_s);
    _stats.groupingTime(query_time_s - match_time_s);
    _stats.queries(1);
    if (mtf.match_limiter().was_limited()) {
        _stats.limited_queries(1);        
    }
    return reply;
}

FeatureSet::SP
MatchMaster::getFeatureSet(const MatchToolsFactory &mtf,
                           const std::vector<uint32_t> &docs, bool summaryFeatures)
{
    MatchTools::UP matchTools = mtf.createMatchTools();
    if (summaryFeatures) {
        matchTools->setup_summary();
    } else {
        matchTools->setup_dump();
    }
    RankProgram &rankProgram = matchTools->rank_program();

    std::vector<vespalib::string> featureNames;
    FeatureResolver resolver(rankProgram.get_seeds());
    featureNames.reserve(resolver.num_features());
    for (size_t i = 0; i < resolver.num_features(); ++i) {
        featureNames.emplace_back(resolver.name_of(i));
    }
    auto retval = std::make_shared<FeatureSet>(featureNames, docs.size());
    if (docs.empty()) {
        return retval;
    }
    FeatureSet &fs = *retval;

    SearchIterator &search = matchTools->search();
    search.initRange(docs.front(), docs.back()+1);
    for (uint32_t i = 0; i < docs.size(); ++i) {
        if (search.seek(docs[i])) {
            uint32_t docId = search.getDocId();
            search.unpack(docId);
            search::feature_t * f = fs.getFeaturesByIndex(fs.addDocId(docId));
            for (uint32_t j = 0; j < featureNames.size(); ++j) {
                f[j] = resolver.resolve(j).as_number(docId);
            }
        } else {
            LOG(debug, "getFeatureSet: Did not find hit for docid '%u'. Skipping hit", docs[i]);
        }
    }
    if (auto onSummaryTask = mtf.createOnSummaryTask()) {
        onSummaryTask->run(docs);
    }
    return retval;
}

}
