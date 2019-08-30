// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "singleenumattribute.h"
#include "enumattribute.hpp"
#include "ipostinglistattributebase.h"
#include "singleenumattributesaver.h"
#include "load_utils.h"

namespace search {

template <typename B>
SingleValueEnumAttribute<B>::
SingleValueEnumAttribute(const vespalib::string &baseFileName,
                         const AttributeVector::Config &cfg)
    : B(baseFileName, cfg),
      SingleValueEnumAttributeBase(cfg, getGenerationHolder())
{
}

template <typename B>
SingleValueEnumAttribute<B>::~SingleValueEnumAttribute()
{
    getGenerationHolder().clearHoldLists();
}

template <typename B>
bool
SingleValueEnumAttribute<B>::onAddDoc(DocId doc)
{
    if (doc < _enumIndices.capacity()) {
        _enumIndices.reserve(doc+1);
        return true;
    }
    return false;
}

template <typename B>
void
SingleValueEnumAttribute<B>::onAddDocs(DocId limit)
{
    _enumIndices.reserve(limit);
}

template <typename B>
bool
SingleValueEnumAttribute<B>::addDoc(DocId & doc)
{
    bool incGen = false;
    doc = SingleValueEnumAttributeBase::addDoc(incGen);
    if (doc > 0u) {
        // Make sure that a valid value(magic default) is referenced,
        // even between addDoc and commit().
        if (_enumIndices[0].valid()) {
            _enumIndices[doc] = _enumIndices[0];
            this->_enumStore.incRefCount(_enumIndices[0]);
        }
    }
    this->incNumDocs();
    this->updateUncommittedDocIdLimit(doc);
    incGen |= onAddDoc(doc);
    if (incGen) {
        this->incGeneration();
    } else
        this->removeAllOldGenerations();
    return true;
}

template <typename B>
uint32_t
SingleValueEnumAttribute<B>::getValueCount(DocId doc) const
{
    if (doc >= this->getNumDocs()) {
        return 0;
    }
    return 1;
}

template <typename B>
void
SingleValueEnumAttribute<B>::onCommit()
{
    this->checkSetMaxValueCount(1);

    // update enum store
    auto updater = this->_enumStore.make_batch_updater();
    this->insertNewUniqueValues(updater);
    // apply updates
    applyValueChanges(updater);
    this->_changes.clear();
    updater.commit();
    freezeEnumDictionary();
    std::atomic_thread_fence(std::memory_order_release);
    this->removeAllOldGenerations();
}

template <typename B>
void
SingleValueEnumAttribute<B>::onUpdateStat()
{
    // update statistics
    vespalib::MemoryUsage total = _enumIndices.getMemoryUsage();
    total.mergeGenerationHeldBytes(getGenerationHolder().getHeldBytes());
    total.merge(this->_enumStore.getValuesMemoryUsage());
    total.merge(this->_enumStore.getDictionaryMemoryUsage());
    total.merge(this->getChangeVectorMemoryUsage());
    mergeMemoryStats(total);
    this->updateStatistics(_enumIndices.size(), this->_enumStore.getNumUniques(), total.allocatedBytes(),
                     total.usedBytes(), total.deadBytes(), total.allocatedBytesOnHold());
}

template <typename B>
void
SingleValueEnumAttribute<B>::considerUpdateAttributeChange(const Change & c, UniqueSet & newUniques)
{
    EnumIndex idx;
    if (!this->_enumStore.findIndex(c._data.raw(), idx)) {
        newUniques.insert(c._data);
    }
    considerUpdateAttributeChange(c); // for numeric
}

template <typename B>
void
SingleValueEnumAttribute<B>::considerAttributeChange(const Change & c, UniqueSet & newUniques)
{
    if (c._type == ChangeBase::UPDATE) {
        considerUpdateAttributeChange(c, newUniques);
    } else if (c._type >= ChangeBase::ADD && c._type <= ChangeBase::DIV) {
        considerArithmeticAttributeChange(c, newUniques); // for numeric
    } else if (c._type == ChangeBase::CLEARDOC) {
        this->_defaultValue._doc = c._doc;
        considerUpdateAttributeChange(this->_defaultValue, newUniques);
    }
}

template <typename B>
void
SingleValueEnumAttribute<B>::reEnumerate(const EnumIndexMap & old2New)
{
    this->logEnumStoreEvent("reenumerate", "reserved");
    auto newIndexes = std::make_unique<vespalib::Array<EnumIndex>>();
    newIndexes->reserve(_enumIndices.capacity());
    this->logEnumStoreEvent("reenumerate", "start");
    for (uint32_t i = 0; i < _enumIndices.size(); ++i) {
        EnumIndex oldIdx = _enumIndices[i];
        EnumIndex newIdx;
        if (oldIdx.valid()) {
            newIdx = old2New[oldIdx];
        }
        newIndexes->push_back_fast(newIdx);
    }
    this->logEnumStoreEvent("compactfixup", "drain");
    {
        EnumModifier enumGuard(this->getEnumModifier());
        this->logEnumStoreEvent("compactfixup", "start");
        _enumIndices.replaceVector(std::move(newIndexes));
    }
    this->logEnumStoreEvent("compactfixup", "complete");
    this->logEnumStoreEvent("reenumerate", "complete");
}

template <typename B>
void
SingleValueEnumAttribute<B>::applyUpdateValueChange(const Change& c, EnumStoreBatchUpdater& updater)
{
    EnumIndex oldIdx = _enumIndices[c._doc];
    EnumIndex newIdx;
    this->_enumStore.findIndex(c._data.raw(), newIdx);
    updateEnumRefCounts(c, newIdx, oldIdx, updater);
}

template <typename B>
void
SingleValueEnumAttribute<B>::applyValueChanges(EnumStoreBatchUpdater& updater)
{
    ValueModifier valueGuard(this->getValueModifier());
    for (const auto& change : this->_changes) {
        if (change._type == ChangeBase::UPDATE) {
            applyUpdateValueChange(change, updater);
        } else if (change._type >= ChangeBase::ADD && change._type <= ChangeBase::DIV) {
            applyArithmeticValueChange(change, updater);
        } else if (change._type == ChangeBase::CLEARDOC) {
            this->_defaultValue._doc = change._doc;
            applyUpdateValueChange(this->_defaultValue, updater);
        }
    }
}

template <typename B>
void
SingleValueEnumAttribute<B>::updateEnumRefCounts(const Change& c, EnumIndex newIdx, EnumIndex oldIdx,
                                                 EnumStoreBatchUpdater& updater)
{
    updater.inc_ref_count(newIdx);
    _enumIndices[c._doc] = newIdx;
    if (oldIdx.valid()) {
        updater.dec_ref_count(oldIdx);
    }
}

template <typename B>
void
SingleValueEnumAttribute<B>::fillValues(LoadedVector & loaded)
{
    if constexpr (!std::is_same_v<LoadedVector, NoLoadedVector>) {
        uint32_t numDocs = this->getNumDocs();
        getGenerationHolder().clearHoldLists();
        _enumIndices.reset();
        _enumIndices.unsafe_reserve(numDocs);
        for (DocId doc = 0; doc < numDocs; ++doc, loaded.next()) {
            _enumIndices.push_back(loaded.read().getEidx());
        }
    }
}


template <typename B>
void
SingleValueEnumAttribute<B>::fillEnumIdx(ReaderBase &attrReader,
                                         const IEnumStore::IndexVector &eidxs,
                                         LoadedEnumAttributeVector &loaded)
{
    attribute::loadFromEnumeratedSingleValue(_enumIndices,
                                             getGenerationHolder(),
                                             attrReader,
                                             eidxs,
                                             attribute::SaveLoadedEnum(loaded));
}
    

template <typename B>
void
SingleValueEnumAttribute<B>::fillEnumIdx(ReaderBase &attrReader,
                                         const IEnumStore::IndexVector &eidxs,
                                         IEnumStore::EnumVector &enumHist)
{
    attribute::loadFromEnumeratedSingleValue(_enumIndices,
                                             getGenerationHolder(),
                                             attrReader,
                                             eidxs,
                                             attribute::SaveEnumHist(enumHist));
}
    


template <typename B>
void
SingleValueEnumAttribute<B>::removeOldGenerations(generation_t firstUsed)
{
    this->_enumStore.trimHoldLists(firstUsed);
    getGenerationHolder().trimHoldLists(firstUsed);
}

template <typename B>
void
SingleValueEnumAttribute<B>::onGenerationChange(generation_t generation)
{
    /*
     * Freeze tree before generation is increased in attribute vector
     * but after generation is increased in tree. This ensures that
     * unlocked readers accessing a frozen tree will access a
     * sufficiently new frozen tree.
     */
    freezeEnumDictionary();
    getGenerationHolder().transferHoldLists(generation - 1);
    this->_enumStore.transferHoldLists(generation - 1);
}


template <typename B>
void
SingleValueEnumAttribute<B>::clearDocs(DocId lidLow, DocId lidLimit)
{
    EnumHandle e(0);
    bool findDefaultEnumRes(this->findEnum(this->getDefaultEnumTypeValue(), e));
    if (!findDefaultEnumRes) {
        e = EnumHandle();
    }
    assert(lidLow <= lidLimit);
    assert(lidLimit <= this->getNumDocs());
    for (DocId lid = lidLow; lid < lidLimit; ++lid) {
        if (_enumIndices[lid] != datastore::EntryRef(e)) {
            this->clearDoc(lid);
        }
    }
}


template <typename B>
void
SingleValueEnumAttribute<B>::onShrinkLidSpace()
{
    EnumHandle e(0);
    bool findDefaultEnumRes(this->findEnum(this->getDefaultEnumTypeValue(), e));
    assert(findDefaultEnumRes);
    uint32_t committedDocIdLimit = this->getCommittedDocIdLimit();
    assert(_enumIndices.size() >= committedDocIdLimit);
    attribute::IPostingListAttributeBase *pab = this->getIPostingListAttributeBase();
    if (pab != nullptr) {
        pab->clearPostings(e, committedDocIdLimit, _enumIndices.size());
    }
    _enumIndices.shrink(committedDocIdLimit);
    this->setNumDocs(committedDocIdLimit);
}

template <typename B>
std::unique_ptr<AttributeSaver>
SingleValueEnumAttribute<B>::onInitSave(vespalib::stringref fileName)
{
    auto guard = this->getGenerationHandler().takeGuard();
    return std::make_unique<SingleValueEnumAttributeSaver>
        (std::move(guard),
         this->createAttributeHeader(fileName),
         getIndicesCopy(this->getCommittedDocIdLimit()),
         this->_enumStore);
}

} // namespace search
