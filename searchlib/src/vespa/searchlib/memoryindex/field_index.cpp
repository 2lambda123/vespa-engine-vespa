// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "field_index.h"
#include "ordered_field_index_inserter.h"
#include <vespa/vespalib/util/stringfmt.h>
#include <vespa/vespalib/util/exceptions.h>
#include <vespa/searchlib/bitcompression/posocccompression.h>
#include <vespa/searchlib/btree/btreenode.hpp>
#include <vespa/searchlib/btree/btreenodeallocator.hpp>
#include <vespa/searchlib/btree/btreenodestore.hpp>
#include <vespa/searchlib/btree/btreestore.hpp>
#include <vespa/searchlib/btree/btreeiterator.hpp>
#include <vespa/searchlib/btree/btreeroot.hpp>
#include <vespa/searchlib/btree/btree.hpp>
#include <vespa/vespalib/util/array.hpp>

using search::index::DocIdAndFeatures;
using search::index::WordDocElementFeatures;
using search::index::Schema;

namespace search::memoryindex {

using datastore::EntryRef;

vespalib::asciistream &
operator<<(vespalib::asciistream & os, const FieldIndex::WordKey & rhs)
{
    os << "wr(" << rhs._wordRef.ref() << ")";
    return os;
}

FieldIndex::FieldIndex(const Schema & schema, uint32_t fieldId)
    : _wordStore(),
      _numUniqueWords(0),
      _generationHandler(),
      _dict(),
      _postingListStore(),
      _featureStore(schema),
      _fieldId(fieldId),
      _remover(_wordStore),
      _inserter(std::make_unique<OrderedFieldIndexInserter>(*this))
{ }

FieldIndex::~FieldIndex()
{
    _postingListStore.disableFreeLists();
    _postingListStore.disableElemHoldList();
    _dict.disableFreeLists();
    _dict.disableElemHoldList();
    // XXX: Kludge
    for (DictionaryTree::Iterator it = _dict.begin();
         it.valid(); ++it) {
        EntryRef pidx(it.getData());
        if (pidx.valid()) {
            _postingListStore.clear(pidx);
            // Before updating ref
            std::atomic_thread_fence(std::memory_order_release);
            it.writeData(EntryRef().ref());
        }
    }
    _postingListStore.clearBuilder();
    freeze();   // Flush all pending posting list tree freezes
    transferHoldLists();
    _dict.clear();  // Clear dictionary
    freeze();   // Flush pending freeze for dictionary tree.
    transferHoldLists();
    incGeneration();
    trimHoldLists();
}

FieldIndex::PostingList::Iterator
FieldIndex::find(const vespalib::stringref word) const
{
    DictionaryTree::Iterator itr = _dict.find(WordKey(EntryRef()), KeyComp(_wordStore, word));
    if (itr.valid()) {
        return _postingListStore.begin(EntryRef(itr.getData()));
    }
    return PostingList::Iterator();
}

FieldIndex::PostingList::ConstIterator
FieldIndex::findFrozen(const vespalib::stringref word) const
{
    auto itr = _dict.getFrozenView().find(WordKey(EntryRef()), KeyComp(_wordStore, word));
    if (itr.valid()) {
        return _postingListStore.beginFrozen(EntryRef(itr.getData()));
    }
    return PostingList::Iterator();
}

void
FieldIndex::compactFeatures()
{
    std::vector<uint32_t> toHold;

    toHold = _featureStore.startCompact();
    auto itr = _dict.begin();
    uint32_t packedIndex = _fieldId;
    for (; itr.valid(); ++itr) {
        PostingListStore::RefType pidx(EntryRef(itr.getData()));
        if (!pidx.valid()) {
            continue;
        }
        uint32_t clusterSize = _postingListStore.getClusterSize(pidx);
        if (clusterSize == 0) {
            const PostingList *tree = _postingListStore.getTreeEntry(pidx);
            auto pitr = tree->begin(_postingListStore.getAllocator());
            for (; pitr.valid(); ++pitr) {
                EntryRef oldFeatures(pitr.getData());

                // Filter on which buffers to move features from when
                // performing incremental compaction.

                EntryRef newFeatures = _featureStore.moveFeatures(packedIndex, oldFeatures);

                // Features must be written before reference is updated.
                std::atomic_thread_fence(std::memory_order_release);

                // Ugly, ugly due to const_cast in iterator
                pitr.writeData(newFeatures.ref());
            }
        } else {
            const PostingListKeyDataType *shortArray = _postingListStore.getKeyDataEntry(pidx, clusterSize);
            const PostingListKeyDataType *ite = shortArray + clusterSize;
            for (const PostingListKeyDataType *it = shortArray; it < ite; ++it) {
                EntryRef oldFeatures(it->getData());

                // Filter on which buffers to move features from when
                // performing incremental compaction.

                EntryRef newFeatures = _featureStore.moveFeatures(packedIndex, oldFeatures);

                // Features must be written before reference is updated.
                std::atomic_thread_fence(std::memory_order_release);

                // Ugly, ugly due to const_cast, but new data is
                // semantically equal to old data
                const_cast<PostingListKeyDataType *>(it)->setData(newFeatures.ref());
            }
        }
    }
    using generation_t = GenerationHandler::generation_t;
    _featureStore.finishCompact(toHold);
    generation_t generation = _generationHandler.getCurrentGeneration();
    _featureStore.transferHoldLists(generation);
}

void
FieldIndex::dump(search::index::IndexBuilder & indexBuilder)
{
    vespalib::stringref word;
    FeatureStore::DecodeContextCooked decoder(nullptr);
    DocIdAndFeatures features;
    vespalib::Array<uint32_t> wordMap(_numUniqueWords + 1, 0);
    _featureStore.setupForField(_fieldId, decoder);
    for (auto itr = _dict.begin(); itr.valid(); ++itr) {
        const WordKey & wk = itr.getKey();
        PostingListStore::RefType plist(EntryRef(itr.getData()));
        word = _wordStore.getWord(wk._wordRef);
        if (!plist.valid()) {
            continue;
        }
        indexBuilder.startWord(word);
        uint32_t clusterSize = _postingListStore.getClusterSize(plist);
        if (clusterSize == 0) {
            const PostingList *tree = _postingListStore.getTreeEntry(plist);
            auto pitr = tree->begin(_postingListStore.getAllocator());
            assert(pitr.valid());
            for (; pitr.valid(); ++pitr) {
                uint32_t docId = pitr.getKey();
                EntryRef featureRef(pitr.getData());
                _featureStore.setupForReadFeatures(featureRef, decoder);
                decoder.readFeatures(features);
                features.set_doc_id(docId);
                indexBuilder.add_document(features);
            }
        } else {
            const PostingListKeyDataType *kd =
                _postingListStore.getKeyDataEntry(plist, clusterSize);
            const PostingListKeyDataType *kde = kd + clusterSize;
            for (; kd != kde; ++kd) {
                uint32_t docId = kd->_key;
                EntryRef featureRef(kd->getData());
                _featureStore.setupForReadFeatures(featureRef, decoder);
                decoder.readFeatures(features);
                features.set_doc_id(docId);
                indexBuilder.add_document(features);
            }
        }
        indexBuilder.endWord();
    }
}

MemoryUsage
FieldIndex::getMemoryUsage() const
{
    MemoryUsage usage;
    usage.merge(_wordStore.getMemoryUsage());
    usage.merge(_dict.getMemoryUsage());
    usage.merge(_postingListStore.getMemoryUsage());
    usage.merge(_featureStore.getMemoryUsage());
    usage.merge(_remover.getStore().getMemoryUsage());
    return usage;
}

}

