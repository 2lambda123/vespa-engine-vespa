// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP("attribute_test");

#include <vespa/fastos/file.h>
#include <vespa/vespalib/testkit/testapp.h>
#include <vespa/document/fieldvalue/document.h>
#include <vespa/document/update/arithmeticvalueupdate.h>
#include <vespa/searchcommon/attribute/attributecontent.h>
#include <vespa/searchcore/proton/attribute/attribute_collection_spec_factory.h>
#include <vespa/searchcore/proton/attribute/attribute_writer.h>
#include <vespa/searchcore/proton/attribute/attributemanager.h>
#include <vespa/searchcore/proton/attribute/filter_attribute_manager.h>
#include <vespa/searchcore/proton/test/attribute_utils.h>
#include <vespa/searchlib/attribute/attributefactory.h>
#include <vespa/searchlib/attribute/integerbase.h>
#include <vespa/searchlib/common/idestructorcallback.h>
#include <vespa/searchlib/index/docbuilder.h>
#include <vespa/searchlib/index/dummyfileheadercontext.h>
#include <vespa/searchlib/util/filekit.h>
#include <vespa/vespalib/io/fileutil.h>

#include <vespa/document/predicate/predicate_slime_builder.h>
#include <vespa/document/update/assignvalueupdate.h>
#include <vespa/searchlib/attribute/attributevector.hpp>
#include <vespa/searchlib/attribute/predicate_attribute.h>
#include <vespa/searchlib/predicate/predicate_index.h>
#include <vespa/searchlib/attribute/singlenumericattribute.hpp>
#include <vespa/searchlib/predicate/predicate_hash.h>
#include <vespa/searchlib/common/foregroundtaskexecutor.h>
#include <vespa/searchcore/proton/test/directory_handler.h>
#include <vespa/vespalib/tensor/tensor.h>
#include <vespa/vespalib/tensor/types.h>
#include <vespa/vespalib/tensor/default_tensor.h>
#include <vespa/vespalib/tensor/tensor_factory.h>
#include <vespa/searchlib/attribute/tensorattribute.h>


namespace vespa { namespace config { namespace search {}}}

using std::string;
using namespace vespa::config::search;
using namespace config;
using namespace document;
using namespace proton;
using namespace search;
using namespace search::index;
using search::attribute::TensorAttribute;
using search::TuneFileAttributes;
using search::index::DummyFileHeaderContext;
using search::predicate::PredicateIndex;
using search::predicate::PredicateHash;
using vespalib::tensor::Tensor;
using vespalib::tensor::TensorType;
using vespalib::tensor::TensorCells;
using vespalib::tensor::TensorDimensions;

typedef search::attribute::Config AVConfig;
typedef search::attribute::BasicType AVBasicType;
typedef search::attribute::CollectionType AVCollectionType;
typedef proton::AttributeCollectionSpec::Attribute AttrSpec;
typedef proton::AttributeCollectionSpec::AttributeList AttrSpecList;
typedef proton::AttributeCollectionSpec AttrMgrSpec;
typedef SingleValueNumericAttribute<IntegerAttributeTemplate<int32_t> > Int32AttributeVector;

namespace
{

const uint64_t createSerialNum = 42u;

}

AVConfig
unregister(const AVConfig & cfg)
{
    AVConfig retval = cfg;
    return retval;
}

const string test_dir = "test_output";
const AVConfig INT32_SINGLE = unregister(AVConfig(AVBasicType::INT32));
const AVConfig INT32_ARRAY = unregister(AVConfig(AVBasicType::INT32, AVCollectionType::ARRAY));

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

const std::shared_ptr<IDestructorCallback> emptyCallback;


struct Fixture
{
    test::DirectoryHandler _dirHandler;
    DummyFileHeaderContext   _fileHeaderContext;
    ForegroundTaskExecutor   _attributeFieldWriter;
    proton::AttributeManager::SP _m;
    AttributeWriter aw;

