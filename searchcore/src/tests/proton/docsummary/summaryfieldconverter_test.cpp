// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Unit tests for summaryfieldconverter.

#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP("summaryfieldconverter_test");

#include <vespa/document/annotation/annotation.h>
#include <vespa/document/annotation/span.h>
#include <vespa/document/annotation/spanlist.h>
#include <vespa/document/annotation/spantree.h>
#include <vespa/document/base/documentid.h>
#include <vespa/document/base/exceptions.h>
#include <vespa/document/base/field.h>
#include <vespa/document/datatype/annotationtype.h>
#include <vespa/document/datatype/arraydatatype.h>
#include <vespa/document/datatype/datatype.h>
#include <vespa/document/datatype/documenttype.h>
#include <vespa/document/datatype/structdatatype.h>
#include <vespa/document/datatype/urldatatype.h>
#include <vespa/document/datatype/weightedsetdatatype.h>
#include <vespa/document/fieldvalue/arrayfieldvalue.h>
#include <vespa/document/fieldvalue/bytefieldvalue.h>
#include <vespa/document/fieldvalue/document.h>
#include <vespa/document/fieldvalue/doublefieldvalue.h>
#include <vespa/document/fieldvalue/floatfieldvalue.h>
#include <vespa/document/fieldvalue/intfieldvalue.h>
#include <vespa/document/fieldvalue/longfieldvalue.h>
#include <vespa/document/fieldvalue/predicatefieldvalue.h>
#include <vespa/document/fieldvalue/rawfieldvalue.h>
#include <vespa/document/fieldvalue/shortfieldvalue.h>
#include <vespa/document/fieldvalue/stringfieldvalue.h>
#include <vespa/document/fieldvalue/structfieldvalue.h>
#include <vespa/document/fieldvalue/weightedsetfieldvalue.h>
#include <vespa/document/fieldvalue/tensorfieldvalue.h>
#include <vespa/document/predicate/predicate.h>
#include <vespa/document/repo/configbuilder.h>
#include <vespa/document/repo/documenttyperepo.h>
#include <vespa/searchcore/proton/docsummary/summaryfieldconverter.h>
#include <vespa/searchcore/proton/docsummary/linguisticsannotation.h>
#include <vespa/searchcore/proton/docsummary/searchdatatype.h>
#include <vespa/searchcommon/common/schema.h>
#include <vespa/config-summarymap.h>
#include <vespa/vespalib/geo/zcurve.h>
#include <vespa/vespalib/testkit/testapp.h>
#include <vespa/vespalib/data/slime/json_format.h>
#include <vespa/vespalib/data/slime/binary_format.h>
#include <vespa/searchlib/util/slime_output_raw_buf_adapter.h>
#include <vespa/vespalib/tensor/tensor.h>
#include <vespa/vespalib/tensor/types.h>
#include <vespa/vespalib/tensor/default_tensor.h>
#include <vespa/vespalib/tensor/tensor_factory.h>

using vespa::config::search::SummarymapConfig;
using vespa::config::search::SummarymapConfigBuilder;
using document::Annotation;
using document::AnnotationType;
using document::ArrayDataType;
using document::ArrayFieldValue;
using document::ByteFieldValue;
using document::DataType;
using document::Document;
using document::DocumenttypesConfig;
using document::DocumenttypesConfigBuilder;
using document::DocumentId;
using document::DocumentType;
using document::DocumentTypeRepo;
using document::DoubleFieldValue;
using document::FeatureSet;
using document::Field;
using document::FieldNotFoundException;
using document::FieldValue;
using document::FloatFieldValue;
using document::IntFieldValue;
using document::LongFieldValue;
using document::Predicate;
using document::PredicateFieldValue;
using document::RawFieldValue;
using document::ShortFieldValue;
using document::Span;
using document::SpanList;
using document::SpanTree;
using document::StringFieldValue;
using document::StructDataType;
using document::StructFieldValue;
using document::UrlDataType;
using document::WeightedSetDataType;
using document::WeightedSetFieldValue;
using document::TensorFieldValue;
using search::index::Schema;
using vespalib::Slime;
using vespalib::slime::Cursor;
using vespalib::string;
using namespace proton;
using namespace proton::linguistics;
using vespalib::geo::ZCurve;
using vespalib::tensor::Tensor;
using vespalib::tensor::TensorCells;
using vespalib::tensor::TensorDimensions;

