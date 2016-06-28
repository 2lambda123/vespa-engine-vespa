// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "enumstore.h"
#include "postinglisttraits.h"
#include "postingstore.h"
#include "ipostinglistsearchcontext.h"
#include <vespa/searchlib/common/bitvector.h>
#include "attributevector.h"
#include <vespa/vespalib/util/regexp.h>
#include <cstdlib>

namespace search
{

namespace attribute
{


/**
 * Search context helper for posting list attributes, used to instantiate
 * iterators based on posting lists instead of brute force filtering search.
 */

class PostingListSearchContext : public IPostingListSearchContext
{
protected:
    typedef EnumPostingTree Dictionary;
    typedef Dictionary::ConstIterator DictionaryConstIterator;
    typedef Dictionary::FrozenView FrozenDictionary;
    typedef EnumStoreBase::Index EnumIndex;
    
    const FrozenDictionary _frozenDictionary;
    DictionaryConstIterator _lowerDictItr;
    DictionaryConstIterator _upperDictItr;
    uint32_t                _uniqueValues;
    uint32_t                _docIdLimit;
    uint32_t                _dictSize;
    uint64_t                _numValues; // attr.getStatus().getNumValues();
    bool                    _hasWeight;
    bool                    _useBitVector;
    search::btree::EntryRef _pidx;
    search::btree::EntryRef _frozenRoot; // Posting list in tree form
    float _FSTC;  // Filtering Search Time Constant
    float _PLSTC; // Posting List Search Time Constant
    const EnumStoreBase    &_esb;
    uint32_t                _minBvDocFreq;
    const GrowableBitVector *_gbv; // bitvector if _useBitVector has been set
        

    PostingListSearchContext(const Dictionary &dictionary,
                             uint32_t docIdLimit,
                             uint64_t numValues,
                             bool hasWeight,
                             const EnumStoreBase &esb,
                             uint32_t minBvDocFreq,
                             bool useBitVector);

    ~PostingListSearchContext(void);

    void lookupTerm(const EnumStoreComparator &comp);
    void lookupRange(const EnumStoreComparator &low, const EnumStoreComparator &high);
    void lookupSingle(void);
    virtual bool useThis(const DictionaryConstIterator & it) const {
        (void) it;
        return true;
    }

    float calculateFilteringCost(void) const {
        // filtering search time (ms) ~ FSTC * numValues; (FSTC =
        // Filtering Search Time Constant)
        return _FSTC * _numValues;
    }

    float calculatePostingListCost(uint32_t approxNumHits) const {
        // search time (ms) ~ PLSTC * numHits * log(numHits); (PLSTC =
        // Posting List Search Time Constant)
        return _PLSTC * approxNumHits;
    }

    uint32_t calculateApproxNumHits(void) const {
        float docsPerUniqueValue = static_cast<float>(_docIdLimit) /
                                   static_cast<float>(_dictSize);
        return static_cast<uint32_t>(docsPerUniqueValue * _uniqueValues);
    }

    virtual bool fallbackToFiltering(void) const {
        uint32_t numHits = calculateApproxNumHits();
        // numHits > 1000: make sure that posting lists are unit tested.
        return (numHits > 1000) &&
            (calculateFilteringCost() < calculatePostingListCost(numHits));
    }

public:
};


template <class DataT>
class PostingListSearchContextT : public PostingListSearchContext
{
protected:
    typedef DataT DataType;
    typedef PostingListTraits<DataType> Traits;
    typedef typename Traits::PostingList PostingList;
    typedef typename Traits::Posting Posting;
    typedef std::vector<Posting> PostingVector;
    typedef btree::EntryRef EntryRef;
    typedef typename PostingList::ConstIterator PostingConstIterator;

    const PostingList    &_postingList;
    /*
     * Synthetic posting lists for range search, in array or bitvector form
     */
    PostingVector  _array;
    BitVector::UP  _bitVector;
    bool           _fetchPostingsDone;
    bool           _arrayValid;
    
    static const long MIN_UNIQUE_VALUES_BEFORE_APPROXIMATION = 100;
    static const long MIN_UNIQUE_VALUES_TO_NUMDOCS_RATIO_BEFORE_APPROXIMATION = 20;
    static const long MIN_APPROXHITS_TO_NUMDOCS_RATIO_BEFORE_APPROXIMATION = 10;

    PostingListSearchContextT(const Dictionary &dictionary,
                              uint32_t docIdLimit,
                              uint64_t numValues,
                              bool hasWeight,
                              const PostingList &postingList,
                              const EnumStoreBase &esb,
                              uint32_t minBvCocFreq,
                              bool useBitVector);

    void lookupSingle(void);
    size_t countHits(void) const;
    void fillArray(size_t numDocs);
    void fillBitVector(void);

    PostingVector &
    merge(PostingVector &v, PostingVector &temp,
          const std::vector<size_t> & startPos) __attribute__((noinline));

