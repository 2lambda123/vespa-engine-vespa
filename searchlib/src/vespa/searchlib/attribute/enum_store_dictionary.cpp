// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "enum_store_dictionary.h"
#include "enumstore.h"
#include <vespa/vespalib/btree/btree.hpp>
#include <vespa/vespalib/btree/btreeiterator.hpp>
#include <vespa/vespalib/btree/btreenode.hpp>
#include <vespa/vespalib/btree/btreenodeallocator.hpp>
#include <vespa/vespalib/btree/btreeroot.hpp>
#include <vespa/vespalib/datastore/datastore.hpp>
#include <vespa/vespalib/datastore/unique_store_dictionary.hpp>
#include <vespa/vespalib/util/bufferwriter.h>

#include <vespa/log/log.h>
LOG_SETUP(".searchlib.attribute.enum_store_dictionary");

using search::datastore::EntryComparator;
using search::datastore::EntryRef;
using search::datastore::UniqueStoreAddResult;

namespace search {

using btree::BTreeNode;

template <typename DictionaryT>
void
EnumStoreDictionary<DictionaryT>::remove_unused_values(const IndexSet& unused,
                                                       const datastore::EntryComparator& cmp)
{
    if (unused.empty()) {
        return;
    }
    for (const auto& ref : unused) {
        this->remove(cmp, ref);
    }
}

template <typename DictionaryT>
EnumStoreDictionary<DictionaryT>::EnumStoreDictionary(IEnumStore& enumStore)
    : ParentUniqueStoreDictionary(),
      _enumStore(enumStore)
{
}

template <typename DictionaryT>
EnumStoreDictionary<DictionaryT>::~EnumStoreDictionary() = default;

template <typename DictionaryT>
void
EnumStoreDictionary<DictionaryT>::set_ref_counts(const EnumVector& hist)
{
    _enumStore.set_ref_counts(hist, this->_dict);
}

template <typename DictionaryT>
void
EnumStoreDictionary<DictionaryT>::free_unused_values(const datastore::EntryComparator& cmp)
{
    IndexSet unused;

    // find unused enums
    for (auto iter = this->_dict.begin(); iter.valid(); ++iter) {
        _enumStore.free_value_if_unused(iter.getKey(), unused);
    }
    remove_unused_values(unused, cmp);
}

template <typename DictionaryT>
void
EnumStoreDictionary<DictionaryT>::free_unused_values(const IndexSet& to_remove,
                                                     const datastore::EntryComparator& cmp)
{
    IndexSet unused;
    for (const auto& index : to_remove) {
        _enumStore.free_value_if_unused(index, unused);
    }
    remove_unused_values(unused, cmp);
}

template <typename DictionaryT>
void
EnumStoreDictionary<DictionaryT>::remove(const EntryComparator &comp, EntryRef ref)
{
    assert(ref.valid());
    auto itr = this->_dict.lowerBound(ref, comp);
    assert(itr.valid() && itr.getKey() == ref);
    if constexpr (std::is_same_v<DictionaryT, EnumPostingTree>) {
        assert(EntryRef(itr.getData()) == EntryRef());
    }
    this->_dict.remove(itr);
}

template <typename DictionaryT>
bool
EnumStoreDictionary<DictionaryT>::find_index(const datastore::EntryComparator& cmp,
                                             Index& idx) const
{
    auto itr = this->_dict.find(Index(), cmp);
    if (!itr.valid()) {
        return false;
    }
    idx = itr.getKey();
    return true;
}

template <typename DictionaryT>
bool
EnumStoreDictionary<DictionaryT>::find_frozen_index(const datastore::EntryComparator& cmp,
                                                    Index& idx) const
{
    auto itr = this->_dict.getFrozenView().find(Index(), cmp);
    if (!itr.valid()) {
        return false;
    }
    idx = itr.getKey();
    return true;
}

template <typename DictionaryT>
std::vector<IEnumStore::EnumHandle>
EnumStoreDictionary<DictionaryT>::find_matching_enums(const datastore::EntryComparator& cmp) const
{
    std::vector<IEnumStore::EnumHandle> result;
    auto itr = this->_dict.getFrozenView().find(Index(), cmp);
    while (itr.valid() && !cmp(Index(), itr.getKey())) {
        result.push_back(itr.getKey().ref());
        ++itr;
    }
    return result;
}

template <>
EnumPostingTree &
EnumStoreDictionary<EnumTree>::get_posting_dictionary()
{
    LOG_ABORT("should not be reached");
}

template <>
EnumPostingTree &
EnumStoreDictionary<EnumPostingTree>::get_posting_dictionary()
{
    return _dict;
}

template <>
const EnumPostingTree &
EnumStoreDictionary<EnumTree>::get_posting_dictionary() const
{
    LOG_ABORT("should not be reached");
}

template <>
const EnumPostingTree &
EnumStoreDictionary<EnumPostingTree>::get_posting_dictionary() const
{
    return _dict;
}

EnumStoreFoldedDictionary::EnumStoreFoldedDictionary(IEnumStore& enumStore, std::unique_ptr<EntryComparator> folded_compare)
    : EnumStoreDictionary<EnumPostingTree>(enumStore),
      _folded_compare(std::move(folded_compare))
{
}

EnumStoreFoldedDictionary::~EnumStoreFoldedDictionary() = default;

UniqueStoreAddResult
EnumStoreFoldedDictionary::add(const EntryComparator& comp, std::function<EntryRef(void)> insertEntry)
{
    auto it = _dict.lowerBound(EntryRef(), comp);
    if (it.valid() && !comp(EntryRef(), it.getKey())) {
        // Entry already exists
        return UniqueStoreAddResult(it.getKey(), false);
    }
    EntryRef newRef = insertEntry();
    _dict.insert(it, newRef, EntryRef().ref());
    // Maybe move posting list reference from next entry
    ++it;
    if (it.valid() && EntryRef(it.getData()).valid() && !(*_folded_compare)(newRef, it.getKey())) {
        EntryRef posting_list_ref(it.getData());
        _dict.thaw(it);
        it.writeData(EntryRef().ref());
        --it;
        assert(it.valid() && it.getKey() == newRef);
        it.writeData(posting_list_ref.ref());
    }
    return UniqueStoreAddResult(newRef, true);
}

void
EnumStoreFoldedDictionary::remove(const EntryComparator& comp, EntryRef ref)
{
    assert(ref.valid());
    auto it = _dict.lowerBound(ref, comp);
    assert(it.valid() && it.getKey() == ref);
    EntryRef posting_list_ref(it.getData());
    _dict.remove(it);
    // Maybe copy posting list reference to next entry
    if (posting_list_ref.valid()) {
        if (it.valid() && !EntryRef(it.getData()).valid() && !(*_folded_compare)(ref, it.getKey())) {
            this->_dict.thaw(it);
            it.writeData(posting_list_ref.ref());
        } else {
            LOG_ABORT("Posting list not cleared for removed unique value");
        }
    }
}

template class EnumStoreDictionary<EnumTree>;

template class EnumStoreDictionary<EnumPostingTree>;

template
class btree::BTreeNodeT<IEnumStore::Index, EnumTreeTraits::INTERNAL_SLOTS>;

template
class btree::BTreeNodeTT<IEnumStore::Index, uint32_t, btree::NoAggregated, EnumTreeTraits::INTERNAL_SLOTS>;

template
class btree::BTreeNodeTT<IEnumStore::Index, btree::BTreeNoLeafData, btree::NoAggregated, EnumTreeTraits::LEAF_SLOTS>;

template
class btree::BTreeInternalNode<IEnumStore::Index, btree::NoAggregated, EnumTreeTraits::INTERNAL_SLOTS>;

template
class btree::BTreeLeafNode<IEnumStore::Index, btree::BTreeNoLeafData, btree::NoAggregated, EnumTreeTraits::LEAF_SLOTS>;

template
class btree::BTreeLeafNode<IEnumStore::Index, uint32_t, btree::NoAggregated, EnumTreeTraits::LEAF_SLOTS>;

template
class btree::BTreeLeafNodeTemp<IEnumStore::Index, btree::BTreeNoLeafData, btree::NoAggregated, EnumTreeTraits::LEAF_SLOTS>;

template
class btree::BTreeLeafNodeTemp<IEnumStore::Index, uint32_t, btree::NoAggregated, EnumTreeTraits::LEAF_SLOTS>;

template
class btree::BTreeNodeStore<IEnumStore::Index, btree::BTreeNoLeafData, btree::NoAggregated,
                            EnumTreeTraits::INTERNAL_SLOTS, EnumTreeTraits::LEAF_SLOTS>;

template
class btree::BTreeNodeStore<IEnumStore::Index, uint32_t, btree::NoAggregated,
                            EnumTreeTraits::INTERNAL_SLOTS, EnumTreeTraits::LEAF_SLOTS>;

template
class btree::BTreeRoot<IEnumStore::Index, btree::BTreeNoLeafData, btree::NoAggregated,
                       const datastore::EntryComparatorWrapper, EnumTreeTraits>;

template
class btree::BTreeRoot<IEnumStore::Index, uint32_t, btree::NoAggregated,
                       const datastore::EntryComparatorWrapper, EnumTreeTraits>;

template
class btree::BTreeRootT<IEnumStore::Index, btree::BTreeNoLeafData, btree::NoAggregated,
                        const datastore::EntryComparatorWrapper, EnumTreeTraits>;

template
class btree::BTreeRootT<IEnumStore::Index, uint32_t, btree::NoAggregated,
                        const datastore::EntryComparatorWrapper, EnumTreeTraits>;

template
class btree::BTreeRootBase<IEnumStore::Index, btree::BTreeNoLeafData, btree::NoAggregated,
                           EnumTreeTraits::INTERNAL_SLOTS, EnumTreeTraits::LEAF_SLOTS>;

template
class btree::BTreeRootBase<IEnumStore::Index, uint32_t, btree::NoAggregated,
                           EnumTreeTraits::INTERNAL_SLOTS, EnumTreeTraits::LEAF_SLOTS>;

template
class btree::BTreeNodeAllocator<IEnumStore::Index, btree::BTreeNoLeafData, btree::NoAggregated,
                                EnumTreeTraits::INTERNAL_SLOTS, EnumTreeTraits::LEAF_SLOTS>;

template
class btree::BTreeNodeAllocator<IEnumStore::Index, uint32_t, btree::NoAggregated,
                                EnumTreeTraits::INTERNAL_SLOTS, EnumTreeTraits::LEAF_SLOTS>;

template
class btree::BTreeIteratorBase<IEnumStore::Index, btree::BTreeNoLeafData, btree::NoAggregated,
                               EnumTreeTraits::INTERNAL_SLOTS, EnumTreeTraits::LEAF_SLOTS, EnumTreeTraits::PATH_SIZE>;
template
class btree::BTreeIteratorBase<IEnumStore::Index, uint32_t, btree::NoAggregated,
                               EnumTreeTraits::INTERNAL_SLOTS, EnumTreeTraits::LEAF_SLOTS, EnumTreeTraits::PATH_SIZE>;

template class btree::BTreeConstIterator<IEnumStore::Index, btree::BTreeNoLeafData, btree::NoAggregated,
                                         const datastore::EntryComparatorWrapper, EnumTreeTraits>;

template class btree::BTreeConstIterator<IEnumStore::Index, uint32_t, btree::NoAggregated,
                                         const datastore::EntryComparatorWrapper, EnumTreeTraits>;

template
class btree::BTreeIterator<IEnumStore::Index, btree::BTreeNoLeafData, btree::NoAggregated,
                           const datastore::EntryComparatorWrapper, EnumTreeTraits>;
template
class btree::BTreeIterator<IEnumStore::Index, uint32_t, btree::NoAggregated,
                           const datastore::EntryComparatorWrapper, EnumTreeTraits>;

template
class btree::BTree<IEnumStore::Index, btree::BTreeNoLeafData, btree::NoAggregated,
                   const datastore::EntryComparatorWrapper, EnumTreeTraits>;
template
class btree::BTree<IEnumStore::Index, uint32_t, btree::NoAggregated,
                   const datastore::EntryComparatorWrapper, EnumTreeTraits>;

}