    Fixture()
        : _dirHandler(test_dir),
          _fileHeaderContext(),
          _attributeFieldWriter(),
          _m(std::make_shared<proton::AttributeManager>
             (test_dir, "test.subdb", TuneFileAttributes(),
              _fileHeaderContext, _attributeFieldWriter)),
          aw(_m)
    {
    }
    AttributeVector::SP addAttribute(const vespalib::string &name) {
        return _m->addAttribute(name, AVConfig(AVBasicType::INT32),
                                createSerialNum);
    }
    void put(SerialNum serialNum, const Document &doc, DocumentIdT lid,
             bool immediateCommit = true) {
        aw.put(serialNum, doc, lid, immediateCommit, emptyCallback);
    }
    void update(SerialNum serialNum, const DocumentUpdate &upd,
                DocumentIdT lid, bool immediateCommit) {
        aw.update(serialNum, upd, lid, immediateCommit, emptyCallback);
    }
    void remove(SerialNum serialNum, DocumentIdT lid, bool immediateCommit = true) {
        aw.remove(serialNum, lid, immediateCommit, emptyCallback);
    }
    void commit(SerialNum serialNum) {
        aw.commit(serialNum, emptyCallback);
    }
};


TEST_F("require that attribute adapter handles put", Fixture)
{
    Schema s;
    s.addAttributeField(Schema::AttributeField("a1", Schema::INT32, Schema::SINGLE));
    s.addAttributeField(Schema::AttributeField("a2", Schema::INT32, Schema::ARRAY));
    s.addAttributeField(Schema::AttributeField("a3", Schema::FLOAT, Schema::SINGLE));
    s.addAttributeField(Schema::AttributeField("a4", Schema::STRING, Schema::SINGLE));

    DocBuilder idb(s);

    proton::AttributeManager & am = *f._m;
    AttributeVector::SP a1 = f.addAttribute("a1");
    AttributeVector::SP a2 =
        am.addAttribute("a2",
                        AVConfig(AVBasicType::INT32,
                                 AVCollectionType::ARRAY),
                        createSerialNum);
    AttributeVector::SP a3 =
        am.addAttribute("a3", AVConfig(AVBasicType::FLOAT),
                        createSerialNum);
    AttributeVector::SP a4 = am.addAttribute("a4",
                                             AVConfig(AVBasicType::STRING),
                                             createSerialNum);

    attribute::IntegerContent ibuf;
    attribute::FloatContent fbuf;
    attribute::ConstCharContent sbuf;
    { // empty document should give default values
        EXPECT_EQUAL(1u, a1->getNumDocs());
        f.put(1, *idb.startDocument("doc::1").endDocument(), 1);
        EXPECT_EQUAL(2u, a1->getNumDocs());
        EXPECT_EQUAL(2u, a2->getNumDocs());
        EXPECT_EQUAL(2u, a3->getNumDocs());
        EXPECT_EQUAL(2u, a4->getNumDocs());
        EXPECT_EQUAL(1u, a1->getStatus().getLastSyncToken());
        EXPECT_EQUAL(1u, a2->getStatus().getLastSyncToken());
        EXPECT_EQUAL(1u, a3->getStatus().getLastSyncToken());
        EXPECT_EQUAL(1u, a4->getStatus().getLastSyncToken());
        ibuf.fill(*a1, 1);
        EXPECT_EQUAL(1u, ibuf.size());
        EXPECT_TRUE(search::attribute::isUndefined<int32_t>(ibuf[0]));
        ibuf.fill(*a2, 1);
        EXPECT_EQUAL(0u, ibuf.size());
        fbuf.fill(*a3, 1);
        EXPECT_EQUAL(1u, fbuf.size());
        EXPECT_TRUE(search::attribute::isUndefined<float>(fbuf[0]));
        sbuf.fill(*a4, 1);
        EXPECT_EQUAL(1u, sbuf.size());
        EXPECT_EQUAL(strcmp("", sbuf[0]), 0);
    }
    { // document with single value & multi value attribute
        Document::UP doc = idb.startDocument("doc::2").
            startAttributeField("a1").addInt(10).endField().
            startAttributeField("a2").startElement().addInt(20).endElement().
                                      startElement().addInt(30).endElement().endField().endDocument();
        f.put(2, *doc, 2);
        EXPECT_EQUAL(3u, a1->getNumDocs());
        EXPECT_EQUAL(3u, a2->getNumDocs());
        EXPECT_EQUAL(2u, a1->getStatus().getLastSyncToken());
        EXPECT_EQUAL(2u, a2->getStatus().getLastSyncToken());
        EXPECT_EQUAL(2u, a3->getStatus().getLastSyncToken());
        EXPECT_EQUAL(2u, a4->getStatus().getLastSyncToken());
        ibuf.fill(*a1, 2);
        EXPECT_EQUAL(1u, ibuf.size());
        EXPECT_EQUAL(10u, ibuf[0]);
        ibuf.fill(*a2, 2);
        EXPECT_EQUAL(2u, ibuf.size());
        EXPECT_EQUAL(20u, ibuf[0]);
        EXPECT_EQUAL(30u, ibuf[1]);
    }
    { // replace existing document
        Document::UP doc = idb.startDocument("doc::2").
            startAttributeField("a1").addInt(100).endField().
            startAttributeField("a2").startElement().addInt(200).endElement().
                                      startElement().addInt(300).endElement().
                                      startElement().addInt(400).endElement().endField().endDocument();
        f.put(3, *doc, 2);
        EXPECT_EQUAL(3u, a1->getNumDocs());
        EXPECT_EQUAL(3u, a2->getNumDocs());
        EXPECT_EQUAL(3u, a1->getStatus().getLastSyncToken());
        EXPECT_EQUAL(3u, a2->getStatus().getLastSyncToken());
        EXPECT_EQUAL(3u, a3->getStatus().getLastSyncToken());
        EXPECT_EQUAL(3u, a4->getStatus().getLastSyncToken());
        ibuf.fill(*a1, 2);
        EXPECT_EQUAL(1u, ibuf.size());
        EXPECT_EQUAL(100u, ibuf[0]);
        ibuf.fill(*a2, 2);
        EXPECT_EQUAL(3u, ibuf.size());
        EXPECT_EQUAL(200u, ibuf[0]);
        EXPECT_EQUAL(300u, ibuf[1]);
        EXPECT_EQUAL(400u, ibuf[2]);
    }
}

