// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/vespalib/testkit/testapp.h>
#include <vespa/searchcore/proton/documentmetastore/i_store.h>
#include <vespa/searchcore/proton/documentmetastore/lidreusedelayer.h>
#include <vespa/searchcore/proton/server/executorthreadingservice.h>
#include <vespa/searchcore/proton/test/thread_utils.h>
#include <vespa/searchcore/proton/test/threading_service_observer.h>
#include <vespa/vespalib/util/lambdatask.h>

#include <vespa/log/log.h>
LOG_SETUP("lidreusedelayer_test");

using vespalib::makeLambdaTask;

namespace proton {

namespace {

bool
assertThreadObserver(uint32_t masterExecuteCnt,
                     uint32_t indexExecuteCnt,
                     uint32_t summaryExecuteCnt,
                     const test::ThreadingServiceObserver &observer)
{
    if (!EXPECT_EQUAL(masterExecuteCnt,
                      observer.masterObserver().getExecuteCnt())) {
        return false;
    }
    if (!EXPECT_EQUAL(summaryExecuteCnt,
                      observer.summaryObserver().getExecuteCnt())) {
        return false;
    }
    if (!EXPECT_EQUAL(indexExecuteCnt,
                      observer.indexObserver().getExecuteCnt())) {
        return false;
    }
    return true;
}

}

class MyMetaStore : public documentmetastore::IStore
{
public:
    bool     _freeListActive;
    uint32_t _removeCompleteCount;
    uint32_t _removeBatchCompleteCount;
    uint32_t _removeCompleteLids;

    MyMetaStore()
        : _freeListActive(false),
          _removeCompleteCount(0),
          _removeBatchCompleteCount(0),
          _removeCompleteLids(0)
    {
    }

    ~MyMetaStore() override = default;

    Result inspectExisting(const GlobalId &) const override {
        return Result();
    }

    Result inspect(const GlobalId &) override {
        return Result();
    }

    Result put(const GlobalId &, const BucketId &, const Timestamp &, uint32_t, DocId) override {
        return Result();
    }

    bool updateMetaData(DocId, const BucketId &, const Timestamp &) override {
        return true;
    }

    bool remove(DocId) override {
        return true;
    }

    void removeComplete(DocId) override {
        ++_removeCompleteCount;
        ++_removeCompleteLids;
    }

    void move(DocId, DocId) override {
    }

    bool validLid(DocId) const override {
        return true;
    }

    void removeBatch(const std::vector<DocId> &, const DocId) override {}

    void removeBatchComplete(const std::vector<DocId> &lidsToRemove) override{
        ++_removeBatchCompleteCount;
        _removeCompleteLids += lidsToRemove.size();
    }

    const RawDocumentMetaData &getRawMetaData(DocId) const override {
        LOG_ABORT("should not be reached");
    }

    bool getFreeListActive() const override {
        return _freeListActive;
    }

    bool
    assertWork(uint32_t expRemoveCompleteCount,
               uint32_t expRemoveBatchCompleteCount,
               uint32_t expRemoveCompleteLids) const
    {
        if (!EXPECT_EQUAL(expRemoveCompleteCount, _removeCompleteCount)) {
            return false;
        }
        if (!EXPECT_EQUAL(expRemoveBatchCompleteCount,
                          _removeBatchCompleteCount)) {
            return false;
        }
        if (!EXPECT_EQUAL(expRemoveCompleteLids, _removeCompleteLids)) {
            return false;
        }
        return true;
    }
};

class Fixture
{
public:
    using LidReuseDelayer = documentmetastore::LidReuseDelayer;
    using LidReuseDelayerConfig = documentmetastore::LidReuseDelayerConfig;
    vespalib::ThreadStackExecutor _sharedExecutor;
    ExecutorThreadingService _writeServiceReal;
    test::ThreadingServiceObserver _writeService;
    MyMetaStore _store;
    std::unique_ptr<LidReuseDelayer> _lidReuseDelayer;

    Fixture()
        : _sharedExecutor(1, 0x10000),
          _writeServiceReal(_sharedExecutor),
          _writeService(_writeServiceReal),
          _store(),
          _lidReuseDelayer(std::make_unique<LidReuseDelayer>(_writeService, _store, LidReuseDelayerConfig()))
    {
    }

    ~Fixture() {
        commit();
    }

    template <typename FunctionType>
    void runInMaster(FunctionType func) {
        test::runInMaster(_writeService, func);
    }

    void
    cycledLids(const std::vector<uint32_t> &lids)
    {
        if (lids.size() == 1) {
            _store.removeComplete(lids[0]);
        } else {
            _store.removeBatchComplete(lids);
        }
    }

    void
    performCycleLids(const std::vector<uint32_t> &lids)
    {
        _writeService.master().execute(
                makeLambdaTask([=]() { cycledLids(lids);}));
    }

    void
    cycleLids(const std::vector<uint32_t> &lids)
    {
        if (lids.empty())
            return;
        _writeService.index().execute(
                makeLambdaTask([=]() { performCycleLids(lids);}));
    }

