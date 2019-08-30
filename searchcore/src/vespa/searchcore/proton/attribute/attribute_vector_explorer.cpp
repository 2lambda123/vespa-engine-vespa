// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "attribute_vector_explorer.h"
#include <vespa/searchlib/attribute/i_enum_store.h>
#include <vespa/searchlib/attribute/multi_value_mapping.h>
#include <vespa/searchlib/attribute/attributevector.h>
#include <vespa/searchlib/attribute/ipostinglistattributebase.h>
#include <vespa/vespalib/data/slime/cursor.h>

using search::attribute::Status;
using search::AddressSpaceUsage;
using search::AttributeVector;
using search::IEnumStore;
using vespalib::AddressSpace;
using vespalib::MemoryUsage;
using search::attribute::MultiValueMappingBase;
using search::attribute::IPostingListAttributeBase;
using namespace vespalib::slime;

namespace proton {

namespace {

void
convertStatusToSlime(const Status &status, Cursor &object)
{
    object.setLong("numDocs", status.getNumDocs());
    object.setLong("numValues", status.getNumValues());
    object.setLong("numUniqueValues", status.getNumUniqueValues());
    object.setLong("lastSerialNum", status.getLastSyncToken());
    object.setLong("updateCount", status.getUpdateCount());
    object.setLong("nonIdempotentUpdateCount", status.getNonIdempotentUpdateCount());
    object.setLong("bitVectors", status.getBitVectors());
    {
        Cursor &memory = object.setObject("memoryUsage");
        memory.setLong("allocatedBytes", status.getAllocated());
        memory.setLong("usedBytes", status.getUsed());
        memory.setLong("deadBytes", status.getDead());
        memory.setLong("onHoldBytes", status.getOnHold());
        memory.setLong("onHoldBytesMax", status.getOnHoldMax());
    }
}

void
convertGenerationToSlime(const AttributeVector &attr, Cursor &object)
{
    object.setLong("firstUsed", attr.getFirstUsedGeneration());
    object.setLong("current", attr.getCurrentGeneration());
}

void
convertAddressSpaceToSlime(const AddressSpace &addressSpace, Cursor &object)
{
    object.setDouble("usage", addressSpace.usage());
    object.setLong("used", addressSpace.used());
    object.setLong("dead", addressSpace.dead());
    object.setLong("limit", addressSpace.limit());
}

void
convertAddressSpaceUsageToSlime(const AddressSpaceUsage &usage, Cursor &object)
{
    convertAddressSpaceToSlime(usage.enumStoreUsage(), object.setObject("enumStore"));
    convertAddressSpaceToSlime(usage.multiValueUsage(), object.setObject("multiValue"));
}

void
convertMemoryUsageToSlime(const MemoryUsage &usage, Cursor &object)
{
    object.setLong("allocated", usage.allocatedBytes());
    object.setLong("used", usage.usedBytes());
    object.setLong("dead", usage.deadBytes());
    object.setLong("onHold", usage.allocatedBytesOnHold());
}

void
convertEnumStoreToSlime(const IEnumStore &enumStore, Cursor &object)
{
    object.setLong("numUniques", enumStore.getNumUniques());
    convertMemoryUsageToSlime(enumStore.getValuesMemoryUsage(), object.setObject("valuesMemoryUsage"));
    convertMemoryUsageToSlime(enumStore.getDictionaryMemoryUsage(), object.setObject("dictionaryMemoryUsage"));
}

void
convertMultiValueToSlime(const MultiValueMappingBase &multiValue, Cursor &object)
{
    object.setLong("totalValueCnt", multiValue.getTotalValueCnt());
    convertMemoryUsageToSlime(multiValue.getMemoryUsage(), object.setObject("memoryUsage"));
}

void
convertChangeVectorToSlime(const AttributeVector &v, Cursor &object)
{
    MemoryUsage usage = v.getChangeVectorMemoryUsage();
    convertMemoryUsageToSlime(usage, object);
}

void
convertPostingBaseToSlime(const IPostingListAttributeBase &postingBase, Cursor &object)
{
    convertMemoryUsageToSlime(postingBase.getMemoryUsage(), object.setObject("memoryUsage"));
}

}

AttributeVectorExplorer::AttributeVectorExplorer(ExclusiveAttributeReadAccessor::UP attribute)
    : _attribute(std::move(attribute))
{
}

void
AttributeVectorExplorer::get_state(const vespalib::slime::Inserter &inserter, bool full) const
{
    ExclusiveAttributeReadAccessor::Guard::UP readGuard = _attribute->takeGuard();
    const AttributeVector &attr = readGuard->get();
    const Status &status = attr.getStatus();
    Cursor &object = inserter.insertObject();
    if (full) {
        convertStatusToSlime(status, object.setObject("status"));
        convertGenerationToSlime(attr, object.setObject("generation"));
        convertAddressSpaceUsageToSlime(attr.getAddressSpaceUsage(), object.setObject("addressSpaceUsage"));
        const IEnumStore *enumStore = attr.getEnumStoreBase();
        if (enumStore) {
            convertEnumStoreToSlime(*enumStore, object.setObject("enumStore"));
        }
        const MultiValueMappingBase *multiValue = attr.getMultiValueBase();
        if (multiValue) {
            convertMultiValueToSlime(*multiValue, object.setObject("multiValue"));
        }
        const IPostingListAttributeBase *postingBase = attr.getIPostingListAttributeBase();
        if (postingBase) {
            convertPostingBaseToSlime(*postingBase, object.setObject("postingList"));
        }
        convertChangeVectorToSlime(attr, object.setObject("changeVector"));
        object.setLong("committedDocIdLimit", attr.getCommittedDocIdLimit());
        object.setLong("createSerialNum", attr.getCreateSerialNum());
    } else {
        object.setLong("numDocs", status.getNumDocs());
        object.setLong("lastSerialNum", status.getLastSyncToken());
        object.setLong("allocatedMemory", status.getAllocated());
        object.setLong("committedDocIdLimit", attr.getCommittedDocIdLimit());
    }
}

} // namespace proton
