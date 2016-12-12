// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include <vespa/searchlib/common/bitvector.h>
#include <vespa/searchlib/common/resultset.h>
#include <vespa/searchlib/util/rand48.h>
#include <vespa/searchlib/test/fakedata/fakeword.h>
#include <vespa/searchlib/test/fakedata/fakewordset.h>
#include <vespa/searchlib/index/docidandfeatures.h>
#include <vespa/searchlib/index/postinglisthandle.h>
#include <vespa/searchlib/diskindex/zcposocc.h>
#include <vespa/searchlib/diskindex/zcposoccrandread.h>
#include <vespa/searchlib/diskindex/checkpointfile.h>
#include <vespa/searchlib/index/dummyfileheadercontext.h>
#include <vespa/searchlib/index/schemautil.h>
#include <vespa/vespalib/objects/nbostream.h>
#include <vespa/searchlib/diskindex/fieldwriter.h>
#include <vespa/searchlib/diskindex/fieldreader.h>
#include <vespa/vespalib/io/fileutil.h>
#include <vespa/searchlib/util/dirtraverse.h>
#include <vespa/searchlib/diskindex/pagedict4file.h>
#include <vespa/searchlib/diskindex/pagedict4randread.h>
#include <vespa/log/log.h>
LOG_SETUP("fieldwriter_test");


using search::ResultSet;
using search::fef::TermFieldMatchData;
using search::fef::TermFieldMatchDataArray;
using search::queryeval::SearchIterator;
using search::fakedata::FakeWord;
using search::fakedata::FakeWordSet;
using search::index::PostingListParams;
using search::index::PostingListCounts;
using search::index::PostingListOffsetAndCounts;
using search::index::Schema;
using search::index::SchemaUtil;
using search::common::FileHeaderContext;
using search::index::DummyFileHeaderContext;
using search::diskindex::CheckPointFile;
using search::TuneFileSeqRead;
using search::TuneFileSeqWrite;
using search::TuneFileRandRead;
using vespalib::nbostream;
using search::diskindex::FieldWriter;
using search::diskindex::FieldReader;
using search::diskindex::DocIdMapping;
using search::diskindex::WordNumMapping;
using search::diskindex::PageDict4RandRead;
using namespace search::index;

// needed to resolve external symbol from httpd.h on AIX
void FastS_block_usr2() { }

namespace fieldwriter {

uint32_t minSkipDocs = 64;
uint32_t minChunkDocs = 262144;

vespalib::string dirprefix = "index/";

void
disableSkip(void)
{
    minSkipDocs = 10000000;
    minChunkDocs = 1 << 30;
}

void
enableSkip(void)
{
    minSkipDocs = 64;
    minChunkDocs = 1 << 30;
}

void
enableSkipChunks(void)
{
    minSkipDocs = 64;
    minChunkDocs = 9000;	// Unrealistic low for testing
}


vespalib::string
makeWordString(uint64_t wordNum)
{
    using AS = vespalib::asciistream;
    AS ws;
    ws << AS::Width(4) << AS::Fill('0') << wordNum;
    return ws.str();
}


typedef std::shared_ptr<FieldReader> FieldReaderSP;
typedef std::shared_ptr<FieldWriter> FieldWriterSP;

class FieldWriterTest : public FastOS_Application
{
private:
    bool _verbose;
    uint32_t _numDocs;
    uint32_t _commonDocFreq;
    uint32_t _numWordsPerClass;
    FakeWordSet _wordSet;
    FakeWordSet _wordSet2;
public:
    search::Rand48 _rnd;

private:
    void Usage(void);
    void testFake(const std::string &postingType, FakeWord &fw);
public:
    FieldWriterTest(void);
    ~FieldWriterTest(void);
    int Main(void);
};


void
FieldWriterTest::Usage(void)
{
    printf("fieldwriter_test "
           "[-c <commonDocFreq>] "
           "[-d <numDocs>] "
           "[-v] "
           "[-w <numWordPerClass>]\n");
}


FieldWriterTest::FieldWriterTest(void)
    : _verbose(false),
      _numDocs(3000000),
      _commonDocFreq(50000),
      _numWordsPerClass(6),
      _wordSet(),
      _wordSet2(),
      _rnd()
{
}


FieldWriterTest::~FieldWriterTest(void)
{
}


class WrappedFieldWriter : public search::fakedata::CheckPointCallback
{
public:
    FieldWriterSP _fieldWriter;
private:
    bool _dynamicK;
    uint32_t _numWordIds;
    uint32_t _docIdLimit;
    vespalib::string _namepref;
    Schema _schema;
    uint32_t _indexId;

public:

