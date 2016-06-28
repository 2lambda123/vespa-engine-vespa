// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP("maintenancecontroller_test");
#include <vespa/searchcore/proton/test/test.h>
#include <vespa/searchlib/index/docbuilder.h>
#include <vespa/vespalib/testkit/testapp.h>
#include <vespa/searchcore/proton/attribute/attribute_usage_filter.h>
#include <vespa/searchcore/proton/attribute/i_attribute_manager.h>
#include <vespa/searchcore/proton/common/doctypename.h>
#include <vespa/searchcore/proton/common/feedtoken.h>
#include <vespa/searchcore/proton/server/idocumentmovehandler.h>
#include <vespa/searchcore/proton/server/executor_thread_service.h>
#include <vespa/searchcore/proton/server/i_operation_storer.h>
#include <vespa/searchcore/proton/server/ipruneremoveddocumentshandler.h>
#include <vespa/searchcore/proton/server/iheartbeathandler.h>
#include <vespa/searchcore/proton/server/maintenance_controller_explorer.h>
#include <vespa/searchcore/proton/server/maintenance_jobs_injector.h>
#include <vespa/searchcore/proton/server/maintenancecontroller.h>
#include <vespa/searchcore/proton/server/ibucketmodifiedhandler.h>
#include <vespa/searchlib/attribute/attributeguard.h>
#include <vespa/searchlib/attribute/attributecontext.h>
#include <vespa/searchlib/common/idocumentmetastore.h>
#include <vespa/vespalib/util/closuretask.h>
#include <vespa/vespalib/util/sync.h>
#include <vespa/searchcore/proton/feedoperation/moveoperation.h>
#include <vespa/searchcore/proton/test/clusterstatehandler.h>
#include <vespa/searchcore/proton/test/buckethandler.h>
#include <vespa/searchcore/proton/test/disk_mem_usage_notifier.h>
#include <vespa/vespalib/util/threadstackexecutor.h>

using namespace proton;
using namespace vespalib::slime;
using document::BucketId;
using document::Document;
using document::DocumentId;
using fastos::ClockSystem;
using fastos::TimeStamp;
using search::AttributeGuard;
using search::DocumentIdT;
using search::DocumentMetaData;
using search::SerialNum;
using storage::spi::BucketInfo;
using storage::spi::Timestamp;
using vespalib::makeTask;
using vespalib::makeClosure;
using vespalib::Slime;
using proton::matching::ISessionCachePruner;

typedef BucketId::List BucketIdVector;
typedef std::set<BucketId> BucketIdSet;

constexpr int TIMEOUT_MS = 60000;
constexpr double TIMEOUT_SEC = 60.0;

namespace
{

void
sampleThreadId(FastOS_ThreadId *threadId)
{
    *threadId = FastOS_Thread::GetCurrentThreadId();
}

}  // namespace


class MyDocumentSubDB
{
    typedef std::map<DocumentIdT, Document::SP> DocMap;
    DocMap _docs;
    uint32_t _subDBId;
    DocumentMetaStore::SP _metaStoreSP;
    DocumentMetaStore & _metaStore;
    const document::DocumentTypeRepo::SP &_repo;
    const DocTypeName &_docTypeName;

public:
    MyDocumentSubDB(uint32_t subDBId,
                    SubDbType subDbType,
                    const document::DocumentTypeRepo::SP &repo,
                    std::shared_ptr<BucketDBOwner> bucketDB,
                    const DocTypeName &docTypeName)
        : _docs(),
          _subDBId(subDBId),
          _metaStoreSP(std::make_shared<DocumentMetaStore>(bucketDB,
                       DocumentMetaStore::getFixedName(),
                       search::GrowStrategy(),
                       DocumentMetaStore::IGidCompare::SP(new DocumentMetaStore::DefaultGidCompare),
                       subDbType)),
          _metaStore(*_metaStoreSP),
          _repo(repo),
          _docTypeName(docTypeName)
    {
        _metaStore.constructFreeList();
    }

    uint32_t
    getSubDBId(void) const
    {
        return _subDBId;
    }

    Document::UP
    getDocument(DocumentIdT lid) const
    {
        DocMap::const_iterator it(_docs.find(lid));
        if (it != _docs.end()) {
            return Document::UP(it->second->clone());
        } else {
            return Document::UP();
        }
    }

    MaintenanceDocumentSubDB
    getSubDB(void);

    void
    handlePruneRemovedDocuments(const PruneRemovedDocumentsOperation &op);

    void
    handlePut(PutOperation &op);

    void
    handleRemove(RemoveOperation &op);

    void
    prepareMove(MoveOperation &op);

    void
    handleMove(const MoveOperation &op);

    uint32_t
    getNumUsedLids(void) const;

    uint32_t
    getDocumentCount(void) const
    {
        return _docs.size();
    }

    void setBucketState(const BucketId &bucket, bool active) {
        _metaStore.setBucketState(bucket, active);
    }

    const IDocumentMetaStore &
    getMetaStore() const
    {
        return _metaStore;
    }
};


struct MyDocumentRetriever : public DocumentRetrieverBaseForTest
{
    MyDocumentSubDB &_subDB;

    MyDocumentRetriever(MyDocumentSubDB &subDB)
        : _subDB(subDB)
    {
    }

    virtual const document::DocumentTypeRepo &
    getDocumentTypeRepo(void) const
    {
        abort();
    }

    virtual void
    getBucketMetaData(const storage::spi::Bucket &,
                      DocumentMetaData::Vector &) const
    {
        abort();
    }
    virtual DocumentMetaData
    getDocumentMetaData(const DocumentId &) const
    {
        return DocumentMetaData();
    }

    virtual Document::UP
    getDocument(DocumentIdT lid) const
    {
        return _subDB.getDocument(lid);
    }

    virtual CachedSelect::SP
    parseSelect(const vespalib::string &) const
    {
        return CachedSelect::SP();
    }
};


struct MyBucketModifiedHandler : public IBucketModifiedHandler
{
    BucketIdVector _modified;
    virtual void notifyBucketModified(const BucketId &bucket) {
        BucketIdVector::const_iterator itr = std::find(_modified.begin(), _modified.end(), bucket);
        _modified.push_back(bucket);
    }
    void reset() { _modified.clear(); }
};


struct MySessionCachePruner : public ISessionCachePruner
{
    bool isInvoked;
    MySessionCachePruner() : isInvoked(false) { }
    void pruneTimedOutSessions(fastos::TimeStamp current) {
        (void) current;
        isInvoked = true;
    }
};


