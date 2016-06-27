// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP("persistenceengine_test");

#include <vespa/document/repo/documenttyperepo.h>
#include <vespa/persistence/spi/documentselection.h>
#include <vespa/searchcore/proton/persistenceengine/bucket_guard.h>
#include <vespa/searchcore/proton/persistenceengine/ipersistenceengineowner.h>
#include <vespa/searchcore/proton/persistenceengine/persistenceengine.h>
#include <vespa/vespalib/testkit/testapp.h>
#include <vespa/document/fieldset/fieldsets.h>
#include <set>

using document::BucketId;
using document::Document;
using document::DocumentId;
using document::DocumentType;
using search::DocumentMetaData;
using storage::spi::BucketChecksum;
using storage::spi::BucketInfo;
using storage::spi::ClusterState;
using storage::spi::DocumentSelection;
using storage::spi::GetResult;
using namespace proton;
using namespace vespalib;

DocumentType
createDocType(const vespalib::string &name, int32_t id)
{
    return DocumentType(name, id);
}


document::Document::SP
createDoc(const DocumentType &docType, const DocumentId &docId)
{
    return document::Document::SP(new document::Document(docType, docId));
}


document::DocumentUpdate::SP
createUpd(const DocumentType& docType, const DocumentId &docId)
{
    return document::DocumentUpdate::SP(new document::DocumentUpdate(docType, docId));
}


document::Document::UP
clone(const document::Document::SP &doc)
{
    return document::Document::UP(doc->clone());
}


document::DocumentUpdate::UP
clone(const document::DocumentUpdate::SP &upd)
{
    return document::DocumentUpdate::UP(upd->clone());
}


storage::spi::ClusterState
createClusterState(const storage::lib::State& nodeState =
                   storage::lib::State::UP)
{
    using storage::lib::Distribution;
    using storage::lib::Node;
    using storage::lib::NodeState;
    using storage::lib::NodeType;
    using storage::lib::State;
    using vespa::config::content::StorDistributionConfigBuilder;
    typedef StorDistributionConfigBuilder::Group Group;
    typedef Group::Nodes Nodes;
    storage::lib::ClusterState cstate;
    StorDistributionConfigBuilder dc;

    cstate.setNodeState(Node(NodeType::STORAGE, 0),
                        NodeState(NodeType::STORAGE,
                                  nodeState,
                                  "dummy desc",
                                  1.0,
                                  1));
    cstate.setClusterState(State::UP);
    dc.redundancy = 1;
    dc.readyCopies = 1;
    dc.group.push_back(Group());
    Group &g(dc.group[0]);
    g.index = "invalid";
    g.name = "invalid";
    g.capacity = 1.0;
    g.partitions = "";
    g.nodes.push_back(Nodes());
    Nodes &n(g.nodes[0]);
    n.index = 0;
    Distribution dist(dc);
    return ClusterState(cstate, 0, dist);
}


struct MyDocumentRetriever : DocumentRetrieverBaseForTest {
    document::DocumentTypeRepo repo;
    const Document *document;
    Timestamp timestamp;
    DocumentId &last_doc_id;

    MyDocumentRetriever(const Document *d, Timestamp ts, DocumentId &last_id)
        : repo(), document(d), timestamp(ts), last_doc_id(last_id) {}
    virtual const document::DocumentTypeRepo &getDocumentTypeRepo() const {
        return repo;
    }
    virtual void getBucketMetaData(const storage::spi::Bucket &,
                                   search::DocumentMetaData::Vector &v) const {
        if (document != 0) {
            v.push_back(getDocumentMetaData(document->getId()));
        }
    }
    virtual DocumentMetaData getDocumentMetaData(const DocumentId &id) const {
        last_doc_id = id;
        if (document != 0) {
            return DocumentMetaData(1, timestamp, document::BucketId(1),
                                    document->getId().getGlobalId());
        }
        return DocumentMetaData();
    }
    virtual document::Document::UP getDocument(search::DocumentIdT) const {
        if (document != 0) {
            return Document::UP(document->clone());
        }
        return Document::UP();
    }

    virtual CachedSelect::SP
    parseSelect(const vespalib::string &) const
    {
        return CachedSelect::SP();
    }
};