TEST_F("require that attribute adapter handles predicate put", Fixture)
{
    Schema s;
    s.addAttributeField(
            Schema::AttributeField("a1", Schema::BOOLEANTREE, Schema::SINGLE));
    DocBuilder idb(s);

    proton::AttributeManager & am = *f._m;
    AttributeVector::SP a1 = am.addAttribute("a1",
                                             AVConfig(AVBasicType::PREDICATE),
                                             createSerialNum);

    PredicateIndex &index = static_cast<PredicateAttribute &>(*a1).getIndex();

    // empty document should give default values
    EXPECT_EQUAL(1u, a1->getNumDocs());
    f.put(1, *idb.startDocument("doc::1").endDocument(), 1);
    EXPECT_EQUAL(2u, a1->getNumDocs());
    EXPECT_EQUAL(1u, a1->getStatus().getLastSyncToken());
    EXPECT_EQUAL(0u, index.getZeroConstraintDocs().size());

    // document with single value attribute
    PredicateSlimeBuilder builder;
    Document::UP doc =
        idb.startDocument("doc::2").startAttributeField("a1")
        .addPredicate(builder.true_predicate().build())
        .endField().endDocument();
    f.put(2, *doc, 2);
    EXPECT_EQUAL(3u, a1->getNumDocs());
    EXPECT_EQUAL(2u, a1->getStatus().getLastSyncToken());
    EXPECT_EQUAL(1u, index.getZeroConstraintDocs().size());

    auto it = index.getIntervalIndex().lookup(PredicateHash::hash64("foo=bar"));
    EXPECT_FALSE(it.valid());

    // replace existing document
    doc = idb.startDocument("doc::2").startAttributeField("a1")
          .addPredicate(builder.feature("foo").value("bar").build())
          .endField().endDocument();
    f.put(3, *doc, 2);
    EXPECT_EQUAL(3u, a1->getNumDocs());
    EXPECT_EQUAL(3u, a1->getStatus().getLastSyncToken());

    it = index.getIntervalIndex().lookup(PredicateHash::hash64("foo=bar"));
    EXPECT_TRUE(it.valid());
}