namespace search::btree {

template
class BTreeNodeDataWrap<memoryindex::FieldIndex::WordKey, BTreeDefaultTraits::LEAF_SLOTS>;

template
class BTreeNodeT<memoryindex::FieldIndex::WordKey, BTreeDefaultTraits::INTERNAL_SLOTS>;

#if 0
template
class BTreeNodeT<memoryindex::FieldIndex::WordKey,
                 BTreeDefaultTraits::LEAF_SLOTS>;
#endif

template
class BTreeNodeTT<memoryindex::FieldIndex::WordKey,
                  datastore::EntryRef,
                  search::btree::NoAggregated,
                  BTreeDefaultTraits::INTERNAL_SLOTS>;

template
class BTreeNodeTT<memoryindex::FieldIndex::WordKey,
                  memoryindex::FieldIndex::PostingListPtr,
                  search::btree::NoAggregated,
                  BTreeDefaultTraits::LEAF_SLOTS>;

template
class BTreeInternalNode<memoryindex::FieldIndex::WordKey,
                        search::btree::NoAggregated,
                        BTreeDefaultTraits::INTERNAL_SLOTS>;

template
class BTreeLeafNode<memoryindex::FieldIndex::WordKey,
                    memoryindex::FieldIndex::PostingListPtr,
                    search::btree::NoAggregated,
                    BTreeDefaultTraits::LEAF_SLOTS>;

template
class BTreeNodeStore<memoryindex::FieldIndex::WordKey,
                     memoryindex::FieldIndex::PostingListPtr,
                     search::btree::NoAggregated,
                     BTreeDefaultTraits::INTERNAL_SLOTS,
                     BTreeDefaultTraits::LEAF_SLOTS>;

template
class BTreeIterator<memoryindex::FieldIndex::WordKey,
                    memoryindex::FieldIndex::PostingListPtr,
                    search::btree::NoAggregated,
                    const memoryindex::FieldIndex::KeyComp,
                    BTreeDefaultTraits>;

template
class BTree<memoryindex::FieldIndex::WordKey,
            memoryindex::FieldIndex::PostingListPtr,
            search::btree::NoAggregated,
            const memoryindex::FieldIndex::KeyComp,
            BTreeDefaultTraits>;

template
class BTreeRoot<memoryindex::FieldIndex::WordKey,
                memoryindex::FieldIndex::PostingListPtr,
                search::btree::NoAggregated,
                const memoryindex::FieldIndex::KeyComp,
                BTreeDefaultTraits>;

template
class BTreeRootBase<memoryindex::FieldIndex::WordKey,
                    memoryindex::FieldIndex::PostingListPtr,
                    search::btree::NoAggregated,
                    BTreeDefaultTraits::INTERNAL_SLOTS,
                    BTreeDefaultTraits::LEAF_SLOTS>;

template
class BTreeNodeAllocator<memoryindex::FieldIndex::WordKey,
                         memoryindex::FieldIndex::PostingListPtr,
                         search::btree::NoAggregated,
                         BTreeDefaultTraits::INTERNAL_SLOTS,
                         BTreeDefaultTraits::LEAF_SLOTS>;

}