    WrappedFieldWriter(const vespalib::string &namepref,
                      bool dynamicK,
                      uint32_t numWordIds,
                      uint32_t docIdLimit);

    virtual void
    checkPoint(void) override;

    void
    earlyOpen(void);

    void
    lateOpen(void);

    void
    open(void);

    void
    close(void);

    void
    writeCheckPoint(void);

    void
    readCheckPoint(bool first);
};


WrappedFieldWriter::WrappedFieldWriter(const vespalib::string &namepref,
                                       bool dynamicK,
                                       uint32_t numWordIds,
                                       uint32_t docIdLimit)
    : _fieldWriter(),
      _dynamicK(dynamicK),
      _numWordIds(numWordIds),
      _docIdLimit(docIdLimit),
      _namepref(dirprefix + namepref),
      _schema(),
      _indexId()
{
    schema::CollectionType ct(schema::SINGLE);
    _schema.addIndexField(Schema::IndexField("field1", schema::STRING, ct));
    _indexId = _schema.getIndexFieldId("field1");
}


void
WrappedFieldWriter::earlyOpen(void)
{
    TuneFileSeqWrite tuneFileWrite;
    _fieldWriter.reset(new  FieldWriter(_docIdLimit, _numWordIds));
    _fieldWriter->earlyOpen(_namepref,
                            minSkipDocs, minChunkDocs, _dynamicK, _schema,
                            _indexId,
                            tuneFileWrite);
}


void
WrappedFieldWriter::lateOpen(void)
{
    TuneFileSeqWrite tuneFileWrite;
    DummyFileHeaderContext fileHeaderContext;
    fileHeaderContext.disableFileName();
    _fieldWriter->lateOpen(tuneFileWrite, fileHeaderContext);
}


void
WrappedFieldWriter::open(void)
{
    earlyOpen();
    lateOpen();
}


void
WrappedFieldWriter::close(void)
{
    _fieldWriter->close();
    _fieldWriter.reset();
}


void
WrappedFieldWriter::writeCheckPoint(void)
{
    CheckPointFile chkptfile("chkpt");
    nbostream out;
    _fieldWriter->checkPointWrite(out);
    chkptfile.write(out, DummyFileHeaderContext());
}


void
WrappedFieldWriter::readCheckPoint(bool first)
{
    CheckPointFile chkptfile("chkpt");
    nbostream in;
    bool openRes = chkptfile.read(in);
    assert(first || openRes);
    (void) first;
    if (!openRes)
        return;
    _fieldWriter->checkPointRead(in);
    assert(in.empty());
}


void
WrappedFieldWriter::checkPoint(void)
{
    writeCheckPoint();
    _fieldWriter.reset();
    earlyOpen();
    readCheckPoint(false);
    lateOpen();
}


class WrappedFieldReader : public search::fakedata::CheckPointCallback
{
public:
    FieldReaderSP _fieldReader;
private:
    std::string _namepref;
    uint32_t _numWordIds;
    uint32_t _docIdLimit;
    WordNumMapping _wmap;
    DocIdMapping _dmap;
    Schema _oldSchema;
    Schema _schema;

public:
    WrappedFieldReader(const vespalib::string &namepref,
                      uint32_t numWordIds,
                      uint32_t docIdLimit);

    ~WrappedFieldReader(void);

    void
    earlyOpen(void);

    void
    lateOpen(void);

    void
    open(void);

    void
    close(void);

