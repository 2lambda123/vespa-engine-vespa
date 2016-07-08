// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".proton.matching.matcher");

#include "isearchcontext.h"
#include "match_master.h"
#include "match_params.h"
#include "matcher.h"
#include "query.h"
#include "queryenvironment.h"
#include "result_processor.h"
#include "sessionmanager.h"
#include <vespa/searchlib/common/resultset.h>
#include <vespa/searchlib/engine/errorcodes.h>
#include <vespa/searchlib/features/setup.h>
#include <vespa/searchlib/fef/properties.h>
#include <vespa/searchlib/fef/test/plugin/setup.h>
#include <vespa/searchlib/queryeval/searchiterator.h>
#include <vespa/vespalib/util/exceptions.h>

using search::fef::Properties;
using namespace search;
using namespace search::engine;
using namespace search::grouping;
using search::attribute::IAttributeContext;
using search::fef::MatchDataLayout;
using search::fef::MatchData;
using search::queryeval::Blueprint;
using search::queryeval::SearchIterator;
using vespalib::Doom;

namespace proton {
namespace matching {

namespace {

// used to give out empty blacklist blueprints
struct StupidMetaStore : IDocumentMetaStore {
    bool getGid(DocId, GlobalId &) const override { return false; }
    bool getLid(const GlobalId &, DocId &) const override { return false; }
    DocumentMetaData getMetaData(const GlobalId &) const override { return DocumentMetaData(); }
    void getMetaData(const BucketId &, DocumentMetaData::Vector &) const override { }
    DocId getCommittedDocIdLimit() const override { return 1; }
    DocId getNumUsedLids() const override { return 0; }
    DocId getNumActiveLids() const override { return 0; }
    LidUsageStats getLidUsageStats() const override { return LidUsageStats(); }
    Blueprint::UP createBlackListBlueprint() const override {
        return Blueprint::UP();
    }
};

FeatureSet::SP findFeatureSet(const DocsumRequest &req,
                              MatchToolsFactory &mtf, bool summaryFeatures) {
    std::vector<uint32_t> docs;
    for (size_t i = 0; i < req.hits.size(); ++i) {
        if (req.hits[i].docid != search::endDocId) {
            docs.push_back(req.hits[i].docid);
        }
    }
    std::sort(docs.begin(), docs.end());
    return MatchMaster::getFeatureSet(mtf, docs, summaryFeatures);
}

size_t numThreads(size_t hits, size_t minHits) {
    return static_cast<size_t>(std::ceil(double(hits) / double(minHits)));
}

class LimitedThreadBundleWrapper final : public vespalib::ThreadBundle
{
public:
    LimitedThreadBundleWrapper(vespalib::ThreadBundle &threadBundle, uint32_t maxThreads) :
        _threadBundle(threadBundle),
        _maxThreads(std::min(maxThreads, static_cast<uint32_t>(threadBundle.size())))
    { }
private:
    size_t size() const override { return _maxThreads; }
    void run(const std::vector<vespalib::Runnable*> &targets) override {
        _threadBundle.run(targets);
    }
    vespalib::ThreadBundle &_threadBundle;
    const uint32_t          _maxThreads;
};

bool willNotNeedRanking(const SearchRequest & request, const GroupingContext & groupingContext) {
    return (!groupingContext.needRanking() && (request.maxhits == 0))
           || (!request.sortSpec.empty() && (request.sortSpec.find("[rank]") == vespalib::string::npos));
}

}  // namespace proton::matching::<unnamed>

FeatureSet::SP
Matcher::getFeatureSet(const DocsumRequest & req,
                       ISearchContext & searchCtx,
                       IAttributeContext & attrCtx,
                       SessionManager & sessionMgr,
                       bool summaryFeatures)
{
    SessionId sessionId(&req.sessionId[0], req.sessionId.size());
    if (!sessionId.empty()) {
        const Properties &cache_props = req.propertiesMap.cacheProperties();
        bool searchSessionCached = cache_props.lookup("query").found();
        if (searchSessionCached) {
            SearchSession::SP session(sessionMgr.pickSearch(sessionId));
            if (session.get()) {
                MatchToolsFactory &mtf = session->getMatchToolsFactory();
                FeatureSet::SP result = findFeatureSet(req, mtf, summaryFeatures);
                session->releaseEnumGuards();
                return result;
            }
        }
    }

    StupidMetaStore metaStore;
    MatchToolsFactory mtf(_queryLimiter, Doom(_clock, req.getTimeOfDoom()), searchCtx, attrCtx,
                          req.getStackRef(), req.location, _viewResolver,
                          metaStore, _indexEnv,
                          *_rankSetup, req.propertiesMap.rankProperties(),
                          req.propertiesMap.featureOverrides());
    if (!mtf.valid()) {
        LOG(warning, "getFeatureSet(%s): query execution failed "
            "(invalid query). Returning empty feature set",
            (summaryFeatures ? "summary features" : "rank features"));
        return FeatureSet::SP(new FeatureSet());
    }
    return findFeatureSet(req, mtf, summaryFeatures);
}

Matcher::Matcher(const index::Schema &schema,
                 const Properties &props,
                 const vespalib::Clock & clock,
                 QueryLimiter & queryLimiter,
                 uint32_t distributionKey)
    : _indexEnv(schema, props),
      _blueprintFactory(),
      _rankSetup(),
      _viewResolver(ViewResolver::createFromSchema(schema)),
      _statsLock(),
      _stats(),
      _clock(clock),
      _queryLimiter(queryLimiter),
      _distributionKey(distributionKey)
{
    features::setup_search_features(_blueprintFactory);
    fef::test::setup_fef_test_plugin(_blueprintFactory);
    _rankSetup.reset(new fef::RankSetup(_blueprintFactory, _indexEnv));
    _rankSetup->configure(); // reads config values from the property map
    if (!_rankSetup->compile()) {
        throw vespalib::IllegalArgumentException("failed to compile rank setup", VESPA_STRLOC);
    }
}

MatchingStats
Matcher::getStats()
{
    vespalib::LockGuard guard(_statsLock);
    MatchingStats stats = _stats;
    _stats = MatchingStats();
    return stats;
}

SearchReply::UP
Matcher::handleGroupingSession(SessionManager &sessionMgr,
                               GroupingContext & groupingContext,
                               GroupingSession::UP groupingSession)
{
    SearchReply::UP reply = std::make_unique<SearchReply>();
    groupingSession->continueExecution(groupingContext);
    groupingContext.getResult().swap(reply->groupResult);
    if (!groupingSession->finished()) {
        sessionMgr.insert(std::move(groupingSession));
    }
    return reply;
}

size_t Matcher::computeNumThreadsPerSearch(Blueprint::HitEstimate hits) const {
    size_t threads = _rankSetup->getNumThreadsPerSearch();
    if ((threads > 1) && (_rankSetup->getMinHitsPerThread() > 0)) {
        threads = (hits.empty) ? 1 : std::min(threads, numThreads(hits.estHits, _rankSetup->getMinHitsPerThread()));
    }
    return threads;
}

SearchReply::UP
Matcher::match(const SearchRequest &request,
               vespalib::ThreadBundle &threadBundle,
               ISearchContext &searchContext,
               IAttributeContext &attrContext,
               SessionManager &sessionMgr,
               const IDocumentMetaStore &metaStore,
               SearchSession::OwnershipBundle &&owned_objects)
{
    fastos::StopWatch total_matching_time;
    total_matching_time.start();
    MatchingStats my_stats;
    SearchReply::UP reply = std::make_unique<SearchReply>();
    { // we want to measure full set-up and tear-down time as part of
      // collateral time
        GroupingContext groupingContext(_clock, request.getTimeOfDoom(), &request.groupSpec[0], request.groupSpec.size());
        SessionId sessionId(&request.sessionId[0], request.sessionId.size());
        bool shouldCacheSearchSession = false;
        bool shouldCacheGroupingSession = false;
        if (!sessionId.empty()) {
            const Properties &cache_props = request.propertiesMap.cacheProperties();
            shouldCacheGroupingSession = cache_props.lookup("grouping").found();
            shouldCacheSearchSession = cache_props.lookup("query").found();
            if (shouldCacheGroupingSession) {
                GroupingSession::UP session(sessionMgr.pickGrouping(sessionId));
                if (session.get()) {
                    return handleGroupingSession(sessionMgr, groupingContext, std::move(session));
                }
            }
        }
        const Properties *feature_overrides = &request.propertiesMap.featureOverrides();
        if (shouldCacheSearchSession) {
            owned_objects.feature_overrides.reset(new Properties(*feature_overrides));
            feature_overrides = owned_objects.feature_overrides.get();
        }
        MatchToolsFactory::UP mtf = create_match_tools_factory(request, searchContext, attrContext, metaStore, *feature_overrides);
        if (!mtf->valid()) {
            reply->errorCode = ECODE_QUERY_PARSE_ERROR;
            reply->errorMessage = "query execution failed (invalid query)";
            return reply;
        }

        MatchParams params(searchContext.getDocIdLimit(), _rankSetup->getHeapSize(), _rankSetup->getArraySize(),
                           _rankSetup->getRankScoreDropLimit(), request.offset, request.maxhits,
                           !_rankSetup->getSecondPhaseRank().empty(), !willNotNeedRanking(request, groupingContext));

        ResultProcessor rp(attrContext, metaStore, sessionMgr, groupingContext, sessionId, request.sortSpec, params.offset, params.hits);

        size_t numThreadsPerSearch = computeNumThreadsPerSearch(mtf->estimate());
        LimitedThreadBundleWrapper limitedThreadBundle(threadBundle, numThreadsPerSearch);
        MatchMaster master;
        ResultProcessor::Result::UP result = master.match(params, limitedThreadBundle, *mtf, rp, _distributionKey, _rankSetup->getNumSearchPartitions());
        my_stats = master.getStats();
        size_t estimate = std::min(static_cast<size_t>(metaStore.getCommittedDocIdLimit()), mtf->match_limiter().getDocIdSpaceEstimate());
        if (shouldCacheSearchSession && ((result->_numFs4Hits != 0) || shouldCacheGroupingSession)) {
            SearchSession::SP session = std::make_shared<SearchSession>(sessionId, request.getTimeOfDoom(), std::move(mtf), std::move(owned_objects));
            session->releaseEnumGuards();
            sessionMgr.insert(std::move(session));
        }
        reply = std::move(result->_reply);
        reply->useCoverage = true;
        reply->coverage.setActive(metaStore.getNumActiveLids());
        reply->coverage.setCovered(std::min(static_cast<size_t>(metaStore.getNumActiveLids()),
                                            (estimate * metaStore.getNumActiveLids())/metaStore.getCommittedDocIdLimit()));
        LOG(debug, "numThreadsPerSearch = %d. Configured = %d, estimated hits=%d, totalHits=%ld",
                   numThreadsPerSearch, _rankSetup->getNumThreadsPerSearch(), mtf->estimate().estHits, reply->totalHitCount);
    }
    total_matching_time.stop();
    my_stats.queryCollateralTime(total_matching_time.elapsed().sec() - my_stats.queryLatencyAvg());
    {
        vespalib::LockGuard guard(_statsLock);
        _stats.add(my_stats);
    }
    return reply;
}

FeatureSet::SP
Matcher::getSummaryFeatures(const DocsumRequest & req,
                            ISearchContext & searchCtx,
                            IAttributeContext & attrCtx,
                            SessionManager &sessionMgr)
{
    return getFeatureSet(req, searchCtx, attrCtx, sessionMgr, true);
}

FeatureSet::SP
Matcher::getRankFeatures(const DocsumRequest & req,
                         ISearchContext & searchCtx,
                         IAttributeContext & attrCtx,
                         SessionManager &sessionMgr)
{
    return getFeatureSet(req, searchCtx, attrCtx, sessionMgr, false);
}

} // namespace matching
} // namespace proton
