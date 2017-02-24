// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP("attribute_manager_test");

#include <vespa/fastos/file.h>
#include <vespa/vespalib/testkit/testapp.h>
#include <vespa/searchcommon/attribute/attributecontent.h>
#include <vespa/searchcore/proton/attribute/attribute_collection_spec_factory.h>
#include <vespa/searchcore/proton/attribute/attributemanager.h>
#include <vespa/searchcore/proton/attribute/attribute_manager_initializer.h>
#include <vespa/searchcore/proton/attribute/attribute_writer.h>
#include <vespa/searchcore/proton/attribute/exclusive_attribute_read_accessor.h>
#include <vespa/searchcore/proton/attribute/sequential_attributes_initializer.h>
#include <vespa/searchcore/proton/attribute/i_attribute_functor.h>
#include <vespa/searchcore/proton/initializer/initializer_task.h>
#include <vespa/searchcore/proton/initializer/task_runner.h>
#include <vespa/searchcore/proton/test/attribute_utils.h>
#include <vespa/searchcore/proton/test/attribute_vectors.h>
#include <vespa/searchcore/proton/test/directory_handler.h>
#include <vespa/searchlib/attribute/attributefactory.h>
#include <vespa/searchlib/attribute/integerbase.h>
#include <vespa/searchlib/index/dummyfileheadercontext.h>
#include <vespa/searchlib/util/filekit.h>

#include <vespa/searchlib/attribute/attributevector.hpp>
#include <vespa/searchlib/attribute/predicate_attribute.h>
#include <vespa/searchlib/predicate/predicate_index.h>
#include <vespa/searchlib/predicate/predicate_tree_annotator.h>
#include <vespa/searchlib/attribute/singlenumericattribute.hpp>
#include <vespa/searchlib/common/foregroundtaskexecutor.h>
#include <vespa/vespalib/util/threadstackexecutor.h>
#include <vespa/searchcore/proton/common/hw_info.h>
#include <vespa/config-attributes.h>

namespace vespa { namespace config { namespace search {}}}

using std::string;
using namespace vespa::config::search;
using namespace config;
using namespace document;
using namespace proton;
using namespace search;
using namespace search::index;
using proton::initializer::InitializerTask;
using proton::test::AttributeUtils;
using proton::test::Int32Attribute;
using search::TuneFileAttributes;
using search::index::DummyFileHeaderContext;
using search::ForegroundTaskExecutor;
using search::predicate::PredicateIndex;
using search::predicate::PredicateTreeAnnotations;
using vespa::config::search::AttributesConfig;
using vespa::config::search::AttributesConfigBuilder;

typedef search::attribute::Config AVConfig;
typedef proton::AttributeCollectionSpec::Attribute AttrSpec;
typedef proton::AttributeCollectionSpec::AttributeList AttrSpecList;
typedef proton::AttributeCollectionSpec AttrMgrSpec;

namespace {

const uint64_t createSerialNum = 42u;

class MyAttributeFunctor : public proton::IAttributeFunctor
{
    std::vector<vespalib::string> _names;

public:
    virtual void
    operator()(const search::AttributeVector &attributeVector) override {
        _names.push_back(attributeVector.getName());
    }

    std::string getSortedNames() {
        std::ostringstream os;
        std::sort(_names.begin(), _names.end());
        for (const vespalib::string &name : _names) {
            if (!os.str().empty())
                os << ",";
            os << name;
        }
        return os.str();
    }
};

}

const string test_dir = "test_output";
const AVConfig INT32_SINGLE = AttributeUtils::getInt32Config();
const AVConfig INT32_ARRAY = AttributeUtils::getInt32ArrayConfig();

void
fillAttribute(const AttributeVector::SP &attr, uint32_t numDocs, int64_t value, uint64_t lastSyncToken)
{
    test::AttributeUtils::fillAttribute(attr, numDocs, value, lastSyncToken);
}

