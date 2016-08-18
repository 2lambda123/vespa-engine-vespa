// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "idocumentstore.h"
#include <vespa/vespalib/stllike/cache.h>

namespace search {
namespace docstore {

class KeySet {
public:
    KeySet() : _keys() { }
    KeySet(const IDocumentStore::LidVector &keys);
    uint32_t hash() const { return _keys.empty() ? 0 : _keys[0]; }
    bool operator==(const KeySet &rhs) const { return _keys == rhs._keys; }
    bool operator<(const KeySet &rhs) const { return _keys < rhs._keys; }
    bool contains(const KeySet &rhs) const;
private:
    IDocumentStore::LidVector _keys;
};

class BlobSet {
public:
    class LidPosition {
    public:
        LidPosition(uint32_t lid, uint32_t offset, uint32_t size) : _lid(lid), _offset(offset), _size(size) { }
        uint32_t    lid() const { return _lid; }
        uint32_t offset() const { return _offset; }
        uint32_t   size() const { return _size; }
    private:
        uint32_t _lid;
        uint32_t _offset;
        uint32_t _size;
    };

    typedef std::vector<LidPosition> Positions;
    void append(uint32_t lid, vespalib::ConstBufferRef blob);
    void remove(uint32_t lid);
    vespalib::ConstBufferRef get(uint32_t lid) const;
    vespalib::ConstBufferRef getBuffer(uint32_t lid) const;
private:
    Positions           _positions;
    vespalib::nbostream _buffer;
};

class CompressedBlobSet {
public:
    CompressedBlobSet();
    CompressedBlobSet(const BlobSet & uncompressed);
    CompressedBlobSet(CompressedBlobSet && rhs);
    CompressedBlobSet & operator=(CompressedBlobSet && rhs);
    CompressedBlobSet(const CompressedBlobSet &) = default;
    CompressedBlobSet & operator=(const CompressedBlobSet &) = default;
    size_t size() const { return _positions.capacity() * sizeof(BlobSet::Positions::value_type) + _buffer.size(); }
    BlobSet getBlobSet() const;
private:
    BlobSet::Positions  _positions;
    vespalib::MallocPtr _buffer;
};

class VisitCache {
public:
    using Keys=IDocumentStore::LidVector;

    VisitCache(IDataStore &store, size_t cacheSize, const document::CompressionConfig &compression);

    CompressedBlobSet read(const Keys & keys) const;
    void remove(uint32_t key);

private:
    class BackingStore {
    public:
        BackingStore(IDataStore &store, const document::CompressionConfig &compression) :
            _backingStore(store),
            _compression(compression)
        { }
        bool read(const KeySet &key, CompressedBlobSet &blobs) const;
        void write(const KeySet &, const CompressedBlobSet &) { }
        void erase(const KeySet &) { }
        const document::CompressionConfig &getCompression(void) const { return _compression; }
    private:
        IDataStore &_backingStore;
        const document::CompressionConfig &_compression;
    };

    typedef vespalib::CacheParam<vespalib::LruParam<KeySet, CompressedBlobSet>,
        BackingStore,
        vespalib::zero<KeySet>,
        vespalib::size<CompressedBlobSet> > CacheParams;
    typedef vespalib::cache<CacheParams> Cache;

    BackingStore            _store;
    std::unique_ptr<Cache>  _cache;
};

}
}
