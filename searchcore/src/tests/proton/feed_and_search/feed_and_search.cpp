// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP("feed_and_search_test");

#include <vespa/document/datatype/datatype.h>
#include <vespa/document/fieldvalue/document.h>
#include <vespa/document/fieldvalue/fieldvalue.h>
#include <vespa/searchlib/memoryindex/memoryindex.h>
#include <vespa/searchlib/diskindex/diskindex.h>
#include <vespa/searchlib/diskindex/indexbuilder.h>
#include <vespa/searchlib/fef/fef.h>
#include <vespa/searchlib/index/docbuilder.h>
#include <vespa/searchlib/index/dummyfileheadercontext.h>
#include <vespa/searchlib/query/base.h>
#include <vespa/searchlib/query/tree/simplequery.h>
#include <vespa/searchlib/queryeval/blueprint.h>
#include <vespa/searchlib/queryeval/searchiterator.h>
#include <vespa/searchlib/queryeval/fake_requestcontext.h>
#include <vespa/vespalib/testkit/testapp.h>
#include <sstream>
#include <vespa/searchlib/diskindex/fusion.h>
#include <vespa/searchlib/common/documentsummary.h>
#include <vespa/searchlib/common/sequencedtaskexecutor.h>

using document::DataType;
using document::Document;
using document::FieldValue;
using search::DocumentIdT;
using search::TuneFileIndexing;
using search::TuneFileSearch;
using search::diskindex::DiskIndex;
using search::diskindex::IndexBuilder;
using search::diskindex::SelectorArray;
using search::fef::FieldPositionsIterator;
using search::fef::MatchData;
using search::fef::MatchDataLayout;
using search::fef::TermFieldHandle;
using search::fef::TermFieldMatchData;
using search::index::DocBuilder;
using search::index::Schema;
using search::index::DummyFileHeaderContext;
using search::memoryindex::MemoryIndex;
using search::query::SimpleStringTerm;
using search::queryeval::Blueprint;
using search::queryeval::FieldSpec;
using search::queryeval::FieldSpecList;
using search::queryeval::SearchIterator;
using search::queryeval::Searchable;
using search::queryeval::FakeRequestContext;
using std::ostringstream;
using vespalib::string;
using search::docsummary::DocumentSummary;

namespace {

class Test : public vespalib::TestApp {
    const char *current_state;
    void DumpState(bool) {
      fprintf(stderr, "%s: ERROR: in %s\n", GetName(), current_state);
    }

    void requireThatMemoryIndexCanBeDumpedAndSearched();