void
fillAttribute(const AttributeVector::SP &attr, uint32_t from, uint32_t to, int64_t value, uint64_t lastSyncToken)
{
    test::AttributeUtils::fillAttribute(attr, from, to, value, lastSyncToken);
}

struct BaseFixture
{
    test::DirectoryHandler _dirHandler;
    DummyFileHeaderContext   _fileHeaderContext;
    ForegroundTaskExecutor   _attributeFieldWriter;
    HwInfo                   _hwInfo;
    BaseFixture()
        : _dirHandler(test_dir),
          _fileHeaderContext(),
          _attributeFieldWriter(),
          _hwInfo()
    {
    }
};


struct AttributeManagerFixture
{
    proton::AttributeManager::SP _msp;
    proton::AttributeManager &_m;
    AttributeWriter _aw;
    AttributeManagerFixture(BaseFixture &bf)
        : _msp(std::make_shared<proton::AttributeManager>
               (test_dir, "test.subdb", TuneFileAttributes(), bf._fileHeaderContext,
                bf._attributeFieldWriter, bf._hwInfo)),
          _m(*_msp),
          _aw(_msp)
    {
    }
    AttributeVector::SP addAttribute(const vespalib::string &name) {
        return _m.addAttribute(name, INT32_SINGLE, createSerialNum);
    }
};

struct Fixture : public BaseFixture, public AttributeManagerFixture
{
    Fixture()
        : BaseFixture(),
          AttributeManagerFixture(*static_cast<BaseFixture *>(this))
    {
    }
};

struct SequentialAttributeManager
{
    SequentialAttributesInitializer initializer;
    proton::AttributeManager mgr;
    SequentialAttributeManager(const AttributeManager &currMgr,
                               const AttrMgrSpec &newSpec)
        : initializer(newSpec.getDocIdLimit()),
          mgr(currMgr, newSpec, initializer)
    {
        mgr.addInitializedAttributes(initializer.getInitializedAttributes());
    }
};

struct DummyInitializerTask : public InitializerTask
{
    virtual void run() override {}
};

struct ParallelAttributeManager
{
    InitializerTask::SP documentMetaStoreInitTask;
    BucketDBOwner::SP bucketDbOwner;
    DocumentMetaStore::SP documentMetaStore;
    search::GrowStrategy attributeGrow;
    size_t attributeGrowNumDocs;
    bool fastAccessAttributesOnly;
    std::shared_ptr<AttributeManager::SP> mgr;
    AttributeManagerInitializer::SP initializer;

    ParallelAttributeManager(search::SerialNum configSerialNum,
                             AttributeManager::SP baseAttrMgr,
                             const AttributesConfig &attrCfg,
                             uint32_t docIdLimit)
        : documentMetaStoreInitTask(std::make_shared<DummyInitializerTask>()),
          bucketDbOwner(std::make_shared<BucketDBOwner>()),
          documentMetaStore(std::make_shared<DocumentMetaStore>(bucketDbOwner)),
          attributeGrow(),
          attributeGrowNumDocs(1),
          fastAccessAttributesOnly(false),
          mgr(std::make_shared<AttributeManager::SP>()),
          initializer(std::make_shared<AttributeManagerInitializer>
                              (configSerialNum, documentMetaStoreInitTask, documentMetaStore, baseAttrMgr, attrCfg,
                               attributeGrow, attributeGrowNumDocs, fastAccessAttributesOnly, mgr))
    {
        documentMetaStore->setCommittedDocIdLimit(docIdLimit);
        vespalib::ThreadStackExecutor executor(3, 128 * 1024);
        initializer::TaskRunner taskRunner(executor);
        taskRunner.runTask(initializer);
    }
};


