// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "bufferstate.h"
#include "datastore.h"
#include "entryref.h"
#include <vespa/vespalib/util/array.h>
#include <unordered_map>

namespace search {
namespace datastore {

/**
 * Datastore for storing arrays of type EntryT that is accessed via a 32-bit EntryRef.
 *
 * The default EntryRef type uses 17 bits for offset (131072 values) and 15 bits for buffer id (32768 buffers).
 * Arrays of size [1,maxSmallArraySize] are stored in buffers with arrays of equal size.
 * Arrays of size >maxSmallArraySize are stored in buffers with vespalib::Array instances that are heap allocated.
 *
 * The max value of maxSmallArraySize is (2^bufferBits - 1).
 */
template <typename EntryT, typename RefT = EntryRefT<17> >
class ArrayStore
{
public:
    using ConstArrayRef = vespalib::ConstArrayRef<EntryT>;
    using DataStoreType  = DataStoreT<RefT>;
    using SmallArrayType = BufferType<EntryT>;
    using LargeArray = vespalib::Array<EntryT>;
    using LargeArrayType = BufferType<LargeArray>;

private:
    DataStoreType _store;
    uint32_t _maxSmallArraySize;
    std::vector<std::unique_ptr<SmallArrayType>> _smallArrayTypes;
    LargeArrayType _largeArrayType;
    uint32_t _largeArrayTypeId;

    void initArrayTypes();
    // 1-to-1 mapping between type ids and array sizes is enforced during initialization.
    uint32_t getTypeId(size_t arraySize) const { return arraySize; }
    size_t getArraySize(uint32_t typeId) const { return typeId; }
    EntryRef addSmallArray(const ConstArrayRef &array);
    EntryRef addLargeArray(const ConstArrayRef &array);
    ConstArrayRef getSmallArray(RefT ref, size_t arraySize) const;
    ConstArrayRef getLargeArray(RefT ref) const;

public:
    ArrayStore(uint32_t maxSmallArraySize);
    ~ArrayStore();
    EntryRef add(const ConstArrayRef &array);
    ConstArrayRef get(EntryRef ref) const;
};

}
}
