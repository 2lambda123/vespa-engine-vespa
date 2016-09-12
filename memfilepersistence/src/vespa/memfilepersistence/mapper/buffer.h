// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
 * \class storage::memfile::Buffer
 * \ingroup memfile
 *
 * \brief Simple wrapper class to contain an aligned buffer.
 *
 * For direct IO operations, we need to use 512 byte aligned buffers. This is
 * a simple wrapper class to get such a buffer.
 */

#pragma once

#include <vespa/vespalib/util/alloc.h>
#include <vespa/vespalib/util/linkedptr.h>

namespace storage {
namespace memfile {

class Buffer
{
    // Use AutoAlloc to transparently use mmap for large buffers.
    // It is crucial that any backing buffer type returns an address that is
    // 512-byte aligned, or direct IO will scream at us and fail everything.
    static constexpr size_t MMapLimit = vespalib::MMapAlloc::HUGEPAGE_SIZE;
    using BackingType = vespalib::AutoAlloc<MMapLimit, 512>;

    BackingType _buffer;
    // Actual, non-aligned size (as opposed to _buffer.size()).
    size_t _size;

public:
    typedef vespalib::LinkedPtr<Buffer> LP;

    Buffer(const Buffer &) = delete;
    Buffer & operator = (const Buffer &) = delete;
    Buffer(size_t size);

    /**
     * Resize buffer while keeping data that exists in the intersection of
     * the old and new buffers' sizes.
     */
    void resize(size_t size);

    char* getBuffer() noexcept {
        return static_cast<char*>(_buffer.get());
    }
    const char* getBuffer() const noexcept {
        return static_cast<const char*>(_buffer.get());
    }
    size_t getSize() const noexcept {
        return _size;
    }

    operator char*() noexcept { return getBuffer(); }

};

} // storage
} // memfile