TEST_F("require that attributes are added", Fixture)
{
    EXPECT_TRUE(f.addAttribute("a1").get() != NULL);
    EXPECT_TRUE(f.addAttribute("a2").get() != NULL);
    EXPECT_EQUAL("a1", (*f._m.getAttribute("a1"))->getName());
    EXPECT_EQUAL("a1", (*f._m.getAttributeStableEnum("a1"))->getName());
    EXPECT_EQUAL("a2", (*f._m.getAttribute("a2"))->getName());
    EXPECT_EQUAL("a2", (*f._m.getAttributeStableEnum("a2"))->getName());
    EXPECT_TRUE(!f._m.getAttribute("not")->valid());
}

TEST_F("require that predicate attributes are added", Fixture)
{
    EXPECT_TRUE(f._m.addAttribute("p1", AttributeUtils::getPredicateConfig(),
                                  createSerialNum).get() != NULL);
    EXPECT_EQUAL("p1", (*f._m.getAttribute("p1"))->getName());
    EXPECT_EQUAL("p1", (*f._m.getAttributeStableEnum("p1"))->getName());
}

TEST_F("require that attributes are flushed and loaded", BaseFixture)
{
    IndexMetaInfo ia1(test_dir + "/a1");
    IndexMetaInfo ia2(test_dir + "/a2");
    IndexMetaInfo ia3(test_dir + "/a3");
    {
        AttributeManagerFixture amf(f);
        proton::AttributeManager &am = amf._m;
        AttributeVector::SP a1 = amf.addAttribute("a1");
        EXPECT_EQUAL(1u, a1->getNumDocs());	// Resized to size of attributemanager
        fillAttribute(a1, 1, 3, 2, 10);
        EXPECT_EQUAL(3u, a1->getNumDocs());	// Resized to size of attributemanager
        AttributeVector::SP a2 = amf.addAttribute("a2");
        EXPECT_EQUAL(1u, a2->getNumDocs());	// Not resized to size of attributemanager
        fillAttribute(a2, 1, 5, 4, 10);
        EXPECT_EQUAL(5u, a2->getNumDocs());	// Increased
        EXPECT_TRUE(ia1.load());
        EXPECT_TRUE(!ia1.getBestSnapshot().valid);
        EXPECT_TRUE(ia2.load());
        EXPECT_TRUE(!ia2.getBestSnapshot().valid);
        EXPECT_TRUE(!ia3.load());
        am.flushAll(0);
        EXPECT_TRUE(ia1.load());
        EXPECT_EQUAL(10u, ia1.getBestSnapshot().syncToken);
        EXPECT_TRUE(ia2.load());
        EXPECT_EQUAL(10u, ia2.getBestSnapshot().syncToken);
    }
    {
        AttributeManagerFixture amf(f);
        proton::AttributeManager &am = amf._m;
        AttributeVector::SP a1 = amf.addAttribute("a1"); // loaded

        EXPECT_EQUAL(3u, a1->getNumDocs());
        fillAttribute(a1, 1, 2, 20);
        EXPECT_EQUAL(4u, a1->getNumDocs());
        AttributeVector::SP a2 = amf.addAttribute("a2"); // loaded
        EXPECT_EQUAL(5u, a2->getNumDocs());
        EXPECT_EQUAL(4u, a1->getNumDocs());
        amf._aw.onReplayDone(5u);
        EXPECT_EQUAL(5u, a2->getNumDocs());
        EXPECT_EQUAL(5u, a1->getNumDocs());
        fillAttribute(a2, 1, 4, 20);
        EXPECT_EQUAL(6u, a2->getNumDocs());
        AttributeVector::SP a3 = amf.addAttribute("a3"); // not-loaded
        EXPECT_EQUAL(1u, a3->getNumDocs());
        amf._aw.onReplayDone(6);
        EXPECT_EQUAL(6u, a3->getNumDocs());
        fillAttribute(a3, 1, 7, 6, 20);
        EXPECT_EQUAL(7u, a3->getNumDocs());
        EXPECT_TRUE(ia1.load());
        EXPECT_EQUAL(10u, ia1.getBestSnapshot().syncToken);
        EXPECT_TRUE(ia2.load());
        EXPECT_EQUAL(10u, ia2.getBestSnapshot().syncToken);
        EXPECT_TRUE(ia3.load());
        EXPECT_TRUE(!ia3.getBestSnapshot().valid);
        am.flushAll(0);
        EXPECT_TRUE(ia1.load());
        EXPECT_EQUAL(20u, ia1.getBestSnapshot().syncToken);
        EXPECT_TRUE(ia2.load());
        EXPECT_EQUAL(20u, ia2.getBestSnapshot().syncToken);
        EXPECT_TRUE(ia3.load());
        EXPECT_EQUAL(20u, ia3.getBestSnapshot().syncToken);
    }
    {
        AttributeManagerFixture amf(f);
        AttributeVector::SP a1 = amf.addAttribute("a1"); // loaded
        EXPECT_EQUAL(6u, a1->getNumDocs());
        AttributeVector::SP a2 = amf.addAttribute("a2"); // loaded
        EXPECT_EQUAL(6u, a1->getNumDocs());
        EXPECT_EQUAL(6u, a2->getNumDocs());
        AttributeVector::SP a3 = amf.addAttribute("a3"); // loaded
        EXPECT_EQUAL(6u, a1->getNumDocs());
        EXPECT_EQUAL(6u, a2->getNumDocs());
        EXPECT_EQUAL(7u, a3->getNumDocs());
        amf._aw.onReplayDone(7);
        EXPECT_EQUAL(7u, a1->getNumDocs());
        EXPECT_EQUAL(7u, a2->getNumDocs());
        EXPECT_EQUAL(7u, a3->getNumDocs());
    }
}