struct MyHandler : public IPersistenceHandler, IBucketFreezer {
    bool                         initialized;
    Bucket                       lastBucket;
    Timestamp                    lastTimestamp;
    DocumentId                   lastDocId;
    Timestamp                    existingTimestamp;
    const ClusterState*          lastCalc;
    storage::spi::BucketInfo::ActiveState lastBucketState;
    BucketIdListResult::List     bucketList;
    Result                       bucketStateResult;
    BucketInfo                   bucketInfo;
    Result                       deleteBucketResult;
    BucketIdListResult::List     modBucketList;
    Result                       _splitResult;
    Result                       _joinResult;
    Result                       _createBucketResult;
    const Document *document;
    std::multiset<uint64_t> frozen;
    std::multiset<uint64_t> was_frozen;

    MyHandler()
        : initialized(false),
          lastBucket(),
          lastTimestamp(),
          lastDocId(),
          existingTimestamp(),
          lastCalc(NULL),
          lastBucketState(),
          bucketList(),
          bucketStateResult(),
          bucketInfo(),
          deleteBucketResult(),
          modBucketList(),
          _splitResult(),
          _joinResult(),
          _createBucketResult(),
          document(0),
          frozen(),
          was_frozen()
    {
    }

    void setExistingTimestamp(Timestamp ts) {
        existingTimestamp = ts;
    }
    void setDocument(const Document &doc, Timestamp ts) {
        document = &doc;
        setExistingTimestamp(ts);
    }
    void handle(FeedToken token, const Bucket &bucket, Timestamp timestamp, const DocumentId &docId) {
        lastBucket = bucket;
        lastTimestamp = timestamp;
        lastDocId = docId;
        token.ack();
    }

    virtual void initialize() { initialized = true; }

    virtual void handlePut(FeedToken token, const Bucket& bucket,
                           Timestamp timestamp, const document::Document::SP& doc) {
        token.setResult(ResultUP(new storage::spi::Result()), false);
        handle(token, bucket, timestamp, doc->getId());
    }

    virtual void handleUpdate(FeedToken token, const Bucket& bucket,
                              Timestamp timestamp, const document::DocumentUpdate::SP& upd) {
        token.setResult(ResultUP(new storage::spi::UpdateResult(existingTimestamp)),
                        existingTimestamp > 0);
        handle(token, bucket, timestamp, upd->getId());
    }

    virtual void handleRemove(FeedToken token, const Bucket& bucket,
                              Timestamp timestamp, const DocumentId& id) {
        bool wasFound = existingTimestamp > 0;
        token.setResult(ResultUP(new storage::spi::RemoveResult(wasFound)), wasFound);
        handle(token, bucket, timestamp, id);
    }

    virtual void handleListBuckets(IBucketIdListResultHandler &resultHandler) {
        resultHandler.handle(BucketIdListResult(bucketList));
    }

    virtual void handleSetClusterState(const ClusterState &calc,
                                       IGenericResultHandler &resultHandler) {
        lastCalc = &calc;
        resultHandler.handle(Result());
    }

    virtual void handleSetActiveState(const Bucket &bucket,
                                       storage::spi::BucketInfo::ActiveState newState,
                                       IGenericResultHandler &resultHandler) {
        lastBucket = bucket;
        lastBucketState = newState;
        resultHandler.handle(bucketStateResult);
    }

    virtual void handleGetBucketInfo(const Bucket &,
                                     IBucketInfoResultHandler &resultHandler) {
        resultHandler.handle(BucketInfoResult(bucketInfo));
    }

    virtual void
    handleCreateBucket(FeedToken token,
                       const storage::spi::Bucket &)
    {
        token.setResult(ResultUP(new Result(_createBucketResult)), true);
        token.ack();
    }

    virtual void handleDeleteBucket(FeedToken token,
                                    const storage::spi::Bucket &) {
        token.setResult(ResultUP(new Result(deleteBucketResult)), true);
        token.ack();
    }

    virtual void handleGetModifiedBuckets(IBucketIdListResultHandler &resultHandler) {
        resultHandler.handle(BucketIdListResult(modBucketList));
    }

    virtual void
    handleSplit(FeedToken token,
                const storage::spi::Bucket &source,
                const storage::spi::Bucket &target1,
                const storage::spi::Bucket &target2)
    {
        (void) source;
        (void) target1;
        (void) target2;
        token.setResult(ResultUP(new Result(_splitResult)), true);
        token.ack();
    }

