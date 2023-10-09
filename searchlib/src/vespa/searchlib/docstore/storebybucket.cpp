// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "storebybucket.h"
#include <vespa/vespalib/data/databuffer.h>
#include <vespa/vespalib/stllike/hash_map.hpp>
#include <vespa/vespalib/util/cpu_usage.h>
#include <vespa/vespalib/util/lambdatask.h>
#include <algorithm>

namespace search::docstore {

using document::BucketId;
using vespalib::CpuUsage;
using vespalib::makeLambdaTask;

StoreByBucket::StoreByBucket(MemoryDataStore & backingMemory, Executor & executor, CompressionConfig compression) noexcept
    : _chunkSerial(0),
      _current(),
      _where(),
      _backingMemory(backingMemory),
      _executor(executor),
      _lock(),
      _cond(),
      _numChunksPosted(0),
      _chunks(),
      _compression(compression)
{
    createChunk().swap(_current);
}

StoreByBucket::~StoreByBucket() = default;

void
StoreByBucket::add(BucketId bucketId, uint32_t chunkId, uint32_t lid, ConstBufferRef data)
{
    if ( ! _current->hasRoom(data.size())) {
        Chunk::UP tmpChunk = createChunk();
        _current.swap(tmpChunk);
        incChunksPosted();
        auto task = makeLambdaTask([this, chunk=std::move(tmpChunk)]() mutable {
            closeChunk(std::move(chunk));
        });
        _executor.execute(CpuUsage::wrap(std::move(task), CpuUsage::Category::COMPACT));
    }
    _current->append(lid, data);
    _where.emplace_back(bucketId, _current->getId(), chunkId, lid);
}

Chunk::UP
StoreByBucket::createChunk()
{
    return std::make_unique<Chunk>(_chunkSerial++, Chunk::Config(0x10000));
}

size_t
StoreByBucket::getChunkCount() const {
    std::lock_guard guard(_lock);
    return _chunks.size();
}

void
StoreByBucket::closeChunk(Chunk::UP chunk)
{
    vespalib::DataBuffer buffer;
    chunk->pack(1, buffer, _compression);
    buffer.shrink(buffer.getDataLen());
    ConstBufferRef bufferRef(_backingMemory.push_back(buffer.getData(), buffer.getDataLen()).data(), buffer.getDataLen());
    std::lock_guard guard(_lock);
    _chunks[chunk->getId()] = bufferRef;
    if (_numChunksPosted == _chunks.size()) {
        _cond.notify_one();
    }
}

void
StoreByBucket::incChunksPosted() {
    std::lock_guard guard(_lock);
    _numChunksPosted++;
}

void
StoreByBucket::waitAllProcessed() {
    std::unique_lock guard(_lock);
    while (_numChunksPosted != _chunks.size()) {
        _cond.wait(guard);
    }
}

void
StoreByBucket::close() {
    incChunksPosted();
    auto task = makeLambdaTask([this, chunk=std::move(_current)]() mutable {
        closeChunk(std::move(chunk));
    });
    _executor.execute(CpuUsage::wrap(std::move(task), CpuUsage::Category::COMPACT));
    waitAllProcessed();
    std::sort(_where.begin(), _where.end());
}

size_t
StoreByBucket::getBucketCount() const {
    if (_where.empty()) return 0;

    size_t count = 0;
    BucketId prev = _where.front()._bucketId;
    for (const auto & lid : _where) {
        if (lid._bucketId != prev) {
            count++;
            prev = lid._bucketId;
        }
    }
    return count + 1;
}

void
StoreByBucket::drain(IWrite & drainer)
{
    std::vector<Chunk::UP> chunks;
    chunks.resize(_chunks.size());
    for (const auto & it : _chunks) {
        ConstBufferRef buf(it.second);
        chunks[it.first] = std::make_unique<Chunk>(it.first, buf.data(), buf.size());
    }
    _chunks.clear();
    for (auto & idx : _where) {
        vespalib::ConstBufferRef data(chunks[idx._id]->getLid(idx._lid));
        drainer.write(idx._bucketId, idx._chunkId, idx._lid, data);
    }
}

}

VESPALIB_HASH_MAP_INSTANTIATE(uint64_t, vespalib::ConstBufferRef);