TEST_F("require that predicate attributes are flushed and loaded", BaseFixture)
{
    IndexMetaInfo ia1(test_dir + "/a1");
    {
        AttributeManagerFixture amf(f);
        proton::AttributeManager &am = amf._m;
        AttributeVector::SP a1 =
            am.addAttribute("a1",
                            AttributeUtils::getPredicateConfig(),
                            createSerialNum);
        EXPECT_EQUAL(1u, a1->getNumDocs());

        PredicateAttribute &pa = static_cast<PredicateAttribute &>(*a1);
        PredicateIndex &index = pa.getIndex();
        uint32_t doc_id;
        a1->addDoc(doc_id);
        index.indexEmptyDocument(doc_id);
        pa.commit(10, 10);

        EXPECT_EQUAL(2u, a1->getNumDocs());

        EXPECT_TRUE(ia1.load());
        EXPECT_TRUE(!ia1.getBestSnapshot().valid);
        am.flushAll(0);
        EXPECT_TRUE(ia1.load());
        EXPECT_EQUAL(10u, ia1.getBestSnapshot().syncToken);
    }
    {
        AttributeManagerFixture amf(f);
        proton::AttributeManager &am = amf._m;
        AttributeVector::SP a1 =
            am.addAttribute("a1", AttributeUtils::getPredicateConfig(),
                            createSerialNum); // loaded
        EXPECT_EQUAL(2u, a1->getNumDocs());

        PredicateAttribute &pa = static_cast<PredicateAttribute &>(*a1);
        PredicateIndex &index = pa.getIndex();
        uint32_t doc_id;
        a1->addDoc(doc_id);
        PredicateTreeAnnotations annotations(3);
        annotations.interval_map[123] = {{ 0x0001ffff }};
        index.indexDocument(1, annotations);
        pa.commit(20, 20);

        EXPECT_EQUAL(3u, a1->getNumDocs());
        EXPECT_TRUE(ia1.load());
        EXPECT_EQUAL(10u, ia1.getBestSnapshot().syncToken);
        am.flushAll(0);
        EXPECT_TRUE(ia1.load());
        EXPECT_EQUAL(20u, ia1.getBestSnapshot().syncToken);
    }
}

TEST_F("require that extra attribute is added", Fixture)
{
    AttributeVector::SP extra(new Int32Attribute("extra"));
    f._m.addExtraAttribute(extra);
    AttributeGuard::UP exguard(f._m.getAttribute("extra"));
    EXPECT_TRUE(dynamic_cast<Int32Attribute *>(exguard->operator->()) !=
                NULL);
}

