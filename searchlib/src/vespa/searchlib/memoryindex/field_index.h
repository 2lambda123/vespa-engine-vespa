// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "field_index_base.h"
#include "posting_list_entry.h"
#include <vespa/searchlib/index/indexbuilder.h>
#include <vespa/searchlib/queryeval/searchiterator.h>
#include <vespa/vespalib/btree/btree.h>
#include <vespa/vespalib/btree/btreenodeallocator.h>
#include <vespa/vespalib/btree/btreeroot.h>
#include <vespa/vespalib/btree/btreestore.h>

namespace search::memoryindex {

class IOrderedFieldIndexInserter;

/**
 * Implementation of memory index for a single field using lock-free B-Trees in underlying components.
 *
 * It consists of the following components:
 *   - WordStore containing all unique words in this field (across all documents).
 *   - B-Tree dictionary that maps from unique word (32-bit ref) -> posting list (32-bit ref).
 *   - B-Tree posting lists that maps from document id (32-bit) -> features (32-bit ref).
 *   - BTreeStore containing all the posting lists.
 *   - FeatureStore containing information on where a (word, document) pair matched this field.
 *     This information is unpacked and used during ranking.
 *
 * Elements in the three stores are accessed using 32-bit references / handles.
 *
 * The template parameter specifies whether the underlying posting lists have interleaved features or not.
 */
template <bool interleaved_features>
class FieldIndex : public FieldIndexBase {
public:
    static constexpr bool has_interleaved_features = interleaved_features;

    // Mapping from docid -> feature ref
    using PostingListEntryType = PostingListEntry<interleaved_features>;
    using PostingList = btree::BTreeRoot<uint32_t, PostingListEntryType, search::btree::NoAggregated>;
    using PostingListStore = btree::BTreeStore<uint32_t, PostingListEntryType,
                                               search::btree::NoAggregated,
                                               std::less<uint32_t>,
                                               btree::BTreeDefaultTraits>;
    using PostingListKeyDataType = typename PostingListStore::KeyDataType;

private:
    PostingListStore _postingListStore;

    void freeze() {
        _postingListStore.freeze();
        _dict.getAllocator().freeze();
    }

    void trimHoldLists() {
        GenerationHandler::generation_t usedGen =
            _generationHandler.getFirstUsedGeneration();
        _postingListStore.trimHoldLists(usedGen);
        _dict.getAllocator().trimHoldLists(usedGen);
        _featureStore.trimHoldLists(usedGen);
    }

    void transferHoldLists() {
        GenerationHandler::generation_t generation =
            _generationHandler.getCurrentGeneration();
        _postingListStore.transferHoldLists(generation);
        _dict.getAllocator().transferHoldLists(generation);
        _featureStore.transferHoldLists(generation);
    }

    void incGeneration() {
        _generationHandler.incGeneration();
    }

public:
    FieldIndex(const index::Schema& schema, uint32_t fieldId);
    FieldIndex(const index::Schema& schema, uint32_t fieldId, const index::FieldLengthInfo& info);
    ~FieldIndex();

    typename PostingList::Iterator find(const vespalib::stringref word) const;
    typename PostingList::ConstIterator findFrozen(const vespalib::stringref word) const;

    void compactFeatures() override;

    void dump(search::index::IndexBuilder & indexBuilder) override;

    vespalib::MemoryUsage getMemoryUsage() const override;
    PostingListStore &getPostingListStore() { return _postingListStore; }

    void commit() override {
        _remover.flush();
        freeze();
        transferHoldLists();
        incGeneration();
        trimHoldLists();
    }

    /**
     * Should only by used by unit tests.
     */
    queryeval::SearchIterator::UP make_search_iterator(const vespalib::string& term,
                                                       uint32_t field_id,
                                                       const fef::TermFieldMatchDataArray& match_data) const;

    std::unique_ptr<queryeval::SimpleLeafBlueprint> make_term_blueprint(const vespalib::string& term,
                                                                        const queryeval::FieldSpecBase& field,
                                                                        uint32_t field_id) override;
};

}

namespace search::btree {

extern template
class BTreeNodeDataWrap<memoryindex::FieldIndexBase::WordKey,
                        BTreeDefaultTraits::LEAF_SLOTS>;

extern template
class BTreeNodeT<memoryindex::FieldIndexBase::WordKey,
                 BTreeDefaultTraits::INTERNAL_SLOTS>;

#if 0
extern template
class BTreeNodeT<memoryindex::FieldIndexBase::WordKey,
                 BTreeDefaultTraits::LEAF_SLOTS>;
#endif

extern template
class BTreeNodeTT<memoryindex::FieldIndexBase::WordKey,
                  datastore::EntryRef,
                  search::btree::NoAggregated,
                  BTreeDefaultTraits::INTERNAL_SLOTS>;

extern template
class BTreeNodeTT<memoryindex::FieldIndexBase::WordKey,
                  memoryindex::FieldIndexBase::PostingListPtr,
                  search::btree::NoAggregated,
                  BTreeDefaultTraits::LEAF_SLOTS>;

extern template
class BTreeInternalNode<memoryindex::FieldIndexBase::WordKey,
                        search::btree::NoAggregated,
                        BTreeDefaultTraits::INTERNAL_SLOTS>;

extern template
class BTreeLeafNode<memoryindex::FieldIndexBase::WordKey,
                    memoryindex::FieldIndexBase::PostingListPtr,
                    search::btree::NoAggregated,
                    BTreeDefaultTraits::LEAF_SLOTS>;

extern template
class BTreeNodeStore<memoryindex::FieldIndexBase::WordKey,
                     memoryindex::FieldIndexBase::PostingListPtr,
                     search::btree::NoAggregated,
                     BTreeDefaultTraits::INTERNAL_SLOTS,
                     BTreeDefaultTraits::LEAF_SLOTS>;

extern template
class BTreeIterator<memoryindex::FieldIndexBase::WordKey,
                    memoryindex::FieldIndexBase::PostingListPtr,
                    search::btree::NoAggregated,
                    const memoryindex::FieldIndexBase::KeyComp,
                    BTreeDefaultTraits>;

extern template
class BTree<memoryindex::FieldIndexBase::WordKey,
            memoryindex::FieldIndexBase::PostingListPtr,
            search::btree::NoAggregated,
            const memoryindex::FieldIndexBase::KeyComp,
            BTreeDefaultTraits>;

extern template
class BTreeRoot<memoryindex::FieldIndexBase::WordKey,
                memoryindex::FieldIndexBase::PostingListPtr,
                search::btree::NoAggregated,
                const memoryindex::FieldIndexBase::KeyComp,
                BTreeDefaultTraits>;

extern template
class BTreeRootBase<memoryindex::FieldIndexBase::WordKey,
                    memoryindex::FieldIndexBase::PostingListPtr,
                    search::btree::NoAggregated,
                    BTreeDefaultTraits::INTERNAL_SLOTS,
                    BTreeDefaultTraits::LEAF_SLOTS>;

extern template
class BTreeNodeAllocator<memoryindex::FieldIndexBase::WordKey,
                         memoryindex::FieldIndexBase::PostingListPtr,
                         search::btree::NoAggregated,
                         BTreeDefaultTraits::INTERNAL_SLOTS,
                         BTreeDefaultTraits::LEAF_SLOTS>;

}
