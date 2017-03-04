// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "dociditerator.h"
#include "attributeiterators.h"
#include "diversity.h"
#include <vespa/searchlib/queryeval/emptysearch.h>
#include <vespa/searchlib/common/bitvectoriterator.h>

namespace search {

using queryeval::EmptySearch;
using queryeval::SearchIterator;

namespace attribute {

template <typename DataT>
PostingListSearchContextT<DataT>::
PostingListSearchContextT(const Dictionary &dictionary,
                          uint32_t docIdLimit,
                          uint64_t numValues,
                          bool hasWeight,
                          const PostingList &postingList,
                          const EnumStoreBase &esb,
                          uint32_t minBvDocFreq,
                          bool useBitVector)
    : PostingListSearchContext(dictionary, docIdLimit, numValues, hasWeight,
                               esb, minBvDocFreq, useBitVector),
      _postingList(postingList),
      _array(),
      _bitVector(),
      _fetchPostingsDone(false),
      _arrayValid(false)
{
}


template <typename DataT>
void
PostingListSearchContextT<DataT>::lookupSingle(void)
{
    PostingListSearchContext::lookupSingle();
    if (!_pidx.valid())
        return;
    uint32_t typeId = _postingList.getTypeId(_pidx);
    if (!_postingList.isSmallArray(typeId)) {
        if (_postingList.isBitVector(typeId)) {
            const BitVectorEntry *bve = _postingList.getBitVectorEntry(_pidx);
            const GrowableBitVector *bv = bve->_bv.get();
            if (_useBitVector) {
                _gbv = bv;
            } else {
                _pidx = bve->_tree;
                if (_pidx.valid()) { 
                    typename PostingList::BTreeType::FrozenView
                        frozenView(_postingList.getTreeEntry(_pidx)->
                                   getFrozenView(_postingList.getAllocator()));
                    _frozenRoot = frozenView.getRoot();
                    if (!_frozenRoot.valid()) {
                        _pidx = datastore::EntryRef();
                    }
                } else {
                    _gbv = bv; 
                }
            }
        } else {
            typename PostingList::BTreeType::FrozenView
                frozenView(_postingList.getTreeEntry(_pidx)->
                           getFrozenView(_postingList.getAllocator()));
            _frozenRoot = frozenView.getRoot();
            if (!_frozenRoot.valid()) {
                _pidx = datastore::EntryRef();
            }
        }
    }
}


template <typename DataT>
size_t
PostingListSearchContextT<DataT>::countHits(void) const
{
    size_t sum(0);
    for (auto it(_lowerDictItr); it != _upperDictItr; ++it) {
        if (useThis(it)) {
            sum += _postingList.frozenSize(it.getData());
        }
    }
    return sum;
}


template <typename DataT>
void
PostingListSearchContextT<DataT>::fillArray(size_t numDocs)
{
    _array.clear();
    _array.reserve(numDocs);
    std::vector<size_t> startPos;
    startPos.reserve(_uniqueValues + 1);
    startPos.push_back(0);
    for (auto it(_lowerDictItr); it != _upperDictItr; ++it) {
        if (useThis(it)) {
            _postingList.foreach_frozen(it.getData(),
                                        [&](uint32_t key, const DataT &data)
                                        { _array.push_back(Posting(key, data));
                                        });
            startPos.push_back(_array.size());
        }
    }
    if (startPos.size() > 2) {
        PostingVector temp(_array.size());
        _array.swap(merge(_array, temp, startPos));
    }
    _arrayValid = true;
}


template <typename DataT>
void
PostingListSearchContextT<DataT>::fillBitVector(void)
{
    _bitVector = BitVector::create(_docIdLimit);
    BitVector &bv(*_bitVector);
    uint32_t limit = bv.size();
    for (auto it(_lowerDictItr); it != _upperDictItr; ++it) {
        if (useThis(it)) {
            _postingList.foreach_frozen_key(it.getData(),
                                            [&](uint32_t key)
                                            { if (key < limit) {
                                                    bv.setBit(key);
                                                }
                                            });
        }
    }
    bv.invalidateCachedCount();
}


template <typename DataT>
typename PostingListSearchContextT<DataT>::PostingVector &
PostingListSearchContextT<DataT>::
merge(PostingVector &v, PostingVector &temp,
      const std::vector<size_t> &startPos)
{
    std::vector<size_t> nextStartPos;
    nextStartPos.reserve((startPos.size() + 1) / 2);
    nextStartPos.push_back(0);
    for (size_t i(0), m((startPos.size() - 1) / 2); i < m; i++) {
        size_t aStart = startPos[i * 2 + 0];
        size_t aEnd = startPos[i * 2 + 1];
        size_t bStart = aEnd;
        size_t bEnd = startPos[i * 2 + 2];
        typename PostingVector::const_iterator it = v.begin();
        std::merge(it + aStart, it + aEnd,
                   it + bStart, it + bEnd,
                   temp.begin() + aStart);
        nextStartPos.push_back(bEnd);
    }
    if ((startPos.size() - 1) % 2) {
        for (size_t i(startPos[startPos.size() - 2]), m(v.size()); i < m; i++) {
            temp[i] = v[i];
        }
        nextStartPos.push_back(temp.size());
    }
    return (nextStartPos.size() > 2) ? merge(temp, v, nextStartPos) : temp;
}


template <typename DataT>
void
PostingListSearchContextT<DataT>::fetchPostings(bool strict)
{
    assert(!_fetchPostingsDone);
    _fetchPostingsDone = true;
    if (_uniqueValues < 2u) {
        return;
    }
    if (strict && !fallbackToFiltering()) {
        size_t sum(countHits());
        if (sum < _docIdLimit / 64) {
            fillArray(sum);
        } else {
            fillBitVector();
        }
    }
}


template <typename DataT>
void
PostingListSearchContextT<DataT>::diversify(bool forward, size_t wanted_hits, 
                                            const IAttributeVector &diversity_attr, size_t max_per_group,
                                            size_t cutoff_groups, bool cutoff_strict)
{
    assert(!_fetchPostingsDone);
    _fetchPostingsDone = true;
    _array.clear();
    _array.reserve(wanted_hits);
    std::vector<size_t> fragments;
    fragments.push_back(0);
    diversity::diversify(forward, _lowerDictItr, _upperDictItr, _postingList, wanted_hits,
                         diversity_attr, max_per_group, cutoff_groups, cutoff_strict,
                         _array, fragments);
    if (fragments.size() > 2) {
        PostingVector temp(_array.size());
        _array.swap(merge(_array, temp, fragments));
    }
    _arrayValid = true;
}


template <typename DataT>
SearchIterator::UP
PostingListSearchContextT<DataT>::
createPostingIterator(fef::TermFieldMatchData *matchData, bool strict)
{
    assert(_fetchPostingsDone);
    if (_uniqueValues == 0u) {
        return SearchIterator::UP(new EmptySearch());
    }
    if (_arrayValid || (_bitVector.get() != nullptr)) { // synthetic results are available
        if (!_array.empty()) {
            assert(_arrayValid);
            typedef DocIdIterator<Posting> DocIt;
            DocIt postings;
            postings.set(&_array[0], &_array[_array.size()]);
            return (_postingList._isFilter)
                ? SearchIterator::UP(new FilterAttributePostingListIteratorT<DocIt>(matchData, postings))
                : SearchIterator::UP(new AttributePostingListIteratorT<DocIt>(_hasWeight, matchData, postings));
        }
        if (_arrayValid) {
            return SearchIterator::UP(new EmptySearch());
        }
        BitVector *bv(_bitVector.get());
        assert(bv != nullptr);
        return search::BitVectorIterator::create(bv, bv->size(), *matchData, strict);
    }
    if (_uniqueValues == 1) {
        if (_gbv != nullptr) {
            return BitVectorIterator::create(_gbv, std::min(_gbv->size(), _docIdLimit), *matchData, strict);
        }
        if (!_pidx.valid()) {
            return SearchIterator::UP(new EmptySearch());
        }
        const PostingList &postingList = _postingList;
        if (!_frozenRoot.valid()) {
            uint32_t clusterSize = _postingList.getClusterSize(_pidx);
            assert(clusterSize != 0);
            typedef DocIdMinMaxIterator<Posting> DocIt;
            DocIt postings;
            const Posting *array = postingList.getKeyDataEntry(_pidx, clusterSize);
            postings.set(array, array + clusterSize);
            return (postingList._isFilter)
                ? SearchIterator::UP(new FilterAttributePostingListIteratorT<DocIt>(matchData, postings))
                : SearchIterator::UP(new AttributePostingListIteratorT<DocIt>(_hasWeight, matchData, postings));
        }
        typename PostingList::BTreeType::FrozenView frozen(_frozenRoot, postingList.getAllocator());

        return (_postingList._isFilter)
            ? SearchIterator::UP(new FilterAttributePostingListIteratorT<PostingConstIterator> (matchData, frozen.getRoot(), frozen.getAllocator()))
            : SearchIterator::UP(new AttributePostingListIteratorT<PostingConstIterator> (_hasWeight, matchData, frozen.getRoot(), frozen.getAllocator()));
    }
    // returning nullptr will trigger fallback to filter iterator
    return SearchIterator::UP();
}


template <typename DataT>
unsigned int
PostingListSearchContextT<DataT>::singleHits(void) const
{
    if (_gbv) {
        // Some inaccuracy is expected, data changes underfeet
        return _gbv->countTrueBits();
    }
    if (!_pidx.valid()) {
        return 0u;
    }
    if (!_frozenRoot.valid()) {
        return _postingList.getClusterSize(_pidx);
    }
    typename PostingList::BTreeType::FrozenView frozenView(_frozenRoot, _postingList.getAllocator());
    return frozenView.size();
}

template <typename DataT>
unsigned int
PostingListSearchContextT<DataT>::approximateHits(void) const
{
    unsigned int numHits = 0;
    if (_uniqueValues == 0u) {
    } else if (_uniqueValues == 1u) {
        numHits = singleHits();
    } else {
        if (this->fallbackToFiltering()) {
            numHits = _docIdLimit;
        } else if (_uniqueValues > MIN_UNIQUE_VALUES_BEFORE_APPROXIMATION) {
            if ((_uniqueValues *
                 MIN_UNIQUE_VALUES_TO_NUMDOCS_RATIO_BEFORE_APPROXIMATION >
                 static_cast<int>(_docIdLimit)) ||
                (this->calculateApproxNumHits() *
                 MIN_APPROXHITS_TO_NUMDOCS_RATIO_BEFORE_APPROXIMATION >
                 _docIdLimit)) {
                numHits = this->calculateApproxNumHits();
            } else {
                // XXX: Unsafe
                numHits = countHits();
            }
        } else {
            // XXX: Unsafe
            numHits = countHits();
        }
    }
    return numHits;
}


template <typename DataT>
void
PostingListSearchContextT<DataT>::applyRangeLimit(int rangeLimit)
{
    if (rangeLimit > 0) {
        DictionaryConstIterator middle = _lowerDictItr;
        for (int n(0); (n < rangeLimit) && (middle != _upperDictItr); ++middle) {
            n += _postingList.frozenSize(middle.getData());
        }
        _upperDictItr = middle;
        _uniqueValues = _upperDictItr - _lowerDictItr;
    } else if ((rangeLimit < 0) && (_lowerDictItr != _upperDictItr)) {
        rangeLimit = -rangeLimit;
        DictionaryConstIterator middle = _upperDictItr;
        for (int n(0); (n < rangeLimit) && (middle != _lowerDictItr); ) {
            --middle;
            n += _postingList.frozenSize(middle.getData());
        }
        _lowerDictItr = middle;
        _uniqueValues = _upperDictItr - _lowerDictItr;
    }
}


template <typename DataT>
PostingListFoldedSearchContextT<DataT>::
PostingListFoldedSearchContextT(const Dictionary &dictionary,
                                uint32_t docIdLimit,
                                uint64_t numValues,
                                bool hasWeight,
                                const PostingList &postingList,
                                const EnumStoreBase &esb,
                                uint32_t minBvDocFreq,
                                bool useBitVector)
    : Parent(dictionary, docIdLimit, numValues, hasWeight, postingList,
             esb, minBvDocFreq, useBitVector)
{
}


template <typename DataT>
unsigned int
PostingListFoldedSearchContextT<DataT>::approximateHits(void) const
{
    unsigned int numHits = 0;
    if (_uniqueValues == 0u) {
    } else if (_uniqueValues == 1u) {
        numHits = singleHits();
    } else {
        if (this->fallbackToFiltering()) {
            numHits = _docIdLimit;
        } else {
            // XXX: Unsafe
            numHits = countHits();
        }
    }
    return numHits;
}

} // namespace attribute

} // namespace search

