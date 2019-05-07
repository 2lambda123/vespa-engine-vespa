// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "fakewordset.h"
#include "fakeword.h"
#include <vespa/fastos/time.h>
#include <sstream>

#include <vespa/log/log.h>
LOG_SETUP(".fakewordset");

namespace search::fakedata {

using FakeWordVector = FakeWordSet::FakeWordVector;
using index::PostingListParams;
using index::SchemaUtil;
using index::schema::CollectionType;
using index::schema::DataType;

namespace {

void
applyDocIdBiasToVector(FakeWordVector& words, uint32_t docIdBias)
{
    for (auto& word : words) {
        word->addDocIdBias(docIdBias);
    }
}

}

FakeWordSet::FakeWordSet()
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

FakeWordSet::~FakeWordSet() = default;

void
FakeWordSet::setupParams(bool hasElements,
                         bool hasElementWeights)
{
    _schema.clear();

    assert(hasElements || !hasElementWeights);
    Schema::CollectionType collectionType(CollectionType::SINGLE);
    if (hasElements) {
        if (hasElementWeights) {
            collectionType = CollectionType::WEIGHTEDSET;
        } else {
            collectionType = CollectionType::ARRAY;
        }
    }
    Schema::IndexField indexField("field0", DataType::STRING, collectionType);
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
        _words[COMMON_WORD].push_back(std::make_unique<FakeWord>(numDocs, commonDocFreq, commonDocFreq / 2,
                                                                 common + vi.str(), rnd,
                                                                 _fieldsParams[packedIndex],
                                                                 packedIndex));

        _words[MEDIUM_WORD].push_back(std::make_unique<FakeWord>(numDocs, 1000, 500,
                                                                 medium + vi.str(), rnd,
                                                                 _fieldsParams[packedIndex],
                                                                 packedIndex));

        _words[RARE_WORD].push_back(std::make_unique<FakeWord>(numDocs, 10, 5,
                                                               rare + vi.str(), rnd,
                                                               _fieldsParams[packedIndex],
                                                               packedIndex));
    }
    tv.SetNow();
    after = tv.Secs();
    LOG(info, "leave setupWords, elapsed %10.6f s", after - before);
}

int
FakeWordSet::getNumWords() const
{
    int ret = 0;
    for (const auto& words : _words) {
        ret += words.size();
    }
    return ret;
}

void
FakeWordSet::addDocIdBias(uint32_t docIdBias)
{
    for (auto& words : _words) {
        applyDocIdBiasToVector(words, docIdBias);
    }
}

}