    virtual void
    handleJoin(FeedToken token,
               const storage::spi::Bucket &source1,
               const storage::spi::Bucket &source2,
               const storage::spi::Bucket &target) override
    {
        (void) source1;
        (void) source2;
        (void) target;
        token.setResult(ResultUP(new Result(_joinResult)), true);
        token.ack();
    }

    virtual RetrieversSP getDocumentRetrievers(storage::spi::ReadConsistency) override {
        RetrieversSP ret(new std::vector<IDocumentRetriever::SP>);
        ret->push_back(IDocumentRetriever::SP(new MyDocumentRetriever(
                                0, Timestamp(), lastDocId)));
        ret->push_back(IDocumentRetriever::SP(new MyDocumentRetriever(
                                document, existingTimestamp, lastDocId)));
        return ret;
    }

    virtual BucketGuard::UP lockBucket(const storage::spi::Bucket &b) override {
        return BucketGuard::UP(new BucketGuard(b.getBucketId(), *this));
    }

    virtual void
    handleListActiveBuckets(IBucketIdListResultHandler &resultHandler) override
    {
        BucketIdListResult::List list;
        resultHandler.handle(BucketIdListResult(list));
    }

    virtual void
    handlePopulateActiveBuckets(document::BucketId::List &buckets,
                                IGenericResultHandler &resultHandler) override
    {
        (void) buckets;
        resultHandler.handle(Result());
    }

    virtual void freezeBucket(BucketId bucket) override {
        frozen.insert(bucket.getId());
        was_frozen.insert(bucket.getId());
    }
    virtual void thawBucket(BucketId bucket) override {
        std::multiset<uint64_t>::iterator it = frozen.find(bucket.getId());
        ASSERT_TRUE(it != frozen.end());
        frozen.erase(it);
    }
    bool isFrozen(const Bucket &bucket) {
        return frozen.find(bucket.getBucketId().getId()) != frozen.end();
    }
    bool wasFrozen(const Bucket &bucket) {
        return was_frozen.find(bucket.getBucketId().getId())
            != was_frozen.end();
    }
};


struct HandlerSet {
    IPersistenceHandler::SP phandler1;
    IPersistenceHandler::SP phandler2;
    MyHandler              &handler1;
    MyHandler              &handler2;
    HandlerSet() :
        phandler1(new MyHandler()),
        phandler2(new MyHandler()),
        handler1(static_cast<MyHandler &>(*phandler1.get())),
        handler2(static_cast<MyHandler &>(*phandler2.get()))
    {}
};


DocumentType type1(createDocType("type1", 1));
DocumentType type2(createDocType("type2", 2));
DocumentType type3(createDocType("type3", 3));
DocumentId docId0;
DocumentId docId1("id:type1:type1::1");
DocumentId docId2("id:type2:type2::1");
DocumentId docId3("id:type3:type3::1");
Document::SP doc1(createDoc(type1, docId1));
Document::SP doc2(createDoc(type2, docId2));
Document::SP doc3(createDoc(type3, docId3));
Document::SP old_doc(createDoc(type1, DocumentId("doc:old:id-scheme")));
document::DocumentUpdate::SP upd1(createUpd(type1, docId1));
document::DocumentUpdate::SP upd2(createUpd(type2, docId2));
document::DocumentUpdate::SP upd3(createUpd(type3, docId3));
PartitionId partId(0);
BucketId bckId1(1);
BucketId bckId2(2);
BucketId bckId3(3);
Bucket bucket0;
Bucket bucket1(bckId1, partId);
Bucket bucket2(bckId2, partId);
BucketChecksum checksum1(1);
BucketChecksum checksum2(2);
BucketChecksum checksum3(1+2);
BucketInfo bucketInfo1(checksum1, 1, 0, 1, 0);
BucketInfo bucketInfo2(checksum2, 2, 0, 2, 0);
BucketInfo bucketInfo3(checksum3, 3, 0, 3, 0);
Timestamp tstamp0;
Timestamp tstamp1(1);
Timestamp tstamp2(2);
Timestamp tstamp3(3);
DocumentSelection doc_sel("");
Selection selection(doc_sel);


class SimplePersistenceEngineOwner : public IPersistenceEngineOwner
{
    virtual void
    setClusterState(const storage::spi::ClusterState &calc)
    {
        (void) calc;
    }
};

struct SimpleResourceWriteFilter : public IResourceWriteFilter
{
    bool _acceptWriteOperation;
    vespalib::string _message;
    SimpleResourceWriteFilter()
        : _acceptWriteOperation(true),
          _message()
    {}