TEST_F("require that reconfig can add attributes", Fixture)
{
    AttributeVector::SP a1 = f.addAttribute("a1");
    AttributeVector::SP ex(new Int32Attribute("ex"));
    f._m.addExtraAttribute(ex);

    AttrSpecList newSpec;
    newSpec.push_back(AttrSpec("a1", INT32_SINGLE));
    newSpec.push_back(AttrSpec("a2", INT32_SINGLE));
    newSpec.push_back(AttrSpec("a3", INT32_SINGLE));

    SequentialAttributeManager sam(f._m, AttrMgrSpec(newSpec, f._m.getNumDocs(), 0));
    std::vector<AttributeGuard> list;
    sam.mgr.getAttributeList(list);
    std::sort(list.begin(), list.end(), [](const AttributeGuard & a, const AttributeGuard & b) {
        return a->getName() < b->getName();
    });
    EXPECT_EQUAL(3u, list.size());
    EXPECT_EQUAL("a1", list[0]->getName());
    EXPECT_TRUE(list[0].operator->() == a1.get()); // reuse
    EXPECT_EQUAL("a2", list[1]->getName());
    EXPECT_EQUAL("a3", list[2]->getName());
    EXPECT_TRUE(sam.mgr.getAttribute("ex")->operator->() == ex.get()); // reuse
}

TEST_F("require that reconfig can remove attributes", Fixture)
{
    AttributeVector::SP a1 = f.addAttribute("a1");
    AttributeVector::SP a2 = f.addAttribute("a2");
    AttributeVector::SP a3 = f.addAttribute("a3");

    AttrSpecList newSpec;
    newSpec.push_back(AttrSpec("a2", INT32_SINGLE));

    SequentialAttributeManager sam(f._m, AttrMgrSpec(newSpec, 1, 0));
    std::vector<AttributeGuard> list;
    sam.mgr.getAttributeList(list);
    EXPECT_EQUAL(1u, list.size());
    EXPECT_EQUAL("a2", list[0]->getName());
    EXPECT_TRUE(list[0].operator->() == a2.get()); // reuse
}

TEST_F("require that new attributes after reconfig are initialized", Fixture)
{
    AttributeVector::SP a1 = f.addAttribute("a1");
    uint32_t docId(0);
    a1->addDoc(docId);
    EXPECT_EQUAL(1u, docId);
    a1->addDoc(docId);
    EXPECT_EQUAL(2u, docId);
    EXPECT_EQUAL(3u, a1->getNumDocs());

    AttrSpecList newSpec;
    newSpec.push_back(AttrSpec("a1", INT32_SINGLE));
    newSpec.push_back(AttrSpec("a2", INT32_SINGLE));
    newSpec.push_back(AttrSpec("a3", INT32_ARRAY));

    SequentialAttributeManager sam(f._m, AttrMgrSpec(newSpec, 3, 4));
    AttributeGuard::UP a2ap = sam.mgr.getAttribute("a2");
    AttributeGuard &a2(*a2ap);
    EXPECT_EQUAL(3u, a2->getNumDocs());
    EXPECT_TRUE(search::attribute::isUndefined<int32_t>(a2->getInt(1)));
    EXPECT_TRUE(search::attribute::isUndefined<int32_t>(a2->getInt(2)));
    EXPECT_EQUAL(0u, a2->getStatus().getLastSyncToken());
    AttributeGuard::UP a3ap = sam.mgr.getAttribute("a3");
    AttributeGuard &a3(*a3ap);
    AttributeVector::largeint_t buf[1];
    EXPECT_EQUAL(3u, a3->getNumDocs());
    EXPECT_EQUAL(0u, a3->get(1, buf, 1));
    EXPECT_EQUAL(0u, a3->get(2, buf, 1));
    EXPECT_EQUAL(0u, a3->getStatus().getLastSyncToken());
}

