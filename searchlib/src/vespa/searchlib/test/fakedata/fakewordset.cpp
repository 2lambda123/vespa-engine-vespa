// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include "fakewordset.h"
#include "fakeword.h"
#include <vespa/searchlib/index/schemautil.h>
#include <vespa/log/log.h>
LOG_SETUP(".fakewordset");

namespace search {

namespace fakedata {

using index::PostingListParams;
using index::SchemaUtil;

static void
clearFakeWordVector(std::vector<FakeWord *> &v)
{
    for (unsigned int i = 0; i < v.size(); ++i)
        delete v[i];
    v.clear();
}


static void
applyDocIdBiasToVector(std::vector<FakeWord *> &v, uint32_t docIdBias)
{
    for (unsigned int i = 0; i < v.size(); ++i)
        v[i]->addDocIdBias(docIdBias);
}


FakeWordSet::FakeWordSet(void)
    : _words(NUM_WORDCLASSES),
      _schema(),
      _fieldsParams()
{
    setupParams(false, false);
}


FakeWordSet::FakeWordSet(bool hasElements,
                         bool hasElementWeights)
    : _words(NUM_WORDCLASSES),
      _schema(),
      _fieldsParams()
{
    setupParams(hasElements, hasElementWeights);
}


FakeWordSet::~FakeWordSet(void)
{
    dropWords();
}


void
FakeWordSet::setupParams(bool hasElements,
                         bool hasElementWeights)
{
    _schema.clear();

    assert(hasElements || !hasElementWeights);
    Schema::CollectionType collectionType(index::schema::SINGLE);
    if (hasElements) {
        if (hasElementWeights)
            collectionType = index::schema::WEIGHTEDSET;
        else
            collectionType = index::schema::ARRAY;
    }
    Schema::IndexField indexField("field0", index::schema::STRING, collectionType);
    indexField.setAvgElemLen(512u);
    _schema.addIndexField(indexField);
    _fieldsParams.resize(_schema.getNumIndexFields());
    SchemaUtil::IndexIterator it(_schema);
    for(; it.isValid(); ++it) {
        _fieldsParams[it.getIndex()].
            setSchemaParams(_schema, it.getIndex());
    }
}


void
FakeWordSet::setupWords(search::Rand48 &rnd,
                        unsigned int numDocs,
                        unsigned int commonDocFreq,
                        unsigned int numWordsPerWordClass)
{
    std::string common = "common";
    std::string medium = "medium";
    std::string rare = "rare";
    FakeWord *fw;
    FastOS_Time tv;
    double before;
    double after;

    LOG(info, "enter setupWords");
    tv.SetNow();
    before = tv.Secs();
    uint32_t packedIndex = _fieldsParams.size() - 1;
    for (unsigned int i = 0; i < numWordsPerWordClass; ++i) {
        std::ostringstream vi;

        vi << (i + 1);
        fw = new FakeWord(numDocs, commonDocFreq, commonDocFreq / 2,
                          common + vi.str(), rnd,
                          _fieldsParams[packedIndex],
                          packedIndex);
        _words[COMMON_WORD].push_back(fw);
        fw = new FakeWord(numDocs, 1000, 500,
                          medium + vi.str(), rnd,
                          _fieldsParams[packedIndex],
                          packedIndex);
        _words[MEDIUM_WORD].push_back(fw);
        fw = new FakeWord(numDocs, 10, 5,
                          rare + vi.str(), rnd,
                          _fieldsParams[packedIndex],
                          packedIndex);
        _words[RARE_WORD].push_back(fw);
    }
    tv.SetNow();
    after = tv.Secs();
    LOG(info, "leave setupWords, elapsed %10.6f s", after - before);
}


void
FakeWordSet::dropWords(void)
{
    for (unsigned int i = 0; i < _words.size(); ++i)
        clearFakeWordVector(_words[i]);
}


int
FakeWordSet::getNumWords(void)
{
    int ret = 0;
    for (unsigned int i = 0; i < _words.size(); ++i)
        ret += _words[i].size();
    return ret;
}


void
FakeWordSet::addDocIdBias(uint32_t docIdBias)
{
    for (unsigned int i = 0; i < _words.size(); ++i)
        applyDocIdBiasToVector(_words[i], docIdBias);
}


} // namespace fakedata

} // namespace search