    virtual bool acceptWriteOperation() const override { return _acceptWriteOperation; }
    virtual State getAcceptState() const override {
        return IResourceWriteFilter::State(acceptWriteOperation(), _message);
    }
};


struct SimpleFixture {
    SimplePersistenceEngineOwner _owner;
    SimpleResourceWriteFilter _writeFilter;
    PersistenceEngine engine;
    HandlerSet hset;
    SimpleFixture()
        : _owner(),
          engine(_owner, _writeFilter, -1, false),
          hset()
    {
        engine.putHandler(DocTypeName(doc1->getType()), hset.phandler1);
        engine.putHandler(DocTypeName(doc2->getType()), hset.phandler2);
    }
};


void
assertHandler(const Bucket &expBucket, Timestamp expTimestamp,
              const DocumentId &expDocId, const MyHandler &handler)
{
    EXPECT_EQUAL(expBucket, handler.lastBucket);
    EXPECT_EQUAL(expTimestamp, handler.lastTimestamp);
    EXPECT_EQUAL(expDocId, handler.lastDocId);
}


TEST_F("require that getPartitionStates() prepares all handlers", SimpleFixture)
{
    EXPECT_FALSE(f.hset.handler1.initialized);
    EXPECT_FALSE(f.hset.handler2.initialized);
    f.engine.initialize();
    EXPECT_TRUE(f.hset.handler1.initialized);
    EXPECT_TRUE(f.hset.handler2.initialized);
}


TEST_F("require that puts are routed to handler", SimpleFixture)
{
    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    f.engine.put(bucket1, tstamp1, doc1, context);
    assertHandler(bucket1, tstamp1, docId1, f.hset.handler1);
    assertHandler(bucket0, tstamp0, docId0, f.hset.handler2);

    f.engine.put(bucket1, tstamp1, doc2, context);
    assertHandler(bucket1, tstamp1, docId1, f.hset.handler1);
    assertHandler(bucket1, tstamp1, docId2, f.hset.handler2);

    EXPECT_EQUAL(
            Result(Result::PERMANENT_ERROR, "No handler for document type 'type3'"),
            f.engine.put(bucket1, tstamp1, doc3, context));
}


TEST_F("require that puts with old id scheme are rejected", SimpleFixture) {
    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    EXPECT_EQUAL(
            Result(Result::PERMANENT_ERROR, "Old id scheme not supported in "
                   "elastic mode (doc:old:id-scheme)"),
            f.engine.put(bucket1, tstamp1, old_doc, context));
}


TEST_F("require that put is rejected if resource limit is reached", SimpleFixture)
{
    f._writeFilter._acceptWriteOperation = false;
    f._writeFilter._message = "Disk is full";

    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    EXPECT_EQUAL(
            Result(Result::RESOURCE_EXHAUSTED,
                   "Put operation rejected for document 'doc:old:id-scheme': 'Disk is full'"),
            f.engine.put(bucket1, tstamp1, old_doc, context));
}


TEST_F("require that updates are routed to handler", SimpleFixture)
{
    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    f.hset.handler1.setExistingTimestamp(tstamp2);
    UpdateResult ur = f.engine.update(bucket1, tstamp1, upd1, context);
    assertHandler(bucket1, tstamp1, docId1, f.hset.handler1);
    assertHandler(bucket0, tstamp0, docId0, f.hset.handler2);
    EXPECT_EQUAL(tstamp2, ur.getExistingTimestamp());

    f.hset.handler2.setExistingTimestamp(tstamp3);
    ur = f.engine.update(bucket1, tstamp1, upd2, context);
    assertHandler(bucket1, tstamp1, docId1, f.hset.handler1);
    assertHandler(bucket1, tstamp1, docId2, f.hset.handler2);
    EXPECT_EQUAL(tstamp3, ur.getExistingTimestamp());

    EXPECT_EQUAL(
            Result(Result::PERMANENT_ERROR, "No handler for document type 'type3'"),
            f.engine.update(bucket1, tstamp1, upd3, context));
}


TEST_F("require that update is rejected if resource limit is reached", SimpleFixture)
{
    f._writeFilter._acceptWriteOperation = false;
    f._writeFilter._message = "Disk is full";

    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));

    EXPECT_EQUAL(
            Result(Result::RESOURCE_EXHAUSTED,
                   "Update operation rejected for document 'id:type1:type1::1': 'Disk is full'"),
            f.engine.update(bucket1, tstamp1, upd1, context));
}