TEST_F("require that attribute adapter handles remove", Fixture)
{
    AttributeVector::SP a1 = f.addAttribute("a1");
    AttributeVector::SP a2 = f.addAttribute("a2");
    Schema s;
    s.addAttributeField(Schema::AttributeField("a1", Schema::INT32, Schema::SINGLE));
    s.addAttributeField(Schema::AttributeField("a2", Schema::INT32, Schema::SINGLE));

    DocBuilder idb(s);

    fillAttribute(a1, 1, 10, 1);
    fillAttribute(a2, 1, 20, 1);

    f.remove(2, 0);

    EXPECT_TRUE(search::attribute::isUndefined<int32_t>(a1->getInt(0)));
    EXPECT_TRUE(search::attribute::isUndefined<int32_t>(a2->getInt(0)));

    f.remove(2, 0); // same sync token as previous
    try {
        f.remove(1, 0); // lower sync token than previous
        EXPECT_TRUE(true);	// update is ignored
    } catch (vespalib::IllegalStateException & e) {
        LOG(info, "Got expected exception: '%s'", e.getMessage().c_str());
        EXPECT_TRUE(true);
    }
}

void verifyAttributeContent(const AttributeVector & v, uint32_t lid, vespalib::stringref expected)
{
    attribute::ConstCharContent sbuf;
    sbuf.fill(v, lid);
    EXPECT_EQUAL(1u, sbuf.size());
    EXPECT_EQUAL(expected, sbuf[0]);
}

TEST_F("require that visibilitydelay is honoured", Fixture)
{
    proton::AttributeManager & am = *f._m;
    AttributeVector::SP a1 = am.addAttribute("a1",
                                             AVConfig(AVBasicType::STRING),
                                             createSerialNum);
    Schema s;
    s.addAttributeField(Schema::AttributeField("a1", Schema::STRING, Schema::SINGLE));
    DocBuilder idb(s);
    EXPECT_EQUAL(1u, a1->getNumDocs());
    EXPECT_EQUAL(0u, a1->getStatus().getLastSyncToken());
    Document::UP doc = idb.startDocument("doc::1")
                              .startAttributeField("a1").addStr("10").endField()
                          .endDocument();
    f.put(3, *doc, 1);
    EXPECT_EQUAL(2u, a1->getNumDocs());
    EXPECT_EQUAL(3u, a1->getStatus().getLastSyncToken());
    AttributeWriter awDelayed(f._m);
    awDelayed.put(4, *doc, 2, false, emptyCallback);
    EXPECT_EQUAL(3u, a1->getNumDocs());
    EXPECT_EQUAL(3u, a1->getStatus().getLastSyncToken());
    awDelayed.put(5, *doc, 4, false, emptyCallback);
    EXPECT_EQUAL(5u, a1->getNumDocs());
    EXPECT_EQUAL(3u, a1->getStatus().getLastSyncToken());
    awDelayed.commit(6, emptyCallback);
    EXPECT_EQUAL(6u, a1->getStatus().getLastSyncToken());

    AttributeWriter awDelayedShort(f._m);
    awDelayedShort.put(7, *doc, 2, false, emptyCallback);
    EXPECT_EQUAL(6u, a1->getStatus().getLastSyncToken());
    awDelayedShort.put(8, *doc, 2, false, emptyCallback);
    awDelayedShort.commit(8, emptyCallback);
    EXPECT_EQUAL(8u, a1->getStatus().getLastSyncToken());

    verifyAttributeContent(*a1, 2, "10");
    awDelayed.put(9, *idb.startDocument("doc::1").startAttributeField("a1").addStr("11").endField().endDocument(),
            2, false, emptyCallback);
    awDelayed.put(10, *idb.startDocument("doc::1").startAttributeField("a1").addStr("20").endField().endDocument(),
            2, false, emptyCallback);
    awDelayed.put(11, *idb.startDocument("doc::1").startAttributeField("a1").addStr("30").endField().endDocument(),
            2, false, emptyCallback);
    EXPECT_EQUAL(8u, a1->getStatus().getLastSyncToken());
    verifyAttributeContent(*a1, 2, "10");
    awDelayed.commit(12, emptyCallback);
    EXPECT_EQUAL(12u, a1->getStatus().getLastSyncToken());
    verifyAttributeContent(*a1, 2, "30");
    
}