    void
    writeCheckPoint(void);

    void
    readCheckPoint(bool first);

    virtual void
    checkPoint(void) override;
};


WrappedFieldReader::WrappedFieldReader(const vespalib::string &namepref,
                                     uint32_t numWordIds,
                                     uint32_t docIdLimit)
    : search::fakedata::CheckPointCallback(),
      _fieldReader(),
      _namepref(dirprefix + namepref),
      _numWordIds(numWordIds),
      _docIdLimit(docIdLimit),
      _wmap(),
      _dmap(),
      _oldSchema(),
      _schema()
{
    Schema::CollectionType ct(schema::SINGLE);
    _oldSchema.addIndexField(Schema::IndexField("field1",
                                                schema::STRING,
                                                ct));
    _schema.addIndexField(Schema::IndexField("field1",
                                             schema::STRING,
                                             ct));
}


WrappedFieldReader::~WrappedFieldReader(void)
{
}


void
WrappedFieldReader::earlyOpen(void)
{
    TuneFileSeqRead tuneFileRead;
    _fieldReader.reset(new FieldReader());
    _fieldReader->earlyOpen(_namepref, tuneFileRead);
}


void
WrappedFieldReader::lateOpen(void)
{
    TuneFileSeqRead tuneFileRead;
    _wmap.setup(_numWordIds);
    _dmap.setup(_docIdLimit);
    _fieldReader->setup(_wmap, _dmap);
    _fieldReader->lateOpen(_namepref, tuneFileRead);
}


void
WrappedFieldReader::open(void)
{
    earlyOpen();
    lateOpen();
}


void
WrappedFieldReader::close(void)
{
    _fieldReader->close();
    _fieldReader.reset();
}


void
WrappedFieldReader::writeCheckPoint(void)
{
    CheckPointFile chkptfile("chkpt");
    nbostream out;
    _fieldReader->checkPointWrite(out);
    chkptfile.write(out, DummyFileHeaderContext());
}


void
WrappedFieldReader::readCheckPoint(bool first)
{
    CheckPointFile chkptfile("chkpt");
    nbostream in;
    bool openRes = chkptfile.read(in);
    assert(first || openRes);
    (void) first;
    if (!openRes)
        return;
    _fieldReader->checkPointRead(in);
    assert(in.empty());
}


void
WrappedFieldReader::checkPoint(void)
{
    writeCheckPoint();
    _fieldReader.reset();
    earlyOpen();
    readCheckPoint(false);
    lateOpen();
}


void
writeField(FakeWordSet &wordSet,
           uint32_t docIdLimit,
           const std::string &namepref,
           bool dynamicK)
{
    const char *dynamicKStr = dynamicK ? "true" : "false";

    FastOS_Time tv;
    double before;
    double after;

    LOG(info,
        "enter writeField, "
        "namepref=%s, dynamicK=%s",
        namepref.c_str(),
        dynamicKStr);
    tv.SetNow();
    before = tv.Secs();
    WrappedFieldWriter ostate(namepref,
                             dynamicK,
                             wordSet.getNumWords(), docIdLimit);
    FieldWriter::remove(namepref);
    ostate.open();

    unsigned int wordNum = 1;
    uint32_t checkPointCheck = 0;
    uint32_t checkPointInterval = 12227;
    for (unsigned int wc = 0; wc < wordSet._words.size(); ++wc) {
        for (unsigned int wi = 0; wi < wordSet._words[wc].size(); ++wi) {
            FakeWord &fw = *wordSet._words[wc][wi];
            ostate._fieldWriter->newWord(makeWordString(wordNum));
            fw.dump(ostate._fieldWriter, false,
                    checkPointCheck,
                    checkPointInterval,
                    NULL);
            ++wordNum;
        }
    }
    ostate.close();

    tv.SetNow();
    after = tv.Secs();
    LOG(info,
        "leave writeField, "
        "namepref=%s, dynamicK=%s"
        " elapsed=%10.6f",
        namepref.c_str(),
        dynamicKStr,
        after - before);
}


void
writeFieldCheckPointed(FakeWordSet &wordSet,
             uint32_t docIdLimit,
             const std::string &namepref,
             bool dynamicK)
{
    const char *dynamicKStr = dynamicK ? "true" : "false";

    FastOS_Time tv;
    double before;
    double after;
    bool first = true;

    LOG(info,
        "enter writeFieldCheckPointed, "
        "namepref=%s, dynamicK=%s",
        namepref.c_str(),
        dynamicKStr);
    tv.SetNow();
    before = tv.Secs();

    unsigned int wordNum = 1;
    uint32_t checkPointCheck = 0;
    uint32_t checkPointInterval = 12227;
    for (unsigned int wc = 0; wc < wordSet._words.size(); ++wc) {
        for (unsigned int wi = 0; wi < wordSet._words[wc].size(); ++wi) {
            FakeWord &fw = *wordSet._words[wc][wi];

            WrappedFieldWriter ostate(namepref,
                                     dynamicK,
                                     wordSet.getNumWords(), docIdLimit);
            ostate.earlyOpen();
            ostate.readCheckPoint(first);
            first = false;
            ostate.lateOpen();
            ostate._fieldWriter->newWord(makeWordString(wordNum));
            fw.dump(ostate._fieldWriter, false,
                    checkPointCheck,
                    checkPointInterval,
                    &ostate);
            ostate.writeCheckPoint();
            ++wordNum;
        }
    }
    do {
        WrappedFieldWriter ostate(namepref,
                                 dynamicK,
                                 wordSet.getNumWords(), docIdLimit);
        ostate.earlyOpen();
        ostate.readCheckPoint(first);
        ostate.lateOpen();
        ostate.close();
    } while (0);
    CheckPointFile dropper("chkpt");
    dropper.remove();

    tv.SetNow();
    after = tv.Secs();
    LOG(info,
        "leave writeFieldCheckPointed, "
        "namepref=%s, dynamicK=%s"
        " elapsed=%10.6f",
        namepref.c_str(),
        dynamicKStr,
        after - before);
}


void
readField(FakeWordSet &wordSet,
          uint32_t docIdLimit,
          const std::string &namepref,
          bool dynamicK,
          bool verbose)
{
    const char *dynamicKStr = dynamicK ? "true" : "false";

    FastOS_Time tv;
    double before;
    double after;
    WrappedFieldReader istate(namepref, wordSet.getNumWords(),
                             docIdLimit);
    LOG(info,
        "enter readField, "
        "namepref=%s, dynamicK=%s",
        namepref.c_str(),
        dynamicKStr);
    tv.SetNow();
    before = tv.Secs();
    istate.open();
    if (istate._fieldReader->isValid())
        istate._fieldReader->read();

    TermFieldMatchData mdfield1;

    unsigned int wordNum = 1;
    uint32_t checkPointCheck = 0;
    uint32_t checkPointInterval = 12227;
    for (unsigned int wc = 0; wc < wordSet._words.size(); ++wc) {
        for (unsigned int wi = 0; wi < wordSet._words[wc].size(); ++wi) {
            FakeWord &fw = *wordSet._words[wc][wi];

            TermFieldMatchDataArray tfmda;
            tfmda.add(&mdfield1);

            fw.validate(istate._fieldReader, wordNum,
                        tfmda, verbose,
                        checkPointCheck, checkPointInterval, &istate);
            ++wordNum;
        }
    }

    istate.close();
    tv.SetNow();
    after = tv.Secs();
    CheckPointFile dropper("chkpt");
    dropper.remove();
    LOG(info,
        "leave readField, "
        "namepref=%s, dynamicK=%s"
        " elapsed=%10.6f",
        namepref.c_str(),
        dynamicKStr,
        after - before);
}


void
randReadField(FakeWordSet &wordSet,
              const std::string &namepref,
              bool dynamicK,
              bool verbose)
{
    const char *dynamicKStr = dynamicK ? "true" : "false";

    FastOS_Time tv;
    double before;
    double after;
    PostingListCounts counts;

    LOG(info,
        "enter randReadField,"
        " namepref=%s, dynamicK=%s",
        namepref.c_str(),
        dynamicKStr);
    tv.SetNow();
    before = tv.Secs();

    std::string cname = dirprefix + namepref;
    cname += "dictionary";

    std::unique_ptr<search::index::DictionaryFileRandRead> dictFile;
    dictFile.reset(new PageDict4RandRead);

    search::index::PostingListFileRandRead *postingFile = NULL;
    if (dynamicK)
        postingFile =
            new search::diskindex::ZcPosOccRandRead;
    else
        postingFile =
            new search::diskindex::Zc4PosOccRandRead;

    TuneFileSeqRead tuneFileRead;
    TuneFileRandRead tuneFileRandRead;
    bool openCntRes = dictFile->open(cname, tuneFileRandRead);
    assert(openCntRes);
    (void) openCntRes;
    vespalib::string cWord;

    std::string pname = dirprefix + namepref + "posocc.dat";
    pname += ".compressed";
    bool openPostingRes = postingFile->open(pname, tuneFileRandRead);
    assert(openPostingRes);
    (void) openPostingRes;

    for (int loop = 0; loop < 1; ++loop) {
        unsigned int wordNum = 1;
        for (unsigned int wc = 0; wc < wordSet._words.size(); ++wc) {
            for (unsigned int wi = 0; wi < wordSet._words[wc].size(); ++wi) {
                FakeWord &fw = *wordSet._words[wc][wi];

                PostingListOffsetAndCounts offsetAndCounts;
                uint64_t checkWordNum;
                dictFile->lookup(makeWordString(wordNum),
                                 checkWordNum,
                                 offsetAndCounts);
                assert(wordNum == checkWordNum);

                counts = offsetAndCounts._counts;
                search::index::PostingListHandle handle;

                handle._bitLength = counts._bitLength;
                handle._file = postingFile;
                handle._bitOffset = offsetAndCounts._offset;

                postingFile->readPostingList(counts,
                        0,
                        counts._segments.empty() ? 1 : counts._segments.size(),
                        handle);

                TermFieldMatchData mdfield1;
                TermFieldMatchDataArray tfmda;
                tfmda.add(&mdfield1);

                std::unique_ptr<SearchIterator>
                    sb(handle.createIterator(counts, tfmda));

                // LOG(info, "loop=%d, wordNum=%u", loop, wordNum);
                fw.validate(sb.get(), tfmda, verbose);

                sb.reset(handle.createIterator(counts, tfmda));
                fw.validate(sb.get(), tfmda, 19, verbose);

                sb.reset(handle.createIterator(counts, tfmda));
                fw.validate(sb.get(), tfmda, 99, verbose);

                sb.reset(handle.createIterator(counts, tfmda));
                fw.validate(sb.get(), tfmda, 799, verbose);

                sb.reset(handle.createIterator(counts, tfmda));
                fw.validate(sb.get(), tfmda, 6399, verbose);

                sb.reset(handle.createIterator(counts, tfmda));
                fw.validate(sb.get(), tfmda, 11999, verbose);
                ++wordNum;
            }
        }
    }

    postingFile->close();
    dictFile->close();
    delete postingFile;
    dictFile.reset();
    tv.SetNow();
    after = tv.Secs();
    LOG(info,
        "leave randReadField, namepref=%s,"
        " dynamicK=%s, "
        "elapsed=%10.6f",
        namepref.c_str(),
        dynamicKStr,
        after - before);
}


void
fusionField(uint32_t numWordIds,
            uint32_t docIdLimit,
            const vespalib::string &ipref,
            const vespalib::string &opref,
            bool doRaw,
            bool dynamicK)
{
    const char *rawStr = doRaw ? "true" : "false";
    const char *dynamicKStr = dynamicK ? "true" : "false";


    LOG(info,
        "enter fusionField, ipref=%s, opref=%s,"
        " raw=%s,"
        " dynamicK=%s",
        ipref.c_str(),
        opref.c_str(),
        rawStr,
        dynamicKStr);

    FastOS_Time tv;
    double before;
    double after;
    WrappedFieldWriter ostate(opref,
                             dynamicK,
                             numWordIds, docIdLimit);
    WrappedFieldReader istate(ipref, numWordIds, docIdLimit);

    tv.SetNow();
    before = tv.Secs();

    ostate.open();
    istate.open();

    if (doRaw) {
        PostingListParams featureParams;
        featureParams.clear();
        featureParams.set("cooked", false);
        istate._fieldReader->setFeatureParams(featureParams);
    }
    if (istate._fieldReader->isValid())
        istate._fieldReader->read();

    while (istate._fieldReader->isValid()) {
        istate._fieldReader->write(*ostate._fieldWriter);
        istate._fieldReader->read();
    }
    istate.close();
    ostate.close();
    tv.SetNow();
    after = tv.Secs();
    LOG(info,
        "leave fusionField, ipref=%s, opref=%s,"
        " raw=%s dynamicK=%s, "
        " elapsed=%10.6f",
        ipref.c_str(),
        opref.c_str(),
        rawStr,
        dynamicKStr,
        after - before);
}


void
testFieldWriterVariants(FakeWordSet &wordSet,
                        uint32_t docIdLimit, bool verbose)
{
    CheckPointFile dropper("chkpt");
    dropper.remove();
    disableSkip();
    writeField(wordSet, docIdLimit, "new4", true);
    readField(wordSet, docIdLimit, "new4", true, verbose);
    readField(wordSet, docIdLimit, "new4", true, verbose);
    writeFieldCheckPointed(wordSet, docIdLimit, "new6", true);
    writeField(wordSet, docIdLimit, "new5", false);
    readField(wordSet, docIdLimit, "new5", false, verbose);
    writeFieldCheckPointed(wordSet, docIdLimit, "new7", false);
    enableSkip();
    writeField(wordSet, docIdLimit, "newskip4", true);
    readField(wordSet, docIdLimit, "newskip4", true, verbose);
    writeFieldCheckPointed(wordSet, docIdLimit, "newskip6",
                                      true);
    writeField(wordSet, docIdLimit, "newskip5", false);
    readField(wordSet, docIdLimit, "newskip5", false, verbose);
    writeFieldCheckPointed(wordSet, docIdLimit, "newskip7",
                                      false);
    enableSkipChunks();
    writeField(wordSet, docIdLimit, "newchunk4", true);
    readField(wordSet, docIdLimit, "newchunk4", true, verbose);
    writeFieldCheckPointed(wordSet, docIdLimit, "newchunk6",
                                      true);
    writeField(wordSet, docIdLimit, "newchunk5", false);
    readField(wordSet, docIdLimit,
                "newchunk5",false, verbose);
    writeFieldCheckPointed(wordSet, docIdLimit, "newchunk7",
                                      false);
    disableSkip();
    fusionField(wordSet.getNumWords(),
                docIdLimit,
                "new4", "new4x",
                false, true);
    fusionField(wordSet.getNumWords(),
                docIdLimit,
                "new4", "new4xx",
                true, true);
    fusionField(wordSet.getNumWords(),
                docIdLimit,
                "new5", "new5x",
                false, false);
    fusionField(wordSet.getNumWords(),
                docIdLimit,
                "new5", "new5xx",
                true, false);
    randReadField(wordSet, "new4", true, verbose);
    randReadField(wordSet, "new5", false, verbose);
    enableSkip();
    fusionField(wordSet.getNumWords(),
                docIdLimit,
                "newskip4", "newskip4x",
                false, true);
    fusionField(wordSet.getNumWords(),
                docIdLimit,
                "newskip4", "newskip4xx",
                true, true);
    fusionField(wordSet.getNumWords(),
                docIdLimit,
                "newskip5", "newskip5x",
                false, false);
    fusionField(wordSet.getNumWords(),
                docIdLimit,
                "newskip5", "newskip5xx",
                true, false);
    randReadField(wordSet, "newskip4", true,  verbose);
    randReadField(wordSet, "newskip5", false, verbose);
    enableSkipChunks();
    fusionField(wordSet.getNumWords(),
                           docIdLimit,
                           "newchunk4", "newchunk4x",
                           false, true);
    fusionField(wordSet.getNumWords(),
                docIdLimit,
                "newchunk4", "newchunk4xx",
                true, true);
    fusionField(wordSet.getNumWords(),
                docIdLimit,
                "newchunk5", "newchunk5x",
                false, false);
    fusionField(wordSet.getNumWords(),
                docIdLimit,
                "newchunk5", "newchunk5xx",
                true, false);
    randReadField(wordSet, "newchunk4", true, verbose);
    randReadField(wordSet, "newchunk5", false, verbose);
}


void
testFieldWriterVariantsWithHighLids(FakeWordSet &wordSet, uint32_t docIdLimit,
                             bool verbose)
{
    CheckPointFile dropper("chkpt");
    dropper.remove();
    disableSkip();
    writeField(wordSet, docIdLimit, "hlid4", true);
    readField(wordSet, docIdLimit, "hlid4", true, verbose);
    writeField(wordSet, docIdLimit, "hlid5", false);
    readField(wordSet, docIdLimit, "hlid5", false, verbose);
    randReadField(wordSet, "hlid4", true, verbose);
    randReadField(wordSet, "hlid5", false, verbose);
    enableSkip();
    writeField(wordSet, docIdLimit, "hlidskip4", true);
    readField(wordSet, docIdLimit, "hlidskip4", true, verbose);
    writeField(wordSet, docIdLimit, "hlidskip5", false);
    readField(wordSet, docIdLimit, "hlidskip5", false, verbose);
    randReadField(wordSet, "hlidskip4", true, verbose);
    randReadField(wordSet, "hlidskip5", false, verbose);
    enableSkipChunks();
    writeField(wordSet, docIdLimit, "hlidchunk4", true);
    readField(wordSet, docIdLimit, "hlidchunk4", true, verbose);
    writeField(wordSet, docIdLimit, "hlidchunk5", false);
    readField(wordSet, docIdLimit, "hlidchunk5", false, verbose);
    randReadField(wordSet, "hlidchunk4", true, verbose);
    randReadField(wordSet, "hlidchunk5", false, verbose);
}

int
FieldWriterTest::Main(void)
{
    int argi;
    char c;
    const char *optArg;

    if (_argc > 0) {
        DummyFileHeaderContext::setCreator(_argv[0]);
    }
    argi = 1;

    while ((c = GetOpt("c:d:vw:", optArg, argi)) != -1) {
        switch(c) {
        case 'c':
            _commonDocFreq = atoi(optArg);
            if (_commonDocFreq == 0)
                _commonDocFreq = 1;
            break;
        case 'd':
            _numDocs = atoi(optArg);
            break;
        case 'v':
            _verbose = true;
            break;
        case 'w':
            _numWordsPerClass = atoi(optArg);
            break;
        default:
            Usage();
            return 1;
        }
    }

    if (_commonDocFreq > _numDocs) {
        Usage();
        return 1;
    }

    _wordSet.setupParams(false, false);
    _wordSet.setupWords(_rnd, _numDocs, _commonDocFreq, _numWordsPerClass);

    vespalib::mkdir("index", false);
    testFieldWriterVariants(_wordSet, _numDocs, _verbose);

    _wordSet2.setupParams(false, false);
    _wordSet2.setupWords(_rnd, _numDocs, _commonDocFreq, 3);
    uint32_t docIdBias = 700000000;
    _wordSet2.addDocIdBias(docIdBias);	// Large skip numbers
    testFieldWriterVariantsWithHighLids(_wordSet2, _numDocs + docIdBias,
                                        _verbose);
    return 0;
}

} // namespace fieldwriter

int
main(int argc, char **argv)
{
    fieldwriter::FieldWriterTest app;

    setvbuf(stdout, NULL, _IOLBF, 32768);
    app._rnd.srand48(32);
    return app.Entry(argc, argv);
}
