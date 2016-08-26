// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP("docsummary_test");
#include <vespa/document/fieldvalue/document.h>
#include <vespa/searchcore/proton/attribute/attribute_writer.h>
#include <vespa/searchcore/proton/common/bucketfactory.h>
#include <vespa/searchcore/proton/docsummary/docsumcontext.h>
#include <vespa/searchcore/proton/docsummary/documentstoreadapter.h>
#include <vespa/searchcore/proton/docsummary/summarymanager.h>
#include <vespa/searchcore/proton/server/documentdb.h>
#include <vespa/searchcore/proton/server/memoryconfigstore.h>
#include <vespa/searchcore/proton/metrics/metricswireservice.h>
#include <vespa/searchcore/proton/server/summaryadapter.h>
#include <vespa/searchlib/common/idestructorcallback.h>
#include <vespa/searchlib/docstore/logdocumentstore.h>
#include <vespa/searchlib/engine/docsumapi.h>
#include <vespa/searchlib/index/docbuilder.h>
#include <vespa/searchlib/index/dummyfileheadercontext.h>
#include <vespa/searchlib/transactionlog/translogserver.h>
#include <tests/proton/common/dummydbowner.h>
#include <vespa/vespalib/testkit/testapp.h>
#include <vespa/searchlib/transactionlog/nosyncproxy.h>
#include <vespa/vespalib/tensor/tensor_factory.h>
#include <vespa/vespalib/tensor/default_tensor.h>
#include <vespa/searchlib/attribute/tensorattribute.h>

using namespace document;
using namespace search;
using namespace search::docsummary;
using namespace search::engine;
using namespace search::index;
using namespace search::transactionlog;
using namespace cloud::config::filedistribution;
using search::TuneFileDocumentDB;
using document::DocumenttypesConfig;
using storage::spi::Timestamp;
using search::index::DummyFileHeaderContext;
using vespa::config::search::core::ProtonConfig;
using vespalib::tensor::Tensor;
using vespalib::tensor::TensorCells;
using vespalib::tensor::TensorDimensions;
using vespalib::tensor::TensorFactory;

typedef std::unique_ptr<GeneralResult> GeneralResultPtr;

namespace proton {

class DirMaker
{
public:
    DirMaker(const vespalib::string & dir) :
        _dir(dir)
    {
        FastOS_File::MakeDirectory(dir.c_str());
    }
    ~DirMaker()
    {
        FastOS_File::EmptyAndRemoveDirectory(_dir.c_str());
    }
private:
    vespalib::string _dir;
};

class BuildContext
{
public:
    DirMaker _dmk;
    DocBuilder _bld;
    DocumentTypeRepo::SP _repo;
    DummyFileHeaderContext _fileHeaderContext;
    vespalib::ThreadStackExecutor _summaryExecutor;
    search::transactionlog::NoSyncProxy _noTlSyncer;
    search::LogDocumentStore _str;
    uint64_t _serialNum;

    BuildContext(const Schema &schema)
        : _dmk("summary"),
          _bld(schema),
          _repo(new DocumentTypeRepo(_bld.getDocumentType())),
          _summaryExecutor(4, 128 * 1024),
          _noTlSyncer(),
          _str(_summaryExecutor, "summary",
               LogDocumentStore::Config(
                       DocumentStore::Config(),
                       LogDataStore::Config()),
               GrowStrategy(),
               TuneFileSummary(),
               _fileHeaderContext,
               _noTlSyncer,
               NULL),
          _serialNum(1)
    {
    }

    ~BuildContext(void)
    {
    }

    void
    endDocument(uint32_t docId)
    {
        Document::UP doc = _bld.endDocument();
        _str.write(_serialNum++, *doc, docId);
    }

    FieldCacheRepo::UP createFieldCacheRepo(const ResultConfig &resConfig) const {
        return FieldCacheRepo::UP(new FieldCacheRepo(resConfig, _bld.getDocumentType()));
    }
};


namespace {

const char *
getDocTypeName(void)
{
    return "searchdocument";
}

Tensor::UP createTensor(const TensorCells &cells,
                        const TensorDimensions &dimensions) {
    vespalib::tensor::DefaultTensor::builder builder;
    return TensorFactory::create(cells, dimensions, builder);
}

}  // namespace


class DBContext : public DummyDBOwner
{
public:
    DirMaker _dmk;
    DummyFileHeaderContext _fileHeaderContext;
    TransLogServer _tls;
    vespalib::ThreadStackExecutor _summaryExecutor;
    bool _mkdirOk;
    matching::QueryLimiter _queryLimiter;
    vespalib::Clock _clock;
    DummyWireService _dummy;
    config::DirSpec _spec;
    DocumentDBConfigHelper _configMgr;
    DocumentDBConfig::DocumenttypesConfigSP _documenttypesConfig;
    const DocumentTypeRepo::SP _repo;
    TuneFileDocumentDB::SP _tuneFileDocumentDB;
    std::unique_ptr<DocumentDB> _ddb;
    AttributeWriter::UP _aw;
    ISummaryAdapter::SP _sa;