    bool
    delayReuse(uint32_t lid)
    {
        bool res = false;
        runInMaster([&] () { res = _lidReuseDelayer->delayReuse(lid); } );
        return res;
    }

    bool
    delayReuse(const std::vector<uint32_t> &lids)
    {
        bool res = false;
        runInMaster([&] () { res = _lidReuseDelayer->delayReuse(lids); });
        return res;
    }

    void
    configureLidReuseDelayer(bool immediateCommit, bool hasIndexedOrAttributeFields) {
        runInMaster([&] () {
            _lidReuseDelayer = std::make_unique<LidReuseDelayer>(_writeService, _store,
                                                                 LidReuseDelayerConfig(immediateCommit ? vespalib::duration::zero() : 1ms,
                                                                                       hasIndexedOrAttributeFields));
        } );
    }

    void commit() {
        runInMaster([&] () { cycleLids(_lidReuseDelayer->getReuseLids()); });
    }

    void
    sync()
    {
        _writeService.sync();
    }

    void
    scheduleDelayReuseLid(uint32_t lid)
    {
        runInMaster([&] () { cycleLids({ lid }); });
    }

    void
    scheduleDelayReuseLids(const std::vector<uint32_t> &lids)
    {
        runInMaster([&] () { cycleLids(lids); });
    }
};


TEST_F("require that nothing happens before free list is active", Fixture)
{
    f.configureLidReuseDelayer(true, true);
    EXPECT_FALSE(f.delayReuse(4));
    EXPECT_FALSE(f.delayReuse({ 5, 6}));
    EXPECT_TRUE(f._store.assertWork(0, 0, 0));
    EXPECT_TRUE(assertThreadObserver(3, 0, 0, f._writeService));
}


TEST_F("require that single lid is delayed", Fixture)
{
    f._store._freeListActive = true;
    f.configureLidReuseDelayer(true, true);
    EXPECT_TRUE(f.delayReuse(4));
    f.scheduleDelayReuseLid(4);
    EXPECT_TRUE(f._store.assertWork(1, 0, 1));
    EXPECT_TRUE(assertThreadObserver(4, 1, 0, f._writeService));
}


TEST_F("require that lid vector is delayed", Fixture)
{
    f._store._freeListActive = true;
    f.configureLidReuseDelayer(true, true);
    EXPECT_TRUE(f.delayReuse({ 5, 6, 7}));
    f.scheduleDelayReuseLids({ 5, 6, 7});
    EXPECT_TRUE(f._store.assertWork(0, 1, 3));
    EXPECT_TRUE(assertThreadObserver(4, 1, 0, f._writeService));
}


TEST_F("require that reuse can be batched", Fixture)
{
    f._store._freeListActive = true;
    f.configureLidReuseDelayer(false, true);
    EXPECT_FALSE(f.delayReuse(4));
    EXPECT_FALSE(f.delayReuse({ 5, 6, 7}));
    EXPECT_TRUE(f._store.assertWork(0, 0, 0));
    EXPECT_TRUE(assertThreadObserver(3, 0, 0, f._writeService));
    f.commit();
    EXPECT_TRUE(f._store.assertWork(0, 1, 4));
    EXPECT_TRUE(assertThreadObserver(5, 1, 0, f._writeService));
    EXPECT_FALSE(f.delayReuse(8));
    EXPECT_FALSE(f.delayReuse({ 9, 10}));
    EXPECT_TRUE(f._store.assertWork(0, 1, 4));
    EXPECT_TRUE(assertThreadObserver(7, 1, 0, f._writeService));
}


TEST_F("require that single element array is optimized", Fixture)
{
    f._store._freeListActive = true;
    f.configureLidReuseDelayer(false, true);
    EXPECT_FALSE(f.delayReuse({ 4}));
    EXPECT_TRUE(f._store.assertWork(0, 0, 0));
    EXPECT_TRUE(assertThreadObserver(2, 0, 0, f._writeService));
    f.commit();
    f.configureLidReuseDelayer(true, true);
    EXPECT_TRUE(f._store.assertWork(1, 0, 1));
    EXPECT_TRUE(assertThreadObserver(5, 1, 0, f._writeService));
    EXPECT_TRUE(f.delayReuse({ 8}));
    f.scheduleDelayReuseLids({ 8});
    EXPECT_TRUE(f._store.assertWork(2, 0, 2));
    EXPECT_TRUE(assertThreadObserver(8, 2, 0, f._writeService));
}


TEST_F("require that lids are reused faster with no indexed fields", Fixture)
{
    f._store._freeListActive = true;
    f.configureLidReuseDelayer(true, false);
    EXPECT_FALSE(f.delayReuse(4));
    EXPECT_TRUE(f._store.assertWork(1, 0, 1));
    EXPECT_TRUE(assertThreadObserver(2, 0, 0, f._writeService));
    EXPECT_FALSE(f.delayReuse({ 5, 6, 7}));
    EXPECT_TRUE(f._store.assertWork(1, 1, 4));
    EXPECT_TRUE(assertThreadObserver(3, 0, 0, f._writeService));
}

}



TEST_MAIN()
{
    TEST_RUN_ALL();
}