class MyFeedHandler : public IDocumentMoveHandler,
                      public IPruneRemovedDocumentsHandler,
                      public IHeartBeatHandler,
                      public IWipeOldRemovedFieldsHandler,
                      public IOperationStorer
{
    FastOS_ThreadId                _executorThreadId;
    std::vector<MyDocumentSubDB *> _subDBs;
    SerialNum                      _serialNum;
    uint32_t                       _heartBeats;
    fastos::TimeStamp              _wipeTimeLimit;
public:
    MyFeedHandler(FastOS_ThreadId &executorThreadId);

    virtual
    ~MyFeedHandler();

    bool
    isExecutorThread(void);

    virtual void
    handleMove(MoveOperation &op);

    virtual void
    performPruneRemovedDocuments(PruneRemovedDocumentsOperation &op);

    virtual void
    heartBeat(void);

    virtual void
    wipeOldRemovedFields(TimeStamp wipeTimeLimit);

    void
    setSubDBs(const std::vector<MyDocumentSubDB *> &subDBs);

    SerialNum
    incSerialNum(void)
    {
        return ++_serialNum;
    }

    // Implements IOperationStorer
    virtual void
    storeOperation(FeedOperation &op);

    uint32_t
    getHeartBeats(void)
    {
        return _heartBeats;
    }

    fastos::TimeStamp
    getWipeTimeLimit()
    {
        return _wipeTimeLimit;
    }
};


class MyExecutor: public vespalib::ThreadStackExecutor
{
public:
    FastOS_ThreadId		  _threadId;

    MyExecutor(void);

    virtual
    ~MyExecutor(void);

    bool
    isIdle(void);

    bool
    waitIdle(double timeout);
};


class MyFrozenBucket
{
    IBucketFreezer &_freezer;
    BucketId _bucketId;
public:
    typedef std::unique_ptr<MyFrozenBucket> UP;

    MyFrozenBucket(IBucketFreezer &freezer,
                   const BucketId &bucketId)
        : _freezer(freezer),
          _bucketId(bucketId)
    {
        _freezer.freezeBucket(_bucketId);
    }

    ~MyFrozenBucket(void)
    {
        _freezer.thawBucket(_bucketId);
    }
};

struct MySimpleJob : public IMaintenanceJob
{
    vespalib::CountDownLatch _latch;
    size_t                   _runCnt;

    MySimpleJob(double delay,
                double interval,
                uint32_t finishCount)
        : IMaintenanceJob("my_job", delay, interval),
          _latch(finishCount),
          _runCnt(0)
    {
    }
    void block() { setBlocked(true); }
    virtual bool run() {
        LOG(info, "MySimpleJob::run()");
        _latch.countDown();
        ++_runCnt;
        return true;
    }
};

struct MySplitJob : public MySimpleJob
{
    MySplitJob(double delay,
               double interval,
               uint32_t finishCount)
        : MySimpleJob(delay, interval, finishCount)
    {
    }
    virtual bool run() {
        LOG(info, "MySplitJob::run()");
        _latch.countDown();
        ++_runCnt;
        return _latch.getCount() == 0;
    }
};

struct MyLongRunningJob : public IMaintenanceJob
{
    vespalib::Gate _firstRun;

    MyLongRunningJob(double delay,
                     double interval)
        : IMaintenanceJob("long_running_job", delay, interval),
          _firstRun()
    {
    }
    void block() { setBlocked(true); }
    virtual bool run() {
        _firstRun.countDown();
        usleep(10000);
        return false;
    }
};


struct MyAttributeManager : public proton::IAttributeManager
{
    virtual AttributeGuard::UP
    getAttribute(const string &) const override {
        abort();
    }

    virtual AttributeGuard::UP
    getAttributeStableEnum(const string &) const override {
        abort();
    }

    virtual void
    getAttributeList(std::vector<AttributeGuard> &) const override {
        abort();
    }

    virtual search::attribute::IAttributeContext::UP
    createContext() const override {
        abort();
    }

    virtual IAttributeManager::SP
    create(const AttributeCollectionSpec &) const override {
        abort();
    }

    virtual std::vector<searchcorespi::IFlushTarget::SP>
    getFlushTargets() const override {
        abort();
    }

    virtual search::SerialNum
    getFlushedSerialNum(const vespalib::string &) const override {
        abort();
    }

    virtual search::SerialNum getOldestFlushedSerialNumber() const override {
        abort();
    }

    virtual search::SerialNum
    getNewestFlushedSerialNumber() const override {
        abort();
    }

    virtual void
    getAttributeListAll(std::vector<search::AttributeGuard> &)
        const override {
        abort();
    }

    virtual void
    wipeHistory(const search::index::Schema &) override {
        abort();
    }

    virtual const IAttributeFactory::SP &
    getFactory() const override {
        abort();
    }

    virtual search::ISequencedTaskExecutor &
    getAttributeFieldWriter() const override {
        abort();
    }

    virtual search::AttributeVector *
    getWritableAttribute(const vespalib::string &) const override {
        abort();
    }

    virtual const std::vector<search::AttributeVector *> &
    getWritableAttributes() const override {
        abort();
    }

    virtual void
    asyncForEachAttribute(std::shared_ptr<IAttributeFunctor>)
        const override {
    }

    virtual ExclusiveAttributeReadAccessor::UP
    getExclusiveReadAccessor(const vespalib::string &) const override {
        abort();
    }
};

class MaintenanceControllerFixture : public ICommitable
{
public:
    MyExecutor                    _executor;
    ExecutorThreadService         _threadService;
    DocTypeName                   _docTypeName;
    test::UserDocumentsBuilder    _builder;
    std::shared_ptr<BucketDBOwner> _bucketDB;
    test::BucketStateCalculator::SP _calc;
    test::ClusterStateHandler     _clusterStateHandler;
    test::BucketHandler           _bucketHandler;
    MyBucketModifiedHandler       _bmc;
    MyDocumentSubDB               _ready;
    MyDocumentSubDB               _removed;
    MyDocumentSubDB               _notReady;
    MySessionCachePruner          _gsp;
    MyFeedHandler                 _fh;
    DocumentDBMaintenanceConfig::SP _mcCfg;
    bool                          _injectDefaultJobs;
    DocumentDBJobTrackers         _jobTrackers;
    std::shared_ptr<proton::IAttributeManager> _readyAttributeManager;
    std::shared_ptr<proton::IAttributeManager> _notReadyAttributeManager;
    AttributeUsageFilter          _attributeUsageFilter;
    test::DiskMemUsageNotifier    _diskMemUsageNotifier;
    MaintenanceController         _mc;