TEST_F("require that attribute adapter handles predicate remove", Fixture)
{
    proton::AttributeManager & am = *f._m;
    AttributeVector::SP a1 = am.addAttribute("a1",
                                             AVConfig(AVBasicType::PREDICATE),
                                             createSerialNum);
    Schema s;
    s.addAttributeField(
            Schema::AttributeField("a1", Schema::BOOLEANTREE, Schema::SINGLE));

    DocBuilder idb(s);
    PredicateSlimeBuilder builder;
    Document::UP doc =
        idb.startDocument("doc::1").startAttributeField("a1")
        .addPredicate(builder.true_predicate().build())
        .endField().endDocument();
    f.put(1, *doc, 1);
    EXPECT_EQUAL(2u, a1->getNumDocs());

    PredicateIndex &index = static_cast<PredicateAttribute &>(*a1).getIndex();
    EXPECT_EQUAL(1u, index.getZeroConstraintDocs().size());
    f.remove(2, 1);
    EXPECT_EQUAL(0u, index.getZeroConstraintDocs().size());
}

TEST_F("require that attribute adapter handles update", Fixture)
{
    AttributeVector::SP a1 = f.addAttribute("a1");
    AttributeVector::SP a2 = f.addAttribute("a2");

    fillAttribute(a1, 1, 10, 1);
    fillAttribute(a2, 1, 20, 1);

    Schema schema;
    schema.addAttributeField(Schema::AttributeField(
                    "a1", Schema::INT32,
                    Schema::SINGLE));
    schema.addAttributeField(Schema::AttributeField(
                    "a2", Schema::INT32,
                    Schema::SINGLE));
    DocBuilder idb(schema);
    const document::DocumentType &dt(idb.getDocumentType());
    DocumentUpdate upd(dt, DocumentId("doc::1"));
    upd.addUpdate(FieldUpdate(upd.getType().getField("a1"))
                  .addUpdate(ArithmeticValueUpdate(ArithmeticValueUpdate::Add, 5)));
    upd.addUpdate(FieldUpdate(upd.getType().getField("a2"))
                  .addUpdate(ArithmeticValueUpdate(ArithmeticValueUpdate::Add, 10)));

    bool immediateCommit = true;
    f.update(2, upd, 1, immediateCommit);

    attribute::IntegerContent ibuf;
    ibuf.fill(*a1, 1);
    EXPECT_EQUAL(1u, ibuf.size());
    EXPECT_EQUAL(15u, ibuf[0]);
    ibuf.fill(*a2, 1);
    EXPECT_EQUAL(1u, ibuf.size());
    EXPECT_EQUAL(30u, ibuf[0]);

    f.update(2, upd, 1, immediateCommit); // same sync token as previous
    try {
        f.update(1, upd, 1, immediateCommit); // lower sync token than previous
        EXPECT_TRUE(true);	// update is ignored
    } catch (vespalib::IllegalStateException & e) {
        LOG(info, "Got expected exception: '%s'", e.getMessage().c_str());
        EXPECT_TRUE(true);
    }
}

TEST_F("require that attribute adapter handles predicate update", Fixture)
{
    proton::AttributeManager & am = *f._m;
    AttributeVector::SP a1 = am.addAttribute("a1",
                                             AVConfig(AVBasicType::PREDICATE),
                                             createSerialNum);
    Schema schema;
    schema.addAttributeField(Schema::AttributeField(
                    "a1", Schema::BOOLEANTREE,
                    Schema::SINGLE));

    DocBuilder idb(schema);
    PredicateSlimeBuilder builder;
    Document::UP doc =
        idb.startDocument("doc::1").startAttributeField("a1")
        .addPredicate(builder.true_predicate().build())
        .endField().endDocument();
    f.put(1, *doc, 1);
    EXPECT_EQUAL(2u, a1->getNumDocs());

    const document::DocumentType &dt(idb.getDocumentType());
    DocumentUpdate upd(dt, DocumentId("doc::1"));
    PredicateFieldValue new_value(builder.feature("foo").value("bar").build());
    upd.addUpdate(FieldUpdate(upd.getType().getField("a1"))
                  .addUpdate(AssignValueUpdate(new_value)));

    PredicateIndex &index = static_cast<PredicateAttribute &>(*a1).getIndex();
    EXPECT_EQUAL(1u, index.getZeroConstraintDocs().size());
    EXPECT_FALSE(index.getIntervalIndex().lookup(PredicateHash::hash64("foo=bar")).valid());
    bool immediateCommit = true;
    f.update(2, upd, 1, immediateCommit);
    EXPECT_EQUAL(0u, index.getZeroConstraintDocs().size());
    EXPECT_TRUE(index.getIntervalIndex().lookup(PredicateHash::hash64("foo=bar")).valid());
}