typedef SummaryFieldConverter SFC;

namespace {

struct FieldBlock {
    vespalib::string input;
    Slime slime;
    search::RawBuf binary;
    vespalib::string json;

    explicit FieldBlock(const vespalib::string &jsonInput)
        : input(jsonInput), slime(), binary(1024), json()
    {
        size_t used = vespalib::slime::JsonFormat::decode(jsonInput, slime);
        EXPECT_EQUAL(jsonInput.size(), used);
        {
            search::SlimeOutputRawBufAdapter adapter(binary);
            vespalib::slime::JsonFormat::encode(slime, adapter, true);
            json.assign(binary.GetDrainPos(), binary.GetUsedLen());
            binary.reset();
        }
        search::SlimeOutputRawBufAdapter adapter(binary);
        vespalib::slime::BinaryFormat::encode(slime, adapter);
    }
};

class Test : public vespalib::TestApp {
    std::unique_ptr<Schema> _schema;
    std::unique_ptr<SummarymapConfigBuilder> _summarymap;
    DocumentTypeRepo::SP      _documentRepo;
    const DocumentType       *_documentType;
    document::FixedTypeRepo   _fixedRepo;

    void setUp();
    void tearDown();

    const DataType &getDataType(const string &name) const;

    template <typename T>
    T getValueAs(const string &field_name, const Document &doc);

    template <typename T>
    T
    cvtValueAs(const FieldValue::UP &fv);

    template <typename T>
    T
    cvtAttributeAs(const FieldValue::UP &fv);

    template <typename T>
    T
    cvtSummaryAs(bool markup, const FieldValue::UP &fv);

    void checkString(const string &str, const FieldValue *value);
    void checkData(const search::RawBuf &data, const FieldValue *value);
    template <unsigned int N>
    void checkArray(const char *(&str)[N], const FieldValue *value);
    void setSummaryField(const string &name);
    void setAttributeField(const string &name);

