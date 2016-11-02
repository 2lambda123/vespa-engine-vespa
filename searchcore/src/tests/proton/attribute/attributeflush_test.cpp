// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP("attributeflush_test");
#include <vespa/vespalib/testkit/testapp.h>
#include <vespa/vespalib/util/threadstackexecutor.h>
#include <vespa/vespalib/util/sync.h>
#include <vespa/searchcore/proton/attribute/attributemanager.h>
#include <vespa/searchcore/proton/attribute/attribute_writer.h>
#include <vespa/searchcore/proton/attribute/flushableattribute.h>
#include <vespa/searchlib/attribute/attributefactory.h>
#include <vespa/searchlib/attribute/integerbase.h>
#include <vespa/searchlib/common/indexmetainfo.h>
#include <vespa/searchlib/util/dirtraverse.h>
#include <vespa/vespalib/io/fileutil.h>
#include <vespa/searchlib/index/dummyfileheadercontext.h>
#include <vespa/searchlib/common/foregroundtaskexecutor.h>
#include <vespa/searchcore/proton/test/directory_handler.h>
#include <vespa/vespalib/util/mock_hw_info.h>
#include <vespa/searchlib/attribute/attributevector.hpp>

using namespace document;
using namespace search;
using namespace vespalib;

using search::index::DummyFileHeaderContext;

typedef search::attribute::Config AVConfig;
typedef search::attribute::BasicType AVBasicType;
typedef search::attribute::CollectionType AVCollectionType;

typedef std::shared_ptr<Gate> GateSP;

namespace proton {

namespace
{

const uint64_t createSerialNum = 42u;

}

class TaskWrapper : public Executor::Task
{
private:
    Executor::Task::UP _task;
    GateSP             _gate;
public:
    TaskWrapper(Executor::Task::UP task, const GateSP &gate)
        : _task(std::move(task)),
          _gate(gate)
    {
    }

    virtual void
    run(void)
    {
        _task->run();
        _gate->countDown();
        LOG(info, "doneFlushing");
    }
};


class FlushHandler
{
private:
    ThreadStackExecutor _executor;
public:
    GateSP gate;

    FlushHandler()
        : _executor(1, 65536),
          gate()
    {
    }

    void
    doFlushing(Executor::Task::UP task)
    {
        Executor::Task::UP wrapper(new TaskWrapper(std::move(task), gate));
        Executor::Task::UP ok = _executor.execute(std::move(wrapper));
        assert(ok.get() == NULL);
    }
};


class UpdaterTask
{
private:
    proton::AttributeManager & _am;
public:
    UpdaterTask(proton::AttributeManager & am)
        :
        _am(am)
    {
    }

    void
    startFlushing(uint64_t syncToken, FlushHandler & handler);

    void
    run(void);
};


void
UpdaterTask::startFlushing(uint64_t syncToken, FlushHandler & handler)
{
    handler.gate.reset(new Gate());
    IFlushTarget::SP flushable = _am.getFlushable("a1");
    LOG(info, "startFlushing(%" PRIu64 ")", syncToken);
    handler.doFlushing(flushable->initFlush(syncToken));
}


void
UpdaterTask::run(void)
{
    LOG(info, "UpdaterTask::run(begin)");
    uint32_t totalDocs = 2000000;
    uint32_t totalDocsMax = 125000000; // XXX: Timing dependent.
    uint32_t slowdownUpdateLim = 4000000;
    bool slowedDown = false;
    uint32_t incDocs = 1000;
    uint64_t commits = 0;
    uint32_t flushCount = 0;
    uint64_t flushedToken = 0;
    uint64_t needFlushToken = 0;
    FlushHandler flushHandler;
    for (uint32_t i = incDocs;
         i <= totalDocs || (flushCount + (flushedToken <
                                          needFlushToken) <= 2 &&
                            i <= totalDocsMax);
         i += incDocs) {
        uint32_t startDoc = 0;
        uint32_t lastDoc = 0;
        AttributeGuard::UP agap = _am.getAttribute("a1");
        AttributeGuard &ag(*agap);
        IntegerAttribute & ia = static_cast<IntegerAttribute &>(*ag);
        for (uint32_t j = i - incDocs; j < i; ++j) {
            if (j >= ag->getNumDocs()) {
                ag->addDocs(startDoc, lastDoc, incDocs);
                if (i % (totalDocs / 20) == 0) {
                    LOG(info,
                        "addDocs(%u, %u, %u)",
                        startDoc, lastDoc, ag->getNumDocs());
                }
            }
            ia.update(j, i);
        }
        ia.commit(i-1, i); // save i as last sync token
        needFlushToken = i;
        assert(i + 1 == ag->getNumDocs());
        if ((commits++ % 20 == 0) &&
            (flushHandler.gate.get() == NULL ||
             flushHandler.gate->getCount() == 0)) {
            startFlushing(i, flushHandler);
            ++flushCount;
            flushedToken = i;
            slowedDown = false;
        }
        if (needFlushToken > flushedToken + slowdownUpdateLim) {
            FastOS_Thread::Sleep(100);
            if (!slowedDown) {
                LOG(warning,
                    "Slowing down updates due to slow flushing (slow disk ?)");
            }
            slowedDown = true;
        }
    }
    if (flushHandler.gate.get() != NULL) {
        flushHandler.gate->await();
    }
    if (flushedToken < needFlushToken) {
        startFlushing(needFlushToken, flushHandler);
        flushHandler.gate->await();
    }
    LOG(info, "UpdaterTask::run(end)");
}


AVConfig
getInt32Config()
{
    return AVConfig(AVBasicType::INT32);
}


class Test : public vespalib::TestApp
{
private:
    void
    requireThatUpdaterAndFlusherCanRunConcurrently(void);