TEST_F("require that removed attributes can resurrect", BaseFixture)
{
    proton::AttributeManager::SP am1(
            new proton::AttributeManager(test_dir, "test.subdb",
                                         TuneFileAttributes(),
                                         f._fileHeaderContext,
                                         f._attributeFieldWriter, f._hwInfo));
    {
        AttributeVector::SP a1 =
            am1->addAttribute("a1", INT32_SINGLE,
                              0);
        fillAttribute(a1, 2, 10, 15);
        EXPECT_EQUAL(3u, a1->getNumDocs());
    }

    AttrSpecList ns1;
    SequentialAttributeManager am2(*am1, AttrMgrSpec(ns1, 3, 16));
    am1.reset();

    AttrSpecList ns2;
    ns2.push_back(AttrSpec("a1", INT32_SINGLE));
    // 2 new documents added since a1 was removed
    SequentialAttributeManager am3(am2.mgr, AttrMgrSpec(ns2, 5, 20));

    AttributeGuard::UP ag1ap = am3.mgr.getAttribute("a1");
    AttributeGuard &ag1(*ag1ap);
    ASSERT_TRUE(ag1.valid());
    EXPECT_EQUAL(5u, ag1->getNumDocs());
    EXPECT_EQUAL(10, ag1->getInt(1));
    EXPECT_EQUAL(10, ag1->getInt(2));
    EXPECT_TRUE(search::attribute::isUndefined<int32_t>(ag1->getInt(3)));
    EXPECT_TRUE(search::attribute::isUndefined<int32_t>(ag1->getInt(4)));
    EXPECT_EQUAL(16u, ag1->getStatus().getLastSyncToken());
}

TEST_F("require that extra attribute is not treated as removed", Fixture)
{
    AttributeVector::SP ex(new Int32Attribute("ex"));
    f._m.addExtraAttribute(ex);
    ex->commit(1,1);

    AttrSpecList ns;
    SequentialAttributeManager am2(f._m, AttrMgrSpec(ns, 2, 1));
    EXPECT_TRUE(am2.mgr.getAttribute("ex")->operator->() == ex.get()); // reuse
}

TEST_F("require that history can be wiped", Fixture)
{
    f.addAttribute("a1");
    f.addAttribute("a2");
    f.addAttribute("a3");
    f._m.flushAll(10);
    Schema hs;
    hs.addAttributeField(Schema::AttributeField("a1", schema::INT32));
    hs.addAttributeField(Schema::AttributeField("a3", schema::INT32));
    f._m.wipeHistory(hs);
    FastOS_StatInfo si;
    EXPECT_TRUE(!FastOS_File::Stat(vespalib::string(test_dir + "/a1").c_str(), &si));
    EXPECT_TRUE(FastOS_File::Stat(vespalib::string(test_dir + "/a2").c_str(), &si));
    EXPECT_TRUE(!FastOS_File::Stat(vespalib::string(test_dir + "/a3").c_str(), &si));
}

TEST_F("require that lid space can be compacted", Fixture)
{
    AttributeVector::SP a1 = f.addAttribute("a1");
    AttributeVector::SP a2 = f.addAttribute("a2");
    AttributeVector::SP ex(new Int32Attribute("ex"));
    f._m.addExtraAttribute(ex);
    const int64_t attrValue = 33;
    fillAttribute(a1, 20, attrValue, 100);
    fillAttribute(a2, 20, attrValue, 100);
    fillAttribute(ex, 20, attrValue, 100);

    EXPECT_EQUAL(21u, a1->getNumDocs());
    EXPECT_EQUAL(21u, a2->getNumDocs());
    EXPECT_EQUAL(20u, ex->getNumDocs());
    EXPECT_EQUAL(21u, a1->getCommittedDocIdLimit());
    EXPECT_EQUAL(21u, a2->getCommittedDocIdLimit());
    EXPECT_EQUAL(20u, ex->getCommittedDocIdLimit());

    f._aw.compactLidSpace(10, 101);

    EXPECT_EQUAL(21u, a1->getNumDocs());
    EXPECT_EQUAL(21u, a2->getNumDocs());
    EXPECT_EQUAL(20u, ex->getNumDocs());
    EXPECT_EQUAL(10u, a1->getCommittedDocIdLimit());
    EXPECT_EQUAL(10u, a2->getCommittedDocIdLimit());
    EXPECT_EQUAL(20u, ex->getCommittedDocIdLimit());
}