    MaintenanceControllerFixture(void);

    virtual
    ~MaintenanceControllerFixture(void);

    void
    syncSubDBs(void);

    void commit() override {
    }

    void commitAndWait() override {
    }

    void
    performSyncSubDBs(void);

    void
    notifyClusterStateChanged(void);

    void
    performNotifyClusterStateChanged(void);

    void
    startMaintenance(void);

    void injectMaintenanceJobs();

    void
    performStartMaintenance(void);

    void
    stopMaintenance(void);

    void
    forwardMaintenanceConfig(void);

    void
    performForwardMaintenanceConfig(void);

    void
    insertDocs(const test::UserDocuments &docs,
               MyDocumentSubDB &subDb);

    void
    removeDocs(const test::UserDocuments &docs,
               Timestamp timestamp);

    void
    setPruneConfig(const DocumentDBPruneRemovedDocumentsConfig &pruneConfig)
    {
        DocumentDBMaintenanceConfig::SP
            newCfg(new DocumentDBMaintenanceConfig(
                           pruneConfig,
                           _mcCfg->getHeartBeatConfig(),
                           _mcCfg->getWipeOldRemovedFieldsConfig(),
                           _mcCfg->getSessionCachePruneInterval(),
                           _mcCfg->getVisibilityDelay(),
                           _mcCfg->getLidSpaceCompactionConfig(),
                           _mcCfg->getAttributeUsageFilterConfig(),
                           _mcCfg->getAttributeUsageSampleInterval()));
        _mcCfg = newCfg;
        forwardMaintenanceConfig();
    }

    void
    setHeartBeatConfig(const DocumentDBHeartBeatConfig &heartBeatConfig)
    {
        DocumentDBMaintenanceConfig::SP
            newCfg(new DocumentDBMaintenanceConfig(
                           _mcCfg->getPruneRemovedDocumentsConfig(),
                           heartBeatConfig,
                           _mcCfg->getWipeOldRemovedFieldsConfig(),
                           _mcCfg->getSessionCachePruneInterval(),
                           _mcCfg->getVisibilityDelay(),
                           _mcCfg->getLidSpaceCompactionConfig(),
                           _mcCfg->getAttributeUsageFilterConfig(),
                           _mcCfg->getAttributeUsageSampleInterval()));
        _mcCfg = newCfg;
        forwardMaintenanceConfig();
    }

    void
    setWipeOldRemovedFieldsConfig(const DocumentDBWipeOldRemovedFieldsConfig &wipeConfig)
    {
        DocumentDBMaintenanceConfig::SP
            newCfg(new DocumentDBMaintenanceConfig(
                           _mcCfg->getPruneRemovedDocumentsConfig(),
                           _mcCfg->getHeartBeatConfig(),
                           wipeConfig,
                           _mcCfg->getSessionCachePruneInterval(),
                           _mcCfg->getVisibilityDelay(),
                           _mcCfg->getLidSpaceCompactionConfig(),
                           _mcCfg->getAttributeUsageFilterConfig(),
                           _mcCfg->getAttributeUsageSampleInterval()));
        _mcCfg = newCfg;
        forwardMaintenanceConfig();
    }


    void
    setGroupingSessionPruneInterval(double groupingSessionPruneInterval)
    {
        DocumentDBMaintenanceConfig::SP
            newCfg(new DocumentDBMaintenanceConfig(
                           _mcCfg->getPruneRemovedDocumentsConfig(),
                           _mcCfg->getHeartBeatConfig(),
                           _mcCfg->getWipeOldRemovedFieldsConfig(),
                           groupingSessionPruneInterval,
                           _mcCfg->getVisibilityDelay(),
                           _mcCfg->getLidSpaceCompactionConfig(),
                           _mcCfg->getAttributeUsageFilterConfig(),
                           _mcCfg->getAttributeUsageSampleInterval()));
        _mcCfg = newCfg;
        forwardMaintenanceConfig();
    }


    void
    performNotifyBucketStateChanged(document::BucketId bucketId,
                                    BucketInfo::ActiveState newState)
    {
        _bucketHandler.notifyBucketStateChanged(bucketId, newState);
    }

    void
    notifyBucketStateChanged(const document::BucketId &bucketId,
                             BucketInfo::ActiveState newState)
    {
        _executor.execute(makeTask(makeClosure(this,
                                               &MaintenanceControllerFixture::
                                               performNotifyBucketStateChanged,
                                               bucketId, newState)));
        _executor.sync();
    }
};


MaintenanceDocumentSubDB
MyDocumentSubDB::getSubDB(void)
{
    IDocumentRetriever::SP retriever(new MyDocumentRetriever(*this));

    return MaintenanceDocumentSubDB(_metaStoreSP,
                                    retriever,
                                    _subDBId);
}


void
MyDocumentSubDB::handlePruneRemovedDocuments(
        const PruneRemovedDocumentsOperation &op)
{
    assert(_subDBId == 1u);
    typedef LidVectorContext::LidVector LidVector;
    const SerialNum serialNum = op.getSerialNum();
    const LidVectorContext &lidCtx = *op.getLidsToRemove();
    const LidVector &lidsToRemove(lidCtx.getLidVector());
    _metaStore.removeBatch(lidsToRemove, lidCtx.getDocIdLimit());
    _metaStore.removeBatchComplete(lidsToRemove);
    _metaStore.commit(serialNum);
    for (LidVector::const_iterator it = lidsToRemove.begin(),
                                  ite = lidsToRemove.end();
         it != ite; ++it) {
        search::DocumentIdT lid(*it);
        _docs.erase(lid);
    }
}