    void
    requireThatFlushableAttributeReportsMemoryUsage(void);

    void
    requireThatFlushableAttributeManagesSyncTokenInfo(std::shared_ptr<vespalib::IHwInfo> hwInfo);

    void
    requireThatFlushTargetsCanBeRetrieved(void);

    void
    requireThatCleanUpIsPerformedAfterFlush(void);

    void
    requireThatFlushStatsAreUpdated(void);

    void
    requireThatOnlyOneFlusherCanRunAtTheSameTime(void);

    void
    requireThatLastFlushTimeIsReported(void);

    void
    requireThatShrinkWorks();
public:
    int
    Main(void);
};


const string test_dir = "flush";

struct BaseFixture
{
    test::DirectoryHandler _dirHandler;
    DummyFileHeaderContext   _fileHeaderContext;
    ForegroundTaskExecutor   _attributeFieldWriter;
    std::shared_ptr<vespalib::IHwInfo> _hwInfo;
    BaseFixture()
        : _dirHandler(test_dir),
          _fileHeaderContext(),
          _attributeFieldWriter(),
          _hwInfo(std::make_shared<vespalib::MockHwInfo>())
    {
    }
    BaseFixture(const std::shared_ptr<IHwInfo> &hwInfo)
        : _dirHandler(test_dir),
          _fileHeaderContext(),
          _attributeFieldWriter(),
          _hwInfo(hwInfo)
    {
    }
};


struct AttributeManagerFixture
{
    AttributeManager::SP _msp;
    AttributeManager &_m;
    AttributeWriter _aw;
    AttributeManagerFixture(BaseFixture &bf)
        : _msp(std::make_shared<AttributeManager>
               (test_dir, "test.subdb", TuneFileAttributes(), bf._fileHeaderContext,
                bf._attributeFieldWriter, bf._hwInfo)),
          _m(*_msp),
          _aw(_msp)
    {
    }
    AttributeVector::SP addAttribute(const vespalib::string &name) {
        return _m.addAttribute(name, getInt32Config(), createSerialNum);
    }
};

struct Fixture : public BaseFixture, public AttributeManagerFixture
{
    Fixture()
        : BaseFixture(),
          AttributeManagerFixture(*static_cast<BaseFixture *>(this))
    {
    }
    Fixture(const std::shared_ptr<IHwInfo> &hwInfo)
        : BaseFixture(hwInfo),
          AttributeManagerFixture(*static_cast<BaseFixture *>(this))
    {
    }
};



void
Test::requireThatUpdaterAndFlusherCanRunConcurrently(void)
{
    Fixture f;
    AttributeManager &am = f._m;
    EXPECT_TRUE(f.addAttribute("a1").get() != NULL);
    IFlushTarget::SP ft = am.getFlushable("a1");
    (static_cast<FlushableAttribute *>(ft.get()))->setCleanUpAfterFlush(false);
    UpdaterTask updaterTask(am);
    updaterTask.run();

    IndexMetaInfo info("flush/a1");
    EXPECT_TRUE(info.load());
    EXPECT_TRUE(info.snapshots().size() > 2);
    for (size_t i = 0; i < info.snapshots().size(); ++i) {
        const IndexMetaInfo::Snapshot & snap = info.snapshots()[i];
        LOG(info,
            "Snapshot(%" PRIu64 ", %s)",
            snap.syncToken, snap.dirName.c_str());
        if (snap.syncToken > 0) {
            EXPECT_TRUE(snap.valid);
            std::string baseFileName = "flush/a1/" + snap.dirName + "/a1";
            AttributeVector::SP attr =
                AttributeFactory::createAttribute(baseFileName,
                        getInt32Config());
            EXPECT_TRUE(attr->load());
            EXPECT_EQUAL((uint32_t)snap.syncToken + 1, attr->getNumDocs());
        }
    }
}


void
Test::requireThatFlushableAttributeReportsMemoryUsage(void)
{
    Fixture f;
    AttributeManager &am = f._m;
    AttributeVector::SP av = f.addAttribute("a2");
    av->addDocs(100);
    av->commit();
    IFlushTarget::SP fa = am.getFlushable("a2");
    EXPECT_TRUE(av->getStatus().getAllocated() >= 100u * sizeof(int32_t));
    EXPECT_EQUAL(av->getStatus().getUsed(),
               fa->getApproxMemoryGain().getBefore()+0lu);
    // attributes stay in memory
    EXPECT_EQUAL(fa->getApproxMemoryGain().getBefore(),
               fa->getApproxMemoryGain().getAfter());
}


void
Test::requireThatFlushableAttributeManagesSyncTokenInfo(std::shared_ptr<vespalib::IHwInfo> hwInfo)
{
    Fixture f(hwInfo);
    AttributeManager &am = f._m;
    AttributeVector::SP av = f.addAttribute("a3");
    av->addDocs(1);
    IFlushTarget::SP fa = am.getFlushable("a3");

    IndexMetaInfo info("flush/a3");
    EXPECT_EQUAL(0u, fa->getFlushedSerialNum());
    EXPECT_TRUE(fa->initFlush(0).get() == NULL);
    EXPECT_TRUE(info.load());
    EXPECT_EQUAL(0u, info.snapshots().size());

    av->commit(10, 10); // last sync token = 10
    EXPECT_EQUAL(0u, fa->getFlushedSerialNum());
    EXPECT_TRUE(fa->initFlush(10).get() != NULL);
    fa->initFlush(10)->run();
    EXPECT_EQUAL(10u, fa->getFlushedSerialNum());
    EXPECT_TRUE(info.load());
    EXPECT_EQUAL(1u, info.snapshots().size());
    EXPECT_TRUE(info.snapshots()[0].valid);
    EXPECT_EQUAL(10u, info.snapshots()[0].syncToken);

    av->commit(20, 20); // last sync token = 20
    EXPECT_EQUAL(10u, fa->getFlushedSerialNum());
    fa->initFlush(20)->run();
    EXPECT_EQUAL(20u, fa->getFlushedSerialNum());
    EXPECT_TRUE(info.load());
    EXPECT_EQUAL(1u, info.snapshots().size()); // snapshot 10 removed
    EXPECT_TRUE(info.snapshots()[0].valid);
    EXPECT_EQUAL(20u, info.snapshots()[0].syncToken);
}


void
Test::requireThatFlushTargetsCanBeRetrieved(void)
{
    Fixture f;
    AttributeManager &am = f._m;
    f.addAttribute("a4");
    f.addAttribute("a5");
    std::vector<IFlushTarget::SP> ftl = am.getFlushTargets();
    EXPECT_EQUAL(2u, ftl.size());
    EXPECT_EQUAL(am.getFlushable("a4").get(), ftl[0].get());
    EXPECT_EQUAL(am.getFlushable("a5").get(), ftl[1].get());
}


void
Test::requireThatCleanUpIsPerformedAfterFlush(void)
{
    Fixture f;
    AttributeVector::SP av = f.addAttribute("a6");
    av->addDocs(1);
    av->commit(30, 30);

    // fake up some snapshots
    std::string snap10 = "flush/a6/snapshot-10";
    std::string snap20 = "flush/a6/snapshot-20";
    vespalib::mkdir(snap10, false);
    vespalib::mkdir(snap20, false);
    IndexMetaInfo info("flush/a6");
    info.addSnapshot(IndexMetaInfo::Snapshot(true, 10, "snapshot-10"));
    info.addSnapshot(IndexMetaInfo::Snapshot(false, 20, "snapshot-20"));
    EXPECT_TRUE(info.save());

    FlushableAttribute fa(av, "flush", TuneFileAttributes(),
                          f._fileHeaderContext, f._attributeFieldWriter,
                          f._hwInfo);
    fa.initFlush(30)->run();

    EXPECT_TRUE(info.load());
    EXPECT_EQUAL(1u, info.snapshots().size()); // snapshots 10 & 20 removed
    EXPECT_TRUE(info.snapshots()[0].valid);
    EXPECT_EQUAL(30u, info.snapshots()[0].syncToken);
    FastOS_StatInfo statInfo;
    EXPECT_TRUE(!FastOS_File::Stat(snap10.c_str(), &statInfo));
    EXPECT_TRUE(!FastOS_File::Stat(snap20.c_str(), &statInfo));
}


void
Test::requireThatFlushStatsAreUpdated(void)
{
    Fixture f;
    AttributeManager &am = f._m;
    AttributeVector::SP av = f.addAttribute("a7");
    av->addDocs(1);
    av->commit(100,100);
    IFlushTarget::SP ft = am.getFlushable("a7");
    ft->initFlush(101)->run();
    FlushStats stats = ft->getLastFlushStats();
    EXPECT_EQUAL("flush/a7/snapshot-101", stats.getPath());
    EXPECT_EQUAL(8u, stats.getPathElementsToLog());
}


void
Test::requireThatOnlyOneFlusherCanRunAtTheSameTime(void)
{
    Fixture f;
    AttributeManager &am = f._m;
    AttributeVector::SP av = f.addAttribute("a8");
    av->addDocs(10000);
    av->commit(9,9);
    IFlushTarget::SP ft = am.getFlushable("a8");
    (static_cast<FlushableAttribute *>(ft.get()))->setCleanUpAfterFlush(false);
    vespalib::ThreadStackExecutor exec(16, 64000);

    for (size_t i = 10; i < 100; ++i) {
        av->commit(i, i);
        vespalib::Executor::Task::UP task = ft->initFlush(i);
        exec.execute(std::move(task));
    }
    exec.sync();
    exec.shutdown();

    IndexMetaInfo info("flush/a8");
    ASSERT_TRUE(info.load());
    LOG(info, "Found %zu snapshots", info.snapshots().size());
    for (size_t i = 0; i < info.snapshots().size(); ++i) {
        EXPECT_EQUAL(true, info.snapshots()[i].valid);
    }
    IndexMetaInfo::Snapshot best = info.getBestSnapshot();
    EXPECT_EQUAL(true, best.valid);
    EXPECT_EQUAL(99u, best.syncToken);
    FlushStats stats = ft->getLastFlushStats();
    EXPECT_EQUAL("flush/a8/snapshot-99", stats.getPath());
}


void
Test::requireThatLastFlushTimeIsReported(void)
{
    BaseFixture f;
    FastOS_StatInfo stat;
    { // no meta info file yet
        AttributeManagerFixture amf(f);
        AttributeManager &am = amf._m;
        AttributeVector::SP av = amf.addAttribute("a9");
        EXPECT_EQUAL(0, am.getFlushable("a9")->getLastFlushTime().time());
    }
    { // no snapshot flushed yet
        AttributeManagerFixture amf(f);
        AttributeManager &am = amf._m;
        AttributeVector::SP av = amf.addAttribute("a9");
        IFlushTarget::SP ft = am.getFlushable("a9");
        EXPECT_EQUAL(0, ft->getLastFlushTime().time());
        ft->initFlush(5)->run();
        EXPECT_TRUE(FastOS_File::Stat("flush/a9/snapshot-5", &stat));
        EXPECT_EQUAL(stat._modifiedTime, ft->getLastFlushTime().time());
    }
    { // snapshot flushed
        AttributeManagerFixture amf(f);
        AttributeManager &am = amf._m;
        amf.addAttribute("a9");
        IFlushTarget::SP ft = am.getFlushable("a9");
        EXPECT_EQUAL(stat._modifiedTime, ft->getLastFlushTime().time());
        { // updated flush time after nothing to flush
            FastOS_Thread::Sleep(8000);
            fastos::TimeStamp now = fastos::ClockSystem::now();
            Executor::Task::UP task = ft->initFlush(5);
            EXPECT_TRUE(task.get() == NULL);
            EXPECT_LESS(stat._modifiedTime, ft->getLastFlushTime().time());
            EXPECT_APPROX(now.time(), ft->getLastFlushTime().time(), 8);
        }
    }
}


void
Test::requireThatShrinkWorks()
{
    Fixture f;
    AttributeManager &am = f._m;
    AttributeVector::SP av = f.addAttribute("a10");
    
    av->addDocs(1000 - av->getNumDocs());
    av->commit(10, 10);
    IFlushTarget::SP ft = am.getFlushable("a10");
    EXPECT_EQUAL(ft->getApproxMemoryGain().getBefore(),
                 ft->getApproxMemoryGain().getAfter());
    AttributeGuard::UP g = am.getAttribute("a10");
    EXPECT_FALSE(av->wantShrinkLidSpace());
    EXPECT_FALSE(av->canShrinkLidSpace());
    EXPECT_EQUAL(1000u, av->getNumDocs());
    EXPECT_EQUAL(1000u, av->getCommittedDocIdLimit());
    av->compactLidSpace(100);
    EXPECT_TRUE(av->wantShrinkLidSpace());
    EXPECT_FALSE(av->canShrinkLidSpace());
    EXPECT_EQUAL(1000u, av->getNumDocs());
    EXPECT_EQUAL(100u, av->getCommittedDocIdLimit());
    f._aw.heartBeat(11);
    EXPECT_TRUE(av->wantShrinkLidSpace());
    EXPECT_FALSE(av->canShrinkLidSpace());
    EXPECT_EQUAL(ft->getApproxMemoryGain().getBefore(),
                 ft->getApproxMemoryGain().getAfter());
    g.reset();
    f._aw.heartBeat(11);
    EXPECT_TRUE(av->wantShrinkLidSpace());
    EXPECT_TRUE(av->canShrinkLidSpace());
    EXPECT_TRUE(ft->getApproxMemoryGain().getBefore() >
                ft->getApproxMemoryGain().getAfter());
    EXPECT_EQUAL(1000u, av->getNumDocs());
    EXPECT_EQUAL(100u, av->getCommittedDocIdLimit());
    vespalib::ThreadStackExecutor exec(1, 128 * 1024);
    vespalib::Executor::Task::UP task = ft->initFlush(11);
    exec.execute(std::move(task));
    exec.sync();
    exec.shutdown();
    EXPECT_FALSE(av->wantShrinkLidSpace());
    EXPECT_FALSE(av->canShrinkLidSpace());
    EXPECT_EQUAL(ft->getApproxMemoryGain().getBefore(),
                 ft->getApproxMemoryGain().getAfter());
    EXPECT_EQUAL(100u, av->getNumDocs());
    EXPECT_EQUAL(100u, av->getCommittedDocIdLimit());
}


int
Test::Main(void)
{
    TEST_INIT("attributeflush_test");

    if (_argc > 0) {
        DummyFileHeaderContext::setCreator(_argv[0]);
    }
    vespalib::rmdir(test_dir, true);
    TEST_DO(requireThatUpdaterAndFlusherCanRunConcurrently());
    TEST_DO(requireThatFlushableAttributeReportsMemoryUsage());
    TEST_DO(requireThatFlushableAttributeManagesSyncTokenInfo(std::make_shared<vespalib::MockHwInfo>()));
    TEST_DO(requireThatFlushTargetsCanBeRetrieved());
    TEST_DO(requireThatCleanUpIsPerformedAfterFlush());
    TEST_DO(requireThatFlushStatsAreUpdated());
    TEST_DO(requireThatOnlyOneFlusherCanRunAtTheSameTime());
    TEST_DO(requireThatLastFlushTimeIsReported());
    TEST_DO(requireThatShrinkWorks());
    vespalib::rmdir(test_dir, true);
    TEST_DO(requireThatFlushableAttributeManagesSyncTokenInfo(std::make_shared<vespalib::MockHwInfo>(true)));
    vespalib::rmdir(test_dir, true);

    TEST_DONE();
}

}

TEST_APPHOOK(proton::Test);
