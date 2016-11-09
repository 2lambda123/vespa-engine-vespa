// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "array_store.h"
#include "datastore.hpp"

namespace search {
namespace datastore {

constexpr size_t MIN_BUFFER_CLUSTERS = 1024;

template <typename EntryT, typename RefT>
void
ArrayStore<EntryT, RefT>::initArrayTypes()
{
    _largeArrayTypeId = _store.addType(&_largeArrayType);
    for (uint32_t arraySize = 1; arraySize <= _maxSmallArraySize; ++arraySize) {
        _smallArrayTypes.push_back(std::make_unique<SmallArrayType>(arraySize, MIN_BUFFER_CLUSTERS, RefT::offsetSize()));
        _smallArrayTypeIds.add(_store.addType(_smallArrayTypes.back().get()), arraySize);
    }
}

template <typename EntryT, typename RefT>
ArrayStore<EntryT, RefT>::ArrayStore(uint32_t maxSmallArraySize)
    : _store(),
      _maxSmallArraySize(maxSmallArraySize),
      _smallArrayTypes(),
      _smallArrayTypeIds(),
      _largeArrayType(1, MIN_BUFFER_CLUSTERS, RefT::offsetSize()),
      _largeArrayTypeId()
{
    initArrayTypes();
    _store.initActiveBuffers();
}

template <typename EntryT, typename RefT>
ArrayStore<EntryT, RefT>::~ArrayStore()
{
    _store.dropBuffers();
}

template <typename EntryT, typename RefT>
EntryRef
ArrayStore<EntryT, RefT>::add(const ConstArrayRef &array)
{
    if (array.size() == 0) {
        return EntryRef();
    }
    if (array.size() <= _maxSmallArraySize) {
        return addSmallArray(array);
    } else {
        return addLargeArray(array);
    }
}

template <typename EntryT, typename RefT>
EntryRef
ArrayStore<EntryT, RefT>::addSmallArray(const ConstArrayRef &array)
{
    uint32_t typeId = _smallArrayTypeIds.getTypeId(array.size());
    _store.ensureBufferCapacity(typeId, array.size());
    uint32_t activeBufferId = _store.getActiveBufferId(typeId);
    BufferState &state = _store.getBufferState(activeBufferId);
    assert(state._state == BufferState::ACTIVE);
    size_t oldBufferSize = state.size();
    EntryT *buf = _store.template getBufferEntry<EntryT>(activeBufferId, oldBufferSize);
    for (size_t i = 0; i < array.size(); ++i) {
        new (static_cast<void *>(buf + i)) EntryT(array[i]);
    }
    state.pushed_back(array.size());
    return RefT((oldBufferSize / array.size()), activeBufferId);
}

template <typename EntryT, typename RefT>
EntryRef
ArrayStore<EntryT, RefT>::addLargeArray(const ConstArrayRef &array)
{
    _store.ensureBufferCapacity(_largeArrayTypeId, 1);
    uint32_t activeBufferId = _store.getActiveBufferId(_largeArrayTypeId);
    BufferState &state = _store.getBufferState(activeBufferId);
    assert(state._state == BufferState::ACTIVE);
    size_t oldBufferSize = state.size();
    LargeArray *buf = _store.template getBufferEntry<LargeArray>(activeBufferId, oldBufferSize);
    new (static_cast<void *>(buf)) LargeArray(array.cbegin(), array.cend());
    state.pushed_back(1);
    return RefT(oldBufferSize, activeBufferId);
}

template <typename EntryT, typename RefT>
typename ArrayStore<EntryT, RefT>::ConstArrayRef
ArrayStore<EntryT, RefT>::get(EntryRef ref) const
{
    if (!ref.valid()) {
        return ConstArrayRef();
    }
    RefT internalRef(ref);
    uint32_t typeId = _store.getTypeId(internalRef.bufferId());
    if (typeId != _largeArrayTypeId) {
        size_t arraySize = _smallArrayTypeIds.getArraySize(typeId);
        return getSmallArray(internalRef, arraySize);
    } else {
        return getLargeArray(internalRef);
    }
}

template <typename EntryT, typename RefT>
typename ArrayStore<EntryT, RefT>::ConstArrayRef
ArrayStore<EntryT, RefT>::getSmallArray(RefT ref, size_t arraySize) const
{
    size_t bufferOffset = ref.offset() * arraySize;
    const EntryT *buf = _store.template getBufferEntry<EntryT>(ref.bufferId(), bufferOffset);
    return ConstArrayRef(buf, arraySize);
}

template <typename EntryT, typename RefT>
typename ArrayStore<EntryT, RefT>::ConstArrayRef
ArrayStore<EntryT, RefT>::getLargeArray(RefT ref) const
{
    const LargeArray *buf = _store.template getBufferEntry<LargeArray>(ref.bufferId(), ref.offset());
    assert(buf->size() > 0);
    return ConstArrayRef(&(*buf)[0], buf->size());
}

}
}