TEST_F("require that removes are routed to handlers", SimpleFixture)
{
    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    RemoveResult rr = f.engine.remove(bucket1, tstamp1, docId3, context);
    assertHandler(bucket0, tstamp0, docId0, f.hset.handler1);
    assertHandler(bucket0, tstamp0, docId0, f.hset.handler2);
    EXPECT_FALSE(rr.wasFound());

    f.hset.handler1.setExistingTimestamp(tstamp2);
    rr = f.engine.remove(bucket1, tstamp1, docId1, context);
    assertHandler(bucket1, tstamp1, docId1, f.hset.handler1);
    assertHandler(bucket0, tstamp0, docId0, f.hset.handler2);
    EXPECT_TRUE(rr.wasFound());

    f.hset.handler1.setExistingTimestamp(tstamp0);
    f.hset.handler2.setExistingTimestamp(tstamp3);
    rr = f.engine.remove(bucket1, tstamp1, docId2, context);
    assertHandler(bucket1, tstamp1, docId1, f.hset.handler1);
    assertHandler(bucket1, tstamp1, docId2, f.hset.handler2);
    EXPECT_TRUE(rr.wasFound());

    f.hset.handler2.setExistingTimestamp(tstamp0);
    rr = f.engine.remove(bucket1, tstamp1, docId2, context);
    assertHandler(bucket1, tstamp1, docId1, f.hset.handler1);
    assertHandler(bucket1, tstamp1, docId2, f.hset.handler2);
    EXPECT_FALSE(rr.wasFound());
}


TEST_F("require that remove is NOT rejected if resource limit is reached", SimpleFixture)
{
    f._writeFilter._acceptWriteOperation = false;
    f._writeFilter._message = "Disk is full";

    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));

    EXPECT_EQUAL(RemoveResult(false), f.engine.remove(bucket1, tstamp1, docId1, context));
}


TEST_F("require that listBuckets() is routed to handlers and merged", SimpleFixture)
{
    f.hset.handler1.bucketList.push_back(bckId1);
    f.hset.handler1.bucketList.push_back(bckId2);
    f.hset.handler2.bucketList.push_back(bckId2);
    f.hset.handler2.bucketList.push_back(bckId3);

    EXPECT_TRUE(f.engine.listBuckets(PartitionId(1)).getList().empty());
    BucketIdListResult result = f.engine.listBuckets(partId);
    const BucketIdListResult::List &bucketList = result.getList();
    EXPECT_EQUAL(3u, bucketList.size());
    EXPECT_EQUAL(bckId1, bucketList[0]);
    EXPECT_EQUAL(bckId2, bucketList[1]);
    EXPECT_EQUAL(bckId3, bucketList[2]);
}


TEST_F("require that setClusterState() is routed to handlers", SimpleFixture)
{
    ClusterState state(createClusterState());

    f.engine.setClusterState(state);
    EXPECT_EQUAL(&state, f.hset.handler1.lastCalc);
    EXPECT_EQUAL(&state, f.hset.handler2.lastCalc);
}


TEST_F("require that setActiveState() is routed to handlers and merged", SimpleFixture)
{
    f.hset.handler1.bucketStateResult = Result(Result::TRANSIENT_ERROR, "err1");
    f.hset.handler2.bucketStateResult = Result(Result::PERMANENT_ERROR, "err2");

    Result result = f.engine.setActiveState(bucket1,
                                             storage::spi::BucketInfo::NOT_ACTIVE);
    EXPECT_EQUAL(Result::PERMANENT_ERROR, result.getErrorCode());
    EXPECT_EQUAL("err1, err2", result.getErrorMessage());
    EXPECT_EQUAL(storage::spi::BucketInfo::NOT_ACTIVE, f.hset.handler1.lastBucketState);
    EXPECT_EQUAL(storage::spi::BucketInfo::NOT_ACTIVE, f.hset.handler2.lastBucketState);

    f.engine.setActiveState(bucket1, storage::spi::BucketInfo::ACTIVE);
    EXPECT_EQUAL(storage::spi::BucketInfo::ACTIVE, f.hset.handler1.lastBucketState);
    EXPECT_EQUAL(storage::spi::BucketInfo::ACTIVE, f.hset.handler2.lastBucketState);
}


