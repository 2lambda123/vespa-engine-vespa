// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP("attribute_reprocessing_initializer_test");

#include <vespa/searchcore/proton/attribute/attribute_populator.h>
#include <vespa/searchcore/proton/attribute/attributemanager.h>
#include <vespa/searchcore/proton/attribute/document_field_populator.h>
#include <vespa/searchcore/proton/reprocessing/attribute_reprocessing_initializer.h>
#include <vespa/searchcore/proton/reprocessing/i_reprocessing_handler.h>
#include <vespa/searchcore/proton/test/attribute_utils.h>
#include <vespa/searchcore/proton/test/directory_handler.h>
#include <vespa/searchlib/index/dummyfileheadercontext.h>
#include <vespa/vespalib/test/insertion_operators.h>
#include <vespa/vespalib/testkit/testapp.h>
#include <vespa/searchcore/proton/common/hw_info.h>
#include <vespa/searchlib/common/foregroundtaskexecutor.h>

using namespace proton;
using namespace search;
using namespace search::index;

const vespalib::string TEST_DIR = "test_output";
const SerialNum INIT_SERIAL_NUM = 10;
typedef std::vector<vespalib::string> StringVector;
typedef std::set<vespalib::string> StringSet;
typedef AttributeReprocessingInitializer::Config ARIConfig;

struct MyReprocessingHandler : public IReprocessingHandler
{
    IReprocessingReader::SP _reader;
    std::vector<IReprocessingRewriter::SP> _rewriters;
    MyReprocessingHandler() : _reader(), _rewriters() {}
    virtual void addReader(const IReprocessingReader::SP &reader) {
        _reader = reader;
    }
    virtual void addRewriter(const IReprocessingRewriter::SP &rewriter) {
        _rewriters.push_back(rewriter);
    }
};

struct MyDocTypeInspector : public IDocumentTypeInspector
{
    typedef std::shared_ptr<MyDocTypeInspector> SP;
    std::set<vespalib::string> _fields;
    MyDocTypeInspector() : _fields() {}
    virtual bool hasField(const vespalib::string &name) const {
        return _fields.count(name) > 0;
    }
};

struct MyConfig
{
    DummyFileHeaderContext _fileHeaderContext;
    ForegroundTaskExecutor _attributeFieldWriter;
    HwInfo _hwInfo;
    AttributeManager::SP _mgr;
    search::index::Schema _schema;
    MyDocTypeInspector::SP _inspector;
    MyConfig()
        : _fileHeaderContext(),
          _attributeFieldWriter(),
          _hwInfo(),
          _mgr(new AttributeManager(TEST_DIR, "test.subdb", TuneFileAttributes(),
                                    _fileHeaderContext,
                                    _attributeFieldWriter, _hwInfo)),
          _schema(),
          _inspector(new MyDocTypeInspector())
    {
    }
    void addFields(const StringVector &fields) {
        for (auto field : fields) {
            _inspector->_fields.insert(field);
        }
    }
    void addAttrs(const StringVector &attrs) {
        for (auto attr : attrs) {
            if (attr == "tensor") {
                _mgr->addAttribute(attr, test::AttributeUtils::getTensorConfig(), 1);
                _schema.addAttributeField(Schema::AttributeField(attr, schema::TENSOR));
            } else if (attr == "predicate") {
                _mgr->addAttribute(attr, test::AttributeUtils::getPredicateConfig(), 1);
                _schema.addAttributeField(Schema::AttributeField(attr, schema::BOOLEANTREE));
            } else {
                _mgr->addAttribute(attr, test::AttributeUtils::getStringConfig(), 1);
                _schema.addAttributeField(Schema::AttributeField(attr, schema::STRING));
            }
        }
    }
    void addIndexField(const vespalib::string &name) {
        _schema.addIndexField(Schema::IndexField(name, schema::STRING));
    }
};

