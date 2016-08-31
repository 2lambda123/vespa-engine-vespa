// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP("documentdb_test");

#include <vespa/document/repo/documenttyperepo.h>
#include <vespa/messagebus/emptyreply.h>
#include <vespa/messagebus/testlib/receptor.h>
#include <vespa/searchcommon/common/schema.h>
#include <vespa/searchcore/proton/attribute/flushableattribute.h>
#include <vespa/searchcore/proton/docsummary/summaryflushtarget.h>
#include <vespa/searchcore/proton/documentmetastore/documentmetastoreflushtarget.h>
#include <vespa/searchcore/proton/flushengine/threadedflushtarget.h>
#include <vespa/searchcore/proton/server/document_db_explorer.h>
#include <vespa/searchcore/proton/server/documentdb.h>
#include <vespa/searchcore/proton/server/memoryconfigstore.h>
#include <vespa/searchcore/proton/metrics/job_tracked_flush_target.h>
#include <vespa/searchcore/proton/metrics/metricswireservice.h>
#include <vespa/searchcorespi/index/indexflushtarget.h>
#include <vespa/searchlib/index/dummyfileheadercontext.h>
#include <vespa/searchlib/transactionlog/translogserver.h>
#include <tests/proton/common/dummydbowner.h>
#include <vespa/vespalib/testkit/testapp.h>

using document::DocumentType;
using document::DocumentTypeRepo;
using search::index::Schema;
using search::transactionlog::TransLogServer;
using namespace proton;
using namespace vespalib::slime;
using namespace cloud::config::filedistribution;
using search::TuneFileDocumentDB;
using document::DocumenttypesConfig;
using search::index::DummyFileHeaderContext;
using searchcorespi::index::IndexFlushTarget;
using vespa::config::search::core::ProtonConfig;
using vespalib::Slime;

namespace {

class LocalTransport : public FeedToken::ITransport {
    mbus::Receptor _receptor;

public:
    void send(mbus::Reply::UP reply) {
        fprintf(stderr, "in local transport.");
        _receptor.handleReply(std::move(reply));
    }

    mbus::Reply::UP getReply() {
        return _receptor.getReply(10000);
    }
};

struct Fixture {
    DummyWireService _dummy;
    DummyDBOwner _dummyDBOwner;
    vespalib::ThreadStackExecutor _summaryExecutor;
    DocumentDB::SP _db;
    DummyFileHeaderContext _fileHeaderContext;
    TransLogServer _tls;
    matching::QueryLimiter _queryLimiter;
    vespalib::Clock _clock;