TEST_F("require that getBucketInfo() is routed to handlers and merged", SimpleFixture)
{
    f.hset.handler1.bucketInfo = bucketInfo1;
    f.hset.handler2.bucketInfo = bucketInfo2;

    BucketInfoResult result = f.engine.getBucketInfo(bucket1);
    EXPECT_EQUAL(bucketInfo3, result.getBucketInfo());
}


TEST_F("require that createBucket() is routed to handlers and merged",
       SimpleFixture)
{
    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    f.hset.handler1._createBucketResult =
        Result(Result::TRANSIENT_ERROR, "err1a");
    f.hset.handler2._createBucketResult =
        Result(Result::PERMANENT_ERROR, "err2a");

    Result result = f.engine.createBucket(bucket1, context);
    EXPECT_EQUAL(Result::PERMANENT_ERROR, result.getErrorCode());
    EXPECT_EQUAL("err1a, err2a", result.getErrorMessage());
}


TEST_F("require that deleteBucket() is routed to handlers and merged", SimpleFixture)
{
    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    f.hset.handler1.deleteBucketResult = Result(Result::TRANSIENT_ERROR, "err1");
    f.hset.handler2.deleteBucketResult = Result(Result::PERMANENT_ERROR, "err2");

    Result result = f.engine.deleteBucket(bucket1, context);
    EXPECT_EQUAL(Result::PERMANENT_ERROR, result.getErrorCode());
    EXPECT_EQUAL("err1, err2", result.getErrorMessage());
}


TEST_F("require that getModifiedBuckets() is routed to handlers and merged", SimpleFixture)
{
    f.hset.handler1.modBucketList.push_back(bckId1);
    f.hset.handler1.modBucketList.push_back(bckId2);
    f.hset.handler2.modBucketList.push_back(bckId2);
    f.hset.handler2.modBucketList.push_back(bckId3);

    BucketIdListResult result = f.engine.getModifiedBuckets();
    const BucketIdListResult::List &bucketList = result.getList();
    EXPECT_EQUAL(3u, bucketList.size());
    EXPECT_EQUAL(bckId1, bucketList[0]);
    EXPECT_EQUAL(bckId2, bucketList[1]);
    EXPECT_EQUAL(bckId3, bucketList[2]);
}


TEST_F("require that get is sent to all handlers", SimpleFixture) {
    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    GetResult result = f.engine.get(bucket1, document::AllFields(), docId1,
                                    context);

    EXPECT_EQUAL(docId1, f.hset.handler1.lastDocId);
    EXPECT_EQUAL(docId1, f.hset.handler2.lastDocId);
}

TEST_F("require that get freezes the bucket", SimpleFixture) {
    EXPECT_FALSE(f.hset.handler1.wasFrozen(bucket1));
    EXPECT_FALSE(f.hset.handler2.wasFrozen(bucket1));
    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    f.engine.get(bucket1, document::AllFields(), docId1, context);
    EXPECT_TRUE(f.hset.handler1.wasFrozen(bucket1));
    EXPECT_TRUE(f.hset.handler2.wasFrozen(bucket1));
    EXPECT_FALSE(f.hset.handler1.isFrozen(bucket1));
    EXPECT_FALSE(f.hset.handler2.isFrozen(bucket1));
}

TEST_F("require that get returns the first document found", SimpleFixture) {
    f.hset.handler1.setDocument(*doc1, tstamp1);
    f.hset.handler2.setDocument(*doc2, tstamp2);
    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    GetResult result = f.engine.get(bucket1, document::AllFields(), docId1,
                                    context);

    EXPECT_EQUAL(docId1, f.hset.handler1.lastDocId);
    EXPECT_EQUAL(DocumentId(), f.hset.handler2.lastDocId);

    EXPECT_EQUAL(tstamp1, result.getTimestamp());
    ASSERT_TRUE(result.hasDocument());
    EXPECT_EQUAL(*doc1, result.getDocument());
}

TEST_F("require that createIterator does", SimpleFixture) {
    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    CreateIteratorResult result =
        f.engine.createIterator(bucket1, document::AllFields(), selection,
                                storage::spi::NEWEST_DOCUMENT_ONLY, context);
    EXPECT_FALSE(result.hasError());
    EXPECT_TRUE(result.getIteratorId());

    uint64_t max_size = 1024;
    IterateResult it_result =
        f.engine.iterate(result.getIteratorId(), max_size, context);
    EXPECT_FALSE(it_result.hasError());
}