    DBContext(const DocumentTypeRepo::SP &repo, const char *docTypeName)
        : _dmk(docTypeName),
          _fileHeaderContext(),
          _tls("tmp", 9013, ".", _fileHeaderContext),
          _summaryExecutor(8, 128*1024),
          _mkdirOk(FastOS_File::MakeDirectory("tmpdb")),
          _queryLimiter(),
          _clock(),
          _dummy(),
          _spec(vespalib::TestApp::GetSourceDirectory()),
          _configMgr(_spec, getDocTypeName()),
          _documenttypesConfig(new DocumenttypesConfig()),
          _repo(repo),
          _tuneFileDocumentDB(new TuneFileDocumentDB()),
          _ddb(),
          _aw(),
          _sa()
    {
        assert(_mkdirOk);
        BootstrapConfig::SP b(new BootstrapConfig(1,
                                                  _documenttypesConfig,
                                                  _repo,
                                                  BootstrapConfig::ProtonConfigSP(new ProtonConfig()),
                                                  BootstrapConfig::FiledistributorrpcConfigSP(new FiledistributorrpcConfig()),
                                                  _tuneFileDocumentDB));
        _configMgr.forwardConfig(b);
        _configMgr.nextGeneration(0);
        if (! FastOS_File::MakeDirectory((std::string("tmpdb/") + docTypeName).c_str())) { abort(); }
        _ddb.reset(new DocumentDB("tmpdb",
                                  _configMgr.getConfig(),
                                  "tcp/localhost:9013",
                                  _queryLimiter,
                                  _clock,
                                  DocTypeName(docTypeName),
                                  ProtonConfig(),
                                  *this,
                                  _summaryExecutor,
                                  _summaryExecutor,
                                  NULL,
                                  _dummy,
                                  _fileHeaderContext,
                                  ConfigStore::UP(new MemoryConfigStore),
                                  std::make_shared<vespalib::
                                                   ThreadStackExecutor>
                                  (16, 128 * 1024))),
        _ddb->start();
        _ddb->waitForOnlineState();
        _aw = AttributeWriter::UP(new AttributeWriter(_ddb->
                                            getReadySubDB()->
                                            getAttributeManager()));
        _sa = _ddb->getReadySubDB()->getSummaryAdapter();
    }
    ~DBContext()
    {
        _sa.reset();
        _aw.reset();
        _ddb.reset();
        FastOS_File::EmptyAndRemoveDirectory("tmp");
        FastOS_File::EmptyAndRemoveDirectory("tmpdb");
    }

    void
    put(const document::Document &doc, const search::DocumentIdT lid)
    {
        const document::DocumentId &docId = doc.getId();
        typedef DocumentMetaStore::Result PutRes;
        IDocumentMetaStore &dms = _ddb->getReadySubDB()->getDocumentMetaStoreContext().get();
        PutRes putRes(dms.put(docId.getGlobalId(),
                              BucketFactory::getBucketId(docId),
                              Timestamp(0u),
                              lid));
        LOG_ASSERT(putRes.ok());
        uint64_t serialNum = _ddb->getFeedHandler().incSerialNum();
        _aw->put(serialNum, doc, lid, true, std::shared_ptr<IDestructorCallback>());
        _ddb->getReadySubDB()->
            getAttributeManager()->getAttributeFieldWriter().sync();
        _sa->put(serialNum, doc, lid);
        const GlobalId &gid = docId.getGlobalId();
        BucketId bucketId(gid.convertToBucketId());
        bucketId.setUsedBits(8);
        storage::spi::Timestamp ts(0);
        DbDocumentId dbdId(lid);
        DbDocumentId prevDbdId(0);
        document::Document::SP xdoc(new document::Document(doc));
        PutOperation op(bucketId,
                        ts,
                        xdoc,
                        serialNum,
                        dbdId,
                        prevDbdId);
        _ddb->getFeedHandler().storeOperation(op);
        SearchView *sv(dynamic_cast<SearchView *>
                       (_ddb->getReadySubDB()->getSearchView().get()));
        if (sv != NULL) {
            // cf. FeedView::putAttributes()
            DocIdLimit &docIdLimit = sv->getDocIdLimit();
            if (docIdLimit.get() <= lid)
                docIdLimit.set(lid + 1);
        }
    }
};

class Test : public vespalib::TestApp
{
private:
    std::unique_ptr<vespa::config::search::SummaryConfig> _summaryCfg;
    ResultConfig          _resultCfg;
    std::set<vespalib::string> _markupFields;

    const vespa::config::search::SummaryConfig &
    getSummaryConfig() const
    {
        return *_summaryCfg;
    }

    const ResultConfig &getResultConfig() const
    {
        return _resultCfg;
    }

    const std::set<vespalib::string> &
    getMarkupFields(void) const
    {
        return _markupFields;
    }

    GeneralResultPtr
    getResult(DocumentStoreAdapter & dsa, uint32_t docId);

    GeneralResultPtr
    getResult(const DocsumReply & reply, uint32_t id, uint32_t resultClassID);

    bool
    assertString(const std::string & exp,
                 const std::string & fieldName,
                 DocumentStoreAdapter &dsa,
                 uint32_t id);

    bool
    assertString(const std::string &exp,
                 const std::string &fieldName,
                 const DocsumReply &reply,
                 uint32_t id,
                 uint32_t resultClassID);

    bool
    assertSlime(const std::string &exp,
                const DocsumReply &reply,
                uint32_t id);

    void
    requireThatAdapterHandlesAllFieldTypes();

    void
    requireThatAdapterHandlesMultipleDocuments();

    void
    requireThatAdapterHandlesDocumentIdField();

    void
    requireThatDocsumRequestIsProcessed();

    void
    requireThatRewritersAreUsed();

    void
    requireThatAttributesAreUsed();

    void
    requireThatSummaryAdapterHandlesPutAndRemove();

    void
    requireThatAnnotationsAreUsed();

    void
    requireThatUrisAreUsed();

    void
    requireThatPositionsAreUsed();

    void
    requireThatRawFieldsWorks();