void
MyDocumentSubDB::handlePut(PutOperation &op)
{
    const SerialNum serialNum = op.getSerialNum();
    const Document::SP &doc = op.getDocument();
    const DocumentId &docId = doc->getId();
    const document::GlobalId &gid = docId.getGlobalId();
    bool needCommit = false;

    if (op.getValidDbdId(_subDBId)) {
        typedef DocumentMetaStore::Result PutRes;

        PutRes putRes(_metaStore.put(gid,
                                     op.getBucketId(),
                                     op.getTimestamp(),
                                     op.getLid()));
        assert(putRes.ok());
        assert(op.getLid() == putRes._lid);
        _docs[op.getLid()] = doc;
        needCommit = true;
    }
    if (op.getValidPrevDbdId(_subDBId) && op.changedDbdId()) {
        assert(_metaStore.validLid(op.getPrevLid()));
        const RawDocumentMetaData &meta(_metaStore.getRawMetaData(op.getPrevLid()));
        assert((_subDBId == 1u) == op.getPrevMarkedAsRemoved());
        assert(meta.getGid() == gid);
        (void) meta;

        bool remres = _metaStore.remove(op.getPrevLid());
        assert(remres);
        (void) remres;
        _metaStore.removeComplete(op.getPrevLid());

        _docs.erase(op.getPrevLid());
        needCommit = true;
    }
    if (needCommit) {
        _metaStore.commit(serialNum, serialNum);
    }
}


void
MyDocumentSubDB::handleRemove(RemoveOperation &op)
{
    const SerialNum serialNum = op.getSerialNum();
    const DocumentId &docId = op.getDocumentId();
    const document::GlobalId &gid = docId.getGlobalId();
    bool needCommit = false;

    if (op.getValidDbdId(_subDBId)) {
        typedef DocumentMetaStore::Result PutRes;

        PutRes putRes(_metaStore.put(gid,
                                     op.getBucketId(),
                                     op.getTimestamp(),
                                     op.getLid()));
        assert(putRes.ok());
        assert(op.getLid() == putRes._lid);
        const document::DocumentType *docType =
            _repo->getDocumentType(_docTypeName.getName());
        Document::UP doc(new Document(*docType, docId));
        doc->setRepo(*_repo);
        _docs[op.getLid()] = std::move(doc);
        needCommit = true;
    }
    if (op.getValidPrevDbdId(_subDBId) && op.changedDbdId()) {
        assert(_metaStore.validLid(op.getPrevLid()));
        const RawDocumentMetaData &meta(_metaStore.getRawMetaData(op.getPrevLid()));
        assert((_subDBId == 1u) == op.getPrevMarkedAsRemoved());
        assert(meta.getGid() == gid);
        (void) meta;

        bool remres = _metaStore.remove(op.getPrevLid());
        assert(remres);
        (void) remres;

        _metaStore.removeComplete(op.getPrevLid());
        _docs.erase(op.getPrevLid());
        needCommit = true;
    }
    if (needCommit) {
        _metaStore.commit(serialNum, serialNum);
    }
}


void
MyDocumentSubDB::prepareMove(MoveOperation &op)
{
    const DocumentId &docId = op.getDocument()->getId();
    const document::GlobalId &gid = docId.getGlobalId();
    DocumentMetaStore::Result inspectResult = _metaStore.inspect(gid);
    assert(!inspectResult._found);
    op.setDbDocumentId(DbDocumentId(_subDBId, inspectResult._lid));
}


void
MyDocumentSubDB::handleMove(const MoveOperation &op)
{
    const SerialNum serialNum = op.getSerialNum();
    const Document::SP &doc = op.getDocument();
    const DocumentId &docId = doc->getId();
    const document::GlobalId &gid = docId.getGlobalId();
    bool needCommit = false;

    if (op.getValidDbdId(_subDBId)) {
        typedef DocumentMetaStore::Result PutRes;

        PutRes putRes(_metaStore.put(gid,
                                     op.getBucketId(),
                                     op.getTimestamp(),
                                     op.getLid()));
        assert(putRes.ok());
        assert(op.getLid() == putRes._lid);
        _docs[op.getLid()] = doc;
        needCommit = true;
    }
    if (op.getValidPrevDbdId(_subDBId)) {
        assert(_metaStore.validLid(op.getPrevLid()));
        const RawDocumentMetaData &meta(_metaStore.getRawMetaData(op.getPrevLid()));
        assert((_subDBId == 1u) == op.getPrevMarkedAsRemoved());
        assert(meta.getGid() == gid);
        (void) meta;

        bool remres = _metaStore.remove(op.getPrevLid());
        assert(remres);
        (void) remres;

        _metaStore.removeComplete(op.getPrevLid());
        _docs.erase(op.getPrevLid());
        needCommit = true;
    }
    if (needCommit) {
        _metaStore.commit(serialNum, serialNum);
    }
}


uint32_t
MyDocumentSubDB::getNumUsedLids(void) const
{
    return _metaStore.getNumUsedLids();
}


MyFeedHandler::MyFeedHandler(FastOS_ThreadId &executorThreadId)
    : IDocumentMoveHandler(),
      IPruneRemovedDocumentsHandler(),
      IHeartBeatHandler(),
      _executorThreadId(executorThreadId),
      _subDBs(),
      _serialNum(0u),
      _heartBeats(0u),
      _wipeTimeLimit()
{
}


MyFeedHandler::~MyFeedHandler(void)
{
}


bool
MyFeedHandler::isExecutorThread(void)
{
    FastOS_ThreadId threadId(FastOS_Thread::GetCurrentThreadId());
    return FastOS_Thread::CompareThreadIds(_executorThreadId, threadId);
}


void
MyFeedHandler::handleMove(MoveOperation &op)
{
    assert(isExecutorThread());
    assert(op.getValidPrevDbdId());
    _subDBs[op.getSubDbId()]->prepareMove(op);
    assert(op.getValidDbdId());
    assert(op.getSubDbId() != op.getPrevSubDbId());
    // Check for wrong magic numbers
    assert(op.getSubDbId() != 1u);
    assert(op.getPrevSubDbId() != 1u);
    assert(op.getSubDbId() < _subDBs.size());
    assert(op.getPrevSubDbId() < _subDBs.size());
    storeOperation(op);
    _subDBs[op.getSubDbId()]->handleMove(op);
    _subDBs[op.getPrevSubDbId()]->handleMove(op);
}


void
MyFeedHandler::performPruneRemovedDocuments(PruneRemovedDocumentsOperation &op)
{
    assert(isExecutorThread());
    if (op.getLidsToRemove()->getNumLids() != 0u) {
        storeOperation(op);
        // magic number.
        _subDBs[1u]->handlePruneRemovedDocuments(op);
    }
}


void
MyFeedHandler::heartBeat(void)
{
    assert(isExecutorThread());
    ++_heartBeats;
}