    void testSearch(Searchable &source,
                    const string &term, uint32_t doc_id);

public:
    int Main();
};

#define TEST_CALL(func) \
    current_state = #func; \
    func();

int
Test::Main()
{
    TEST_INIT("feed_and_search_test");

    if (_argc > 0) {
        DummyFileHeaderContext::setCreator(_argv[0]);
    }
    TEST_CALL(requireThatMemoryIndexCanBeDumpedAndSearched);

    TEST_DONE();
}

const string field_name = "string_field";
const string noise = "noise";
const string word1 = "foo";
const string word2 = "bar";
const DocumentIdT doc_id1 = 1;
const DocumentIdT doc_id2 = 2;
const uint32_t field_id = 1;

Schema getSchema() {
    Schema schema;
    schema.addIndexField(Schema::IndexField(field_name, search::index::schema::STRING));
    return schema;
}

Document::UP buildDocument(DocBuilder & doc_builder, int id,
                           const string &word) {
    ostringstream ost;
    ost << "doc::" << id;
    doc_builder.startDocument(ost.str());
    doc_builder.startIndexField(field_name)
        .addStr(noise).addStr(word).endField();
    return doc_builder.endDocument();
}

// Performs a search using a Searchable.
void Test::testSearch(Searchable &source,
                      const string &term, uint32_t doc_id)
{
    FakeRequestContext requestContext;
    uint32_t fieldId = 0;
    MatchDataLayout mdl;
    TermFieldHandle handle = mdl.allocTermField(fieldId);
    MatchData::UP match_data = mdl.createMatchData();

    SimpleStringTerm node(term, field_name, 0, search::query::Weight(0));
    Blueprint::UP result = source.createBlueprint(requestContext,
            FieldSpecList().add(FieldSpec(field_name, 0, handle)), node);
    result->fetchPostings(true);
    SearchIterator::UP search_iterator =
        result->createSearch(*match_data, true);
    search_iterator->initFullRange();
    ASSERT_TRUE(search_iterator.get());
    ASSERT_TRUE(search_iterator->seek(doc_id));
    EXPECT_EQUAL(doc_id, search_iterator->getDocId());
    search_iterator->unpack(doc_id);
    FieldPositionsIterator it =
        match_data->resolveTermField(handle)->getIterator();
    ASSERT_TRUE(it.valid());
    EXPECT_EQUAL(1u, it.size());
    EXPECT_EQUAL(1u, it.getPosition());  // All hits are at pos 1 in this index

    EXPECT_TRUE(!search_iterator->seek(doc_id + 1));
    EXPECT_TRUE(search_iterator->isAtEnd());
}

// Creates a memory index, inserts documents, performs a few
// searches, dumps the index to disk, and performs the searches
// again.
void Test::requireThatMemoryIndexCanBeDumpedAndSearched() {
    Schema schema = getSchema();
    search::SequencedTaskExecutor indexFieldInverter(2);
    search::SequencedTaskExecutor indexFieldWriter(2);
    MemoryIndex memory_index(schema, indexFieldInverter, indexFieldWriter);
    DocBuilder doc_builder(schema);

    Document::UP doc = buildDocument(doc_builder, doc_id1, word1);
    memory_index.insertDocument(doc_id1, *doc.get());

    doc = buildDocument(doc_builder, doc_id2, word2);
    memory_index.insertDocument(doc_id2, *doc.get());
    memory_index.commit(std::shared_ptr<search::IDestructorCallback>());
    indexFieldWriter.sync();

    testSearch(memory_index, word1, doc_id1);
    testSearch(memory_index, word2, doc_id2);

    const string index_dir = "test_index";
    IndexBuilder index_builder(schema);
    index_builder.setPrefix(index_dir);
    const uint32_t docIdLimit = memory_index.getDocIdLimit();
    const uint64_t num_words = memory_index.getNumWords();
    search::TuneFileIndexing tuneFileIndexing;
    DummyFileHeaderContext fileHeaderContext;
    index_builder.open(docIdLimit, num_words, tuneFileIndexing,
                       fileHeaderContext);
    memory_index.dump(index_builder);
    index_builder.close();

    // Fusion test.  Keep all documents to get an "indentical" copy.
    const string index_dir2 = "test_index2";
    std::vector<string> fusionInputs;
    fusionInputs.push_back(index_dir);
    uint32_t fusionDocIdLimit = 0;
    typedef search::diskindex::Fusion FastS_Fusion;
    bool fret1 = DocumentSummary::readDocIdLimit(index_dir, fusionDocIdLimit);
    ASSERT_TRUE(fret1);
    SelectorArray selector(fusionDocIdLimit, 0);
    bool fret2 = FastS_Fusion::merge(schema,
                                    index_dir2,
                                    fusionInputs,
                                    selector,
                                    false /* dynamicKPosOccFormat */,
                                     tuneFileIndexing,
                                     fileHeaderContext);
    ASSERT_TRUE(fret2);

    // Fusion test with all docs removed in output (doesn't affect word list)
    const string index_dir3 = "test_index3";
    fusionInputs.clear();
    fusionInputs.push_back(index_dir);
    fusionDocIdLimit = 0;
    bool fret3 = DocumentSummary::readDocIdLimit(index_dir, fusionDocIdLimit);
    ASSERT_TRUE(fret3);
    SelectorArray selector2(fusionDocIdLimit, 1);
    bool fret4 = FastS_Fusion::merge(schema,
                                    index_dir3,
                                    fusionInputs,
                                    selector2,
                                    false /* dynamicKPosOccFormat */,
                                     tuneFileIndexing,
                                     fileHeaderContext);
    ASSERT_TRUE(fret4);

    // Fusion test with all docs removed in input (affects word list)
    const string index_dir4 = "test_index4";
    fusionInputs.clear();
    fusionInputs.push_back(index_dir3);
    fusionDocIdLimit = 0;
    bool fret5 = DocumentSummary::readDocIdLimit(index_dir3, fusionDocIdLimit);
    ASSERT_TRUE(fret5);
    SelectorArray selector3(fusionDocIdLimit, 0);
    bool fret6 = FastS_Fusion::merge(schema,
                                    index_dir4,
                                    fusionInputs,
                                    selector3,
                                    false /* dynamicKPosOccFormat */,
                                     tuneFileIndexing,
                                     fileHeaderContext);
    ASSERT_TRUE(fret6);

    DiskIndex disk_index(index_dir);
    ASSERT_TRUE(disk_index.setup(TuneFileSearch()));
    testSearch(disk_index, word1, doc_id1);
    testSearch(disk_index, word2, doc_id2);
    DiskIndex disk_index2(index_dir2);
    ASSERT_TRUE(disk_index2.setup(TuneFileSearch()));
    testSearch(disk_index2, word1, doc_id1);
    testSearch(disk_index2, word2, doc_id2);
}
}  // namespace

TEST_APPHOOK(Test);
