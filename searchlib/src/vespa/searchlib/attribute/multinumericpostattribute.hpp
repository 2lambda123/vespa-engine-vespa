// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "multinumericpostattribute.h"
#include "multi_numeric_enum_search_context.h"
#include <vespa/searchcommon/attribute/config.h>
#include <charconv>

namespace search {

template <typename B, typename M>
void
MultiValueNumericPostingAttribute<B, M>::freezeEnumDictionary()
{
    this->getEnumStore().freeze_dictionary();
}

template <typename B, typename M>
void
MultiValueNumericPostingAttribute<B, M>::mergeMemoryStats(vespalib::MemoryUsage & total)
{
    auto& compaction_strategy = this->getConfig().getCompactionStrategy();
    total.merge(this->get_posting_store().update_stat(compaction_strategy));
}

template <typename B, typename M>
void
MultiValueNumericPostingAttribute<B, M>::applyValueChanges(const DocIndices& docIndices,
                                                           EnumStoreBatchUpdater& updater)
{
    using PostingChangeComputer = PostingChangeComputerT<WeightedIndex, PostingMap>;

    EnumIndexMapper mapper;
    PostingMap changePost(PostingChangeComputer::compute(this->getMultiValueMapping(), docIndices,
                                                         this->getEnumStore().get_comparator(), mapper));
    this->updatePostings(changePost);
    MultiValueNumericEnumAttribute<B, M>::applyValueChanges(docIndices, updater);
}

template <typename B, typename M>
MultiValueNumericPostingAttribute<B, M>::MultiValueNumericPostingAttribute(const vespalib::string & name,
                                                                           const AttributeVector::Config & cfg)
    : MultiValueNumericEnumAttribute<B, M>(name, cfg),
      PostingParent(*this, this->getEnumStore()),
      _posting_store_adapter(*this)
{
}

template <typename B, typename M>
MultiValueNumericPostingAttribute<B, M>::~MultiValueNumericPostingAttribute()
{
    this->disableFreeLists();
    this->disable_entry_hold_list();
    clearAllPostings();
}

template <typename B, typename M>
void
MultiValueNumericPostingAttribute<B, M>::reclaim_memory(generation_t oldest_used_gen)
{
    MultiValueNumericEnumAttribute<B, M>::reclaim_memory(oldest_used_gen);
    _posting_store.reclaim_memory(oldest_used_gen);
}

template <typename B, typename M>
void
MultiValueNumericPostingAttribute<B, M>::before_inc_generation(generation_t current_gen)
{
    _posting_store.freeze();
    MultiValueNumericEnumAttribute<B, M>::before_inc_generation(current_gen);
    _posting_store.assign_generation(current_gen);
}

template <typename B, typename M>
std::unique_ptr<attribute::SearchContext>
MultiValueNumericPostingAttribute<B, M>::getSearch(QueryTermSimpleUP qTerm,
                                                   const attribute::SearchContextParams & params) const
{
    using BaseSC = attribute::MultiNumericEnumSearchContext<typename B::BaseClass::BaseType, M>;
    using SC = attribute::NumericPostingSearchContext<BaseSC, SelfType, int32_t>;
    auto doc_id_limit = this->getCommittedDocIdLimit();
    BaseSC base_sc(std::move(qTerm), *this, this->_mvMapping.make_read_view(doc_id_limit), this->_enumStore);
    return std::make_unique<SC>(std::move(base_sc), params, *this);
}

template <typename B, typename M>
vespalib::datastore::EntryRef
MultiValueNumericPostingAttribute<B, M>::DocidWithWeightPostingStoreAdapter::get_dictionary_snapshot() const
{
    const IEnumStoreDictionary& dictionary = self._enumStore.get_dictionary();
    return dictionary.get_frozen_root();
}

template <typename B, typename M>
IDirectPostingStore::LookupResult
MultiValueNumericPostingAttribute<B, M>::DocidWithWeightPostingStoreAdapter::lookup(const LookupKey & key, vespalib::datastore::EntryRef dictionary_snapshot) const
{
    const IEnumStoreDictionary& dictionary = self._enumStore.get_dictionary();
    int64_t int_term;
    if ( !key.asInteger(int_term)) {
        return LookupResult();
    }
    auto comp = self._enumStore.make_comparator(int_term);
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

template <typename B, typename M>
void
MultiValueNumericPostingAttribute<B, M>::DocidWithWeightPostingStoreAdapter::collect_folded(vespalib::datastore::EntryRef enum_idx, vespalib::datastore::EntryRef dictionary_snapshot, const std::function<void(vespalib::datastore::EntryRef)>& callback)const
{
    (void) dictionary_snapshot;
    callback(enum_idx);
}

template <typename B, typename M>
void
MultiValueNumericPostingAttribute<B, M>::DocidWithWeightPostingStoreAdapter::create(vespalib::datastore::EntryRef posting_idx, std::vector<DocidWithWeightIterator> &dst) const
{
    assert(posting_idx.valid());
    self.get_posting_store().beginFrozen(posting_idx, dst);
}

template <typename B, typename M>
DocidWithWeightIterator
MultiValueNumericPostingAttribute<B, M>::DocidWithWeightPostingStoreAdapter::create(vespalib::datastore::EntryRef posting_idx) const
{
    assert(posting_idx.valid());
    return self.get_posting_store().beginFrozen(posting_idx);
}

template <typename B, typename M>
std::unique_ptr<queryeval::SearchIterator>
MultiValueNumericPostingAttribute<B, M>::DocidWithWeightPostingStoreAdapter::make_bitvector_iterator(vespalib::datastore::EntryRef posting_idx, uint32_t doc_id_limit, fef::TermFieldMatchData &match_data, bool strict) const
{
    return self.get_posting_store().make_bitvector_iterator(posting_idx, doc_id_limit, match_data, strict);
}

template <typename B, typename M>
bool
MultiValueNumericPostingAttribute<B, M>::DocidWithWeightPostingStoreAdapter::has_weight_iterator(vespalib::datastore::EntryRef posting_idx) const noexcept
{
    return self.get_posting_store().has_btree(posting_idx);
}

template <typename B, typename M>
bool
MultiValueNumericPostingAttribute<B, M>::DocidWithWeightPostingStoreAdapter::has_bitvector(vespalib::datastore::EntryRef posting_idx) const noexcept
{
    return self.get_posting_store().has_bitvector(posting_idx);
}

template <typename B, typename M>
int64_t
MultiValueNumericPostingAttribute<B, M>::DocidWithWeightPostingStoreAdapter::get_integer_value(vespalib::datastore::EntryRef enum_idx) const noexcept
{
    return self._enumStore.get_value(enum_idx);
}

template <typename B, typename M>
const IDocidWithWeightPostingStore*
MultiValueNumericPostingAttribute<B, M>::as_docid_with_weight_posting_store() const
{
    if (this->hasWeightedSetType() && (this->getBasicType() == AttributeVector::BasicType::INT64)) {
        return &_posting_store_adapter;
    }
    return nullptr;
}

}