void
MyFeedHandler::wipeOldRemovedFields(fastos::TimeStamp wipeTimeLimit)
{
    assert(isExecutorThread());
    _wipeTimeLimit = wipeTimeLimit;
}


void
MyFeedHandler::setSubDBs(const std::vector<MyDocumentSubDB *> &subDBs)
{
    _subDBs = subDBs;
}


void
MyFeedHandler::storeOperation(FeedOperation &op)
{
    op.setSerialNum(incSerialNum());
}


MyExecutor::MyExecutor(void)
    : vespalib::ThreadStackExecutor(1, 128 * 1024),
      _threadId()
{
    execute(makeTask(makeClosure(&sampleThreadId, &_threadId)));
    sync();
}


MyExecutor::~MyExecutor(void)
{
}


bool
MyExecutor::isIdle(void)
{
    (void) getStats();
    sync();
    Stats stats(getStats());
    return stats.acceptedTasks == 0u;
}


bool
MyExecutor::waitIdle(double timeout)
{
    FastOS_Time startTime;
    startTime.SetNow();
    while (!isIdle()) {
        FastOS_Time cTime;
        cTime.SetNow();
        if (cTime.Secs() - startTime.Secs() >= timeout)
            return false;
    }
    return true;
}


MaintenanceControllerFixture::MaintenanceControllerFixture(void)
    : _executor(),
      _threadService(_executor),
      _docTypeName("searchdocument"), // must match document builder
      _builder(),
      _bucketDB(std::make_shared<BucketDBOwner>()),
      _calc(new test::BucketStateCalculator()),
      _clusterStateHandler(),
      _bucketHandler(),
      _bmc(),
      _ready(0u, SubDbType::READY, _builder.getRepo(), _bucketDB, _docTypeName),
      _removed(1u, SubDbType::REMOVED, _builder.getRepo(), _bucketDB,
               _docTypeName),
      _notReady(2u, SubDbType::NOTREADY, _builder.getRepo(), _bucketDB,
                _docTypeName),
      _gsp(),
      _fh(_executor._threadId),
      _mcCfg(new DocumentDBMaintenanceConfig),
      _injectDefaultJobs(true),
      _jobTrackers(),
      _readyAttributeManager(std::make_shared<MyAttributeManager>()),
      _notReadyAttributeManager(std::make_shared<MyAttributeManager>()),
      _attributeUsageFilter(),
      _mc(_threadService, _docTypeName)
{
    std::vector<MyDocumentSubDB *> subDBs;
    subDBs.push_back(&_ready);
    subDBs.push_back(&_removed);
    subDBs.push_back(&_notReady);
    _fh.setSubDBs(subDBs);
    syncSubDBs();
}


MaintenanceControllerFixture::~MaintenanceControllerFixture(void)
{
    stopMaintenance();
}


void
MaintenanceControllerFixture::syncSubDBs(void)
{
    _executor.execute(makeTask(makeClosure(this,
                                       &MaintenanceControllerFixture::
                                       performSyncSubDBs)));
    _executor.sync();
}


void
MaintenanceControllerFixture::performSyncSubDBs(void)
{
    _mc.syncSubDBs(_ready.getSubDB(),
                   _removed.getSubDB(),
                   _notReady.getSubDB());
}


void
MaintenanceControllerFixture::notifyClusterStateChanged(void)
{
    _executor.execute(makeTask(makeClosure(this,
                                       &MaintenanceControllerFixture::
                                       performNotifyClusterStateChanged)));
    _executor.sync();
}


void
MaintenanceControllerFixture::performNotifyClusterStateChanged(void)
{
    _clusterStateHandler.notifyClusterStateChanged(_calc);
}


void
MaintenanceControllerFixture::startMaintenance(void)
{
    _executor.execute(makeTask(makeClosure(this,
                                       &MaintenanceControllerFixture::
                                       performStartMaintenance)));
    _executor.sync();
}

void
MaintenanceControllerFixture::injectMaintenanceJobs()
{
    if (_injectDefaultJobs) {
        ILidSpaceCompactionHandler::Vector lscHandlers;
        MaintenanceJobsInjector::injectJobs(_mc, *_mcCfg, _fh, _gsp, _fh,
                                            lscHandlers, _fh, _mc, _docTypeName.getName(),
                                            _fh, _fh, _bmc, _clusterStateHandler, _bucketHandler,
                                            _calc,
                                            _diskMemUsageNotifier,
                                            _jobTrackers, *this,
                                            _readyAttributeManager,
                                            _notReadyAttributeManager,
                                            _attributeUsageFilter);
    }
}

void
MaintenanceControllerFixture::performStartMaintenance(void)
{
    injectMaintenanceJobs();
    _mc.start(_mcCfg);
}


void
MaintenanceControllerFixture::stopMaintenance(void)
{
    _mc.stop();
    _executor.sync();
}


void
MaintenanceControllerFixture::forwardMaintenanceConfig(void)
{
    _executor.execute(makeTask(makeClosure(this,
                                       &MaintenanceControllerFixture::
                                       performForwardMaintenanceConfig)));
    _executor.sync();
}


void
MaintenanceControllerFixture::performForwardMaintenanceConfig(void)
{
    _mc.killJobs();
    injectMaintenanceJobs();
    _mc.newConfig(_mcCfg);
}


void
MaintenanceControllerFixture::insertDocs(const test::UserDocuments &docs,
        MyDocumentSubDB &subDb)
{

    for (test::UserDocuments::Iterator itr = docs.begin();
         itr != docs.end();
         ++itr) {
        const test::BucketDocuments &bucketDocs = itr->second;
        for (size_t i = 0; i < bucketDocs.getDocs().size(); ++i) {
            const test::Document &testDoc = bucketDocs.getDocs()[i];
            PutOperation op(testDoc.getBucket(),
                            testDoc.getTimestamp(),
                            testDoc.getDoc());
            op.setDbDocumentId(DbDocumentId(subDb.getSubDBId(),
                                       testDoc.getLid()));
            _fh.storeOperation(op);
            subDb.handlePut(op);
        }
    }
}


