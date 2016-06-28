// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".features.fieldmatch.computer");

#include <iostream>
#include <cstdlib>
#include <set>
#include <vespa/searchlib/features/utils.h>
#include <vespa/searchlib/fef/properties.h>
#include <vespa/vespalib/util/stringfmt.h>
#include "computer.h"

using namespace search::fef;

namespace search {
namespace features {
namespace fieldmatch {


Computer::Computer(const vespalib::string &propertyNamespace, const PhraseSplitter &splitter,
                   const FieldInfo &fieldInfo, const Params &params) :
    _splitter(splitter),
    _fieldId(fieldInfo.id()),
    _params(params),
    _tracing(false),
    _trace(),
    _useCachedHits(true),
    _queryTerms(),
    _queryTermFieldMatch(),
    _totalTermWeight(0),
    _totalTermSignificance(0.0f),
    _match(NULL),
    _fieldLength(FieldPositionsIterator::UNKNOWN_LENGTH),
    _currentMetrics(this),
    _finalMetrics(this),
    _simpleMetrics(params),
    _segments(),
    _alternativeSegmentationsTried(0),
    _cachedHits()
{
    // Store term data for all terms searching in this field
    for (uint32_t i = 0; i < splitter.getNumTerms(); ++i) {
        QueryTerm qt = QueryTermFactory::create(splitter, i, true, true);
        _totalTermWeight += qt.termData()->getWeight().percent();
        _totalTermSignificance += qt.significance();
        _simpleMetrics.addQueryTerm(qt.termData()->getWeight().percent());
        const ITermFieldData *field = qt.termData()->lookupField(_fieldId);
        if (field != 0) {
            qt.fieldHandle(field->getHandle());
            _queryTerms.push_back(qt);
            _simpleMetrics.addSearchedTerm(qt.termData()->getWeight().percent());
            _queryTermFieldMatch.push_back(NULL);
            _cachedHits.push_back(BitVectorData());
        }
    }

    _totalTermWeight = atoi(splitter.getProperties().lookup(propertyNamespace, "totalTermWeight").
                            get(vespalib::make_string("%d", _totalTermWeight)).c_str());
    _totalTermSignificance = atof(splitter.getProperties().lookup(propertyNamespace, "totalTermSignificance").
                                  get(vespalib::make_string("%f", _totalTermSignificance)).c_str());
    if (splitter.getProperties().lookup(propertyNamespace, "totalTermWeight").found()) {
        _simpleMetrics.setTotalWeightInQuery(_totalTermWeight);
    }

    // update current and final metrics after initialization
    _currentMetrics = Metrics(this);
    _finalMetrics = Metrics(this);

    // num query terms searching in this field + 1
    for (uint32_t i = 0; i < (getNumQueryTerms() + 1); ++i) {
        _segments.push_back(SegmentData(SegmentStart::SP(new SegmentStart(this, _currentMetrics))));
    }
}

void
Computer::reset(const MatchData & match)
{
    _currentMetrics.reset();
    _finalMetrics.reset();
    _simpleMetrics.resetMatchData();
    for (uint32_t i = 0; i < _segments.size(); ++i) {
        if (_segments[i].valid) {
            _segments[i].valid = false;
        }
    }
    _alternativeSegmentationsTried = 0;
    for (uint32_t i = 0; i < _cachedHits.size(); ++i) {
        if (_cachedHits[i].valid) {
            _cachedHits[i].valid = false;
        }
    }

    _match = &match;
    _fieldLength = FieldPositionsIterator::UNKNOWN_LENGTH;

    for (uint32_t i = 0; i < _queryTerms.size(); ++i) {
        const ITermData *td = _queryTerms[i].termData();
        const TermFieldMatchData *tfmd = _splitter.resolveTermField(_queryTerms[i].fieldHandle());
        if (tfmd->getDocId() != match.getDocId()) { // only term match data if we have a hit
            tfmd = NULL;
        } else {
            FieldPositionsIterator it = tfmd->getIterator();
            uint32_t fieldLength = it.getFieldLength();
            if (it.valid()) {
                _simpleMetrics.addMatchWithPosOcc(td->getWeight().percent());
                if (fieldLength == 0 || fieldLength == FieldPositionsIterator::UNKNOWN_LENGTH) {
                    _simpleMetrics.hasMatchWithInvalidFieldLength();
                }
            } else {
                _simpleMetrics.addMatch(td->getWeight().percent());
            }
            if (_fieldLength == FieldPositionsIterator::UNKNOWN_LENGTH) {
                _fieldLength = fieldLength; // save away the first valid field length
            }

            if (_useCachedHits && it.valid() && fieldLength != FieldPositionsIterator::UNKNOWN_LENGTH) {
                // cache the field position iterator in a bit vector for faster lookup in
                // findClosestInFieldBySemanticDistance()
                _cachedHits[i].bitvector.clear();
                _cachedHits[i].valid = true;
                if (_cachedHits[i].bitvector.size() < _fieldLength) {
                    _cachedHits[i].bitvector.resize(_fieldLength);
                }
                for (; it.valid(); it.next()) {
                    uint32_t fieldPos = it.getPosition();
                    if (__builtin_expect(fieldPos < _fieldLength, true))
                        _cachedHits[i].bitvector.setBit(fieldPos);
                    else {
                        handleError(fieldPos, match.getDocId());
                    }
                }
            }
        }
        _queryTermFieldMatch[i] = tfmd;
    }
}

void
Computer::handleError(uint32_t fieldPos, uint32_t docId) const
{
    static int errcnt;
    if (errcnt < 1000) {
        errcnt++;
        const FieldInfo * finfo = _splitter.getIndexEnvironment().getField(getFieldId());
        LOG(debug, "Bad field position %u >= fieldLength %u for field '%s' document %u. "
                   "Document was probably refed during query (Ticket 7104969)",
                   fieldPos, _fieldLength,
                   finfo != NULL ?  finfo->name().c_str() : "unknown field",
                   docId);
    }
}

const Metrics &
Computer::run()
{
    exploreSegments();
    return _finalMetrics;
}

int
Computer::findClosestInFieldBySemanticDistance(int i, int previousJ, uint32_t startSemanticDistance)
{
    if (_useCachedHits) {
        if (!_cachedHits[i].valid) {
            return -1; // not matched
        }

        const BitVector & hits = _cachedHits[i].bitvector;

        for (uint32_t distance = startSemanticDistance; distance < _fieldLength; distance++) {
            int j = semanticDistanceToFieldIndex(distance, previousJ);
            if (j < 0) {
                continue;
            }

            if (hits.testBit((uint32_t)j)) {
                return distance;
            }
        }
        return -1;
    }

    const TermFieldMatchData *termFieldMatch = _queryTermFieldMatch[i];
    if (termFieldMatch == NULL) {
        return -1; // not matched
    }

    for (uint32_t distance = startSemanticDistance; distance < _fieldLength; distance++) {
        int j = semanticDistanceToFieldIndex(distance, previousJ);
        if (j < 0) {
            continue;
        }

        FieldPositionsIterator it = termFieldMatch->getIterator();
        while (it.valid() && it.getPosition() < (uint32_t)j) {
            it.next();
        }
        if (it.valid() && it.getPosition() == (uint32_t)j) {
            return distance;
        }
    }
    return -1;
}

int
Computer::semanticDistanceToFieldIndex(int semanticDistance, uint32_t zeroJ) const
{
    if (semanticDistance == -1) {
        return -1;
    }
    int firstSegmentLength = std::min(_params.getProximityLimit(), _fieldLength - zeroJ);
    int secondSegmentLength = std::min(_params.getProximityLimit(), zeroJ);
    if (semanticDistance < firstSegmentLength) {
        return zeroJ + semanticDistance;
    }
    else if (semanticDistance < firstSegmentLength + secondSegmentLength) {
        return zeroJ - semanticDistance - 1 + firstSegmentLength;
    }
    else if ((uint32_t)semanticDistance < _fieldLength - zeroJ + secondSegmentLength) {
        return zeroJ + semanticDistance - secondSegmentLength;
    }
    else {
        return _fieldLength - semanticDistance - 1;
    }
}

int
Computer::fieldIndexToSemanticDistance(int j, uint32_t zeroJ) const
{
    if (j == -1) {
        return -1;
    }
    uint32_t firstSegmentLength = std::min(_params.getProximityLimit(), _fieldLength - zeroJ);
    uint32_t secondSegmentLength = std::min(_params.getProximityLimit(), zeroJ);
    if ((uint32_t)j >= zeroJ) {
        if ((j - zeroJ) < firstSegmentLength) {
            return j - zeroJ; // 0..limit
        }
        else {
            return j - zeroJ + secondSegmentLength; // limit*2..field.length-zeroJ
        }
    }
    else {
        if ((zeroJ - j - 1) < secondSegmentLength) {
            return zeroJ - j + firstSegmentLength - 1; // limit..limit*2
        }
        else {
            return (zeroJ - j - 1) + _fieldLength - zeroJ; // field.length-zeroJ..
        }
    }
}

Computer &
Computer::trace(const vespalib::string &str)
{
    if (_tracing) {
        _trace.push_back(str);
        //LOG(info, "%s", str.c_str());
    }
    return *this;
}

vespalib::string
Computer::getTrace() const
{
    vespalib::string ret = "";
    for (std::vector<vespalib::string>::const_iterator it = _trace.begin();
         it != _trace.end(); ++it) {
        ret += *it;
    }
    return ret;
}

vespalib::string
Computer::toString() const
{
    return vespalib::make_string("Computer(%d query terms,%d field terms,%s)",
                                 getNumQueryTerms(), _fieldLength,
                                 _currentMetrics.toString().c_str());
}

void
Computer::exploreSegments()
{
    if (isTracing()) {
        trace(vespalib::make_string("Calculating matches for %d query terms, %d field terms.",
                                    getNumQueryTerms(), _fieldLength));
    }

    _segments[0].segment->reset(_currentMetrics);
    _segments[0].valid = true;
    SegmentStart *segment = _segments[0].segment.get();
    while (segment != NULL) {
        if (isTracing()) {
            trace(vespalib::make_string("Looking for segment from %s...",
                                        segment->toString().c_str()));
        }

        _currentMetrics = segment->getMetrics(); // take a copy of the segment returned from the current segment.
        bool found = findAlternativeSegmentFrom(segment);
        if (found) {
            if (isTracing()) {
                vespalib::string segments = "[ ";
                const std::vector<uint32_t> &lst = _currentMetrics.getSegmentStarts();
                for (uint32_t i = 0; i < lst.size(); ++i) {
                    segments += vespalib::make_string("%d", lst[i]);
                    if (i < lst.size() - 1) {
                        segments += ", ";
                    }
                }
                segments += " ]";
                trace(vespalib::make_string("...found segments: %s, score %f.",
                                            segments.c_str(),
                                            _currentMetrics.getSegmentationScore()));
            }
        } else {
            if (isTracing()) {
                trace("...no complete and improved segment existed.");
            }
            segment->setOpen(false);
        }
        segment = findOpenSegment(segment->getI());
    }
    _finalMetrics = findLastStartPoint()->getMetrics();
    setOccurrenceCounts(_finalMetrics);
    _finalMetrics.onComplete();
    _finalMetrics.setComplete(true);
}

bool
Computer::findAlternativeSegmentFrom(SegmentStart *segment) {
    int semanticDistanceExplored = segment->getSemanticDistanceExplored();
    int previousI = -1;
    int previousJ = segment->getPreviousJ();
    bool hasOpenSequence = false;
    bool isFirst = true;
    for (uint32_t i = segment->getStartI(); i < getNumQueryTerms(); i++) {
        int semanticDistance = findClosestInFieldBySemanticDistance(i, previousJ, semanticDistanceExplored);
        int j = semanticDistanceToFieldIndex(semanticDistance, previousJ);

        if (j == -1 && semanticDistanceExplored > 0 && isFirst) {
            return false; // segment explored before; no more matches found
        }
        if (hasOpenSequence && (j == -1 || j != previousJ + 1)) {
            _currentMetrics.onSequenceEnd(previousJ);
            hasOpenSequence = false;
        }
        if (isFirst) {
            if (j != -1) {
                segmentStart(i, j, isFirst ? -1 : previousJ);
                segment->exploredTo(j);
                isFirst = false;
            }
            else {
                segment->incrementStartI(); // there are no matches for this i
            }
        }
        else {
            if ((unsigned int)std::abs(j - previousJ) >= _params.getProximityLimit()) {
                segmentEnd(i - 1, previousJ);
                return true;
            }
            else if (j != -1) {
                inSegment(i, j, previousJ, previousI);
            }
        }
        if (j != -1) {
            _currentMetrics.onMatch(i);
            if (!hasOpenSequence) {
                _currentMetrics.onSequenceStart(j);
                hasOpenSequence=true;
            }
            semanticDistanceExplored = 1; // skip the current match when looking for the next
        } else {
            semanticDistanceExplored = 0;
            // we have a match for this term but no position information
            if (_queryTermFieldMatch[i] != NULL && !_cachedHits[i].valid) {
                _currentMetrics.onMatch(i);
            }
        }
        if (j >= 0) {
            previousI = i;
            previousJ = j;
        }
    }
    if (hasOpenSequence) {
        _currentMetrics.onSequenceEnd(previousJ);
    }
    if (!isFirst) {
        segmentEnd(getNumQueryTerms() - 1, previousJ);
        return true;
    }
    else {
        return false;
    }
}

void
Computer::inSegment(int i, int j, int previousJ, int previousI)
{
    _currentMetrics.onPair(i, j, previousJ);
    if (j == previousJ + 1 && i == previousI + 1) {
        _currentMetrics.onInSequence(i, j, previousJ);
    }
    else {
        _currentMetrics.onInSegmentGap(i, j, previousJ);
        if (isTracing()) {
            trace(vespalib::make_string("      in segment gap: %d -> %d", i, j));
        }
    }
}

bool
Computer::segmentStart(int i, int j, int previousJ)
{
    _currentMetrics.onNewSegment(i, j, previousJ);
    if (previousJ >= 0) {
        _currentMetrics.onPair(i, j, previousJ);
    }
    if (isTracing()) {
        trace(vespalib::make_string("    new segment at: %d -> %d", i, j));
    }
    return true;
}

void
Computer::segmentEnd(int i, int j)
{
    if (isTracing()) {
        trace(vespalib::make_string("    segment ended at: %d -> %d", i, j));
    }
    SegmentStart *startOfNext = _segments[i + 1].segment.get();
    if (!_segments[i + 1].valid) {
        startOfNext->reset(_currentMetrics, j, i + 1);
        _segments[i + 1].valid = true;
    }
    else {
        startOfNext->offerHistory(j, _currentMetrics);
    }
}

SegmentStart *
Computer::findOpenSegment(uint32_t startI) {
    for (uint32_t i = startI; i < _segments.size(); i++) {
        SegmentStart *startPoint = _segments[i].valid ? _segments[i].segment.get() : NULL;
        if (startPoint == NULL || !startPoint->isOpen()) {
            continue;
        }
        if (startPoint->getSemanticDistanceExplored() == 0) {
            return startPoint; // first attempt
        }
        if (_alternativeSegmentationsTried >= _params.getMaxAlternativeSegmentations()) {
            continue;
        }
        _alternativeSegmentationsTried++;
        return startPoint;
    }
    return NULL;
}

SegmentStart *
Computer::findLastStartPoint()
{
    for (int i = _segments.size(); --i >= 0; ) {
        SegmentStart *startPoint = _segments[i].valid ? _segments[i].segment.get() : NULL;
        if (startPoint != NULL) {
            return startPoint;
        }
    }
    LOG(error, "findLastStartPoint() could not find any segment start. This should never happen!");
    return NULL;
}

void
Computer::setOccurrenceCounts(Metrics &metrics)
{
    // Find all unique query terms.
    std::vector<uint32_t> uniqueTerms;
    std::set<uint32_t> firstOccs;
    for (uint32_t i = 0; i < _queryTermFieldMatch.size(); ++i) {
        const TermFieldMatchData *termFieldMatch = _queryTermFieldMatch[i];
        if (termFieldMatch == NULL) {
            continue; // not for this match
        }
        FieldPositionsIterator it = termFieldMatch->getIterator();
        if (it.valid()) {
            if (firstOccs.find(it.getPosition()) == firstOccs.end()) {
                uniqueTerms.push_back(i);
                firstOccs.insert(it.getPosition());
            }
        }
    }

    // Commence occurence logic.
    std::vector<feature_t> weightedOccurrences;
    std::vector<feature_t> significantOccurrences;

    uint32_t divider = std::min(_fieldLength, (uint32_t)(_params.getMaxOccurrences() * uniqueTerms.size()));
    uint32_t maxOccurence = std::min(_fieldLength, _params.getMaxOccurrences());

    feature_t occurrence = 0;
    feature_t absoluteOccurrence = 0;
    feature_t weightedAbsoluteOccurrence = 0;
    int totalWeight = 0;
    feature_t totalWeightedOccurrences = 0;
    feature_t totalSignificantOccurrences = 0;

    for (std::vector<uint32_t>::iterator it = uniqueTerms.begin();
         it != uniqueTerms.end(); ++it)
    {
        const QueryTerm &queryTerm = _queryTerms[*it];
        const ITermData &termData = *queryTerm.termData();
        const TermFieldMatchData &termFieldMatch = *_queryTermFieldMatch[*it];

        uint32_t termOccurrences = 0;
        FieldPositionsIterator pos = termFieldMatch.getIterator();
        while (pos.valid() && termOccurrences < _params.getMaxOccurrences()) {
            termOccurrences++;
            pos.next();
        }

        occurrence += (feature_t)termOccurrences / divider;
        absoluteOccurrence += (feature_t)termOccurrences / (_params.getMaxOccurrences() * uniqueTerms.size());

        weightedAbsoluteOccurrence += (feature_t)termOccurrences * termData.getWeight().percent() / _params.getMaxOccurrences();
        totalWeight += termData.getWeight().percent();

        totalWeightedOccurrences += (feature_t)maxOccurence * termData.getWeight().percent() / divider;
        weightedOccurrences.push_back((feature_t)termOccurrences * termData.getWeight().percent() / divider);

        totalSignificantOccurrences += (feature_t)maxOccurence * queryTerm.significance() / divider;
        significantOccurrences.push_back((feature_t)termOccurrences * queryTerm.significance() / divider);
    }
    metrics.setOccurrence(occurrence);
    metrics.setAbsoluteOccurrence(absoluteOccurrence);
    metrics.setWeightedAbsoluteOccurrence(weightedAbsoluteOccurrence / (totalWeight > 0 ? totalWeight : 1));

    feature_t weightedOccurrenceSum = 0;
    for (std::vector<feature_t>::iterator it = weightedOccurrences.begin();
         it != weightedOccurrences.end(); ++it)
    {
        weightedOccurrenceSum += totalWeightedOccurrences > 0.0f ? *it / totalWeightedOccurrences : 0.0f;
    }
    metrics.setWeightedOccurrence(weightedOccurrenceSum);

    feature_t significantOccurrenceSum = 0;
    for (std::vector<feature_t>::iterator it = significantOccurrences.begin();
         it != significantOccurrences.end(); ++it)
    {
        significantOccurrenceSum += totalSignificantOccurrences > 0.0f ? *it / totalSignificantOccurrences : 0.0f;
    }
    metrics.setSignificantOccurrence(significantOccurrenceSum);
}

} // fieldmatch
} // features
} // search