    void fetchPostings(bool strict) override;
    // this will be called instead of the fetchPostings function in some cases
    void diversify(bool forward, size_t wanted_hits, 
                   const IAttributeVector &diversity_attr, size_t max_per_group,
                   size_t cutoff_groups, bool cutoff_strict);

    queryeval::SearchIterator::UP
    createPostingIterator(fef::TermFieldMatchData *matchData, bool strict) override;

    unsigned int singleHits(void) const;
    unsigned int approximateHits(void) const override;
    void applyRangeLimit(int rangeLimit);
};


template <class DataT>
class PostingListFoldedSearchContextT : public PostingListSearchContextT<DataT>
{
protected:
    typedef PostingListSearchContextT<DataT> Parent;
    typedef typename Parent::Dictionary Dictionary;
    typedef typename Parent::PostingList PostingList;
    using Parent::_lowerDictItr;
    using Parent::_uniqueValues;
    using Parent::_postingList;
    using Parent::_docIdLimit;
    using Parent::countHits;
    using Parent::singleHits;

    PostingListFoldedSearchContextT(const Dictionary &dictionary,
                                    uint32_t docIdLimit,
                                    uint64_t numValues,
                                    bool hasWeight,
                                    const PostingList &postingList,
                                    const EnumStoreBase &esb,
                                    uint32_t minBvCocFreq,
                                    bool useBitVector);