    void requireThatSummaryIsAnUnmodifiedString();
    void requireThatAttributeIsAnUnmodifiedString();
    void requireThatArrayIsFlattenedInSummaryField();
    void requireThatWeightedSetIsFlattenedInSummaryField();
    void requireThatPositionsAreTransformedInSummary();
    void requireThatArrayIsPreservedInAttributeField();
    void requireThatPositionsAreTransformedInAttributeField();
    void requireThatPositionArrayIsTransformedInAttributeField();
    void requireThatPositionWeightedSetIsTransformedInAttributeField();
    void requireThatAttributeCanBePrimitiveTypes();
    void requireThatSummaryCanBePrimitiveTypes();
    void requireThatSummaryHandlesCjk();
    void requireThatSearchDataTypeUsesDefaultDataTypes();
    void requireThatLinguisticsAnnotationUsesDefaultDataTypes();
    void requireThatPredicateIsPrinted();
    void requireThatTensorIsPrinted();
    const DocumentType &getDocType() const { return *_documentType; }
    Document makeDocument();
    StringFieldValue annotateTerm(const string &term);
    StringFieldValue makeAnnotatedChineseString();
    StringFieldValue makeAnnotatedString();
    void setSpanTree(StringFieldValue & value, SpanTree::UP tree);
public:
    Test();
    int Main();
};

DocumenttypesConfig getDocumenttypesConfig() {
    using namespace document::config_builder;
    DocumenttypesConfigBuilderHelper builder;
    builder.document(42, "indexingdocument",
                     Struct("indexingdocument.header")
                     .addField("empty", DataType::T_STRING)
                     .addField("string", DataType::T_STRING)
                     .addField("plain_string", DataType::T_STRING)
                     .addField("string_array", Array(DataType::T_STRING))
                     .addField("string_wset", Wset(DataType::T_STRING))
                     .addField("position1", DataType::T_INT)
                     .addField("position2", DataType::T_LONG)
                     .addField("position2_array", Array(DataType::T_LONG))
                     .addField("position2_wset", Wset(DataType::T_LONG))
                     .addField("uri", UrlDataType::getInstance().getId())
                     .addField("uri_array",
                               Array(UrlDataType::getInstance().getId()))
                     .addField("int", DataType::T_INT)
                     .addField("long", DataType::T_LONG)
                     .addField("short", DataType::T_SHORT)
                     .addField("byte", DataType::T_BYTE)
                     .addField("double", DataType::T_DOUBLE)
                     .addField("float", DataType::T_FLOAT)
                     .addField("chinese", DataType::T_STRING)
                     .addField("predicate", DataType::T_PREDICATE)
                     .addField("tensor", DataType::T_TENSOR),
                     Struct("indexingdocument.body"));
    return builder.config();
}

Test::Test() :
    _documentRepo(new DocumentTypeRepo(getDocumenttypesConfig())),
    _documentType(_documentRepo->getDocumentType("indexingdocument")),
    _fixedRepo(*_documentRepo, *_documentType)
{
    ASSERT_TRUE(_documentType);
}

#define TEST_CALL(func) \
    TEST_DO(setUp()); \
    TEST_DO(func); \
    TEST_DO(tearDown())

int
Test::Main()
{
    TEST_INIT("summaryfieldconverter_test");

    TEST_CALL(requireThatSummaryIsAnUnmodifiedString());
    TEST_CALL(requireThatAttributeIsAnUnmodifiedString());
    TEST_CALL(requireThatArrayIsFlattenedInSummaryField());
    TEST_CALL(requireThatWeightedSetIsFlattenedInSummaryField());
    TEST_CALL(requireThatPositionsAreTransformedInSummary());
    TEST_CALL(requireThatArrayIsPreservedInAttributeField());
    TEST_CALL(requireThatPositionsAreTransformedInAttributeField());
    TEST_CALL(requireThatPositionArrayIsTransformedInAttributeField());
    TEST_CALL(requireThatPositionWeightedSetIsTransformedInAttributeField());
    TEST_CALL(requireThatAttributeCanBePrimitiveTypes());
    TEST_CALL(requireThatSummaryCanBePrimitiveTypes());
    TEST_CALL(requireThatSummaryHandlesCjk());
    TEST_CALL(requireThatSearchDataTypeUsesDefaultDataTypes());
    TEST_CALL(requireThatLinguisticsAnnotationUsesDefaultDataTypes());
    TEST_CALL(requireThatPredicateIsPrinted());
    TEST_CALL(requireThatTensorIsPrinted());

    TEST_DONE();
}

void Test::setUp() {
    _schema.reset(new Schema);
    _summarymap.reset(new SummarymapConfigBuilder);
}

void Test::tearDown() {
}

const DataType &Test::getDataType(const string &name) const {
    const DataType *type = _documentRepo->getDataType(*_documentType, name);
    ASSERT_TRUE(type);
    return *type;
}

template <typename T>
std::unique_ptr<T> makeUP(T *p) { return std::unique_ptr<T>(p); }

StringFieldValue Test::makeAnnotatedString() {
    SpanList *span_list = new SpanList;
    SpanTree::UP tree(new SpanTree(SPANTREE_NAME, makeUP(span_list)));
    // Annotations don't have to be added sequentially.
    tree->annotate(span_list->add(makeUP(new Span(8, 3))),
                   makeUP(new Annotation(*TERM,
                                         makeUP(new StringFieldValue(
                                                         "Annotation")))));
    tree->annotate(span_list->add(makeUP(new Span(0, 3))), *TERM);
    tree->annotate(span_list->add(makeUP(new Span(4, 3))), *TERM);
    tree->annotate(span_list->add(makeUP(new Span(4, 3))),
                   makeUP(new Annotation(*TERM,
                                         makeUP(new StringFieldValue(
                                                         "Multiple")))));
    tree->annotate(span_list->add(makeUP(new Span(1, 2))),
                   makeUP(new Annotation(*TERM,
                                         makeUP(new StringFieldValue(
                                                         "Overlap")))));
    StringFieldValue value("Foo Bar Baz");
    setSpanTree(value, std::move(tree));
    return value;
}

StringFieldValue Test::annotateTerm(const string &term) {
    SpanTree::UP tree(new SpanTree(SPANTREE_NAME, makeUP(new Span(0, term.size()))));
    tree->annotate(tree->getRoot(), *TERM);
    StringFieldValue value(term);
    setSpanTree(value, std::move(tree));
    return value;
}

void Test::setSpanTree(StringFieldValue & value, SpanTree::UP tree) {
    StringFieldValue::SpanTrees trees;
    trees.push_back(std::move(tree));
    value.setSpanTrees(trees, _fixedRepo);
}

StringFieldValue Test::makeAnnotatedChineseString() {
    SpanList *span_list = new SpanList;
    SpanTree::UP tree(new SpanTree(SPANTREE_NAME, makeUP(span_list)));
    // These chinese characters each use 3 bytes in their UTF8 encoding.
    tree->annotate(span_list->add(makeUP(new Span(0, 15))), *TERM);
    tree->annotate(span_list->add(makeUP(new Span(15, 9))), *TERM);
    StringFieldValue value("我就是那个大灰狼");
    setSpanTree(value, std::move(tree));
    return value;
}

Document Test::makeDocument() {
    Document doc(getDocType(), DocumentId("doc:scheme:"));
    doc.setRepo(*_documentRepo);
    doc.setValue("string", makeAnnotatedString());

    doc.setValue("plain_string", StringFieldValue("Plain"));

    ArrayFieldValue array(getDataType("Array<String>"));
    array.add(annotateTerm("\"foO\""));
    array.add(annotateTerm("ba\\R"));
    doc.setValue("string_array", array);

    WeightedSetFieldValue wset(getDataType("WeightedSet<String>"));
    wset.add(annotateTerm("\"foo\""), 2);
    wset.add(annotateTerm("ba\\r"), 4);
    doc.setValue("string_wset", wset);

    doc.setValue("position1", IntFieldValue(5));

    doc.setValue("position2", LongFieldValue(ZCurve::encode(4, 2)));

    StructFieldValue uri(getDataType("url"));
    uri.setValue("all", annotateTerm("http://www.yahoo.com:42/foobar?q#frag"));
    uri.setValue("scheme", annotateTerm("http"));
    uri.setValue("host", annotateTerm("www.yahoo.com"));
    uri.setValue("port", annotateTerm("42"));
    uri.setValue("path", annotateTerm("foobar"));
    uri.setValue("query", annotateTerm("q"));
    uri.setValue("fragment", annotateTerm("frag"));
    doc.setValue("uri", uri);

    ArrayFieldValue uri_array(getDataType("Array<url>"));
    uri.setValue("all", annotateTerm("http://www.yahoo.com:80/foobar?q#frag"));
    uri.setValue("port", annotateTerm("80"));
    uri_array.add(uri);
    uri.setValue("all", annotateTerm("https://www.yahoo.com:443/foo?q#frag"));
    uri.setValue("scheme", annotateTerm("https"));
    uri.setValue("path", annotateTerm("foo"));
    uri.setValue("port", annotateTerm("443"));
    uri_array.add(uri);
    doc.setValue("uri_array", uri_array);

    ArrayFieldValue position2_array(getDataType("Array<Long>"));
    position2_array.add(LongFieldValue(ZCurve::encode(4, 2)));
    position2_array.add(LongFieldValue(ZCurve::encode(4, 4)));
    doc.setValue("position2_array", position2_array);

    WeightedSetFieldValue position2_wset(getDataType("WeightedSet<Long>"));
    position2_wset.add(LongFieldValue(ZCurve::encode(4, 2)), 4);
    position2_wset.add(LongFieldValue(ZCurve::encode(4, 4)), 2);
    doc.setValue("position2_wset", position2_wset);

    doc.setValue("int", IntFieldValue(42));
    doc.setValue("long", LongFieldValue(84));
    doc.setValue("short", ShortFieldValue(21));
    doc.setValue("byte", ByteFieldValue(11));
    doc.setValue("double", DoubleFieldValue(0.4));
    doc.setValue("float", FloatFieldValue(0.2f));

    doc.setValue("chinese", makeAnnotatedChineseString());
    return doc;
}

template <typename T>
T Test::getValueAs(const string &field_name, const Document &doc) {
    FieldValue::UP fv(doc.getValue(field_name));
    const T *value = dynamic_cast<const T *>(fv.get());
    ASSERT_TRUE(value);
    return *value;
}

template <typename T>
T
Test::cvtValueAs(const FieldValue::UP &fv)
{
    ASSERT_TRUE(fv.get() != NULL);
    const T *value = dynamic_cast<const T *>(fv.get());
    ASSERT_TRUE(value);
    return *value;
}

template <typename T>
T
Test::cvtAttributeAs(const FieldValue::UP &fv)
{
    ASSERT_TRUE(fv.get() != NULL);
    return cvtValueAs<T>(fv);
}

template <typename T>
T
Test::cvtSummaryAs(bool markup, const FieldValue::UP &fv)
{
    ASSERT_TRUE(fv.get() != NULL);
    FieldValue::UP r = SFC::convertSummaryField(markup, *fv, false);
    return cvtValueAs<T>(r);
}

void Test::checkString(const string &str, const FieldValue *value) {
    ASSERT_TRUE(value);
    const StringFieldValue *s = dynamic_cast<const StringFieldValue *>(value);
    ASSERT_TRUE(s);
    // fprintf(stderr, ">>>%s<<<   >>>%s<<<\n", str.c_str(), s->getValue().c_str());
    EXPECT_EQUAL(str, s->getValue());
}

void Test::checkData(const search::RawBuf &buf, const FieldValue *value) {
    ASSERT_TRUE(value);
    const RawFieldValue *s = dynamic_cast<const RawFieldValue *>(value);
    ASSERT_TRUE(s);
    auto got = s->getAsRaw();
    EXPECT_EQUAL(buf.GetUsedLen(), got.second);
    EXPECT_TRUE(memcmp(buf.GetDrainPos(), got.first, got.second) == 0);
}

template <unsigned int N>
void Test::checkArray(const char *(&str)[N], const FieldValue *value) {
    ASSERT_TRUE(value);
    const ArrayFieldValue *a = dynamic_cast<const ArrayFieldValue *>(value);
    ASSERT_TRUE(a);
    EXPECT_EQUAL(N, a->size());
    for (size_t i = 0; i < a->size() && i < N; ++i) {
        checkString(str[i], &(*a)[i]);
    }
}

void Test::setSummaryField(const string &field) {
    _schema->addSummaryField(Schema::Field(field, search::index::schema::STRING));
}

void Test::setAttributeField(const string &field) {
    _schema->addAttributeField(Schema::Field(field, search::index::schema::STRING));
}

void Test::requireThatSummaryIsAnUnmodifiedString() {
    setSummaryField("string");
    Document summary = makeDocument();
    checkString("Foo Bar Baz", SFC::convertSummaryField(false,
                                                        *summary.getValue("string"),
                                                        false).get());
}

void Test::requireThatAttributeIsAnUnmodifiedString() {
    setAttributeField("string");
    Document attribute = makeDocument();
    checkString("Foo Bar Baz",
                attribute.getValue("string").get());
}

void Test::requireThatArrayIsFlattenedInSummaryField() {
    setSummaryField("string_array");
    Document summary = makeDocument();
    FieldBlock expect("[\"\\\"foO\\\"\",\"ba\\\\R\"]");
    checkString(expect.json,
                SFC::convertSummaryField(false,
                                         *summary.getValue("string_array"),
                                         false).get());
    checkData(expect.binary,
              SFC::convertSummaryField(false,
                                       *summary.getValue("string_array"),
                                       true).get());
}

void Test::requireThatWeightedSetIsFlattenedInSummaryField() {
    setSummaryField("string_wset");
    Document summary = makeDocument();
    FieldBlock expect("[{\"item\":\"\\\"foo\\\"\",\"weight\":2},{\"item\":\"ba\\\\r\",\"weight\":4}]");
    checkString(expect.json,
                SFC::convertSummaryField(false,
                                         *summary.getValue("string_wset"),
                                         false).get());
    checkData(expect.binary,
              SFC::convertSummaryField(false,
                                       *summary.getValue("string_wset"),
                                       true).get());
}

void Test::requireThatPositionsAreTransformedInSummary() {
    setSummaryField("position1");
    setSummaryField("position2");
    Document summary = makeDocument();
    FieldValue::UP fv = summary.getValue("position1");
    EXPECT_EQUAL(5, cvtSummaryAs<IntFieldValue>(false, fv).getValue());
    FieldValue::UP fv2 = summary.getValue("position2");
    EXPECT_EQUAL(24, cvtSummaryAs<LongFieldValue>(false, fv2).getValue());
}

void Test::requireThatArrayIsPreservedInAttributeField() {
    setAttributeField("string_array");
    Document attribute = makeDocument();
    const char *array[] = { "\"foO\"", "ba\\R" };
    checkArray(array,
               attribute.getValue("string_array").get());
}

void Test::requireThatPositionsAreTransformedInAttributeField() {
    setAttributeField("position1");
    setAttributeField("position2");
    Document attr = makeDocument();
    FieldValue::UP fv = attr.getValue("position1");
    EXPECT_EQUAL(5, cvtAttributeAs<IntFieldValue>(fv).getValue());
    fv = attr.getValue("position2");
    EXPECT_EQUAL(24, cvtAttributeAs<LongFieldValue>(fv).getValue());
}

void Test::requireThatPositionArrayIsTransformedInAttributeField() {
    setAttributeField("position2_array");
    Document attr = makeDocument();
    FieldValue::UP fv = attr.getValue("position2_array");
    ArrayFieldValue a = cvtAttributeAs<ArrayFieldValue>(fv);
    EXPECT_EQUAL(2u, a.size());
    EXPECT_EQUAL(24, dynamic_cast<LongFieldValue &>(a[0]).getValue());
    EXPECT_EQUAL(48, dynamic_cast<LongFieldValue &>(a[1]).getValue());
}

void Test::requireThatPositionWeightedSetIsTransformedInAttributeField() {
    setAttributeField("position2_wset");
    Document attr = makeDocument();
    FieldValue::UP fv = attr.getValue("position2_wset");
    WeightedSetFieldValue w = cvtAttributeAs<WeightedSetFieldValue>(fv);
    EXPECT_EQUAL(2u, w.size());
    WeightedSetFieldValue::iterator it = w.begin();
    EXPECT_EQUAL(24, dynamic_cast<const LongFieldValue&>(*it->first).getValue());
    EXPECT_EQUAL(4, dynamic_cast<IntFieldValue &>(*it->second).getValue());
    ++it;
    EXPECT_EQUAL(48, dynamic_cast<const LongFieldValue&>(*it->first).getValue());
    EXPECT_EQUAL(2, dynamic_cast<IntFieldValue &>(*it->second).getValue());
}

void Test::requireThatAttributeCanBePrimitiveTypes() {
    setAttributeField("int");
    setAttributeField("long");
    setAttributeField("short");
    setAttributeField("byte");
    setAttributeField("double");
    setAttributeField("float");
    Document attribute = makeDocument();
    FieldValue::UP fv = attribute.getValue("int");
    EXPECT_EQUAL(42, cvtAttributeAs<IntFieldValue>(fv).getValue());
    fv = attribute.getValue("long");
    EXPECT_EQUAL(84, cvtAttributeAs<LongFieldValue>(fv).getValue());
    fv = attribute.getValue("short");
    EXPECT_EQUAL(21, cvtAttributeAs<ShortFieldValue>(fv).getValue());
    fv = attribute.getValue("byte");
    EXPECT_EQUAL(11, cvtAttributeAs<ByteFieldValue>(fv).getValue());
    fv = attribute.getValue("double");
    EXPECT_EQUAL(0.4, cvtAttributeAs<DoubleFieldValue>(fv).getValue());
    fv = attribute.getValue("float");
    EXPECT_EQUAL(0.2f, cvtAttributeAs<FloatFieldValue>(fv).getValue());
}

void Test::requireThatSummaryCanBePrimitiveTypes() {
    setSummaryField("int");
    setSummaryField("long");
    setSummaryField("short");
    setSummaryField("byte");
    setSummaryField("double");
    setSummaryField("float");
    Document summary = makeDocument();
    FieldValue::UP fv = summary.getValue("int");
    EXPECT_EQUAL(42, cvtSummaryAs<IntFieldValue>(false, fv).getValue());
    fv = summary.getValue("long");
    EXPECT_EQUAL(84, cvtSummaryAs<LongFieldValue>(false, fv).getValue());
    fv = summary.getValue("short");
    EXPECT_EQUAL(21, cvtSummaryAs<ShortFieldValue>(false, fv).getValue());
    fv = summary.getValue("byte");
    EXPECT_EQUAL(11, cvtSummaryAs<ShortFieldValue>(false, fv).getValue());
    fv = summary.getValue("double");
    EXPECT_EQUAL(0.4, cvtSummaryAs<DoubleFieldValue>(false, fv).getValue());
    fv = summary.getValue("float");
    EXPECT_EQUAL(0.2f, cvtSummaryAs<FloatFieldValue>(false, fv).getValue());
}

void Test::requireThatSummaryHandlesCjk() {
    Document summary = makeDocument();
    FieldValue::UP fv = summary.getValue("chinese");
    EXPECT_EQUAL("我就是那个\037大灰狼\037",
                 cvtSummaryAs<StringFieldValue>(true, fv).getValue());
}

void Test::requireThatSearchDataTypeUsesDefaultDataTypes() {
    const StructDataType *uri =
        dynamic_cast<const StructDataType *>(SearchDataType::URI);
    ASSERT_TRUE(uri);
    ASSERT_TRUE(uri->hasField("all"));
    ASSERT_TRUE(uri->hasField("scheme"));
    ASSERT_TRUE(uri->hasField("host"));
    ASSERT_TRUE(uri->hasField("port"));
    ASSERT_TRUE(uri->hasField("path"));
    ASSERT_TRUE(uri->hasField("query"));
    ASSERT_TRUE(uri->hasField("fragment"));
    EXPECT_EQUAL(*DataType::STRING, uri->getField("all").getDataType());
    EXPECT_EQUAL(*DataType::STRING, uri->getField("scheme").getDataType());
    EXPECT_EQUAL(*DataType::STRING, uri->getField("host").getDataType());
    EXPECT_EQUAL(*DataType::STRING, uri->getField("port").getDataType());
    EXPECT_EQUAL(*DataType::STRING, uri->getField("path").getDataType());
    EXPECT_EQUAL(*DataType::STRING, uri->getField("query").getDataType());
    EXPECT_EQUAL(*DataType::STRING, uri->getField("fragment").getDataType());
}

void Test::requireThatLinguisticsAnnotationUsesDefaultDataTypes() {
    EXPECT_EQUAL(*AnnotationType::TERM, *linguistics::TERM);
    ASSERT_TRUE(AnnotationType::TERM->getDataType());
    ASSERT_TRUE(linguistics::TERM->getDataType());
    EXPECT_EQUAL(*AnnotationType::TERM->getDataType(),
                 *linguistics::TERM->getDataType());
}

void
Test::requireThatPredicateIsPrinted()
{
    std::unique_ptr<Slime> input(new Slime());
    Cursor &obj = input->setObject();
    obj.setLong(Predicate::NODE_TYPE, Predicate::TYPE_FEATURE_SET);
    obj.setString(Predicate::KEY, "foo");
    Cursor &arr = obj.setArray(Predicate::SET);
    arr.addString("bar");

    Document doc(getDocType(), DocumentId("doc:scheme:"));
    doc.setRepo(*_documentRepo);
    doc.setValue("predicate", PredicateFieldValue(std::move(input)));

    checkString("'foo' in ['bar']\n",
                SFC::convertSummaryField(false, *doc.getValue("predicate"), false).get());
}


Tensor::UP
createTensor(const TensorCells &cells, const TensorDimensions &dimensions) {
    vespalib::tensor::DefaultTensor::builder builder;
    return vespalib::tensor::TensorFactory::create(cells, dimensions, builder);
}

void
Test::requireThatTensorIsPrinted()
{
    TensorFieldValue tensorFieldValue;
    tensorFieldValue = createTensor({ {{{"x", "4"}, {"y", "5"}}, 7} },
                                    {"x", "y"});
    Document doc(getDocType(), DocumentId("doc:scheme:"));
    doc.setRepo(*_documentRepo);
    doc.setValue("tensor", tensorFieldValue);

    FieldBlock expect1("{ dimensions: [ 'x', 'y' ], cells: ["
                       "{ address: { x:'4', y:'5' }, value: 7.0 }"
                       "] }");

    TEST_CALL(checkString(expect1.json,
                          SFC::convertSummaryField(false,
                                                   *doc.getValue("tensor"),
                                                   false).get()));
    TEST_CALL(checkData(expect1.binary,
                        SFC::convertSummaryField(false,
                                                 *doc.getValue("tensor"),
                                                 true).get()));
    doc.setValue("tensor", TensorFieldValue());

    FieldBlock expect2("{ }");

    TEST_CALL(checkString(expect2.json,
                          SFC::convertSummaryField(false,
                                                   *doc.getValue("tensor"),
                                                   false).get()));
    TEST_CALL(checkData(expect2.binary,
                        SFC::convertSummaryField(false,
                                                 *doc.getValue("tensor"),
                                                 true).get()));
}

}  // namespace

TEST_APPHOOK(Test);
