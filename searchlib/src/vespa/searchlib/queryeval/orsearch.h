// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "multisearch.h"
#include "unpackinfo.h"

namespace search {
namespace queryeval {

/**
 * A simple implementation of the Or search operation.
 **/
class OrSearch : public MultiSearch
{
public:
    typedef MultiSearch::Children Children;

    // Caller takes ownership of the returned SearchIterator.
    static SearchIterator *create(const Children &children, bool strict);
    static SearchIterator *create(const Children &children, bool strict, const UnpackInfo & unpackInfo);

    std::unique_ptr<BitVector> get_hits(uint32_t begin_id) override;
    void or_hits_into(BitVector &result, uint32_t begin_id) override;
    void and_hits_into(BitVector &result, uint32_t begin_id) override;

protected:
    OrSearch(const  Children & children) : MultiSearch(children) { }
private:
    virtual bool isOr() const { return true; }
};

} // namespace queryeval
} // namespace search