struct AttributeCollectionSpecFixture
{
    AttributesConfigBuilder _builder;
    AttributeCollectionSpecFactory _factory;
    AttributeCollectionSpecFixture(bool fastAccessOnly)
        : _builder(),
          _factory(search::GrowStrategy(), 100, fastAccessOnly)
    {
        addAttribute("a1", false);
        addAttribute("a2", true);
    }
    void addAttribute(const vespalib::string &name, bool fastAccess) {
        AttributesConfigBuilder::Attribute attr;
        attr.name = name;
        attr.fastaccess = fastAccess;
        _builder.attribute.push_back(attr);
    }
    AttributeCollectionSpec::UP create(uint32_t docIdLimit,
                                       search::SerialNum serialNum) {
        return _factory.create(_builder, docIdLimit, serialNum);
    }
};

struct NormalAttributeCollectionSpecFixture : public AttributeCollectionSpecFixture
{
    NormalAttributeCollectionSpecFixture() : AttributeCollectionSpecFixture(false) {}
};

struct FastAccessAttributeCollectionSpecFixture : public AttributeCollectionSpecFixture
{
    FastAccessAttributeCollectionSpecFixture() : AttributeCollectionSpecFixture(true) {}
};

TEST_F("require that normal attribute collection spec can be created",
        NormalAttributeCollectionSpecFixture)
{
    AttributeCollectionSpec::UP spec = f.create(10, 20);
    EXPECT_EQUAL(2u, spec->getAttributes().size());
    EXPECT_EQUAL("a1", spec->getAttributes()[0].getName());
    EXPECT_EQUAL("a2", spec->getAttributes()[1].getName());
    EXPECT_EQUAL(10u, spec->getDocIdLimit());
    EXPECT_EQUAL(20u, spec->getCurrentSerialNum());
}

TEST_F("require that fast access attribute collection spec can be created",
        FastAccessAttributeCollectionSpecFixture)
{
    AttributeCollectionSpec::UP spec = f.create(10, 20);
    EXPECT_EQUAL(1u, spec->getAttributes().size());
    EXPECT_EQUAL("a2", spec->getAttributes()[0].getName());
    EXPECT_EQUAL(10u, spec->getDocIdLimit());
    EXPECT_EQUAL(20u, spec->getCurrentSerialNum());
}

const FilterAttributeManager::AttributeSet ACCEPTED_ATTRIBUTES = {"a2"};

struct FilterFixture
{
    test::DirectoryHandler _dirHandler;
    DummyFileHeaderContext _fileHeaderContext;
    ForegroundTaskExecutor _attributeFieldWriter;
    proton::AttributeManager::SP _baseMgr;
    FilterAttributeManager _filterMgr;
    FilterFixture()
        : _dirHandler(test_dir),
          _fileHeaderContext(),
          _attributeFieldWriter(),
          _baseMgr(new proton::AttributeManager(test_dir, "test.subdb",
                                                TuneFileAttributes(),
                                                _fileHeaderContext,
                                                _attributeFieldWriter)),
          _filterMgr(ACCEPTED_ATTRIBUTES, _baseMgr)
    {
        _baseMgr->addAttribute("a1", INT32_SINGLE, createSerialNum);
        _baseMgr->addAttribute("a2", INT32_SINGLE, createSerialNum);
   }
};

TEST_F("require that filter attribute manager can filter attributes", FilterFixture)
{
    EXPECT_TRUE(f._filterMgr.getAttribute("a1").get() == NULL);
    EXPECT_TRUE(f._filterMgr.getAttribute("a2").get() != NULL);
    std::vector<AttributeGuard> attrs;
    f._filterMgr.getAttributeList(attrs);
    EXPECT_EQUAL(1u, attrs.size());
    EXPECT_EQUAL("a2", attrs[0].get().getName());
}