void
MaintenanceControllerFixture::removeDocs(const test::UserDocuments &docs,
        Timestamp timestamp)
{

    for (test::UserDocuments::Iterator itr = docs.begin();
         itr != docs.end();
         ++itr) {
        const test::BucketDocuments &bucketDocs = itr->second;
        for (size_t i = 0; i < bucketDocs.getDocs().size(); ++i) {
            const test::Document &testDoc = bucketDocs.getDocs()[i];
            RemoveOperation op(testDoc.getBucket(),
                            timestamp,
                            testDoc.getDoc()->getId());
            op.setDbDocumentId(DbDocumentId(_removed.getSubDBId(),
                                       testDoc.getLid()));
            _fh.storeOperation(op);
            _removed.handleRemove(op);
        }
    }
}

TEST_F("require that bucket move controller is active",
       MaintenanceControllerFixture)
{
    f._builder.createDocs(1, 1, 4); // 3 docs
    f._builder.createDocs(2, 4, 6); // 2 docs
    test::UserDocuments readyDocs(f._builder.getDocs());
    BucketId bucketId1(readyDocs.getBucket(1));
    BucketId bucketId2(readyDocs.getBucket(2));
    f.insertDocs(readyDocs, f._ready);
    f._builder.clearDocs();
    f._builder.createDocs(3, 1, 3); // 2 docs
    f._builder.createDocs(4, 3, 6); // 3 docs
    test::UserDocuments notReadyDocs(f._builder.getDocs());
    BucketId bucketId3(notReadyDocs.getBucket(3));
    BucketId bucketId4(notReadyDocs.getBucket(4));
    f.insertDocs(notReadyDocs, f._notReady);
    f._builder.clearDocs();
    f.notifyClusterStateChanged();
    EXPECT_TRUE(f._executor.isIdle());
    EXPECT_EQUAL(5u, f._ready.getNumUsedLids());
    EXPECT_EQUAL(5u, f._ready.getDocumentCount());
    EXPECT_EQUAL(5u, f._notReady.getNumUsedLids());
    EXPECT_EQUAL(5u, f._notReady.getDocumentCount());
    f.startMaintenance();
    ASSERT_TRUE(f._executor.waitIdle(TIMEOUT_SEC));
    EXPECT_EQUAL(0u, f._ready.getNumUsedLids());
    EXPECT_EQUAL(0u, f._ready.getDocumentCount());
    EXPECT_EQUAL(10u, f._notReady.getNumUsedLids());
    EXPECT_EQUAL(10u, f._notReady.getDocumentCount());
    f._calc->addReady(bucketId1);
    f.notifyClusterStateChanged();
    ASSERT_TRUE(f._executor.waitIdle(TIMEOUT_SEC));
    EXPECT_EQUAL(3u, f._ready.getNumUsedLids());
    EXPECT_EQUAL(3u, f._ready.getDocumentCount());
    EXPECT_EQUAL(7u, f._notReady.getNumUsedLids());
    EXPECT_EQUAL(7u, f._notReady.getDocumentCount());
    MyFrozenBucket::UP frozen2(new MyFrozenBucket(f._mc, bucketId2));
    f._calc->addReady(bucketId2);
    f._calc->addReady(bucketId4);
    f.notifyClusterStateChanged();
    ASSERT_TRUE(f._executor.waitIdle(TIMEOUT_SEC));
    EXPECT_EQUAL(6u, f._ready.getNumUsedLids());
    EXPECT_EQUAL(6u, f._ready.getDocumentCount());
    EXPECT_EQUAL(4u, f._notReady.getNumUsedLids());
    EXPECT_EQUAL(4u, f._notReady.getDocumentCount());
    frozen2.reset();
    ASSERT_TRUE(f._executor.waitIdle(TIMEOUT_SEC));
    EXPECT_EQUAL(8u, f._ready.getNumUsedLids());
    EXPECT_EQUAL(8u, f._ready.getDocumentCount());
    EXPECT_EQUAL(2u, f._notReady.getNumUsedLids());
    EXPECT_EQUAL(2u, f._notReady.getDocumentCount());
}

TEST_F("require that document pruner is active",
       MaintenanceControllerFixture)
{
    uint64_t tshz = 1000000;
    uint64_t now = static_cast<uint64_t>(time(0)) * tshz;
    Timestamp remTime(static_cast<Timestamp::Type>(now - 3600 * tshz));
    Timestamp keepTime(static_cast<Timestamp::Type>(now + 3600 * tshz));
    f._builder.createDocs(1, 1, 4); // 3 docs
    f._builder.createDocs(2, 4, 6); // 2 docs
    test::UserDocuments keepDocs(f._builder.getDocs());
    BucketId bucketId1(keepDocs.getBucket(1));
    BucketId bucketId2(keepDocs.getBucket(2));
    f.removeDocs(keepDocs, keepTime);
    f._builder.clearDocs();
    f._builder.createDocs(3, 6, 8); // 2 docs
    f._builder.createDocs(4, 8, 11); // 3 docs
    test::UserDocuments removeDocs(f._builder.getDocs());
    BucketId bucketId3(removeDocs.getBucket(3));
    BucketId bucketId4(removeDocs.getBucket(4));
    f.removeDocs(removeDocs, remTime);
    f.notifyClusterStateChanged();
    EXPECT_TRUE(f._executor.isIdle());
    EXPECT_EQUAL(10u, f._removed.getNumUsedLids());
    EXPECT_EQUAL(10u, f._removed.getDocumentCount());
    f.startMaintenance();
    ASSERT_TRUE(f._executor.waitIdle(TIMEOUT_SEC));
    EXPECT_EQUAL(10u, f._removed.getNumUsedLids());
    EXPECT_EQUAL(10u, f._removed.getDocumentCount());
    MyFrozenBucket::UP frozen3(new MyFrozenBucket(f._mc, bucketId3));
    f.setPruneConfig(DocumentDBPruneRemovedDocumentsConfig(0.2, 900.0));
    for (uint32_t i = 0; i < 6; ++i) {
        FastOS_Thread::Sleep(100);
        ASSERT_TRUE(f._executor.waitIdle(TIMEOUT_SEC));
        if (f._removed.getNumUsedLids() != 10u)
            break;
    }
    EXPECT_EQUAL(10u, f._removed.getNumUsedLids());
    EXPECT_EQUAL(10u, f._removed.getDocumentCount());
    frozen3.reset();
    for (uint32_t i = 0; i < 600; ++i) {
        FastOS_Thread::Sleep(100);
        ASSERT_TRUE(f._executor.waitIdle(TIMEOUT_SEC));
        if (f._removed.getNumUsedLids() != 10u)
            break;
    }
    EXPECT_EQUAL(5u, f._removed.getNumUsedLids());
    EXPECT_EQUAL(5u, f._removed.getDocumentCount());
}

