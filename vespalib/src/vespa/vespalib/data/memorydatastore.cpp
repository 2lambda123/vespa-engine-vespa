// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/vespalib/data/memorydatastore.h>

namespace vespalib {

MemoryDataStore::MemoryDataStore(size_t initialSize, Lock * lock) :
    _buffers(),
    _writePos(0),
    _lock(lock)
{
    _buffers.reserve(24);
    _buffers.emplace_back(initialSize);
}

MemoryDataStore::~MemoryDataStore()
{
}

MemoryDataStore::Reference
MemoryDataStore::push_back(const void * data, const size_t sz)
{
    std::unique_ptr<LockGuard> guard;
    if  (_lock != nullptr) {
        guard.reset(new LockGuard(*_lock));
    }
    const Alloc & b = _buffers.back();
    if ((sz + _writePos) > b.size()) {
        size_t newSize(std::max(sz, _buffers.back().size()*2));
        _buffers.emplace_back(newSize);
        _writePos = 0;
    }
    Alloc & buf = _buffers.back();
    Reference ref(static_cast<char *>(buf.get()) + _writePos);
    _writePos += sz;
    guard.reset();
    memcpy(ref.data(), data, sz);
    return ref;
}

VariableSizeVector::VariableSizeVector(size_t initialSize) :
    _vector(),
    _store(initialSize)
{
}

VariableSizeVector::~VariableSizeVector()
{
}

VariableSizeVector::Reference
VariableSizeVector::push_back(const void * data, const size_t sz)
{
    MemoryDataStore::Reference ptr(_store.push_back(data, sz));
    _vector.push_back(Reference(ptr.data(), sz));
    return _vector.back();
}

} // namespace vespalib
