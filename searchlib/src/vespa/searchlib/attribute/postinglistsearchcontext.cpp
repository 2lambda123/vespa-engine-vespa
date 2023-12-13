// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "postinglistsearchcontext.hpp"
#include "attributeiterators.hpp"
#include "diversity.hpp"
#include <vespa/vespalib/btree/btreeiterator.hpp>

namespace search::attribute {

using vespalib::btree::BTreeNode;

void
MergeBitVectors::merge(vespalib::ThreadBundle &thread_bundle, const std::vector<BitVector *> &toMerge) {
    std::vector<MergeBitVectors> pairs;
    pairs.reserve(toMerge.size() / 2);
    size_t i = 0;
    for (; i + 1 < toMerge.size(); i += 2) {
        pairs.emplace_back(toMerge[i], toMerge[i + 1]);
    }
    thread_bundle.run(pairs);
    size_t remaining = (toMerge.size() + 1) / 2;
    if (remaining > 1) {
        std::vector<BitVector *> nextToMerge;
        nextToMerge.reserve((toMerge.size() + 1) / 2);
        for (const auto &merged: pairs) {
            nextToMerge.push_back(merged._a);
        }
        for (; i < toMerge.size(); i++) {
            nextToMerge.push_back(toMerge[i]);
        }
        merge(thread_bundle, nextToMerge);
    }
}

PostingListSearchContext::
PostingListSearchContext(const IEnumStoreDictionary& dictionary, bool has_btree_dictionary, uint32_t docIdLimit,
                         uint64_t numValues, bool hasWeight, bool useBitVector, const ISearchContext &baseSearchCtx)
    : _dictionary(dictionary),
      _baseSearchCtx(baseSearchCtx),
      _bv(nullptr),
      _frozenDictionary(has_btree_dictionary ? _dictionary.get_posting_dictionary().getFrozenView() : FrozenDictionary()),
      _lowerDictItr(has_btree_dictionary ? DictionaryConstIterator(BTreeNode::Ref(), _frozenDictionary.getAllocator()) : DictionaryConstIterator()),
      _upperDictItr(has_btree_dictionary ? DictionaryConstIterator(BTreeNode::Ref(), _frozenDictionary.getAllocator()) : DictionaryConstIterator()),
      _numValues(numValues),
      _uniqueValues(0u),
      _docIdLimit(docIdLimit),
      _dictSize(_frozenDictionary.size()),
      _pidx(),
      _frozenRoot(),
      _hasWeight(hasWeight),
      _useBitVector(useBitVector),
      _estimated_hits()
{
}


PostingListSearchContext::~PostingListSearchContext() = default;


void
PostingListSearchContext::lookupTerm(const vespalib::datastore::EntryComparator &comp)
{
    auto lookup_result = _dictionary.find_posting_list(comp, _frozenDictionary.getRoot());
    if (lookup_result.first.valid()) {
        _pidx = lookup_result.second;
        _uniqueValues = 1u;
    }
}


void
PostingListSearchContext::lookupRange(const vespalib::datastore::EntryComparator &low,
                                      const vespalib::datastore::EntryComparator &high)
{
    if (!_dictionary.get_has_btree_dictionary()) {
        _uniqueValues = 2; // Avoid zero and single value optimizations, use filtering
        return;
    }
    _lowerDictItr.lower_bound(_frozenDictionary.getRoot(), AtomicEntryRef(), low);
    _upperDictItr = _lowerDictItr;
    if (_upperDictItr.valid() && !high.less(EnumIndex(), _upperDictItr.getKey().load_acquire())) {
        _upperDictItr.seekPast(AtomicEntryRef(), high);
    }
    _uniqueValues = _upperDictItr - _lowerDictItr;
}


void
PostingListSearchContext::lookupSingle()
{
    if (_lowerDictItr.valid()) {
        _pidx = _lowerDictItr.getData().load_acquire();
    }
}

size_t
PostingListSearchContext::estimated_hits_in_range() const
{
    if (_estimated_hits.has_value()) {
        return _estimated_hits.value();
    }
    size_t result = calc_estimated_hits_in_range();
    _estimated_hits = result;
    return result;
}

template class PostingListSearchContextT<vespalib::btree::BTreeNoLeafData>;
template class PostingListSearchContextT<int32_t>;
template class PostingListFoldedSearchContextT<vespalib::btree::BTreeNoLeafData>;
template class PostingListFoldedSearchContextT<int32_t>;

}
