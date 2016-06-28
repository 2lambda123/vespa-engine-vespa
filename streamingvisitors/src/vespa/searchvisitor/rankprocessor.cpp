// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".searchvisitor.rankprocessor");
#include <vespa/searchlib/fef/handle.h>
#include <vespa/searchlib/fef/simpletermfielddata.h>
#include <vespa/vsm/vsm/fieldsearchspec.h>
#include "querytermdata.h"
#include "rankprocessor.h"
#include <cmath>

using search::FeatureSet;
using search::HitList;
using search::Query;
using search::QueryTerm;
using search::QueryTermList;
using search::fef::FeatureHandle;
using search::fef::MatchData;
using search::fef::Properties;
using search::fef::RankProgram;
using search::fef::RankSetup;
using search::fef::IllegalHandle;
using search::fef::ITermData;
using search::fef::ITermFieldData;
using search::fef::TermFieldHandle;
using search::fef::TermFieldMatchData;
using search::fef::TermFieldMatchDataPosition;
using vdslib::SearchResult;

namespace storage {

namespace {

vespalib::string
getIndexName(const vespalib::string & indexName, const vespalib::string & expandedIndexName)
{
    if (indexName == expandedIndexName) {
        return indexName;
    }
    return indexName + "(" + expandedIndexName + ")";
}

FeatureHandle
getFeatureHandle(const RankProgram &rankProgram) {
    std::vector<vespalib::string> featureNames;
    std::vector<FeatureHandle> featureHandles;
    rankProgram.get_seed_handles(featureNames, featureHandles);
    assert(featureNames.size() == 1);
    assert(featureHandles.size() == 1);
    return featureHandles.front();
}

}

void
RankProcessor::initQueryEnvironment()
{
    QueryWrapper::TermList & terms = _query.getTermList();

    for (uint32_t i = 0; i < terms.size(); ++i) {
        if (!terms[i].isPhraseTerm() || terms[i].isFirstPhraseTerm()) { // register 1 term data per phrase
            QueryTermData & qtd = dynamic_cast<QueryTermData &>(terms[i].getTerm()->getQueryItem());

            qtd.getTermData().setWeight(terms[i].getTerm()->weight());
            qtd.getTermData().setUniqueId(terms[i].getTerm()->uniqueId());
            if (terms[i].isFirstPhraseTerm()) {
                qtd.getTermData().setPhraseLength(terms[i].getParent()->width());
            } else {
                qtd.getTermData().setPhraseLength(1);
            }

            vespalib::string expandedIndexName = vsm::FieldSearchSpecMap::stripNonFields(terms[i].getTerm()->index());
            const RankManager::View *view = _rankManagerSnapshot->getView(expandedIndexName);
            if (view != NULL) {
                RankManager::View::const_iterator iter = view->begin();
                RankManager::View::const_iterator endp = view->end();
                for (; iter != endp; ++iter) {
                    qtd.getTermData().addField(*iter).setHandle(_mdLayout.allocTermField(*iter));
                }
            } else {
                LOG(warning, "Could not find a view for index '%s'. Ranking no fields.",
                    getIndexName(terms[i].getTerm()->index(), expandedIndexName).c_str());
            }

            LOG(debug, "Setup query term '%s:%s' (%s)",
                getIndexName(terms[i].getTerm()->index(), expandedIndexName).c_str(),
                terms[i].getTerm()->getTerm(),
                terms[i].isFirstPhraseTerm() ? "phrase" : "term");
            _queryEnv.addTerm(&qtd.getTermData());
        } else {
            LOG(debug, "Ignore query term '%s:%s' (part of phrase)",
                terms[i].getTerm()->index().c_str(), terms[i].getTerm()->getTerm());
        }
    }
}

void
RankProcessor::initHitCollector(size_t wantedHitCount)
{
    _hitCollector.reset(new HitCollector(wantedHitCount));
}

void
RankProcessor::setupRankProgram(RankProgram &program)
{
    program.setup(_mdLayout, _queryEnv, search::fef::Properties());
}

void
RankProcessor::init(bool forRanking, size_t wantedHitCount)
{
    initQueryEnvironment();
    if (forRanking) {
        if (_rankSetup.getSecondPhaseRank().empty()) {
            _rankProgram = _rankSetup.create_first_phase_program();
        } else {
            // We calculate 2. phase ranking for all hits (no need calculating 1. phase ranking as well)
            _rankProgram = _rankSetup.create_second_phase_program();
        }
        setupRankProgram(*_rankProgram);
        _rankScoreHandle = getFeatureHandle(*_rankProgram);
        _summaryProgram = _rankSetup.create_summary_program();
        setupRankProgram(*_summaryProgram);
    } else {
        _rankProgram = _rankSetup.create_dump_program();
        setupRankProgram(*_rankProgram);
    }
    initHitCollector(wantedHitCount);
}

RankProcessor::RankProcessor(RankManager::Snapshot::SP snapshot,
                             const vespalib::string &rankProfile,
                             search::Query & query,
                             const vespalib::string & location,
                             Properties & queryProperties,
                             const search::IAttributeManager * attrMgr) :