TEST_F("require that heartbeats are scheduled",
       MaintenanceControllerFixture)
{
    f.notifyClusterStateChanged();
    f.startMaintenance();
    f.setHeartBeatConfig(DocumentDBHeartBeatConfig(0.2));
    for (uint32_t i = 0; i < 600; ++i) {
        FastOS_Thread::Sleep(100);
        if (f._fh.getHeartBeats() != 0u)
            break;
    }
    EXPECT_GREATER(f._fh.getHeartBeats(), 0u);
}

TEST_F("require that periodic session prunings are scheduled",
       MaintenanceControllerFixture)
{
    ASSERT_FALSE(f._gsp.isInvoked);
    f.notifyClusterStateChanged();
    f.startMaintenance();
    f.setGroupingSessionPruneInterval(0.2);
    for (uint32_t i = 0; i < 600; ++i) {
        FastOS_Thread::Sleep(100);
        if (f._gsp.isInvoked) {
            break;
        }
    }
    ASSERT_TRUE(f._gsp.isInvoked);
}

TEST_F("require that wipe old removed fields are scheduled",
       MaintenanceControllerFixture)
{
    f.notifyClusterStateChanged();
    f.startMaintenance();
    TimeStamp now0 = TimeStamp(ClockSystem::now());
    f.setWipeOldRemovedFieldsConfig(DocumentDBWipeOldRemovedFieldsConfig(0.2, 100));
    TimeStamp now = TimeStamp(ClockSystem::now());
    TimeStamp expWipeTimeLimit = now - TimeStamp(100 * TimeStamp::SEC);
    TimeStamp wtLim;
    for (uint32_t i = 0; i < 600; ++i) {
        FastOS_Thread::Sleep(100);
        wtLim = f._fh.getWipeTimeLimit();
        if (wtLim.sec() != 0u) {
            break;
        }
    }
    TimeStamp now1 = TimeStamp(ClockSystem::now());
    double fuzz = now1.sec() - now0.sec();
    LOG(info,
        "WipeOldRemovedFields: "
        "now(%" PRIu64 "), "
        "expWipeTimeLimit(%" PRIu64 "), "
        "actWipeTimeLimit(%" PRIu64 "), "
        "fuzz(%05.3f)",
        (uint64_t)now.sec(),
        (uint64_t)expWipeTimeLimit.sec(),
        (uint64_t)wtLim.sec(),
        fuzz);
    EXPECT_APPROX(expWipeTimeLimit.sec(), wtLim.sec(), 4u + fuzz);
}

TEST_F("require that active bucket is not moved until de-activated", MaintenanceControllerFixture)
{
    f._builder.createDocs(1, 1, 4); // 3 docs
    f._builder.createDocs(2, 4, 6); // 2 docs
    test::UserDocuments readyDocs(f._builder.getDocs());
    f.insertDocs(readyDocs, f._ready);
    f._builder.clearDocs();
    f._builder.createDocs(3, 1, 3); // 2 docs
    f._builder.createDocs(4, 3, 6); // 3 docs
    test::UserDocuments notReadyDocs(f._builder.getDocs());
    f.insertDocs(notReadyDocs, f._notReady);
    f._builder.clearDocs();

    // bucket 1 (active) should be moved from ready to not ready according to cluster state
    f._calc->addReady(readyDocs.getBucket(2));
    f._ready.setBucketState(readyDocs.getBucket(1), true);

    f.notifyClusterStateChanged();
    EXPECT_TRUE(f._executor.isIdle());
    EXPECT_EQUAL(5u, f._ready.getNumUsedLids());
    EXPECT_EQUAL(5u, f._ready.getDocumentCount());
    EXPECT_EQUAL(5u, f._notReady.getNumUsedLids());
    EXPECT_EQUAL(5u, f._notReady.getDocumentCount());

    f.startMaintenance();
    ASSERT_TRUE(f._executor.waitIdle(TIMEOUT_SEC));
    EXPECT_EQUAL(5u, f._ready.getNumUsedLids());
    EXPECT_EQUAL(5u, f._ready.getDocumentCount());
    EXPECT_EQUAL(5u, f._notReady.getNumUsedLids());
    EXPECT_EQUAL(5u, f._notReady.getDocumentCount());

    // de-activate bucket 1
    f._ready.setBucketState(readyDocs.getBucket(1), false);
    f.notifyBucketStateChanged(readyDocs.getBucket(1), BucketInfo::NOT_ACTIVE);
    ASSERT_TRUE(f._executor.waitIdle(TIMEOUT_SEC));
    EXPECT_EQUAL(2u, f._ready.getNumUsedLids());
    EXPECT_EQUAL(2u, f._ready.getDocumentCount());
    EXPECT_EQUAL(8u, f._notReady.getNumUsedLids());
    EXPECT_EQUAL(8u, f._notReady.getDocumentCount());

    // re-activate bucket 1
    f._ready.setBucketState(readyDocs.getBucket(1), true);
    f.notifyBucketStateChanged(readyDocs.getBucket(1), BucketInfo::ACTIVE);
    ASSERT_TRUE(f._executor.waitIdle(TIMEOUT_SEC));
    EXPECT_EQUAL(5u, f._ready.getNumUsedLids());
    EXPECT_EQUAL(5u, f._ready.getDocumentCount());
    EXPECT_EQUAL(5u, f._notReady.getNumUsedLids());
    EXPECT_EQUAL(5u, f._notReady.getDocumentCount());

    // de-activate bucket 1
    f._ready.setBucketState(readyDocs.getBucket(1), false);
    f.notifyBucketStateChanged(readyDocs.getBucket(1), BucketInfo::NOT_ACTIVE);
    ASSERT_TRUE(f._executor.waitIdle(TIMEOUT_SEC));
    EXPECT_EQUAL(2u, f._ready.getNumUsedLids());
    EXPECT_EQUAL(2u, f._ready.getDocumentCount());
    EXPECT_EQUAL(8u, f._notReady.getNumUsedLids());
    EXPECT_EQUAL(8u, f._notReady.getDocumentCount());

    // re-activate bucket 1
    f._ready.setBucketState(readyDocs.getBucket(1), true);
    f.notifyBucketStateChanged(readyDocs.getBucket(1), BucketInfo::ACTIVE);
    ASSERT_TRUE(f._executor.waitIdle(TIMEOUT_SEC));
    EXPECT_EQUAL(5u, f._ready.getNumUsedLids());
    EXPECT_EQUAL(5u, f._ready.getDocumentCount());
    EXPECT_EQUAL(5u, f._notReady.getNumUsedLids());
    EXPECT_EQUAL(5u, f._notReady.getDocumentCount());
}

