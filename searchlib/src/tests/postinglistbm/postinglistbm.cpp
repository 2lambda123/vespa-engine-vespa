// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "andstress.h"
#include <vespa/fastos/app.h>
#include <vespa/searchlib/common/bitvector.h>
#include <vespa/searchlib/common/resultset.h>
#include <vespa/searchlib/index/docidandfeatures.h>
#include <vespa/searchlib/test/fakedata/fake_match_loop.h>
#include <vespa/searchlib/test/fakedata/fakeposting.h>
#include <vespa/searchlib/test/fakedata/fakeword.h>
#include <vespa/searchlib/test/fakedata/fakewordset.h>
#include <vespa/searchlib/test/fakedata/fpfactory.h>
#include <vespa/searchlib/util/rand48.h>

#include <vespa/log/log.h>

using search::ResultSet;
using search::fef::TermFieldMatchData;
using search::fef::TermFieldMatchDataArray;
using search::index::schema::CollectionType;
using search::index::schema::DataType;
using search::queryeval::SearchIterator;

using namespace search::index;
using namespace search::fakedata;

namespace postinglistbm {

class PostingListBM : public FastOS_Application {
private:
    uint32_t _numDocs;
    uint32_t _commonDocFreq;
    uint32_t _numWordsPerClass;
    std::vector<std::string> _postingTypes;
    uint32_t _loops;
    uint32_t _skipCommonPairsRate;
    FakeWordSet _wordSet;
    uint32_t _stride;
    bool _unpack;

public:
    search::Rand48 _rnd;

public:
    PostingListBM();
    ~PostingListBM();
    int Main() override;
};

void
usage()
{
    printf("Usage: postinglistbm "
           "[-C <skipCommonPairsRate>] "
           "[-T {string, array, weightedSet}] "
           "[-c <commonDoqFreq>] "
           "[-d <numDocs>] "
           "[-l <numLoops>] "
           "[-s <stride>] "
           "[-t <postingType>] "
           "[-u] "
           "[-w <numWordsPerClass>] "
           "[-q]\n");
}

void
badPostingType(const std::string &postingType)
{
    printf("Bad posting list type: %s\n", postingType.c_str());
    printf("Supported types: ");

    bool first = true;
    for (const auto& type : getPostingTypes()) {
        if (first) {
            first = false;
        } else {
            printf(", ");
        }
        printf("%s", type.c_str());
    }
    printf("\n");
}

PostingListBM::PostingListBM()
    : _numDocs(10000000),
      _commonDocFreq(50000),
      _numWordsPerClass(100),
      _postingTypes(),
      _loops(1),
      _skipCommonPairsRate(1),
      _wordSet(),
      _stride(0),
      _unpack(false),
      _rnd()
{
}

PostingListBM::~PostingListBM() = default;

int
PostingListBM::Main()
{
    int argi;
    char c;
    const char *optArg;

    argi = 1;
    bool hasElements = false;
    bool hasElementWeights = false;
    bool quick = false;

    while ((c = GetOpt("C:c:d:l:s:t:uw:T:q", optArg, argi)) != -1) {
        switch(c) {
        case 'C':
            _skipCommonPairsRate = atoi(optArg);
            break;
        case 'T':
            if (strcmp(optArg, "single") == 0) {
                hasElements = false;
                hasElementWeights = false;
            } else if (strcmp(optArg, "array") == 0) {
                hasElements = true;
                hasElementWeights = false;
            } else if (strcmp(optArg, "weightedSet") == 0) {
                hasElements = true;
                hasElementWeights = true;
            } else {
                printf("Bad collection type: %s\n", optArg);
                return 1;
            }
            break;
        case 'c':
            _commonDocFreq = atoi(optArg);
            break;
        case 'd':
            _numDocs = atoi(optArg);
            break;
        case 'l':
            _loops = atoi(optArg);
            break;
        case 's':
            _stride = atoi(optArg);
            break;
        case 't':
            do {
                Schema schema;
                Schema::IndexField indexField("field0",
                        DataType::STRING,
                        CollectionType::SINGLE);
                schema.addIndexField(indexField);
                std::unique_ptr<FPFactory> ff(getFPFactory(optArg, schema));
                if (ff.get() == nullptr) {
                    badPostingType(optArg);
                    return 1;
                }
            } while (0);
            _postingTypes.push_back(optArg);
            break;
        case 'u':
            _unpack = true;
            break;
        case 'w':
            _numWordsPerClass = atoi(optArg);
            break;
        case 'q':
            quick = true;
            _numDocs = 36000;
            _commonDocFreq = 10000;
            _numWordsPerClass = 5;
            break;
        default:
            usage();
            return 1;
        }
    }

    if (_commonDocFreq > _numDocs) {
        usage();
        return 1;
    }

    _wordSet.setupParams(hasElements, hasElementWeights);

    uint32_t numTasks = 40000;
    if (quick) {
        numTasks = 40;
    }
    
    if (_postingTypes.empty()) {
        _postingTypes = getPostingTypes();
    }

    _wordSet.setupWords(_rnd, _numDocs, _commonDocFreq, _numWordsPerClass);

    AndStress andstress;
    andstress.run(_rnd, _wordSet,
                  _numDocs, _commonDocFreq, _postingTypes, _loops,
                  _skipCommonPairsRate,
                  numTasks,
                  _stride,
                  _unpack);
    return 0;
}

}

int
main(int argc, char **argv)
{
    postinglistbm::PostingListBM app;

    setvbuf(stdout, nullptr, _IOLBF, 32768);
    app._rnd.srand48(32);
    return app.Entry(argc, argv);
}
