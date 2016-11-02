// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".proton.server.documentsubdbcollection");

#include "combiningfeedview.h"
#include "commit_and_wait_document_retriever.h"
#include "document_subdb_collection_initializer.h"
#include "documentsubdbcollection.h"
#include "icommitable.h"
#include "idocumentsubdb.h"
#include "maintenancecontroller.h"
#include "searchabledocsubdb.h"

#include <vespa/searchcore/proton/metrics/legacy_documentdb_metrics.h>
#include <vespa/searchcore/proton/reprocessing/i_reprocessing_task.h>

using proton::matching::SessionManager;
using search::index::Schema;
using search::SerialNum;
using vespa::config::search::core::ProtonConfig;

namespace proton {

DocumentSubDBCollection::DocumentSubDBCollection(
        IDocumentSubDB::IOwner &owner,
        search::transactionlog::SyncProxy &tlSyncer,
        const IGetSerialNum &getSerialNum,
        const DocTypeName &docTypeName,
        searchcorespi::index::IThreadingService &writeService,
        vespalib::ThreadExecutor &warmupExecutor,
        vespalib::ThreadStackExecutorBase &summaryExecutor,
        const search::common::FileHeaderContext &fileHeaderContext,
        MetricsWireService &metricsWireService,
        LegacyDocumentDBMetrics &metrics,
        matching::QueryLimiter &queryLimiter,
        const vespalib::Clock &clock,
        vespalib::Lock &configLock,
        const vespalib::string &baseDir,
        const ProtonConfig &protonCfg,
        const std::shared_ptr<vespalib::IHwInfo> &hwInfo)
    : _subDBs(),
      _calc(),
      _readySubDbId(0),
      _remSubDbId(1),
      _notReadySubDbId(2),
      _retrievers(),
      _reprocessingRunner(),
      _bucketDB(),
      _bucketDBHandler()
{
    const ProtonConfig::Grow & growCfg = protonCfg.grow;
    const ProtonConfig::Distribution & distCfg = protonCfg.distribution;
    _bucketDB = std::make_shared<BucketDBOwner>();
    _bucketDBHandler.reset(new bucketdb::BucketDBHandler(*_bucketDB));
    search::GrowStrategy searchableGrowth(growCfg.initial * distCfg.searchablecopies, growCfg.factor, growCfg.add);
    search::GrowStrategy removedGrowth(std::max(1024l, growCfg.initial/100), growCfg.factor, growCfg.add);
    search::GrowStrategy notReadyGrowth(growCfg.initial * (distCfg.redundancy - distCfg.searchablecopies), growCfg.factor, growCfg.add);
    size_t attributeGrowNumDocs(growCfg.numdocs);
    size_t numSearcherThreads = protonCfg.numsearcherthreads;

    StoreOnlyDocSubDB::Context context(owner,
                                       tlSyncer,
                                       getSerialNum,
                                       fileHeaderContext,
                                       writeService,
                                       summaryExecutor,
                                       _bucketDB,
                                       *_bucketDBHandler,
                                       metrics,
                                       configLock,
                                       hwInfo);
    _subDBs.push_back
        (new SearchableDocSubDB
            (SearchableDocSubDB::Config(FastAccessDocSubDB::Config
                (StoreOnlyDocSubDB::Config(docTypeName,
                        "0.ready",
                        baseDir,
                        searchableGrowth,
                        attributeGrowNumDocs,
                        _readySubDbId,
                        SubDbType::READY),
                        true,
                        true,
                        false),
                        numSearcherThreads),
                SearchableDocSubDB::Context(FastAccessDocSubDB::Context
                        (context,
                        metrics.ready.attributes,
                        &metrics.attributes,
                        metricsWireService),
                        queryLimiter,
                        clock,
                        warmupExecutor)));
    _subDBs.push_back
        (new StoreOnlyDocSubDB(StoreOnlyDocSubDB::Config(docTypeName,
                                                     "1.removed",
                                                     baseDir,
                                                     removedGrowth,
                                                     attributeGrowNumDocs,
                                                     _remSubDbId,
                                                     SubDbType::REMOVED),
                             context));
    _subDBs.push_back
        (new FastAccessDocSubDB(FastAccessDocSubDB::Config
                (StoreOnlyDocSubDB::Config(docTypeName,
                        "2.notready",
                        baseDir,
                        notReadyGrowth,
                        attributeGrowNumDocs,
                        _notReadySubDbId,
                        SubDbType::NOTREADY),
                        true,
                        true,
                        true),
                FastAccessDocSubDB::Context(context,
                        metrics.notReady.attributes,
                        NULL,
                        metricsWireService)));
}


DocumentSubDBCollection::~DocumentSubDBCollection()
{
    for (auto subDb : _subDBs) {
        delete subDb;
    }
    _bucketDB.reset();
}

void
DocumentSubDBCollection::createRetrievers()
{
    RetrieversSP retrievers(new std::vector<IDocumentRetriever::SP>);
    retrievers->resize(_subDBs.size());
    uint32_t i = 0;
    for (auto subDb : _subDBs) {
        (*retrievers)[i++].reset(subDb->getDocumentRetriever().release());
    }
    _retrievers.set(retrievers);
}

namespace {

IDocumentRetriever::SP
wrapRetriever(const IDocumentRetriever::SP &retriever,
              ICommitable &commit)
{
    return std::make_shared<CommitAndWaitDocumentRetriever>(retriever, commit);
}

}


void DocumentSubDBCollection::maintenanceSync(MaintenanceController &mc,
                                              ICommitable &commit) {
    RetrieversSP retrievers = getRetrievers();
    MaintenanceDocumentSubDB readySubDB(
            getReadySubDB()->getDocumentMetaStoreContext().getSP(),
            wrapRetriever((*retrievers)[_readySubDbId], commit),
            _readySubDbId);
    MaintenanceDocumentSubDB remSubDB(
            getRemSubDB()->getDocumentMetaStoreContext().getSP(),
            (*retrievers)[_remSubDbId],
            _remSubDbId);
    MaintenanceDocumentSubDB notReadySubDB(
            getNotReadySubDB()->getDocumentMetaStoreContext().getSP(),
            wrapRetriever((*retrievers)[_notReadySubDbId], commit),
            _notReadySubDbId);
    mc.syncSubDBs(readySubDB, remSubDB, notReadySubDB);
}

initializer::InitializerTask::SP
DocumentSubDBCollection::createInitializer(const DocumentDBConfig &configSnapshot,
                                           SerialNum configSerialNum,
                                           const Schema::SP &unionSchema,
                                           const ProtonConfig::Summary & protonSummaryCfg,
                                           const ProtonConfig::Index & indexCfg)
{
    DocumentSubDbCollectionInitializer::SP task =
        std::make_shared<DocumentSubDbCollectionInitializer>();
    for (auto subDb : _subDBs) {
        DocumentSubDbInitializer::SP
            subTask(subDb->createInitializer(configSnapshot,
                                             configSerialNum,
                                             unionSchema,
                                             protonSummaryCfg,
                                             indexCfg));
        task->add(subTask);
    }
    return task;
}


void
DocumentSubDBCollection::initViews(const DocumentDBConfig &configSnapshot,
                                   const SessionManager::SP &sessionManager)
{
    for (auto subDb : _subDBs) {
        subDb->initViews(configSnapshot, sessionManager);
    }
}


void
DocumentSubDBCollection::clearViews()
{
    for (auto subDb : _subDBs) {
        subDb->clearViews();
    }
}


void
DocumentSubDBCollection::onReplayDone()
{
    for (auto subDb : _subDBs) {
        subDb->onReplayDone();
    }
}


void
DocumentSubDBCollection::onReprocessDone(SerialNum serialNum)
{
    for (auto subDb : _subDBs) {
        subDb->onReprocessDone(serialNum);
    }
}


SerialNum
DocumentSubDBCollection::getOldestFlushedSerial()
{
    SerialNum lowest = -1;
    for (auto subDb : _subDBs) {
        lowest = std::min(lowest, subDb->getOldestFlushedSerial());
    }
    return lowest;
}


SerialNum
DocumentSubDBCollection::getNewestFlushedSerial()
{
    SerialNum highest = 0;
    for (auto subDb : _subDBs) {
        highest = std::max(highest, subDb->getNewestFlushedSerial());
    }
    return highest;
}


void
DocumentSubDBCollection::wipeHistory(SerialNum wipeSerial,
                                     const Schema &newHistorySchema,
                                     const Schema &wipeSchema)
{
    for (auto subDb : _subDBs) {
        subDb->wipeHistory(wipeSerial, newHistorySchema, wipeSchema);
    }
}


void
DocumentSubDBCollection::applyConfig(const DocumentDBConfig &newConfigSnapshot,
                                     const DocumentDBConfig &oldConfigSnapshot,
                                     SerialNum serialNum,
                                     const ReconfigParams params)
{
    _reprocessingRunner.reset();
    for (auto subDb : _subDBs) {
        IReprocessingTask::List tasks;
        tasks = subDb->applyConfig(newConfigSnapshot, oldConfigSnapshot,
                                   serialNum, params);
        _reprocessingRunner.addTasks(tasks);
    }
}

IFeedView::SP
DocumentSubDBCollection::getFeedView()
{
    std::vector<IFeedView::SP> views;
    views.reserve(_subDBs.size());

    for (const auto subDb : _subDBs) {
        views.push_back(subDb->getFeedView());
    }
    IFeedView::SP newFeedView;
    assert(views.size() >= 1);
    if (views.size() > 1) {
        return IFeedView::SP(new CombiningFeedView(views, _calc));
    } else {
        assert(views.front() != NULL);
        return views.front();
    }
}

IFlushTarget::List
DocumentSubDBCollection::getFlushTargets()
{
    IFlushTarget::List ret;
    for (auto subDb : _subDBs) {
        IFlushTarget::List iTargets(subDb->getFlushTargets());
        ret.insert(ret.end(), iTargets.begin(), iTargets.end());
    }
    return ret;
}

double
DocumentSubDBCollection::getReprocessingProgress() const
{
    return _reprocessingRunner.getProgress();
}

void
DocumentSubDBCollection::close()
{
    for (auto subDb : _subDBs) {
        subDb->close();
    }
}

} // namespace proton