    Fixture();
};

Fixture::Fixture()
    : _summaryExecutor(8, 128*1024),
      _tls("tmp", 9014, ".", _fileHeaderContext) {

    DocumentDBConfig::DocumenttypesConfigSP documenttypesConfig(new DocumenttypesConfig());
    DocumentType docType("typea", 0);
    DocumentTypeRepo::SP repo(new DocumentTypeRepo(docType));
    TuneFileDocumentDB::SP tuneFileDocumentDB(new TuneFileDocumentDB);
    config::DirSpec spec(vespalib::TestApp::GetSourceDirectory() + "cfg");
    DocumentDBConfigHelper mgr(spec, "typea");
    BootstrapConfig::SP
        b(new BootstrapConfig(1,
                              documenttypesConfig,
                              repo,
                              BootstrapConfig::ProtonConfigSP(new ProtonConfig()),
                              BootstrapConfig::FiledistributorrpcConfigSP(new FiledistributorrpcConfig()),
                              tuneFileDocumentDB));
    mgr.forwardConfig(b);
    mgr.nextGeneration(0);
    _db.reset(new DocumentDB(".", mgr.getConfig(), "tcp/localhost:9014",
                             _queryLimiter, _clock, DocTypeName("typea"),
                             ProtonConfig(),
                             _dummyDBOwner, _summaryExecutor, _summaryExecutor, NULL, _dummy, _fileHeaderContext,
                             ConfigStore::UP(new MemoryConfigStore),
                             std::make_shared<vespalib::ThreadStackExecutor>
                             (16, 128 * 1024)));
    _db->start();
    _db->waitForOnlineState();
}

const IFlushTarget *
extractRealFlushTarget(const IFlushTarget *target)
{
    const JobTrackedFlushTarget *tracked =
            dynamic_cast<const JobTrackedFlushTarget*>(target);
    if (tracked != nullptr) {
        const ThreadedFlushTarget *threaded =
                dynamic_cast<const ThreadedFlushTarget*>(&tracked->getTarget());
        if (threaded != nullptr) {
            return threaded->getFlushTarget().get();
        }
    }
    return nullptr;
}

TEST_F("requireThatIndexFlushTargetIsUsed", Fixture) {
    std::vector<IFlushTarget::SP> targets = f._db->getFlushTargets();
    ASSERT_TRUE(!targets.empty());
    const IndexFlushTarget *index = 0;
    for (size_t i = 0; i < targets.size(); ++i) {
        const IFlushTarget *target = extractRealFlushTarget(targets[i].get());
        if (target != NULL) {
            index = dynamic_cast<const IndexFlushTarget *>(target);
        }
        if (index) {
            break;
        }
    }
    ASSERT_TRUE(index);
}

template <typename Target>
size_t getNumTargets(const std::vector<IFlushTarget::SP> & targets)
{
    size_t retval = 0;
    for (size_t i = 0; i < targets.size(); ++i) {
        const IFlushTarget *target = extractRealFlushTarget(targets[i].get());
        if (dynamic_cast<const Target*>(target) == NULL) {
            continue;
        }
        retval++;
    }
    return retval;
}

TEST_F("requireThatFlushTargetsAreNamedBySubDocumentDB", Fixture) {
    std::vector<IFlushTarget::SP> targets = f._db->getFlushTargets();
    ASSERT_TRUE(!targets.empty());
    for (const IFlushTarget::SP & target : f._db->getFlushTargets()) {
        vespalib::string name = target->getName();
        EXPECT_TRUE((name.find("0.ready.") == 0) ||
                    (name.find("1.removed.") == 0) ||
                    (name.find("2.notready.") == 0));
    }
}

TEST_F("requireThatAttributeFlushTargetsAreUsed", Fixture) {
    std::vector<IFlushTarget::SP> targets = f._db->getFlushTargets();
    ASSERT_TRUE(!targets.empty());
    size_t numAttrs = getNumTargets<FlushableAttribute>(targets);
    // attr1 defined in attributes.cfg
    EXPECT_EQUAL(1u, numAttrs);
}

TEST_F("requireThatDocumentMetaStoreFlushTargetIsUsed", Fixture) {
    std::vector<IFlushTarget::SP> targets = f._db->getFlushTargets();
    ASSERT_TRUE(!targets.empty());
    size_t numMetaStores =
        getNumTargets<DocumentMetaStoreFlushTarget>(targets);
    // document meta store
    EXPECT_EQUAL(3u, numMetaStores);
}

TEST_F("requireThatSummaryFlushTargetsIsUsed", Fixture) {
    std::vector<IFlushTarget::SP> targets = f._db->getFlushTargets();
    ASSERT_TRUE(!targets.empty());
    size_t num = getNumTargets<SummaryFlushTarget>(targets);
    EXPECT_EQUAL(3u, num);
}

TEST_F("requireThatCorrectStatusIsReported", Fixture) {
    StatusReport::UP report(f._db->reportStatus());
    EXPECT_EQUAL("documentdb:typea", report->getComponent());
    EXPECT_EQUAL(StatusReport::UPOK, report->getState());
    EXPECT_EQUAL("", report->getMessage());
}

TEST_F("requireThatStateIsReported", Fixture)
{
    Slime slime;
    SlimeInserter inserter(slime);
    DocumentDBExplorer(f._db).get_state(inserter, false);

    EXPECT_EQUAL(
            "{\n"
            "    \"documentType\": \"typea\",\n"
            "    \"status\": {\n"
            "        \"state\": \"ONLINE\",\n"
            "        \"configState\": \"OK\"\n"
            "    },\n"
            "    \"documents\": {\n"
            "        \"active\": 0,\n"
            "        \"indexed\": 0,\n"
            "        \"stored\": 0,\n"
            "        \"removed\": 0\n"
            "    }\n"
            "}\n",
            slime.toString());
}

TEST_F("require that session manager can be explored", Fixture)
{
    EXPECT_TRUE(DocumentDBExplorer(f._db).get_child("session").get() != nullptr);    
}

}  // namespace

TEST_MAIN() {
    DummyFileHeaderContext::setCreator("documentdb_test");
    FastOS_File::MakeDirectory("typea");
    TEST_RUN_ALL();
    FastOS_FileInterface::EmptyAndRemoveDirectory("typea");
}