    _rankManagerSnapshot(snapshot),
    _rankSetup(snapshot->getRankSetup(rankProfile)),
    _query(query),
    _queryEnv(location, snapshot->getIndexEnvironment(rankProfile), queryProperties, attrMgr),
    _mdLayout(),
    _rankProgram(),
    _score(0.0),
    _summaryProgram(),
    _rankScoreHandle(IllegalHandle),
    _hitCollector()
{
}

void
RankProcessor::initForRanking(size_t wantedHitCount)
{
    return init(true, wantedHitCount);
}

void
RankProcessor::initForDumping(size_t wantedHitCount)
{
    return init(false, wantedHitCount);
}

void
RankProcessor::runRankProgram(uint32_t docId)
{
    _rankProgram->run(docId);
    if (_rankScoreHandle != IllegalHandle) {
        MatchData &matchData = _rankProgram->match_data();
        _score = *(matchData.resolveFeature(_rankScoreHandle));
        if (std::isnan(_score) || std::isinf(_score)) {
            _score = -HUGE_VAL;
        }
    }
}

namespace {

void
copyTermFieldMatchData(const std::vector<search::fef::TermFieldMatchData> &src, MatchData &dst)
{
    assert(src.size() == dst.getNumTermFields());
    for (search::fef::TermFieldHandle handle = 0; handle < dst.getNumTermFields(); ++handle) {
        (*dst.resolveTermField(handle)) = src[handle];
    }
}

class RankProgramWrapper : public HitCollector::IRankProgram
{
private:
    RankProgram &_rankProgram;

public:
    RankProgramWrapper(RankProgram &rankProgram) : _rankProgram(rankProgram) {}
    virtual const MatchData &run(uint32_t docid, const std::vector<search::fef::TermFieldMatchData> &matchData) override {
        // Prepare the match data object used by the rank program with earlier unpacked match data.
        copyTermFieldMatchData(matchData, _rankProgram.match_data());
        _rankProgram.run(docid);
        return _rankProgram.match_data();
    }
};

}

FeatureSet::SP
RankProcessor::calculateFeatureSet()
{
    LOG(debug, "Calculate feature set");
    std::vector<vespalib::string> names;
    std::vector<FeatureHandle> handles;
    RankProgram &rankProgram = *(_summaryProgram.get() != nullptr ? _summaryProgram : _rankProgram);
    rankProgram.get_seed_handles(names, handles);
    LOG(debug, "Feature handles: numNames(%ld), numHandles(%ld)", names.size(), handles.size());
    RankProgramWrapper wrapper(rankProgram);
    FeatureSet::SP sf = _hitCollector->getFeatureSet(wrapper, names, handles);
    LOG(debug, "Feature set: numFeatures(%u), numDocs(%u)", sf->numFeatures(), sf->numDocs());
    return sf;
}

void
RankProcessor::fillSearchResult(vdslib::SearchResult & searchResult)
{
    _hitCollector->fillSearchResult(searchResult);
}

void
RankProcessor::unpackMatchData(uint32_t docId)
{
    MatchData &matchData = _rankProgram->match_data();
    matchData.setDocId(docId);
    unpackMatchData(matchData);
}

void
RankProcessor::unpackMatchData(MatchData &matchData)
{
    QueryWrapper::TermList & terms = _query.getTermList();
    for (uint32_t i = 0; i < terms.size(); ++i) {
        if (!terms[i].isPhraseTerm() || terms[i].isFirstPhraseTerm()) { // consider 1 term data per phrase
            bool isPhrase = terms[i].isFirstPhraseTerm();
            QueryTermData & qtd = static_cast<QueryTermData &>(terms[i].getTerm()->getQueryItem());
            const ITermData &td = qtd.getTermData();

            HitList list;
            const HitList & hitList = isPhrase ?
                terms[i].getParent()->evaluateHits(list) : terms[i].getTerm()->evaluateHits(list);

            if (hitList.size() > 0) { // only unpack if we have a hit
                LOG(debug, "Unpack match data for query term '%s:%s' (%s)",
                    terms[i].getTerm()->index().c_str(), terms[i].getTerm()->getTerm(),
                    isPhrase ? "phrase" : "term");

                uint32_t lastFieldId = -1;
                TermFieldMatchData *tmd = 0;
                uint32_t fieldLen = search::fef::FieldPositionsIterator::UNKNOWN_LENGTH;

                // optimize for hitlist giving all hits for a single field in one chunk
                for (const search::Hit & hit : hitList) {
                    uint32_t fieldId = hit.context();

                    if (fieldId != lastFieldId) {
                        // reset to notfound/unknown values
                        tmd = 0;
                        fieldLen = search::fef::FieldPositionsIterator::UNKNOWN_LENGTH;

                        // setup for new field that had a hit
                        const ITermFieldData *tfd = td.lookupField(fieldId);
                        if (tfd != 0) {
                            tmd = matchData.resolveTermField(tfd->getHandle());
                            tmd->setFieldId(fieldId);
                            // reset field match data, but only once per docId
                            if (tmd->getDocId() != matchData.getDocId()) {
                                tmd->reset(matchData.getDocId());
                            }
                        }
                        // find fieldLen for new field
                        if (isPhrase) {
                            if (fieldId < terms[i].getParent()->getFieldInfoSize()) {
                                const QueryTerm::FieldInfo & fi = terms[i].getParent()->getFieldInfo(fieldId);
                                fieldLen = fi.getFieldLength();
                            }
                        } else {
                            if (fieldId < terms[i].getTerm()->getFieldInfoSize()) {
                                const QueryTerm::FieldInfo & fi = terms[i].getTerm()->getFieldInfo(fieldId);
                                fieldLen = fi.getFieldLength();
                            }
                        }
                        lastFieldId = fieldId;
                    }
                    if (tmd != 0) {
                        // adjust so that the position for phrase terms equals the match for the first term
                        TermFieldMatchDataPosition pos(0, hit.wordpos() - terms[i].getPosAdjust(),
                                                       hit.weight(), fieldLen);
                        tmd->appendPosition(pos);
                        LOG(debug, "Append position(%u), weight(%d), tfmd.weight(%d)",
                                   pos.getPosition(), pos.getElementWeight(), tmd->getWeight());
                    }
                }
            }
        }
    }
}

} // namespace storage