TEST_F("require that a simple maintenance job is executed", MaintenanceControllerFixture)
{
    IMaintenanceJob::UP job(new MySimpleJob(0.2, 0.2, 3));
    MySimpleJob &myJob = static_cast<MySimpleJob &>(*job);
    f._mc.registerJob(std::move(job));
    f._injectDefaultJobs = false;
    f.startMaintenance();
    bool done = myJob._latch.await(TIMEOUT_MS);
    EXPECT_TRUE(done);
    EXPECT_EQUAL(0u, myJob._latch.getCount());
}

TEST_F("require that a split maintenance job is executed", MaintenanceControllerFixture)
{
    IMaintenanceJob::UP job(new MySplitJob(0.2, TIMEOUT_SEC * 2, 3));
    MySplitJob &myJob = static_cast<MySplitJob &>(*job);
    f._mc.registerJob(std::move(job));
    f._injectDefaultJobs = false;
    f.startMaintenance();
    bool done = myJob._latch.await(TIMEOUT_MS);
    EXPECT_TRUE(done);
    EXPECT_EQUAL(0u, myJob._latch.getCount());
}

TEST_F("require that a blocked job is unblocked and executed after thaw bucket",
        MaintenanceControllerFixture)
{
    IMaintenanceJob::UP job1(new MySimpleJob(TIMEOUT_SEC * 2, TIMEOUT_SEC * 2, 1));
    MySimpleJob &myJob1 = static_cast<MySimpleJob &>(*job1);
    IMaintenanceJob::UP job2(new MySimpleJob(TIMEOUT_SEC * 2, TIMEOUT_SEC * 2, 0));
    MySimpleJob &myJob2 = static_cast<MySimpleJob &>(*job2);
    f._mc.registerJob(std::move(job1));
    f._mc.registerJob(std::move(job2));
    f._injectDefaultJobs = false;
    f.startMaintenance();

    myJob1.block();
    EXPECT_TRUE(myJob1.isBlocked());
    EXPECT_FALSE(myJob2.isBlocked());
    IBucketFreezer &ibf = f._mc;
    ibf.freezeBucket(BucketId(1));
    ibf.thawBucket(BucketId(1));
    EXPECT_TRUE(myJob1.isBlocked());
    ibf.freezeBucket(BucketId(1));
    IFrozenBucketHandler & fbh = f._mc;
    // This is to simulate contention, as that is required for notification on thawed buckets.
    EXPECT_FALSE(fbh.acquireExclusiveBucket(BucketId(1)));
    ibf.thawBucket(BucketId(1));
    f._executor.sync();
    EXPECT_FALSE(myJob1.isBlocked());
    EXPECT_FALSE(myJob2.isBlocked());
    bool done1 = myJob1._latch.await(TIMEOUT_MS);
    EXPECT_TRUE(done1);
    FastOS_Thread::Sleep(2000);
    EXPECT_EQUAL(0u, myJob2._runCnt);
}

TEST_F("require that blocked jobs are not executed", MaintenanceControllerFixture)
{
    IMaintenanceJob::UP job(new MySimpleJob(0.2, 0.2, 0));
    MySimpleJob &myJob = static_cast<MySimpleJob &>(*job);
    myJob.block();
    f._mc.registerJob(std::move(job));
    f._injectDefaultJobs = false;
    f.startMaintenance();
    FastOS_Thread::Sleep(2000);
    EXPECT_EQUAL(0u, myJob._runCnt);
}

TEST_F("require that maintenance controller state list jobs", MaintenanceControllerFixture)
{
    {
        IMaintenanceJob::UP job1(new MySimpleJob(TIMEOUT_SEC * 2, TIMEOUT_SEC * 2, 0));
        IMaintenanceJob::UP job2(new MyLongRunningJob(0.2, 0.2));
        MyLongRunningJob &longRunningJob = static_cast<MyLongRunningJob &>(*job2);
        f._mc.registerJob(std::move(job1));
        f._mc.registerJob(std::move(job2));
        f._injectDefaultJobs = false;
        f.startMaintenance();
        longRunningJob._firstRun.await(TIMEOUT_MS);
    }

    MaintenanceControllerExplorer explorer(f._mc.getJobList());
    Slime state;
    SlimeInserter inserter(state);
    explorer.get_state(inserter, true);

    Inspector &runningJobs = state.get()["runningJobs"];
    EXPECT_EQUAL(1u, runningJobs.children());
    EXPECT_EQUAL("long_running_job", runningJobs[0]["name"].asString().make_string());

    Inspector &allJobs = state.get()["allJobs"];
    EXPECT_EQUAL(2u, allJobs.children());
    EXPECT_EQUAL("my_job", allJobs[0]["name"].asString().make_string());
    EXPECT_EQUAL("long_running_job", allJobs[1]["name"].asString().make_string());
}

TEST("Verify FrozenBucketsMap interface") {
    FrozenBucketsMap m;
    BucketId a(8, 6);
    {
        auto guard = m.acquireExclusiveBucket(a);
        EXPECT_TRUE(bool(guard));
        EXPECT_EQUAL(a, guard->getBucket());
    }
    m.freezeBucket(a);
    EXPECT_FALSE(m.thawBucket(a));
    m.freezeBucket(a);
    {
        auto guard = m.acquireExclusiveBucket(a);
        EXPECT_FALSE(bool(guard));
    }
    EXPECT_TRUE(m.thawBucket(a));
    m.freezeBucket(a);
    m.freezeBucket(a);
    m.freezeBucket(a);
    {
        auto guard = m.acquireExclusiveBucket(a);
        EXPECT_FALSE(bool(guard));
    }
    EXPECT_FALSE(m.thawBucket(a));
    EXPECT_FALSE(m.thawBucket(a));
    EXPECT_TRUE(m.thawBucket(a));
    {
        auto guard = m.acquireExclusiveBucket(a);
        EXPECT_TRUE(bool(guard));
        EXPECT_EQUAL(a, guard->getBucket());
    }
}

TEST_MAIN()
{
    TEST_RUN_ALL();
}