TEST_F("require that lid space compaction op can be ignored", Fixture)
{
    AttributeVector::SP a1 = f.addAttribute("a1");
    AttributeVector::SP a2 = f.addAttribute("a2");
    AttributeVector::SP ex(new Int32Attribute("ex"));
    f._m.addExtraAttribute(ex);
    const int64_t attrValue = 33;
    fillAttribute(a1, 20, attrValue, 200);
    fillAttribute(a2, 20, attrValue, 100);
    fillAttribute(ex, 20, attrValue, 100);

    EXPECT_EQUAL(21u, a1->getNumDocs());
    EXPECT_EQUAL(21u, a2->getNumDocs());
    EXPECT_EQUAL(20u, ex->getNumDocs());
    EXPECT_EQUAL(21u, a1->getCommittedDocIdLimit());
    EXPECT_EQUAL(21u, a2->getCommittedDocIdLimit());
    EXPECT_EQUAL(20u, ex->getCommittedDocIdLimit());

    f._aw.compactLidSpace(10, 101);

    EXPECT_EQUAL(21u, a1->getNumDocs());
    EXPECT_EQUAL(21u, a2->getNumDocs());
    EXPECT_EQUAL(20u, ex->getNumDocs());
    EXPECT_EQUAL(21u, a1->getCommittedDocIdLimit());
    EXPECT_EQUAL(10u, a2->getCommittedDocIdLimit());
    EXPECT_EQUAL(20u, ex->getCommittedDocIdLimit());
}

TEST_F("require that flushed serial number can be retrieved", Fixture)
{
    f.addAttribute("a1");
    EXPECT_EQUAL(0u, f._m.getFlushedSerialNum("a1"));
    f._m.flushAll(100);
    EXPECT_EQUAL(100u, f._m.getFlushedSerialNum("a1"));
    EXPECT_EQUAL(0u, f._m.getFlushedSerialNum("a2"));
}


TEST_F("require that writable attributes can be retrieved", Fixture)
{
    auto a1 = f.addAttribute("a1");
    auto a2 = f.addAttribute("a2");
    AttributeVector::SP ex(new Int32Attribute("ex"));
    f._m.addExtraAttribute(ex);
    auto &vec = f._m.getWritableAttributes();
    EXPECT_EQUAL(2u, vec.size());
    EXPECT_EQUAL(a1.get(), vec[0]);
    EXPECT_EQUAL(a2.get(), vec[1]);
    EXPECT_EQUAL(a1.get(), f._m.getWritableAttribute("a1"));
    EXPECT_EQUAL(a2.get(), f._m.getWritableAttribute("a2"));
    AttributeVector *noAttr = nullptr;
    EXPECT_EQUAL(noAttr, f._m.getWritableAttribute("a3"));
    EXPECT_EQUAL(noAttr, f._m.getWritableAttribute("ex"));
}


void
populateAndFlushAttributes(AttributeManagerFixture &f)
{
    const int64_t attrValue = 7;
    AttributeVector::SP a1 = f.addAttribute("a1");
    fillAttribute(a1, 1, 10, attrValue, createSerialNum);
    AttributeVector::SP a2 = f.addAttribute("a2");
    fillAttribute(a2, 1, 10, attrValue, createSerialNum);
    AttributeVector::SP a3 = f.addAttribute("a3");
    fillAttribute(a3, 1, 10, attrValue, createSerialNum);
    f._m.flushAll(createSerialNum + 3);
}