struct Fixture
{
    test::DirectoryHandler _dirHandler;
    DummyFileHeaderContext _fileHeaderContext;
    ForegroundTaskExecutor _attributeFieldWriter;
    HwInfo _hwInfo;
    AttributeManager::SP _mgr;
    MyConfig _oldCfg;
    MyConfig _newCfg;
    AttributeReprocessingInitializer::UP _initializer;
    MyReprocessingHandler _handler;
    Fixture()
        : _dirHandler(TEST_DIR),
          _fileHeaderContext(),
          _attributeFieldWriter(),
          _hwInfo(),
          _mgr(new AttributeManager(TEST_DIR, "test.subdb", TuneFileAttributes(),
                                    _fileHeaderContext,
                                    _attributeFieldWriter, _hwInfo)),
          _initializer(),
          _handler()
    {
    }
    void init() {
        _initializer.reset(new AttributeReprocessingInitializer
                (ARIConfig(_newCfg._mgr, _newCfg._schema, _newCfg._inspector),
                        ARIConfig(_oldCfg._mgr, _oldCfg._schema, _oldCfg._inspector), "test"));
        _initializer->initialize(_handler);
    }
    Fixture &addOldConfig(const StringVector &fields,
                          const StringVector &attrs) {
        return addConfig(fields, attrs, _oldCfg);
    }
    Fixture &addNewConfig(const StringVector &fields,
                          const StringVector &attrs) {
        return addConfig(fields, attrs, _newCfg);
    }
    Fixture &addConfig(const StringVector &fields,
                       const StringVector &attrs,
                       MyConfig &cfg) {
        cfg.addFields(fields);
        cfg.addAttrs(attrs);
        return *this;
    }
    bool assertAttributes(const StringSet &expAttrs) {
        if (expAttrs.empty()) {
            if (!EXPECT_TRUE(_handler._reader.get() == nullptr)) return false;
        } else {
            const AttributePopulator &populator =
                    dynamic_cast<const AttributePopulator &>(*_handler._reader);
            std::vector<search::AttributeVector *> attrList =
                populator.getWriter().getWritableAttributes();
            std::set<vespalib::string> actAttrs;
            for (const auto attr : attrList) {
                actAttrs.insert(attr->getName());
            }
            if (!EXPECT_EQUAL(expAttrs, actAttrs)) return false;
        }
        return true;
    }
    bool assertFields(const StringSet &expFields) {
        if (expFields.empty()) {
            if (!EXPECT_EQUAL(0u, _handler._rewriters.size())) return false;
        } else {
            StringSet actFields;
            for (auto rewriter : _handler._rewriters) {
                const DocumentFieldPopulator &populator =
                    dynamic_cast<const DocumentFieldPopulator &>(*rewriter);
                actFields.insert(populator.getAttribute().getName());
            }
            if (!EXPECT_EQUAL(expFields, actFields)) return false;
        }
        return true;
    }
};

TEST_F("require that new field does NOT require attribute populate", Fixture)
{
    f.addOldConfig({}, {}).addNewConfig({"a"}, {"a"}).init();
    EXPECT_TRUE(f.assertAttributes({}));
}

TEST_F("require that added attribute aspect does require attribute populate", Fixture)
{
    f.addOldConfig({"a"}, {}).addNewConfig({"a"}, {"a"}).init();
    EXPECT_TRUE(f.assertAttributes({"a"}));
}

TEST_F("require that initializer can setup populate of several attributes", Fixture)
{
    f.addOldConfig({"a", "b", "c", "d"}, {"a", "b"}).
            addNewConfig({"a", "b", "c", "d"}, {"a", "b", "c", "d"}).init();
    EXPECT_TRUE(f.assertAttributes({"c", "d"}));
}

TEST_F("require that new field does NOT require document field populate", Fixture)
{
    f.addOldConfig({}, {}).addNewConfig({"a"}, {"a"}).init();
    EXPECT_TRUE(f.assertFields({}));
}

TEST_F("require that removed field does NOT require document field populate", Fixture)
{
    f.addOldConfig({"a"}, {"a"}).addNewConfig({}, {}).init();
    EXPECT_TRUE(f.assertFields({}));
}

TEST_F("require that removed attribute aspect does require document field populate", Fixture)
{
    f.addOldConfig({"a"}, {"a"}).addNewConfig({"a"}, {}).init();
    EXPECT_TRUE(f.assertFields({"a"}));
}

TEST_F("require that removed attribute aspect (when also index field) does NOT require document field populate",
        Fixture)
{
    f.addOldConfig({"a"}, {"a"}).addNewConfig({"a"}, {});
    f._oldCfg.addIndexField("a");
    f._newCfg.addIndexField("a");
    f.init();
    EXPECT_TRUE(f.assertFields({}));
}

TEST_F("require that initializer can setup populate of several document fields", Fixture)
{
    f.addOldConfig({"a", "b", "c", "d"}, {"a", "b", "c", "d"}).
            addNewConfig({"a", "b", "c", "d"}, {"a", "b"}).init();
    EXPECT_TRUE(f.assertFields({"c", "d"}));
}

TEST_F("require that initializer can setup both attribute and document field populate", Fixture)
{
    f.addOldConfig({"a", "b"}, {"a"}).
            addNewConfig({"a", "b"}, {"b"}).init();
    EXPECT_TRUE(f.assertAttributes({"b"}));
    EXPECT_TRUE(f.assertFields({"a"}));
}

TEST_F("require that tensor fields are not populated from attribute", Fixture)
{
    f.addOldConfig({"a", "b", "c", "d", "tensor"},
                   {"a", "b", "c", "d", "tensor"}).
        addNewConfig({"a", "b", "c", "d", "tensor"}, {"a", "b"}).init();
    EXPECT_TRUE(f.assertFields({"c", "d"}));
}

TEST_F("require that predicate fields are not populated from attribute", Fixture)
{
    f.addOldConfig({"a", "b", "c", "d", "predicate"},
                   {"a", "b", "c", "d", "predicate"}).
        addNewConfig({"a", "b", "c", "d", "predicate"}, {"a", "b"}).init();
    EXPECT_TRUE(f.assertFields({"c", "d"}));
}

TEST_MAIN()
{
    TEST_RUN_ALL();
}
