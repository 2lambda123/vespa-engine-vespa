// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "multistringpostattribute.h"
#include "multi_string_enum_search_context.h"
#include <vespa/searchcommon/attribute/config.h>
#include <vespa/searchlib/query/query_term_simple.h>

namespace search {

template <typename B, typename T>
MultiValueStringPostingAttributeT<B, T>::MultiValueStringPostingAttributeT(const vespalib::string & name, const AttributeVector::Config & c)
    : MultiValueStringAttributeT<B, T>(name, c),
      PostingParent(*this, this->getEnumStore()),
      _posting_store_adapter(*this)
{
}

template <typename B, typename T>
MultiValueStringPostingAttributeT<B, T>::MultiValueStringPostingAttributeT(const vespalib::string & name)
    : MultiValueStringPostingAttributeT<B, T>(name, AttributeVector::Config(AttributeVector::BasicType::STRING, attribute::CollectionType::ARRAY))
{
}

template <typename B, typename T>
MultiValueStringPostingAttributeT<B, T>::~MultiValueStringPostingAttributeT()
{
    this->disableFreeLists();
    this->disable_entry_hold_list();
    clearAllPostings();
}

class StringEnumIndexMapper : public EnumIndexMapper
{
public:
    StringEnumIndexMapper(IEnumStoreDictionary & dictionary) : _dictionary(dictionary) { }
    IEnumStore::Index map(IEnumStore::Index original) const override;
    bool hasFold() const override { return true; }
private:
    IEnumStoreDictionary& _dictionary;
};

template <typename B, typename T>
void
MultiValueStringPostingAttributeT<B, T>::
applyValueChanges(const DocIndices& docIndices, EnumStoreBatchUpdater &updater)
{
    using PostingChangeComputer = PostingChangeComputerT<WeightedIndex, PostingMap>;
    EnumStore &enumStore(this->getEnumStore());
    IEnumStoreDictionary& dictionary(enumStore.get_dictionary());

    StringEnumIndexMapper mapper(dictionary);
    PostingMap changePost(PostingChangeComputer::compute(this->getMultiValueMapping(), docIndices,
                                                         enumStore.get_folded_comparator(), mapper));
    this->updatePostings(changePost);
    MultiValueStringAttributeT<B, T>::applyValueChanges(docIndices, updater);
}

template <typename B, typename T>
void
MultiValueStringPostingAttributeT<B, T>::freezeEnumDictionary()
{
    this->getEnumStore().freeze_dictionary();
}

template <typename B, typename T>
void
MultiValueStringPostingAttributeT<B, T>::mergeMemoryStats(vespalib::MemoryUsage &total)
{
    auto& compaction_strategy = this->getConfig().getCompactionStrategy();
    total.merge(this->_posting_store.update_stat(compaction_strategy));
}

template <typename B, typename T>
void
MultiValueStringPostingAttributeT<B, T>::reclaim_memory(generation_t oldest_used_gen)
{
    MultiValueStringAttributeT<B, T>::reclaim_memory(oldest_used_gen);
    _posting_store.reclaim_memory(oldest_used_gen);
}

template <typename B, typename T>
void
MultiValueStringPostingAttributeT<B, T>::before_inc_generation(generation_t current_gen)
{
    _posting_store.freeze();
    MultiValueStringAttributeT<B, T>::before_inc_generation(current_gen);
    _posting_store.assign_generation(current_gen);
}


template <typename B, typename T>
std::unique_ptr<attribute::SearchContext>
MultiValueStringPostingAttributeT<B, T>::getSearch(QueryTermSimpleUP qTerm,
                                                   const attribute::SearchContextParams & params) const
{
    using BaseSC = attribute::MultiStringEnumSearchContext<T>;
    using SC = attribute::StringPostingSearchContext<BaseSC, SelfType, int32_t>;
    bool cased = this->get_match_is_cased();
    auto doc_id_limit = this->getCommittedDocIdLimit();
    BaseSC base_sc(std::move(qTerm), cased, params.fuzzy_matching_algorithm(), *this, this->_mvMapping.make_read_view(doc_id_limit), this->_enumStore);
    return std::make_unique<SC>(std::move(base_sc), params.useBitVector(), *this);
}


template <typename B, typename T>
vespalib::datastore::EntryRef
MultiValueStringPostingAttributeT<B, T>::DocidWithWeightPostingStoreAdapter::get_dictionary_snapshot() const
{
    const IEnumStoreDictionary& dictionary = self._enumStore.get_dictionary();
    return dictionary.get_frozen_root();
}

template <typename B, typename T>
IDirectPostingStore::LookupResult
MultiValueStringPostingAttributeT<B, T>::DocidWithWeightPostingStoreAdapter::lookup(const LookupKey & key, vespalib::datastore::EntryRef dictionary_snapshot) const
{
    const IEnumStoreDictionary& dictionary = self._enumStore.get_dictionary();
    vespalib::stringref keyAsString = key.asString();
    // Assert the unfortunate assumption of the comparators.
    // Should be lifted once they take the length too.
    assert(keyAsString.data()[keyAsString.size()] == '\0');
    auto comp = self._enumStore.make_folded_comparator(keyAsString.data());
    auto find_result = dictionary.find_posting_list(comp, dictionary_snapshot);
    if (find_result.first.valid()) {
        auto pidx = find_result.second;
        if (pidx.valid()) {
            const auto& store = self.get_posting_store();
            auto minmax = store.getAggregated(pidx);
            return LookupResult(pidx, store.frozenSize(pidx), minmax.getMin(), minmax.getMax(), find_result.first);
        }
    }
    return LookupResult();
}

template <typename B, typename T>
void
MultiValueStringPostingAttributeT<B, T>::DocidWithWeightPostingStoreAdapter::collect_folded(vespalib::datastore::EntryRef enum_idx, vespalib::datastore::EntryRef dictionary_snapshot, const std::function<void(vespalib::datastore::EntryRef)>& callback) const
{
    const IEnumStoreDictionary &dictionary = self._enumStore.get_dictionary();
    dictionary.collect_folded(enum_idx, dictionary_snapshot, callback);
}

template <typename B, typename T>
void
MultiValueStringPostingAttributeT<B, T>::DocidWithWeightPostingStoreAdapter::create(vespalib::datastore::EntryRef idx, std::vector<DocidWithWeightIterator> &dst) const
{
    assert(idx.valid());
    self.get_posting_store().beginFrozen(idx, dst);
}

template <typename B, typename M>
DocidWithWeightIterator
MultiValueStringPostingAttributeT<B, M>::DocidWithWeightPostingStoreAdapter::create(vespalib::datastore::EntryRef idx) const
{
    assert(idx.valid());
    return self.get_posting_store().beginFrozen(idx);
}

template <typename B, typename M>
bool
MultiValueStringPostingAttributeT<B, M>::DocidWithWeightPostingStoreAdapter::has_weight_iterator(vespalib::datastore::EntryRef idx) const noexcept
{
    return self.get_posting_store().has_btree(idx);
}

template <typename B, typename M>
bool
MultiValueStringPostingAttributeT<B, M>::DocidWithWeightPostingStoreAdapter::has_bitvector(vespalib::datastore::EntryRef idx) const noexcept
{
    return self.get_posting_store().has_bitvector(idx);
}

template <typename B, typename M>
std::unique_ptr<queryeval::SearchIterator>
MultiValueStringPostingAttributeT<B, M>::DocidWithWeightPostingStoreAdapter::make_bitvector_iterator(vespalib::datastore::EntryRef idx, uint32_t doc_id_limit, fef::TermFieldMatchData &match_data, bool strict) const
{
    return self.get_posting_store().make_bitvector_iterator(idx, doc_id_limit, match_data, strict);
}

template <typename B, typename T>
const IDocidWithWeightPostingStore*
MultiValueStringPostingAttributeT<B, T>::as_docid_with_weight_posting_store() const
{
    // TODO: Add support for handling bit vectors too, and lift restriction on isFilter.
    if (this->hasWeightedSetType() && this->isStringType()) {
        return &_posting_store_adapter;
    }
    return nullptr;
}

}