    void
    requireThatFieldCacheRepoCanReturnDefaultFieldCache();

public:
    Test();
    int Main();
};


GeneralResultPtr
Test::getResult(DocumentStoreAdapter & dsa, uint32_t docId)
{
    DocsumStoreValue docsum = dsa.getMappedDocsum(docId, false);
    ASSERT_TRUE(docsum.pt() != NULL);
    GeneralResultPtr retval(new GeneralResult(dsa.getResultClass(),
                                              0, 0, 0));
    // skip the 4 byte class id
    ASSERT_TRUE(retval->unpack(docsum.pt() + 4,
                               docsum.len() - 4) == 0);
    return retval;
}


GeneralResultPtr
Test::getResult(const DocsumReply & reply, uint32_t id, uint32_t resultClassID)
{
    GeneralResultPtr retval(new GeneralResult(getResultConfig().
                                              LookupResultClass(resultClassID),
                                              0, 0, 0));
    const DocsumReply::Docsum & docsum = reply.docsums[id];
    // skip the 4 byte class id
    ASSERT_EQUAL(0, retval->unpack(docsum.data.c_str() + 4, docsum.data.size() - 4));
    return retval;
}


bool
Test::assertString(const std::string & exp, const std::string & fieldName,
                   DocumentStoreAdapter &dsa,
                   uint32_t id)
{
    GeneralResultPtr res = getResult(dsa, id);
    return EXPECT_EQUAL(exp, std::string(res->GetEntry(fieldName.c_str())->
                                       _stringval,
                                       res->GetEntry(fieldName.c_str())->
                                       _stringlen));
}


bool
Test::assertString(const std::string & exp, const std::string & fieldName,
                   const DocsumReply & reply,
                   uint32_t id, uint32_t resultClassID)
{
    GeneralResultPtr res = getResult(reply, id, resultClassID);
    return EXPECT_EQUAL(exp, std::string(res->GetEntry(fieldName.c_str())->
                                       _stringval,
                                       res->GetEntry(fieldName.c_str())->
                                       _stringlen));
}


bool
Test::assertSlime(const std::string &exp, const DocsumReply &reply, uint32_t id)
{
    const DocsumReply::Docsum & docsum = reply.docsums[id];
    uint32_t classId;
    ASSERT_LESS_EQUAL(sizeof(classId), docsum.data.size());
    memcpy(&classId, docsum.data.c_str(), sizeof(classId));
    ASSERT_EQUAL(::search::fs4transport::SLIME_MAGIC_ID, classId);
    vespalib::Slime slime;
    vespalib::slime::Memory serialized(docsum.data.c_str() + sizeof(classId),
                                       docsum.data.size() - sizeof(classId));
    size_t decodeRes = vespalib::slime::BinaryFormat::decode(serialized,
                                                             slime);
    ASSERT_EQUAL(decodeRes, serialized.size);
    vespalib::Slime expSlime;
    size_t used = vespalib::slime::JsonFormat::decode(exp, expSlime);
    EXPECT_EQUAL(exp.size(), used);
    return EXPECT_EQUAL(expSlime, slime);
}

void
Test::requireThatAdapterHandlesAllFieldTypes()
{
    Schema s;
    s.addSummaryField(Schema::SummaryField("a", Schema::INT8));
    s.addSummaryField(Schema::SummaryField("b", Schema::INT16));
    s.addSummaryField(Schema::SummaryField("c", Schema::INT32));
    s.addSummaryField(Schema::SummaryField("d", Schema::INT64));
    s.addSummaryField(Schema::SummaryField("e", Schema::FLOAT));
    s.addSummaryField(Schema::SummaryField("f", Schema::DOUBLE));
    s.addSummaryField(Schema::SummaryField("g", Schema::STRING));
    s.addSummaryField(Schema::SummaryField("h", Schema::STRING));
    s.addSummaryField(Schema::SummaryField("i", Schema::RAW));
    s.addSummaryField(Schema::SummaryField("j", Schema::RAW));
    s.addSummaryField(Schema::SummaryField("k", Schema::STRING));
    s.addSummaryField(Schema::SummaryField("l", Schema::STRING));

    BuildContext bc(s);
    bc._bld.startDocument("doc::0");
    bc._bld.startSummaryField("a").addInt(255).endField();
    bc._bld.startSummaryField("b").addInt(32767).endField();
    bc._bld.startSummaryField("c").addInt(2147483647).endField();
    bc._bld.startSummaryField("d").addInt(2147483648).endField();
    bc._bld.startSummaryField("e").addFloat(1234.56).endField();
    bc._bld.startSummaryField("f").addFloat(9876.54).endField();
    bc._bld.startSummaryField("g").addStr("foo").endField();
    bc._bld.startSummaryField("h").addStr("bar").endField();
    bc._bld.startSummaryField("i").addStr("baz").endField();
    bc._bld.startSummaryField("j").addStr("qux").endField();
    bc._bld.startSummaryField("k").addStr("<foo>").endField();
    bc._bld.startSummaryField("l").addStr("{foo:10}").endField();
    bc.endDocument(0);

    DocumentStoreAdapter dsa(bc._str,
                             *bc._repo,
                             getResultConfig(), "class0",
                             bc.createFieldCacheRepo(getResultConfig())->getFieldCache("class0"),
                             getMarkupFields());
    GeneralResultPtr res = getResult(dsa, 0);
    EXPECT_EQUAL(255u,        res->GetEntry("a")->_intval);
    EXPECT_EQUAL(32767u,      res->GetEntry("b")->_intval);
    EXPECT_EQUAL(2147483647u, res->GetEntry("c")->_intval);
    EXPECT_EQUAL(2147483648u, res->GetEntry("d")->_int64val);
    EXPECT_APPROX(1234.56,    res->GetEntry("e")->_doubleval, 10e-5);
    EXPECT_APPROX(9876.54,    res->GetEntry("f")->_doubleval, 10e-5);
    EXPECT_EQUAL("foo",       std::string(res->GetEntry("g")->_stringval,
                                        res->GetEntry("g")->_stringlen));
    EXPECT_EQUAL("bar",       std::string(res->GetEntry("h")->_stringval,
                                        res->GetEntry("h")->_stringlen));
    EXPECT_EQUAL("baz",       std::string(res->GetEntry("i")->_dataval,
                                        res->GetEntry("i")->_datalen));
    EXPECT_EQUAL("qux",       std::string(res->GetEntry("j")->_dataval,
                                        res->GetEntry("j")->_datalen));
    EXPECT_EQUAL("<foo>",     std::string(res->GetEntry("k")->_stringval,
                                        res->GetEntry("k")->_stringlen));
    EXPECT_EQUAL("{foo:10}",  std::string(res->GetEntry("l")->_stringval,
                                        res->GetEntry("l")->_stringlen));
}


void
Test::requireThatAdapterHandlesMultipleDocuments()
{
    Schema s;
    s.addSummaryField(Schema::SummaryField("a", Schema::INT32));

    BuildContext bc(s);
    bc._bld.startDocument("doc::0").
        startSummaryField("a").
        addInt(1000).
        endField();
    bc.endDocument(0);
    bc._bld.startDocument("doc::1").
        startSummaryField("a").
        addInt(2000).endField();
    bc.endDocument(1);

    DocumentStoreAdapter dsa(bc._str, *bc._repo, getResultConfig(), "class1",
                             bc.createFieldCacheRepo(getResultConfig())->getFieldCache("class1"),
                             getMarkupFields());
    { // doc 0
        GeneralResultPtr res = getResult(dsa, 0);
        EXPECT_EQUAL(1000u, res->GetEntry("a")->_intval);
    }
    { // doc 1
        GeneralResultPtr res = getResult(dsa, 1);
        EXPECT_EQUAL(2000u, res->GetEntry("a")->_intval);
    }
    { // doc 2
        DocsumStoreValue docsum = dsa.getMappedDocsum(2, false);
        EXPECT_TRUE(docsum.pt() == NULL);
    }
    { // doc 0 (again)
        GeneralResultPtr res = getResult(dsa, 0);
        EXPECT_EQUAL(1000u, res->GetEntry("a")->_intval);
    }
    EXPECT_EQUAL(0u, bc._str.lastSyncToken());
    uint64_t flushToken = bc._str.initFlush(bc._serialNum - 1);
    bc._str.flush(flushToken);
}


void
Test::requireThatAdapterHandlesDocumentIdField()
{
    Schema s;
    s.addSummaryField(Schema::SummaryField("documentid",
                              Schema::STRING));
    BuildContext bc(s);
    bc._bld.startDocument("doc::0").
        startSummaryField("documentid").
        addStr("foo").
        endField();
    bc.endDocument(0);
    DocumentStoreAdapter dsa(bc._str, *bc._repo, getResultConfig(), "class4",
                             bc.createFieldCacheRepo(getResultConfig())->getFieldCache("class4"),
                             getMarkupFields());
    GeneralResultPtr res = getResult(dsa, 0);
    EXPECT_EQUAL("doc::0", std::string(res->GetEntry("documentid")->_stringval,
                                     res->GetEntry("documentid")->_stringlen));
}


GlobalId gid1 = DocumentId("doc::1").getGlobalId(); // lid 1
GlobalId gid2 = DocumentId("doc::2").getGlobalId(); // lid 2
GlobalId gid3 = DocumentId("doc::3").getGlobalId(); // lid 3
GlobalId gid4 = DocumentId("doc::4").getGlobalId(); // lid 4
GlobalId gid9 = DocumentId("doc::9").getGlobalId(); // not existing


void
Test::requireThatDocsumRequestIsProcessed()
{
    Schema s;
    s.addSummaryField(Schema::SummaryField("a", Schema::INT32));

    BuildContext bc(s);
    DBContext dc(bc._repo, getDocTypeName());
    dc.put(*bc._bld.startDocument("doc::1").
           startSummaryField("a").
           addInt(10).
           endField().
           endDocument(),
           1);
    dc.put(*bc._bld.startDocument("doc::2").
           startSummaryField("a").
           addInt(20).
           endField().
           endDocument(),
           2);
    dc.put(*bc._bld.startDocument("doc::3").
           startSummaryField("a").
           addInt(30).
           endField().
           endDocument(),
           3);
    dc.put(*bc._bld.startDocument("doc::4").
           startSummaryField("a").
           addInt(40).
           endField().
           endDocument(),
           4);
    dc.put(*bc._bld.startDocument("doc::5").
           startSummaryField("a").
           addInt(50).
           endField().
           endDocument(),
           5);

    DocsumRequest req;
    req.resultClassName = "class1";
    req.hits.push_back(DocsumRequest::Hit(gid2));
    req.hits.push_back(DocsumRequest::Hit(gid4));
    req.hits.push_back(DocsumRequest::Hit(gid9));
    DocsumReply::UP rep = dc._ddb->getDocsums(req);
    EXPECT_EQUAL(3u, rep->docsums.size());
    EXPECT_EQUAL(2u, rep->docsums[0].docid);
    EXPECT_EQUAL(gid2, rep->docsums[0].gid);
    EXPECT_EQUAL(20u, getResult(*rep, 0, 1)->GetEntry("a")->_intval);
    EXPECT_EQUAL(4u, rep->docsums[1].docid);
    EXPECT_EQUAL(gid4, rep->docsums[1].gid);
    EXPECT_EQUAL(40u, getResult(*rep, 1, 1)->GetEntry("a")->_intval);
    EXPECT_EQUAL(search::endDocId, rep->docsums[2].docid);
    EXPECT_EQUAL(gid9, rep->docsums[2].gid);
    EXPECT_TRUE(rep->docsums[2].data.get() == NULL);
}


void
Test::requireThatRewritersAreUsed()
{
    Schema s;
    s.addSummaryField(Schema::SummaryField("aa", Schema::INT32));
    s.addSummaryField(Schema::SummaryField("ab", Schema::INT32));

    BuildContext bc(s);
    DBContext dc(bc._repo, getDocTypeName());
    dc.put(*bc._bld.startDocument("doc::1").
           startSummaryField("aa").
           addInt(10).
           endField().
           startSummaryField("ab").
           addInt(20).
           endField().
           endDocument(),
           1);

    DocsumRequest req;
    req.resultClassName = "class2";
    req.hits.push_back(DocsumRequest::Hit(gid1));
    DocsumReply::UP rep = dc._ddb->getDocsums(req);
    EXPECT_EQUAL(1u, rep->docsums.size());
    EXPECT_EQUAL(20u, getResult(*rep, 0, 2)->GetEntry("aa")->_intval);
    EXPECT_EQUAL(0u,  getResult(*rep, 0, 2)->GetEntry("ab")->_intval);
}


void
addField(Schema & s,
         const std::string &name,
         Schema::DataType dtype,
         Schema::CollectionType ctype)
{
    s.addSummaryField(Schema::SummaryField(name, dtype, ctype));
    s.addAttributeField(Schema::AttributeField(name, dtype, ctype));
}


void
Test::requireThatAttributesAreUsed()
{
    Schema s;
    addField(s, "ba",
             Schema::INT32, Schema::SINGLE);
    addField(s, "bb",
             Schema::FLOAT, Schema::SINGLE);
    addField(s, "bc",
             Schema::STRING, Schema::SINGLE);
    addField(s, "bd",
             Schema::INT32, Schema::ARRAY);
    addField(s, "be",
             Schema::FLOAT, Schema::ARRAY);
    addField(s, "bf",
             Schema::STRING, Schema::ARRAY);
    addField(s, "bg",
             Schema::INT32, Schema::WEIGHTEDSET);
    addField(s, "bh",
             Schema::FLOAT, Schema::WEIGHTEDSET);
    addField(s, "bi",
             Schema::STRING, Schema::WEIGHTEDSET);
    addField(s, "bj", Schema::TENSOR, Schema::SINGLE);

    BuildContext bc(s);
    DBContext dc(bc._repo, getDocTypeName());
    dc.put(*bc._bld.startDocument("doc::1").
           endDocument(),
           1); // empty doc
    dc.put(*bc._bld.startDocument("doc::2").
           startAttributeField("ba").
           addInt(10).
           endField().
           startAttributeField("bb").
           addFloat(10.1).
           endField().
           startAttributeField("bc").
           addStr("foo").
           endField().
           startAttributeField("bd").
           startElement().
           addInt(20).
           endElement().
           startElement().
           addInt(30).
           endElement().
           endField().
           startAttributeField("be").
           startElement().
           addFloat(20.2).
           endElement().
           startElement().
           addFloat(30.3).
           endElement().
           endField().
           startAttributeField("bf").
           startElement().
           addStr("bar").
           endElement().
           startElement().
           addStr("baz").
           endElement().
           endField().
           startAttributeField("bg").
           startElement(2).
           addInt(40).
           endElement().
           startElement(3).
           addInt(50).
           endElement().
           endField().
           startAttributeField("bh").
           startElement(4).
           addFloat(40.4).
           endElement().
           startElement(5).
           addFloat(50.5).
           endElement().
           endField().
           startAttributeField("bi").
           startElement(7).
           addStr("quux").
           endElement().
           startElement(6).
           addStr("qux").
           endElement().
           endField().
           startAttributeField("bj").
           addTensor(createTensor({ {{}, 3} }, { "x", "y"})).
           endField().
           endDocument(),
           2);
    dc.put(*bc._bld.startDocument("doc::3").
           endDocument(),
           3); // empty doc

    DocsumRequest req;
    req.resultClassName = "class3";
    req.hits.push_back(DocsumRequest::Hit(gid2));
    req.hits.push_back(DocsumRequest::Hit(gid3));
    DocsumReply::UP rep = dc._ddb->getDocsums(req);
    uint32_t rclass = 3;

    EXPECT_EQUAL(2u, rep->docsums.size());
    EXPECT_EQUAL(10u, getResult(*rep, 0, rclass)->GetEntry("ba")->_intval);
    EXPECT_APPROX(10.1, getResult(*rep, 0, rclass)->GetEntry("bb")->_doubleval,
                10e-5);
    EXPECT_TRUE(assertString("foo", "bc", *rep, 0, rclass));
    EXPECT_TRUE(assertString("[\"20\",\"30\"]",     "bd", *rep, 0, rclass));
    EXPECT_TRUE(assertString("[\"20.2\",\"30.3\"]", "be", *rep, 0, rclass));
    EXPECT_TRUE(assertString("[\"bar\",\"baz\"]",   "bf", *rep, 0, rclass));
    EXPECT_TRUE(assertString("[[\"40\",2],[\"50\",3]]",     "bg",
                            *rep, 0, rclass));
    EXPECT_TRUE(assertString("[[\"40.4\",4],[\"50.5\",5]]", "bh",
                            *rep, 0, rclass));
    EXPECT_TRUE(assertString("[[\"quux\",7],[\"qux\",6]]",  "bi",
                            *rep, 0, rclass));
    EXPECT_TRUE(assertString("{\"dimensions\":[\"x\",\"y\"],"
                             "\"cells\":[{\"address\":{},\"value\":3}]}",
                             "bj", *rep, 0, rclass));

    // empty doc
    EXPECT_TRUE(search::attribute::isUndefined<int32_t>
               (getResult(*rep, 1, rclass)->GetEntry("ba")->_intval));
    EXPECT_TRUE(search::attribute::isUndefined<float>
               (getResult(*rep, 1, rclass)->GetEntry("bb")->_doubleval));
    EXPECT_TRUE(assertString("", "bc", *rep, 1, rclass));
    EXPECT_TRUE(assertString("[]", "bd", *rep, 1, rclass));
    EXPECT_TRUE(assertString("[]", "be", *rep, 1, rclass));
    EXPECT_TRUE(assertString("[]", "bf", *rep, 1, rclass));
    EXPECT_TRUE(assertString("[]", "bg", *rep, 1, rclass));
    EXPECT_TRUE(assertString("[]", "bh", *rep, 1, rclass));
    EXPECT_TRUE(assertString("[]", "bi", *rep, 1, rclass));
    EXPECT_TRUE(assertString("", "bj", *rep, 1, rclass));

    proton::IAttributeManager::SP attributeManager =
        dc._ddb->getReadySubDB()->getAttributeManager();
    search::ISequencedTaskExecutor &attributeFieldWriter =
        attributeManager->getAttributeFieldWriter();
    search::AttributeVector *bjAttr =
        attributeManager->getWritableAttribute("bj");
    search::attribute::TensorAttribute *bjTensorAttr =
        dynamic_cast<search::attribute::TensorAttribute *>(bjAttr);

    attributeFieldWriter.
        execute("bj",
                [&]() { bjTensorAttr->setTensor(3,
                            *createTensor({ {{}, 4} }, { "x"}));
                           bjTensorAttr->commit(); });
    attributeFieldWriter.sync();

    DocsumReply::UP rep2 = dc._ddb->getDocsums(req);
    EXPECT_TRUE(assertString("{\"dimensions\":[\"x\",\"y\"],"
                             "\"cells\":[{\"address\":{},\"value\":4}]}",
                             "bj", *rep2, 1, rclass));

    DocsumRequest req3;
    req3.resultClassName = "class3";
    req3._flags = ::search::fs4transport::GDFLAG_ALLOW_SLIME;
    req3.hits.push_back(DocsumRequest::Hit(gid3));
    DocsumReply::UP rep3 = dc._ddb->getDocsums(req3);

    EXPECT_TRUE(assertSlime("{bd:[],be:[],bf:[],bg:[],"
                            "bh:[],bi:[],"
                            "bj:{dimensions:['x','y'],"
                            "cells:[{address:{},value:4.0}]}}",
                            *rep3, 0));
}


void
Test::requireThatSummaryAdapterHandlesPutAndRemove()
{
    Schema s;
    s.addSummaryField(Schema::SummaryField("f1",
                              Schema::STRING,
                              Schema::SINGLE));
    BuildContext bc(s);
    DBContext dc(bc._repo, getDocTypeName());
    Document::UP exp = bc._bld.startDocument("doc::1").
                       startSummaryField("f1").
                       addStr("foo").
                       endField().
                       endDocument();
    dc._sa->put(1, *exp, 1);
    IDocumentStore & store =
        dc._ddb->getReadySubDB()->getSummaryManager()->getBackingStore();
    Document::UP act = store.read(1, *bc._repo);
    EXPECT_TRUE(act.get() != NULL);
    EXPECT_EQUAL(exp->getType(), act->getType());
    EXPECT_EQUAL("foo", act->getValue("f1")->toString());
    dc._sa->remove(2, 1);
    EXPECT_TRUE(store.read(1, *bc._repo).get() == NULL);
}


const std::string TERM_ORIG = "\357\277\271";
const std::string TERM_INDEX = "\357\277\272";
const std::string TERM_END = "\357\277\273";
const std::string TERM_SEP = "\037";
const std::string TERM_EMPTY = "";
namespace
{
  const std::string empty;
}

void
Test::requireThatAnnotationsAreUsed()
{
    Schema s;
    s.addIndexField(Schema::IndexField("g",
                                       Schema::STRING,
                                       Schema::SINGLE));
    s.addSummaryField(Schema::SummaryField("g",
                              Schema::STRING,
                              Schema::SINGLE));
    s.addIndexField(Schema::IndexField("dynamicstring",
                                       Schema::STRING,
                                       Schema::SINGLE));
    s.addSummaryField(Schema::SummaryField("dynamicstring",
                              Schema::STRING,
                              Schema::SINGLE));
    BuildContext bc(s);
    DBContext dc(bc._repo, getDocTypeName());
    Document::UP exp = bc._bld.startDocument("doc::0").
                       startIndexField("g").
                       addStr("foo").
                       addStr("bar").
                       addTermAnnotation("baz").
                       endField().
                       startIndexField("dynamicstring").
                       setAutoAnnotate(false).
                       addStr("foo").
                       addSpan().
                       addAlphabeticTokenAnnotation().
                       addTermAnnotation().
                       addNoWordStr(" ").
                       addSpan().
                       addSpaceTokenAnnotation().
                       addStr("bar").
                       addSpan().
                       addAlphabeticTokenAnnotation().
                       addTermAnnotation("baz").
                       setAutoAnnotate(true).
                       endField().
                       endDocument();
    dc._sa->put(1, *exp, 1);

    IDocumentStore & store =
        dc._ddb->getReadySubDB()->getSummaryManager()->getBackingStore();
    Document::UP act = store.read(1, *bc._repo);
    EXPECT_TRUE(act.get() != NULL);
    EXPECT_EQUAL(exp->getType(), act->getType());
    EXPECT_EQUAL("foo bar", act->getValue("g")->getAsString());
    EXPECT_EQUAL("foo bar", act->getValue("dynamicstring")->getAsString());

    DocumentStoreAdapter dsa(store, *bc._repo, getResultConfig(), "class0",
                             bc.createFieldCacheRepo(getResultConfig())->getFieldCache("class0"),
                             getMarkupFields());
    EXPECT_TRUE(assertString("foo bar", "g", dsa, 1));
    EXPECT_TRUE(assertString(TERM_EMPTY + "foo" + TERM_SEP +
                            " " + TERM_SEP +
                            TERM_ORIG + "bar" + TERM_INDEX + "baz" + TERM_END +
                            TERM_SEP,
                            "dynamicstring", dsa, 1));
}

void
Test::requireThatUrisAreUsed()
{
    Schema s;
    s.addUriIndexFields(Schema::IndexField("urisingle",
                                Schema::STRING,
                                Schema::SINGLE));
    s.addSummaryField(Schema::SummaryField("urisingle",
                              Schema::STRING,
                              Schema::SINGLE));
    s.addUriIndexFields(Schema::IndexField("uriarray",
                                Schema::STRING,
                                Schema::ARRAY));
    s.addSummaryField(Schema::SummaryField("uriarray",
                              Schema::STRING,
                              Schema::ARRAY));
    s.addUriIndexFields(Schema::IndexField("uriwset",
                                Schema::STRING,
                                Schema::WEIGHTEDSET));
    s.addSummaryField(Schema::SummaryField("uriwset",
                              Schema::STRING,
                              Schema::WEIGHTEDSET));
    BuildContext bc(s);
    DBContext dc(bc._repo, getDocTypeName());
    Document::UP exp = bc._bld.startDocument("doc::0").
                       startIndexField("urisingle").
                       startSubField("all").
                       addUrlTokenizedString(
                               "http://www.yahoo.com:81/fluke?ab=2#4").
                       endSubField().
                       startSubField("scheme").
                       addUrlTokenizedString("http").
                       endSubField().
                       startSubField("host").
                       addUrlTokenizedString("www.yahoo.com").
                       endSubField().
                       startSubField("port").
                       addUrlTokenizedString("81").
                       endSubField().
                       startSubField("path").
                       addUrlTokenizedString("/fluke").
                       endSubField().
                       startSubField("query").
                       addUrlTokenizedString("ab=2").
                       endSubField().
                       startSubField("fragment").
                       addUrlTokenizedString("4").
                       endSubField().
                       endField().
                       startIndexField("uriarray").
                       startElement(1).
                       startSubField("all").
                       addUrlTokenizedString(
                               "http://www.yahoo.com:82/fluke?ab=2#8").
                       endSubField().
                       startSubField("scheme").
                       addUrlTokenizedString("http").
                       endSubField().
                       startSubField("host").
                       addUrlTokenizedString("www.yahoo.com").
                       endSubField().
                       startSubField("port").
                       addUrlTokenizedString("82").
                       endSubField().
                       startSubField("path").
                       addUrlTokenizedString("/fluke").
                       endSubField().
                       startSubField("query").
                       addUrlTokenizedString("ab=2").
                       endSubField().
                       startSubField("fragment").
                       addUrlTokenizedString("8").
                       endSubField().
                       endElement().
                       startElement(1).
                       startSubField("all").
                       addUrlTokenizedString(
                               "http://www.flickr.com:82/fluke?ab=2#9").
                       endSubField().
                       startSubField("scheme").
                       addUrlTokenizedString("http").
                       endSubField().
                       startSubField("host").
                       addUrlTokenizedString("www.flickr.com").
                       endSubField().
                       startSubField("port").
                       addUrlTokenizedString("82").
                       endSubField().
                       startSubField("path").
                       addUrlTokenizedString("/fluke").
                       endSubField().
                       startSubField("query").
                       addUrlTokenizedString("ab=2").
                       endSubField().
                       startSubField("fragment").
                       addUrlTokenizedString("9").
                       endSubField().
                       endElement().
                       endField().
                       startIndexField("uriwset").
                       startElement(4).
                       startSubField("all").
                       addUrlTokenizedString(
                               "http://www.yahoo.com:83/fluke?ab=2#12").
                       endSubField().
                       startSubField("scheme").
                       addUrlTokenizedString("http").
                       endSubField().
                       startSubField("host").
                       addUrlTokenizedString("www.yahoo.com").
                       endSubField().
                       startSubField("port").
                       addUrlTokenizedString("83").
                       endSubField().
                       startSubField("path").
                       addUrlTokenizedString("/fluke").
                       endSubField().
                       startSubField("query").
                       addUrlTokenizedString("ab=2").
                       endSubField().
                       startSubField("fragment").
                       addUrlTokenizedString("12").
                       endSubField().
                       endElement().
                       startElement(7).
                       startSubField("all").
                       addUrlTokenizedString(
                               "http://www.flickr.com:85/fluke?ab=2#13").
                       endSubField().
                       startSubField("scheme").
                       addUrlTokenizedString("http").
                       endSubField().
                       startSubField("host").
                       addUrlTokenizedString("www.flickr.com").
                       endSubField().
                       startSubField("port").
                       addUrlTokenizedString("85").
                       endSubField().
                       startSubField("path").
                       addUrlTokenizedString("/fluke").
                       endSubField().
                       startSubField("query").
                       addUrlTokenizedString("ab=2").
                       endSubField().
                       startSubField("fragment").
                       addUrlTokenizedString("13").
                       endSubField().
                       endElement().
                       endField().
                       endDocument();
    dc._sa->put(1, *exp, 1);

    IDocumentStore & store =
        dc._ddb->getReadySubDB()->getSummaryManager()->getBackingStore();
    Document::UP act = store.read(1, *bc._repo);
    EXPECT_TRUE(act.get() != NULL);
    EXPECT_EQUAL(exp->getType(), act->getType());

    DocumentStoreAdapter dsa(store, *bc._repo, getResultConfig(), "class0",
                             bc.createFieldCacheRepo(getResultConfig())->getFieldCache("class0"),
                             getMarkupFields());
    EXPECT_TRUE(assertString("http://www.yahoo.com:81/fluke?ab=2#4",
                            "urisingle", dsa, 1));
    EXPECT_TRUE(assertString("[\"http://www.yahoo.com:82/fluke?ab=2#8\","
                            "\"http://www.flickr.com:82/fluke?ab=2#9\"]",
                            "uriarray", dsa, 1));
    EXPECT_TRUE(assertString("["
                               "{\"item\":\"http://www.yahoo.com:83/fluke?ab=2#12\",\"weight\":4}"
                               ","
                               "{\"item\":\"http://www.flickr.com:85/fluke?ab=2#13\",\"weight\":7}"
                            "]",
                            "uriwset", dsa, 1));
}


void
Test::requireThatPositionsAreUsed()
{
    Schema s;
    s.addAttributeField(Schema::AttributeField("sp2",
                                Schema::INT64));
    s.addAttributeField(Schema::AttributeField("ap2",
                                Schema::INT64,
                                Schema::ARRAY));
    s.addAttributeField(Schema::AttributeField("wp2",
                                Schema::INT64,
                                Schema::WEIGHTEDSET));

    BuildContext bc(s);
    DBContext dc(bc._repo, getDocTypeName());
    Document::UP exp = bc._bld.startDocument("doc::1").
                       startAttributeField("sp2").
                       addPosition(1002, 1003).
                       endField().
                       startAttributeField("ap2").
                       startElement().addPosition(1006, 1007).endElement().
                       startElement().addPosition(1008, 1009).endElement().
                       endField().
                       startAttributeField("wp2").
                       startElement(43).addPosition(1012, 1013).endElement().
                       startElement(44).addPosition(1014, 1015).endElement().
                       endField().
                       endDocument();
    dc.put(*exp, 1);

    IDocumentStore & store =
        dc._ddb->getReadySubDB()->getSummaryManager()->getBackingStore();
    Document::UP act = store.read(1, *bc._repo);
    EXPECT_TRUE(act.get() != NULL);
    EXPECT_EQUAL(exp->getType(), act->getType());

    DocsumRequest req;
    req.resultClassName = "class5";
    req.hits.push_back(DocsumRequest::Hit(gid1));
    DocsumReply::UP rep = dc._ddb->getDocsums(req);
    uint32_t rclass = 5;

    EXPECT_EQUAL(1u, rep->docsums.size());
    EXPECT_EQUAL(1u, rep->docsums[0].docid);
    EXPECT_EQUAL(gid1, rep->docsums[0].gid);
    EXPECT_TRUE(assertString("1047758",
                            "sp2", *rep, 0, rclass));
    EXPECT_TRUE(assertString("<position x=\"1002\" y=\"1003\" latlong=\"N0.001003;E0.001002\" />",
                            "sp2x", *rep, 0, rclass));
    EXPECT_TRUE(assertString("[1047806,1048322]",
                             "ap2", *rep, 0, rclass));
    EXPECT_TRUE(assertString("<position x=\"1006\" y=\"1007\" latlong=\"N0.001007;E0.001006\" />"
                            "<position x=\"1008\" y=\"1009\" latlong=\"N0.001009;E0.001008\" />",
                            "ap2x", *rep, 0, rclass));
    EXPECT_TRUE(assertString("[{\"item\":1048370,\"weight\":43},{\"item\":1048382,\"weight\":44}]",
                            "wp2", *rep, 0, rclass));
    EXPECT_TRUE(assertString("<position x=\"1012\" y=\"1013\" latlong=\"N0.001013;E0.001012\" />"
                            "<position x=\"1014\" y=\"1015\" latlong=\"N0.001015;E0.001014\" />",
                            "wp2x", *rep, 0, rclass));
}


void
Test::requireThatRawFieldsWorks()
{
    Schema s;
    s.addSummaryField(Schema::AttributeField("i",
                                Schema::RAW));
    s.addSummaryField(Schema::AttributeField("araw",
                                Schema::RAW,
                                Schema::ARRAY));
    s.addSummaryField(Schema::AttributeField("wraw",
                              Schema::RAW,
                              Schema::WEIGHTEDSET));

    std::vector<char> binaryBlob;
    binaryBlob.push_back('\0');
    binaryBlob.push_back('\2');
    binaryBlob.push_back('\1');
    std::string raw1s("Single Raw Element");
    std::string raw1a0("Array Raw Element 0");
    std::string raw1a1("Array Raw Element  1");
    std::string raw1w0("Weighted Set Raw Element 0");
    std::string raw1w1("Weighted Set Raw Element  1");
    raw1s += std::string(&binaryBlob[0],
                         &binaryBlob[0] + binaryBlob.size());
    raw1a0 += std::string(&binaryBlob[0],
                          &binaryBlob[0] + binaryBlob.size());
    raw1a1 += std::string(&binaryBlob[0],
                          &binaryBlob[0] + binaryBlob.size());
    raw1w0 += std::string(&binaryBlob[0],
                          &binaryBlob[0] + binaryBlob.size());
    raw1w1 += std::string(&binaryBlob[0],
                          &binaryBlob[0] + binaryBlob.size());

    BuildContext bc(s);
    DBContext dc(bc._repo, getDocTypeName());
    Document::UP exp = bc._bld.startDocument("doc::0").
                       startSummaryField("i").
                       addRaw(raw1s.c_str(), raw1s.size()).
                       endField().
                       startSummaryField("araw").
                       startElement().
                       addRaw(raw1a0.c_str(), raw1a0.size()).
                       endElement().
                       startElement().
                       addRaw(raw1a1.c_str(), raw1a1.size()).
                       endElement().
                       endField().
                       startSummaryField("wraw").
                       startElement(46).
                       addRaw(raw1w1.c_str(), raw1w1.size()).
                       endElement().
                       startElement(45).
                       addRaw(raw1w0.c_str(), raw1w0.size()).
                       endElement().
                       endField().
                       endDocument();
    dc._sa->put(1, *exp, 1);

    IDocumentStore & store =
        dc._ddb->getReadySubDB()->getSummaryManager()->getBackingStore();
    Document::UP act = store.read(1, *bc._repo);
    EXPECT_TRUE(act.get() != NULL);
    EXPECT_EQUAL(exp->getType(), act->getType());

    DocumentStoreAdapter dsa(store, *bc._repo, getResultConfig(), "class0",
                             bc.createFieldCacheRepo(getResultConfig())->getFieldCache("class0"),
                             getMarkupFields());

    ASSERT_TRUE(assertString(raw1s,
                            "i", dsa, 1));
    ASSERT_TRUE(assertString(empty + "[\"" +
                             vespalib::Base64::encode(raw1a0) +
                             "\",\"" +
                             vespalib::Base64::encode(raw1a1) +
                             "\"]",
                             "araw", dsa, 1));
    ASSERT_TRUE(assertString(empty + "[{\"item\":\"" +
                             vespalib::Base64::encode(raw1w1) +
                             "\",\"weight\":46},{\"item\":\"" +
                             vespalib::Base64::encode(raw1w0) +
                             "\",\"weight\":45}]",
                             "wraw", dsa, 1));
}


void
Test::requireThatFieldCacheRepoCanReturnDefaultFieldCache()
{
    Schema s;
    s.addSummaryField(Schema::SummaryField("a", Schema::INT32));
    BuildContext bc(s);
    FieldCacheRepo::UP repo = bc.createFieldCacheRepo(getResultConfig());
    FieldCache::CSP cache = repo->getFieldCache("");
    EXPECT_TRUE(cache.get() == repo->getFieldCache("class1").get());
    EXPECT_EQUAL(1u, cache->size());
    EXPECT_EQUAL("a", cache->getField(0)->getName());
}


Test::Test()
    : _summaryCfg(),
      _resultCfg(),
      _markupFields()
{
    std::string cfgId("summary");
    _summaryCfg = config::ConfigGetter<vespa::config::search::SummaryConfig>::getConfig(
        cfgId, config::FileSpec(vespalib::TestApp::GetSourceDirectory() + "summary.cfg"));
    _resultCfg.ReadConfig(*_summaryCfg, cfgId.c_str());
    std::string mapCfgId("summarymap");
    std::unique_ptr<vespa::config::search::SummarymapConfig> mapCfg = config::ConfigGetter<vespa::config::search::SummarymapConfig>::getConfig(
            mapCfgId, config::FileSpec(vespalib::TestApp::GetSourceDirectory() + "summarymap.cfg"));
    for (size_t i = 0; i < mapCfg->override.size(); ++i) {
        const vespa::config::search::SummarymapConfig::Override & o = mapCfg->override[i];
        if (o.command == "dynamicteaser") {
            vespalib::string markupField = o.arguments;
            if (markupField.empty())
                continue;
            // Assume just one argument: source field that must contain markup
            _markupFields.insert(markupField);
            LOG(info,
                "Field %s has markup",
                markupField.c_str());
        }
    }
}


int
Test::Main()
{
    TEST_INIT("docsummary_test");

    if (_argc > 0) {
        DummyFileHeaderContext::setCreator(_argv[0]);
    }
    TEST_DO(requireThatSummaryAdapterHandlesPutAndRemove());
    TEST_DO(requireThatAdapterHandlesAllFieldTypes());
    TEST_DO(requireThatAdapterHandlesMultipleDocuments());
    TEST_DO(requireThatAdapterHandlesDocumentIdField());
    TEST_DO(requireThatDocsumRequestIsProcessed());
    TEST_DO(requireThatRewritersAreUsed());
    TEST_DO(requireThatAttributesAreUsed());
    TEST_DO(requireThatAnnotationsAreUsed());
    TEST_DO(requireThatUrisAreUsed());
    TEST_DO(requireThatPositionsAreUsed());
    TEST_DO(requireThatRawFieldsWorks());
    TEST_DO(requireThatFieldCacheRepoCanReturnDefaultFieldCache());

    TEST_DONE();
}

}

TEST_APPHOOK(proton::Test);
