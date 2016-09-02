// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".diskindex.disktermblueprint");

#include "disktermblueprint.h"
#include <vespa/searchlib/common/bitvectoriterator.h>
#include <vespa/searchlib/queryeval/booleanmatchiteratorwrapper.h>
#include <vespa/searchlib/queryeval/intermediate_blueprints.h>
#include <vespa/searchlib/queryeval/equiv_blueprint.h>
#include <vespa/vespalib/util/stringfmt.h>

using search::BitVectorIterator;
using search::fef::TermFieldMatchDataArray;
using search::index::Schema;
using search::queryeval::BooleanMatchIteratorWrapper;
using search::queryeval::FieldSpecBase;
using search::queryeval::FieldSpecBaseList;
using search::queryeval::SearchIterator;
using search::queryeval::LeafBlueprint;
using search::queryeval::EquivBlueprint;
using search::queryeval::Blueprint;

namespace search {
namespace diskindex {

namespace {

vespalib::string
getName(uint32_t indexId)
{
    return vespalib::make_string("fieldId(%u)", indexId);
}

}

DiskTermBlueprint::DiskTermBlueprint(const FieldSpecBase & field,
                                     const search::diskindex::DiskIndex & diskIndex,
                                     search::diskindex::DiskIndex::LookupResult::UP lookupRes,
                                     bool useBitVector) :
    SimpleLeafBlueprint(field),
    _field(field),
    _diskIndex(diskIndex),
    _lookupRes(std::move(lookupRes)),
    _useBitVector(useBitVector),
    _fetchPostingsDone(false),
    _hasEquivParent(false),
    _postingHandle(),
    _bitVector()
{
    setEstimate(HitEstimate(_lookupRes->counts._numDocs,
                            _lookupRes->counts._numDocs == 0));
}

namespace {

bool
areAnyParentsEquiv(const Blueprint * node)
{
    return (node == NULL)
           ? false
           : (dynamic_cast<const EquivBlueprint *>(node) != NULL)
             ? true
             : areAnyParentsEquiv(node->getParent());
}

}

void
DiskTermBlueprint::fetchPostings(bool strict)
{
    (void) strict;
    _hasEquivParent = areAnyParentsEquiv(getParent());
    _bitVector = _diskIndex.readBitVector(*_lookupRes);
    if (!_useBitVector || (_bitVector.get() == NULL)) {
        _postingHandle = _diskIndex.readPostingList(*_lookupRes);
    }
    _fetchPostingsDone = true;
}

SearchIterator::UP
DiskTermBlueprint::createLeafSearch(const TermFieldMatchDataArray & tfmda, bool strict) const
{
    if ((_bitVector.get() != NULL) && (_useBitVector || (tfmda[0]->isNotNeeded() && !_hasEquivParent))) {
        LOG(debug, "Return BitVectorIterator: %s, wordNum(%" PRIu64 "), docCount(%" PRIu64 ")",
            getName(_lookupRes->indexId).c_str(), _lookupRes->wordNum, _lookupRes->counts._numDocs);
        return BitVectorIterator::create(_bitVector.get(), tfmda, strict);
    }
    SearchIterator::UP search(_postingHandle->createIterator(_lookupRes->counts, tfmda, _useBitVector));
    if (_useBitVector) {
        LOG(debug, "Return BooleanMatchIteratorWrapper: %s, wordNum(%" PRIu64 "), docCount(%" PRIu64 ")",
            getName(_lookupRes->indexId).c_str(), _lookupRes->wordNum, _lookupRes->counts._numDocs);
        return SearchIterator::UP(new BooleanMatchIteratorWrapper(std::move(search), tfmda));
    }
    LOG(debug, "Return posting list iterator: %s, wordNum(%" PRIu64 "), docCount(%" PRIu64 ")",
        getName(_lookupRes->indexId).c_str(), _lookupRes->wordNum, _lookupRes->counts._numDocs);
    return search;
}

}  // namespace diskindex
}  // namespace search