    unsigned int approximateHits(void) const override;
};


template <typename BaseSC, typename BaseSC2, typename AttrT>
class PostingSearchContext: public BaseSC,
                            public BaseSC2
{
public:
    typedef typename AttrT::EnumStore EnumStore;
protected:
    const AttrT           &_toBeSearched;
    const EnumStore       &_enumStore;
    
    PostingSearchContext(QueryTermSimple::UP qTerm, bool useBitVector, const AttrT &toBeSearched);
};

template <typename BaseSC, typename AttrT, typename DataT>
class StringPostingSearchContext
    : public PostingSearchContext<BaseSC, PostingListFoldedSearchContextT<DataT>, AttrT>
{
private:
    typedef PostingListTraits<DataT> AggregationTraits;
    typedef typename AggregationTraits::PostingList PostingList;
    typedef typename PostingList::Iterator PostingIterator;
    typedef typename PostingList::ConstIterator PostingConstIterator;
    typedef PostingSearchContext<BaseSC, PostingListFoldedSearchContextT<DataT>, AttrT>
    Parent;
    typedef typename Parent::EnumStore EnumStore;
    typedef typename EnumStore::FoldedComparatorType FoldedComparatorType;
    typedef vespalib::Regexp Regexp;
    using Parent::_toBeSearched;
    using Parent::_enumStore;
    using Parent::getRegex;
    bool useThis(const PostingListSearchContext::DictionaryConstIterator & it) const override {
        return getRegex() ? getRegex()->match(_enumStore.getValue(it.getKey())) : true;
    }
public:
    StringPostingSearchContext(QueryTermSimple::UP qTerm, bool useBitVector, const AttrT &toBeSearched);
};

template <typename BaseSC, typename AttrT, typename DataT>
class NumericPostingSearchContext
    : public PostingSearchContext<BaseSC, PostingListSearchContextT<DataT>, AttrT>
{
private:
    typedef PostingSearchContext<BaseSC, PostingListSearchContextT<DataT>, AttrT> Parent;
    typedef PostingListTraits<DataT> AggregationTraits;
    typedef typename AggregationTraits::PostingList PostingList;
    typedef typename PostingList::Iterator PostingIterator;
    typedef typename PostingList::ConstIterator PostingConstIterator;
    typedef typename Parent::EnumStore EnumStore;
    typedef typename EnumStore::ComparatorType ComparatorType;
    typedef typename AttrT::T BaseType;
    typedef typename Parent::Params Params;
    using Parent::_low;
    using Parent::_high;
    using Parent::_toBeSearched;
    using Parent::_enumStore;
    Params _params;
    
    void getIterators(bool shouldApplyRangeLimit);
    bool valid() const override { return this->isValid(); }

    bool fallbackToFiltering(void) const override {
        return (this->getRangeLimit() != 0)
            ? false
            : Parent::fallbackToFiltering();
    }
    unsigned int approximateHits(void) const override {
        const unsigned int estimate = PostingListSearchContextT<DataT>::approximateHits();
        const unsigned int limit = std::abs(this->getRangeLimit());
        return ((limit > 0) && (limit < estimate))
            ? limit
            : estimate;
    }
    void fetchPostings(bool strict) override {
        if (params().diversityAttribute() != nullptr) {
            bool forward = (this->getRangeLimit() > 0);
            size_t wanted_hits = std::abs(this->getRangeLimit());
            PostingListSearchContextT<DataT>::diversify(forward, wanted_hits,
                                                        *(params().diversityAttribute()), this->getMaxPerGroup(),
                                                        params().diversityCutoffGroups(), params().diversityCutoffStrict());
        } else {
            PostingListSearchContextT<DataT>::fetchPostings(strict);
        }
    }

public:
    NumericPostingSearchContext(QueryTermSimple::UP qTerm, const Params & params, const AttrT &toBeSearched);
    const Params &params() const { return _params; }
};
    
    
template <typename BaseSC, typename BaseSC2, typename AttrT>
PostingSearchContext<BaseSC, BaseSC2, AttrT>::
PostingSearchContext(QueryTermSimple::UP qTerm, bool useBitVector, const AttrT &toBeSearched)
    : BaseSC(std::move(qTerm), toBeSearched),
      BaseSC2(toBeSearched.getEnumStore().getPostingDictionary(),
              toBeSearched.getCommittedDocIdLimit(),
              toBeSearched.getStatus().getNumValues(),
              toBeSearched.hasWeightedSetType(),
              toBeSearched.getPostingList(),
              toBeSearched.getEnumStore(),
              toBeSearched._postingList._minBvDocFreq,
              useBitVector),
      _toBeSearched(toBeSearched),
      _enumStore(_toBeSearched.getEnumStore())
{
    this->_plsc = static_cast<attribute::IPostingListSearchContext *>(this);
}


template <typename BaseSC, typename AttrT, typename DataT>
StringPostingSearchContext<BaseSC, AttrT, DataT>::
StringPostingSearchContext(QueryTermSimple::UP qTerm, bool useBitVector, const AttrT &toBeSearched)
    : Parent(std::move(qTerm), useBitVector, toBeSearched)
{
    // after benchmarking prefix search performance on single, array, and weighted set fast-aggregate string attributes
    // with 1M values the following constant has been derived:
    this->_FSTC  = 0.000028;

    // after benchmarking prefix search performance on single, array, and weighted set fast-search string attributes
    // with 1M values the following constant has been derived:
    this->_PLSTC = 0.000000;
    
    if (this->valid()) {
        if (this->isPrefix()) {
            FoldedComparatorType comp(_enumStore, this->queryTerm().getTerm(), true);
            this->lookupRange(comp, comp);
        } else if (this->isRegex()) {
            vespalib::string prefix(Regexp::get_prefix(this->queryTerm().getTerm()));
            FoldedComparatorType comp(_enumStore, prefix.c_str(), true);
            this->lookupRange(comp, comp);
        } else {
            FoldedComparatorType comp(_enumStore, this->queryTerm().getTerm());
            this->lookupTerm(comp);
        }
        if (this->_uniqueValues == 1u) {
            this->lookupSingle();
        }
    }
}


template <typename BaseSC, typename AttrT, typename DataT>
NumericPostingSearchContext<BaseSC, AttrT, DataT>::
NumericPostingSearchContext(QueryTermSimple::UP qTerm, const Params & params_in, const AttrT &toBeSearched)
    : Parent(std::move(qTerm), params_in.useBitVector(), toBeSearched),
      _params(params_in)
{
    // after simplyfying the formula and simple benchmarking and thumbs in the air
    // a ratio of 8 between numvalues and estimated number of hits has been found.
    this->_FSTC = 1;

    this->_PLSTC = 8;
    if (valid()) {
        if (_low == _high) {
            ComparatorType comp(_enumStore, _low);
            this->lookupTerm(comp);
        } else if (_low < _high) {
            bool shouldApplyRangeLimit = (params().diversityAttribute() == nullptr) &&
                                         (this->getRangeLimit() != 0);
            getIterators( shouldApplyRangeLimit );
        }
        if (this->_uniqueValues == 1u) {
            this->lookupSingle();
        }
    }
}


template <typename BaseSC, typename AttrT, typename DataT>
void
NumericPostingSearchContext<BaseSC, AttrT, DataT>::
getIterators(bool shouldApplyRangeLimit)
{
    bool isFloat =
        _toBeSearched.getBasicType() == BasicType::FLOAT ||
        _toBeSearched.getBasicType() == BasicType::DOUBLE;
    bool isUnsigned = _toBeSearched.getInternalBasicType().isUnsigned();
    search::Range<BaseType> capped = this->template cappedRange<BaseType>(isFloat, isUnsigned);

    ComparatorType compLow(_enumStore, capped.lower());
    ComparatorType compHigh(_enumStore, capped.upper());

    this->lookupRange(compLow, compHigh);
    if (shouldApplyRangeLimit) {
        this->applyRangeLimit(this->getRangeLimit());
    }

    if (this->_lowerDictItr != this->_upperDictItr) {
        _low = _enumStore.getValue(this->_lowerDictItr.getKey());
        auto last = this->_upperDictItr;
        --last;
        _high = _enumStore.getValue(last.getKey());
    }
}



extern template class PostingListSearchContextT<btree::BTreeNoLeafData>;
extern template class PostingListSearchContextT<int32_t>;
extern template class PostingListFoldedSearchContextT<btree::BTreeNoLeafData>;
extern template class PostingListFoldedSearchContextT<int32_t>;

} // namespace attribute

} // namespace search