TEST_F("require that iterator ids are unique", SimpleFixture) {
    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    CreateIteratorResult result =
        f.engine.createIterator(bucket1, document::AllFields(), selection,
                                storage::spi::NEWEST_DOCUMENT_ONLY, context);
    CreateIteratorResult result2 =
        f.engine.createIterator(bucket1, document::AllFields(), selection,
                                storage::spi::NEWEST_DOCUMENT_ONLY, context);
    EXPECT_FALSE(result.hasError());
    EXPECT_FALSE(result2.hasError());
    EXPECT_NOT_EQUAL(result.getIteratorId(), result2.getIteratorId());
}

TEST_F("require that iterate requires valid iterator", SimpleFixture) {
    uint64_t max_size = 1024;
    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    IterateResult it_result = f.engine.iterate(IteratorId(1), max_size,
                                               context);
    EXPECT_TRUE(it_result.hasError());
    EXPECT_EQUAL(Result::PERMANENT_ERROR, it_result.getErrorCode());
    EXPECT_EQUAL("Unknown iterator with id 1", it_result.getErrorMessage());

    CreateIteratorResult result =
        f.engine.createIterator(bucket1, document::AllFields(), selection,
                                storage::spi::NEWEST_DOCUMENT_ONLY, context);
    EXPECT_TRUE(result.getIteratorId());

    it_result = f.engine.iterate(result.getIteratorId(), max_size, context);
    EXPECT_FALSE(it_result.hasError());
}

TEST_F("require that iterate returns documents", SimpleFixture) {
    f.hset.handler1.setDocument(*doc1, tstamp1);
    f.hset.handler2.setDocument(*doc2, tstamp2);

    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    uint64_t max_size = 1024;
    CreateIteratorResult result =
        f.engine.createIterator(bucket1, document::AllFields(), selection,
                                storage::spi::NEWEST_DOCUMENT_ONLY, context);
    EXPECT_TRUE(result.getIteratorId());

    IterateResult it_result =
        f.engine.iterate(result.getIteratorId(), max_size, context);
    EXPECT_FALSE(it_result.hasError());
    EXPECT_EQUAL(2u, it_result.getEntries().size());
}

TEST_F("require that destroyIterator prevents iteration", SimpleFixture) {
    f.hset.handler1.setDocument(*doc1, tstamp1);

    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    CreateIteratorResult create_result =
        f.engine.createIterator(bucket1, document::AllFields(), selection,
                                storage::spi::NEWEST_DOCUMENT_ONLY, context);
    EXPECT_TRUE(create_result.getIteratorId());

    Result result = f.engine.destroyIterator(create_result.getIteratorId(),
                                             context);
    EXPECT_FALSE(result.hasError());

    uint64_t max_size = 1024;
    IterateResult it_result =
        f.engine.iterate(create_result.getIteratorId(), max_size, context);
    EXPECT_TRUE(it_result.hasError());
    EXPECT_EQUAL(Result::PERMANENT_ERROR, it_result.getErrorCode());
    string msg_prefix = "Unknown iterator with id";
    EXPECT_EQUAL(msg_prefix,
                 it_result.getErrorMessage().substr(0, msg_prefix.size()));
}

TEST_F("require that buckets are frozen during iterator life", SimpleFixture) {
    EXPECT_FALSE(f.hset.handler1.isFrozen(bucket1));
    EXPECT_FALSE(f.hset.handler2.isFrozen(bucket1));
    storage::spi::LoadType loadType(0, "default");
    Context context(loadType, storage::spi::Priority(0),
                    storage::spi::Trace::TraceLevel(0));
    CreateIteratorResult create_result =
        f.engine.createIterator(bucket1, document::AllFields(), selection,
                                storage::spi::NEWEST_DOCUMENT_ONLY, context);
    EXPECT_TRUE(f.hset.handler1.isFrozen(bucket1));
    EXPECT_TRUE(f.hset.handler2.isFrozen(bucket1));
    f.engine.destroyIterator(create_result.getIteratorId(), context);
    EXPECT_FALSE(f.hset.handler1.isFrozen(bucket1));
    EXPECT_FALSE(f.hset.handler2.isFrozen(bucket1));
}

TEST_MAIN()
{
    TEST_RUN_ALL();
}