TEST_F("require that filter attribute manager can return flushed serial number", FilterFixture)
{
    f._baseMgr->flushAll(100);
    EXPECT_EQUAL(0u, f._filterMgr.getFlushedSerialNum("a1"));
    EXPECT_EQUAL(100u, f._filterMgr.getFlushedSerialNum("a2"));
}

namespace {

Tensor::UP
createTensor(const TensorCells &cells, const TensorDimensions &dimensions) {
    vespalib::tensor::DefaultTensor::builder builder;
    return vespalib::tensor::TensorFactory::create(cells, dimensions, builder);
}


AttributeVector::SP
createTensorAttribute(Fixture &f) {
    AVConfig cfg(AVBasicType::TENSOR);
    cfg.setTensorType(TensorType::fromSpec("tensor(x{},y{})"));
    return f._m->addAttribute("a1", cfg, createSerialNum);
}

Schema
createTensorSchema() {
    Schema schema;
    schema.addAttributeField(Schema::AttributeField("a1", Schema::TENSOR,
                                               Schema::SINGLE));
    return schema;
}

Document::UP
createTensorPutDoc(DocBuilder &builder, const Tensor &tensor) {
    return builder.startDocument("doc::1").
        startAttributeField("a1").
        addTensor(tensor.clone()).endField().endDocument();
}

}


TEST_F("Test that we can use attribute writer to write to tensor attribute",
       Fixture)
{
    AttributeVector::SP a1 = createTensorAttribute(f);
    Schema s = createTensorSchema();
    DocBuilder builder(s);
    auto tensor = createTensor({ {{{"x", "4"}, {"y", "5"}}, 7} },
                               {"x", "y"});
    Document::UP doc = createTensorPutDoc(builder, *tensor);
    f.put(1, *doc, 1);
    EXPECT_EQUAL(2u, a1->getNumDocs());
    TensorAttribute *tensorAttribute =
        dynamic_cast<TensorAttribute *>(a1.get());
    EXPECT_TRUE(tensorAttribute != nullptr);
    auto tensor2 = tensorAttribute->getTensor(1);
    EXPECT_TRUE(static_cast<bool>(tensor2));
    EXPECT_TRUE(tensor->equals(*tensor2));
}

TEST_F("require that attribute writer handles tensor assign update", Fixture)
{
    AttributeVector::SP a1 = createTensorAttribute(f);
    Schema s = createTensorSchema();
    DocBuilder builder(s);
    auto tensor = createTensor({ {{{"x", "6"}, {"y", "7"}}, 9} },
                               {"x", "y"});
    Document::UP doc = createTensorPutDoc(builder, *tensor);
    f.put(1, *doc, 1);
    EXPECT_EQUAL(2u, a1->getNumDocs());
    TensorAttribute *tensorAttribute =
        dynamic_cast<TensorAttribute *>(a1.get());
    EXPECT_TRUE(tensorAttribute != nullptr);
    auto tensor2 = tensorAttribute->getTensor(1);
    EXPECT_TRUE(static_cast<bool>(tensor2));
    EXPECT_TRUE(tensor->equals(*tensor2));

    const document::DocumentType &dt(builder.getDocumentType());
    DocumentUpdate upd(dt, DocumentId("doc::1"));
    auto new_tensor = createTensor({ {{{"x", "8"}, {"y", "9"}}, 11} },
                                   {"x", "y"});
    TensorFieldValue new_value;
    new_value = new_tensor->clone();
    upd.addUpdate(FieldUpdate(upd.getType().getField("a1"))
                  .addUpdate(AssignValueUpdate(new_value)));
    bool immediateCommit = true;
    f.update(2, upd, 1, immediateCommit);
    EXPECT_EQUAL(2u, a1->getNumDocs());
    EXPECT_TRUE(tensorAttribute != nullptr);
    tensor2 = tensorAttribute->getTensor(1);
    EXPECT_TRUE(static_cast<bool>(tensor2));
    EXPECT_TRUE(!tensor->equals(*tensor2));
    EXPECT_TRUE(new_tensor->equals(*tensor2));

}

TEST_MAIN()
{
    vespalib::rmdir(test_dir, true);
    TEST_RUN_ALL();
}