void
validateAttribute(const AttributeVector &attr)
{
    ASSERT_EQUAL(10u, attr.getNumDocs());
    EXPECT_EQUAL(createSerialNum + 3, attr.getStatus().getLastSyncToken());
    for (uint32_t docId = 1; docId < 10; ++docId) {
        EXPECT_EQUAL(7, attr.getInt(docId));
    }
}

TEST_F("require that attributes can be initialized and loaded in sequence", BaseFixture)
{
    {
        AttributeManagerFixture amf(f);
        populateAndFlushAttributes(amf);
    }
    {
        AttributeManagerFixture amf(f);

        AttrSpecList newSpec;
        newSpec.push_back(AttrSpec("a1", INT32_SINGLE));
        newSpec.push_back(AttrSpec("a2", INT32_SINGLE));
        newSpec.push_back(AttrSpec("a3", INT32_SINGLE));

        SequentialAttributeManager newMgr(amf._m, AttrMgrSpec(newSpec, 10, createSerialNum + 5));

        AttributeGuard::UP a1 = newMgr.mgr.getAttribute("a1");
        TEST_DO(validateAttribute(*a1->get()));
        AttributeGuard::UP a2 = newMgr.mgr.getAttribute("a2");
        TEST_DO(validateAttribute(*a2->get()));
        AttributeGuard::UP a3 = newMgr.mgr.getAttribute("a3");
        TEST_DO(validateAttribute(*a3->get()));
    }
}

AttributesConfigBuilder::Attribute
createAttributeConfig(const vespalib::string &name)
{
    AttributesConfigBuilder::Attribute result;
    result.name = name;
    result.datatype = AttributesConfigBuilder::Attribute::Datatype::INT32;
    result.collectiontype = AttributesConfigBuilder::Attribute::Collectiontype::SINGLE;
    return result;
}

TEST_F("require that attributes can be initialized and loaded in parallel", BaseFixture)
{
    {
        AttributeManagerFixture amf(f);
        populateAndFlushAttributes(amf);
    }
    {
        AttributeManagerFixture amf(f);

        AttributesConfigBuilder attrCfg;
        attrCfg.attribute.push_back(createAttributeConfig("a1"));
        attrCfg.attribute.push_back(createAttributeConfig("a2"));
        attrCfg.attribute.push_back(createAttributeConfig("a3"));

        ParallelAttributeManager newMgr(createSerialNum + 5, amf._msp, attrCfg, 10);

        AttributeGuard::UP a1 = newMgr.mgr->get()->getAttribute("a1");
        TEST_DO(validateAttribute(*a1->get()));
        AttributeGuard::UP a2 = newMgr.mgr->get()->getAttribute("a2");
        TEST_DO(validateAttribute(*a2->get()));
        AttributeGuard::UP a3 = newMgr.mgr->get()->getAttribute("a3");
        TEST_DO(validateAttribute(*a3->get()));
    }
}

TEST_F("require that we can call functions on all attributes via functor",
       Fixture)
{
    f.addAttribute("a1");
    f.addAttribute("a2");
    f.addAttribute("a3");
    std::shared_ptr<MyAttributeFunctor> functor =
        std::make_shared<MyAttributeFunctor>();
    f._m.asyncForEachAttribute(functor);
    EXPECT_EQUAL("a1,a2,a3", functor->getSortedNames());
}

TEST_F("require that we can acquire exclusive read access to attribute", Fixture)
{
    f.addAttribute("attr");
    ExclusiveAttributeReadAccessor::UP attrAccessor = f._m.getExclusiveReadAccessor("attr");
    ExclusiveAttributeReadAccessor::UP noneAccessor = f._m.getExclusiveReadAccessor("none");
    EXPECT_TRUE(attrAccessor.get() != nullptr);
    EXPECT_TRUE(noneAccessor.get() == nullptr);
}

TEST_MAIN()
{
    vespalib::rmdir(test_dir, true);
    TEST_RUN_ALL();
}
