// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
#include <vespa/searchlib/aggregation/group.h>
#include <vespa/searchlib/aggregation/maxaggregationresult.h>
#include <vespa/searchlib/aggregation/groupinglevel.h>
#include <vespa/searchlib/aggregation/grouping.h>
#include <vespa/searchlib/expression/aggregationrefnode.h>
#include <vespa/vespalib/objects/visit.h>
#include <vespa/vespalib/objects/objectpredicate.h>
#include <vespa/vespalib/objects/objectoperation.h>
#include <vespa/vespalib/objects/objectdumper.h>
#include <vespa/vespalib/util/optimized.h>
#include <vespa/vespalib/util/vstringfmt.h>
#include <cmath>
#include <cstdlib>

LOG_SETUP(".searchlib.aggregation.group");

namespace search {
namespace aggregation {

using search::expression::FloatResultNode;
using search::expression::AggregationRefNode;
using vespalib::FieldBase;
using vespalib::Serializer;
using vespalib::Deserializer;

namespace {

struct SortByGroupId {
    bool operator()(const Group::ChildP & a, const Group::ChildP & b) {
        return (a->cmpId(*b) < 0);
    }
};

struct SortByGroupRank {
    bool operator()(const Group::ChildP & a, const Group::ChildP & b) {
        return (a->cmpRank(*b) < 0);
    }
};

} // namespace search::aggregation::<unnamed>


IMPLEMENT_IDENTIFIABLE_NS2(search, aggregation, Group, vespalib::Identifiable);

void Group::destruct(GroupList & l, size_t m)
{
    for (size_t i(0); i < m; i++) {
        destruct(l[i]);
    }
    delete [] l;
    l = NULL;
}

int Group::cmpRank(const Group &rhs) const
{
    int diff(0);
    for(size_t i(0), m(getOrderBySize()); (diff == 0) && (i < m); i++) {
        uint32_t index = std::abs(getOrderBy(i)) - 1;
        diff = expr(index).getResult().cmp(rhs.expr(index).getResult())*getOrderBy(i);
    }
    return diff
               ? diff
               : ((_rank > rhs._rank)
                   ? -1
                   : ((_rank < rhs._rank) ? 1 : 0));
}

Group & Group::addOrderBy(const ExpressionNode::CP & orderBy, bool ascending)
{
    assert(getOrderBySize() < sizeof(_orderBy)*2-1);
    assert(getExprSize() < 15);
    addExpressionResult(orderBy);
    setOrderBy(getOrderBySize(), (ascending ? getExprSize() : -getExprSize()));
    setOrderBySize(getOrderBySize() + 1);
    setupAggregationReferences();
    return *this;
}

Group & Group::addAggregationResult(const ExpressionNode::CP & aggr)
{
    assert(getAggrSize() < 15);
    size_t newSize = getAggrSize() + 1 + getExprSize();
    ExpressionVector n = new ExpressionNode::CP[newSize];
    for (size_t i(0), m(getAggrSize()); i < m; i++) {
        n[i] = _aggregationResults[i];
    }
    n[getAggrSize()] = aggr;
    // Copy expressions after aggregationresults
    for (size_t i(getAggrSize()); i < newSize - 1; i++) {
        n[i + 1] = _aggregationResults[i];
    }
    delete [] _aggregationResults;
    _aggregationResults = n;
    setAggrSize(getAggrSize() + 1);
    return *this;
}

Group & Group::addExpressionResult(const ExpressionNode::CP & expressionNode)
{
    uint32_t newSize = getAggrSize() + getExprSize() + 1;
    ExpressionVector n = new ExpressionNode::CP[newSize];
    for (uint32_t i(0); i < (newSize - 1); i++) {
        n[i] = _aggregationResults[i];
    }
    n[newSize - 1] = expressionNode;
    delete [] _aggregationResults;
    _aggregationResults = n;
    setExprSize(getExprSize()+1);
    return *this;
}

void Group::setupAggregationReferences()
{
    AggregationRefNode::Configure exprRefSetup(_aggregationResults);
    select(exprRefSetup, exprRefSetup);
}

Group & Group::addResult(const ExpressionNode::CP & aggr)
{
    assert(getExprSize() < 15);
    addAggregationResult(aggr);
    addExpressionResult(ExpressionNode::CP(new AggregationRefNode(getAggrSize() - 1)));
    setupAggregationReferences();
    return *this;
}

void Group::addChild(Group * child)
{
    const size_t sz(getChildrenSize());
    assert(sz < 0xffffff);
    if (_children == 0) {
        _children = new ChildP[4];
    } else if ((sz >=4) && vespalib::Optimized::msbIdx(sz) == vespalib::Optimized::lsbIdx(sz)) {
        GroupList n = new ChildP[sz*2];
        for (size_t i(0), m(getChildrenSize()); i < m; i++) {
            n[i] = _children[i];
        }
        delete [] _children;
        _children = n;
    }
    _children[sz] = child;
    setChildrenSize(sz + 1);
}

void
Group::selectMembers(const vespalib::ObjectPredicate &predicate,
                     vespalib::ObjectOperation &operation)
{
    if (_id.get()) {
        _id->select(predicate, operation);
    }
    uint32_t totalSize = getAggrSize() + getExprSize();
    for (uint32_t i(0); i < totalSize; i++) {
        _aggregationResults[i]->select(predicate, operation);
    }
}

void
Group::preAggregate()
{
    assert(_childInfo._childMap == NULL);
    _childInfo._childMap = new GroupHash(getChildrenSize()*2, GroupHasher(&_children), GroupEqual(&_children));
    GroupHash & childMap = *_childInfo._childMap;
    for (ChildP *it(_children), *mt(_children + getChildrenSize()); it != mt; ++it) {
        (*it)->preAggregate();
        childMap.insert(it - _children);
    }
}

template <typename Doc>
void Group::collect(const Doc & doc, HitRank rank)
{
    for(size_t i(0), m(getAggrSize()); i < m; i++) {
        getAggr(i)->aggregate(doc, rank);
    }
}

template <typename Doc>
void
Group::aggregate(const Grouping & grouping, uint32_t currentLevel, const Doc & doc, HitRank rank)
{
    if (currentLevel >= grouping.getFirstLevel()) {
        collect(doc, rank);
    }
    if (currentLevel < grouping.getLevels().size()) {
        groupNext(grouping.getLevels()[currentLevel], doc, rank);
    }
}

template <typename Doc>
void
Group::groupNext(const GroupingLevel & level, const Doc & doc, HitRank rank)
{
    const ExpressionTree &selector = level.getExpression();
    if (!selector.execute(doc, rank)) {
        throw std::runtime_error("Does not know how to handle failed select statements");
    }
    const ResultNode &selectResult = selector.getResult();
    level.group(*this, selectResult, doc, rank);
}

Group * Group::groupSingle(const ResultNode & selectResult, HitRank rank, const GroupingLevel & level)
{
    if (_childInfo._childMap == NULL) {
        assert(getChildrenSize() == 0);
        _childInfo._childMap = new GroupHash(1, GroupHasher(&_children), GroupEqual(&_children));
    }
    GroupHash & childMap = *_childInfo._childMap;
    Group * group(NULL);
    GroupHash::iterator found = childMap.find<ResultNode, GroupResult, ResultHash, ResultEqual>(selectResult, GroupResult(&_children));
    if (found == childMap.end()) { // group not present in child map
        if (level.allowMoreGroups(childMap.size())) {
            group = new Group(level.getGroupPrototype());
            group->setId(selectResult);
            group->setRank(rank);
            addChild(group);
            childMap.insert(getChildrenSize() - 1);
        }
    } else {
        group = _children[(*found)];
        if ( ! level.isFrozen()) {
            group->updateRank(rank);
        }
    }
    return group;
}

void
Group::postAggregate()
{
    delete _childInfo._childMap;
    _childInfo._childMap = NULL;
    for (ChildP *it(_children), *mt(_children + getChildrenSize()); it != mt; ++it) {
        (*it)->postAggregate();
    }
}

void
Group::executeOrderBy()
{
    for (size_t i(0), m(getExprSize()); i < m; i++) {
        ExpressionNode & e(expr(i));
        e.prepare(false); // TODO: What should we do about this flag?
        e.execute();
    }
}

void Group::sortById()
{
    std::sort(_children, _children + getChildrenSize(), SortByGroupId());
    for (ChildP *it(_children), *mt(_children + getChildrenSize()); it != mt; ++it) {
        (*it)->sortById();
    }
}

void
Group::merge(const std::vector<GroupingLevel> &levels,
             uint32_t firstLevel, uint32_t currentLevel, Group &b)
{
    bool frozen  = (currentLevel < firstLevel);    // is this level frozen ?
    _rank = std::max(_rank, b._rank);

    if (!frozen) { // should we merge collectors for this level ?
        for(size_t i(0), m(getAggrSize()); i < m; i++) {
            getAggr(i)->merge(*b.getAggr(i));
        }
    }
    GroupList z = new ChildP[getChildrenSize() + b.getChildrenSize()];
    size_t kept(0);
    ChildP * px = _children;
    ChildP * ex = _children + getChildrenSize();
    ChildP * py = b._children;
    ChildP * ey = b._children + b.getChildrenSize();
    while (px != ex && py != ey) {
        int c = (*px)->cmpId(**py);
        if (c == 0) {
            (*px)->merge(levels, firstLevel, currentLevel + 1, **py);
            z[kept++] = *px;
            reset(*px);
            ++px;
            ++py;
        } else if (c < 0) {
            z[kept++] = *px;
            reset(*px);
            ++px;
        } else {
            z[kept++] = *py;
            reset(*py);
            ++py;
        }
    }
    for (; px != ex; ++px) {
        z[kept++] = *px;
        reset(*px);
    }
    for (; py != ey; ++py) {
        z[kept++] = *py;
        reset(*py);
    }
    std::swap(_children, z);
    destruct(z, getAllChildrenSize());
    setChildrenSize(kept);
    _childInfo._allChildren = 0;
}

void
Group::prune(const Group & b, uint32_t lastLevel, uint32_t currentLevel)
{
    if (currentLevel >= lastLevel) {
        return;
    }

    GroupList keep = new ChildP[b.getChildrenSize()];
    size_t kept(0);
    ChildP * px = _children;
    ChildP * ex = _children + getAllChildrenSize();
    const ChildP * py = b._children;
    const ChildP * ey = b._children + b.getChildrenSize();
    // Assumes that both lists are ordered by group id
    while (py != ey && px != ex) {
        if ((*py)->cmpId(**px) > 0) {
            px++;
        } else if ((*py)->cmpId(**px) == 0) {
            keep[kept++] = (*px);
            (*px)->prune((**py), lastLevel, currentLevel + 1);
            reset(*px);
            px++;
            py++;
        } else if ((*py)->cmpId(**px) < 0) {
            py++;
        }
    }
    std::swap(_children, keep);
    destruct(keep, getAllChildrenSize());
    setChildrenSize(kept);
    _childInfo._allChildren = 0;
}

void
Group::mergePartial(const std::vector<GroupingLevel> &levels,
                    uint32_t firstLevel,
                    uint32_t lastLevel,
                    uint32_t currentLevel,
                    const Group & b)
{
    bool frozen  = (currentLevel < firstLevel);

    if (!frozen) {
        for(size_t i(0), m(getAggrSize()); i < m; i++) {
            getAggr(i)->merge(b.getAggr(i));
        }
        for(size_t i(0), m(getExprSize()); i < m; i++) {
            expr(i).execute();
        }


        // At this level, we must create a copy of the other nodes children.
        if (currentLevel >= lastLevel) {
            for (ChildP *it(b._children), *mt(b._children + b.getChildrenSize()); it != mt; ++it) {
                ChildP g(new Group(levels[currentLevel].getGroupPrototype()));
                g->partialCopy(**it);
                addChild(g);
            }
            return;
        }
    }

    ChildP * px = _children;
    ChildP * ex = _children + getChildrenSize();
    const ChildP * py = b._children;
    const ChildP * ey = b._children + b.getChildrenSize();
    // Assumes that both lists are ordered by group id
    while (py != ey && px != ex) {
        if ((*py)->cmpId(**px) > 0) {
            px++;
        } else if ((*py)->cmpId(**px) == 0) {
            (*px)->mergePartial(levels, firstLevel, lastLevel, currentLevel + 1, **py);
            px++;
            py++;
        } else if ((*py)->cmpId(**px) < 0) {
            py++;
        }
    }
}

void
Group::postMerge(const std::vector<GroupingLevel> &levels,
                 uint32_t firstLevel,
                 uint32_t currentLevel)
{
    bool frozen = (currentLevel < firstLevel);    // is this level frozen ?

    if (!frozen) {
        for(size_t i(0), m(getAggrSize()); i < m; i++) {
            getAggr(i)->postMerge();
        }
    }
    bool hasNext = (currentLevel < levels.size()); // is there a next level ?
    if (!hasNext) { // we have reached the bottom of the tree
        return;
    }
    for (ChildP *it(_children), *mt(_children + getChildrenSize()); it != mt; ++it) {
        (*it)->executeOrderBy();
    }
    int64_t maxGroups = levels[currentLevel].getPrecision();
    for (size_t i(getChildrenSize()); i < _childInfo._allChildren; i++) {
        destruct(_children[i]);
        reset(_children[i]);
    }
    _childInfo._allChildren = getChildrenSize();
    if (getChildrenSize() > (uint64_t)maxGroups) { // prune groups
        std::sort(_children, _children + getChildrenSize(), SortByGroupRank());
        setChildrenSize(maxGroups);
    }
    for (ChildP *it(_children), *mt(_children + getChildrenSize()); it != mt; ++it) {
        (*it)->postMerge(levels, firstLevel, currentLevel + 1);
    }
}

Group & Group::setRank(RawRank r)
{
    _rank = std::isnan(r) ? -HUGE_VAL : r;
    return *this;
}

Group & Group::updateRank(RawRank r)
{
    return setRank(std::max(_rank, r));
}

bool Group::needResort() const
{
    bool resort(needFullRank());
    for (const ChildP *it(_children), *mt(_children + getChildrenSize()); !resort && (it != mt); ++it) {
        resort = (*it)->needResort();
    }
    return resort;
}

Serializer & Group::onSerialize(Serializer & os) const
{
    if (getChildrenSize() > 1) {
        for (size_t i(1), m(getChildrenSize()); i < m; i++) {
            assert(_children[i]->cmpId(*_children[i-1]) > 0);
        }
    }
    LOG(debug, "%s", _id->asString().c_str());
    os << _id << _rank;
    os << uint32_t(getOrderBySize());
    for (size_t i(0), m(getOrderBySize()); i < m; i++) {
        os << int32_t(getOrderBy(i));
    }
    os << uint32_t(getAggrSize());
    for(size_t i(0), m(getAggrSize()); i < m; i++) {
        os << getAggrCP(i);
    }
    os << uint32_t(getExprSize());
    for(size_t i(0), m(getExprSize()); i < m; i++) {
        os << getExprCP(i);
    }
    os << uint32_t(getChildrenSize());
    for (size_t i(0), m(getChildrenSize()); i < m; i++) {
        os << *_children[i];
    }
    return os << _tag;
}

Deserializer & Group::onDeserialize(Deserializer & is)
{
    uint32_t count(0);
    is >> _id >> _rank >> count;
    assert(count < sizeof(_orderBy)*2);
    setOrderBySize(count);
    for(uint32_t i(0); i < count; i++) {
        int32_t tmp(0);
        is >> tmp;
        assert((-7<= tmp) && (tmp <= 7));
        setOrderBy(i, tmp);
    }
    uint32_t aggrSize(0);
    is >> aggrSize;
    assert(aggrSize < 16);
    // To avoid protocol changes, we must first deserialize the aggregation
    // results into a temporary buffer, and then reallocate the actual
    // vector when we know the total size. Then we copy the temp buffer and
    // deserialize the rest to the end of the vector.
    ExpressionVector tmpAggregationResults = new ExpressionNode::CP[aggrSize];
    setAggrSize(aggrSize);
    for(uint32_t i(0); i < aggrSize; i++) {
        is >> tmpAggregationResults[i];
    }
    uint32_t exprSize(0);
    is >> exprSize;
    delete [] _aggregationResults;

    _aggregationResults = new ExpressionNode::CP[aggrSize + exprSize];
    for (uint32_t i(0); i < aggrSize; i++) {
        _aggregationResults[i] = tmpAggregationResults[i];
    }
    delete [] tmpAggregationResults;

    assert(exprSize < 16);
    setExprSize(exprSize);
    for (uint32_t i(aggrSize); i < aggrSize + exprSize; i++) {
        is >> _aggregationResults[i];
    }
    setupAggregationReferences();
    is >> count;
    destruct(_children, getAllChildrenSize());
    _childInfo._allChildren = 0;
    _children = new ChildP[std::max(4ul, 2ul << vespalib::Optimized::msbIdx(count))];
    setChildrenSize(count);
    for(uint32_t i(0); i < count; i++) {
        ChildP group(new Group);
        is >> *group;
        _children[i] = group;
    }
    is >> _tag;
    LOG(debug, "%s", _id->asString().c_str());
    if (getChildrenSize() > 1) {
        for (size_t i(1), m(getChildrenSize()); i < m; i++) {
            assert(_children[i]->cmpId(*_children[i-1]) > 0);
        }
    }
    return is;
}

void
Group::visitMembers(vespalib::ObjectVisitor &visitor) const
{
    visit(visitor, "id",                    _id);
    visit(visitor, "rank",                  _rank);
//    visit(visitor, "orderBy",               _orderBy);
    visitor.openStruct("orderBy", "[]");
    visit(visitor, "size", getOrderBySize());
    for (size_t i(0), m(getOrderBySize()); i < m; i++) {
        visit(visitor, vespalib::make_vespa_string("[%lu]", i), getOrderBy(i));
    }
    visitor.closeStruct();
//    visit(visitor, "aggregationResults",    _aggregationResults);
    visitor.openStruct("aggregationresults", "[]");
    visit(visitor, "size", getAggrSize());
    for (size_t i(0), m(getAggrSize()); i < m; i++) {
        visit(visitor, vespalib::make_vespa_string("[%lu]", i), getAggrCP(i));
    }
    visitor.closeStruct();
//    visit(visitor, "expressionResults",     _expressionResults);
    visitor.openStruct("expressionResults", "[]");
    visit(visitor, "size", getExprSize());
    for (size_t i(0), m(getExprSize()); i < m; i++) {
        visit(visitor, vespalib::make_vespa_string("[%lu]", i), getExprCP(i));
    }
    visitor.closeStruct();
    //visit(visitor, "children",              _children);
    visitor.openStruct("children", "[]");
    visit(visitor, "size", getChildrenSize());
    for (size_t i(0), m(getChildrenSize()); i < m; i++) {
        visit(visitor, vespalib::make_vespa_string("[%lu]", i), getChild(i));
    }
    visitor.closeStruct();
    visit(visitor, "tag",                   _tag);
}

Group::Group() :
    _id(),
    _rank(0),
    _packedLength(0),
    _tag(-1),
    _aggregationResults(NULL),
    _orderBy(),
    _children(NULL),
    _childInfo()
{
    memset(_orderBy, 0, sizeof(_orderBy));
    _childInfo._childMap = NULL;
}

Group::Group(const Group & rhs) :
    Identifiable(rhs),
    _id(rhs._id),
    _rank(rhs._rank),
    _packedLength(rhs._packedLength),
    _tag(rhs._tag),
    _aggregationResults(NULL),
    _orderBy(),
    _children(NULL),
    _childInfo()
{
    _childInfo._childMap = NULL;
    memcpy(_orderBy, rhs._orderBy, sizeof(_orderBy));
    uint32_t totalAggrSize = rhs.getAggrSize() + rhs.getExprSize();
    if (totalAggrSize > 0) {
        _aggregationResults = new ExpressionNode::CP[totalAggrSize];
        for (size_t i(0), m(totalAggrSize); i < m; i++) {
            _aggregationResults[i] = rhs._aggregationResults[i];
        }
        setupAggregationReferences();
    }

    if (  rhs.getChildrenSize() > 0 ) {
        _children = new ChildP[std::max(4ul, 2ul << vespalib::Optimized::msbIdx(rhs.getChildrenSize()))];
        size_t i(0);
        for (const ChildP *it(rhs._children), *mt(rhs._children + rhs.getChildrenSize()); it != mt; ++it, i++) {
            _children[i] = ChildP(new Group(**it));
        }
    }
}

Group::~Group()
{
    destruct(_children, getAllChildrenSize());
    setChildrenSize(0);
    _childInfo._allChildren = 0;
    delete [] _aggregationResults;
}

Group & Group::operator =(const Group & rhs)
{
    if (&rhs != this) {
        Group g(rhs);
        swap(g);
    }
    return *this;
}

Group &
Group::partialCopy(const Group & rhs)
{
    setId(*rhs._id);
    _rank = rhs._rank;
    uint32_t totalAggrSize = getAggrSize() + getExprSize();
    for(size_t i(0), m(totalAggrSize); i < m; i++) {
        _aggregationResults[i] = rhs._aggregationResults[i];
    }
    for(size_t i(0), m(getAggrSize()); i < m; i++) {
        getAggr(i)->reset();
    }
    setAggrSize(rhs.getAggrSize());
    setOrderBySize(rhs.getOrderBySize());
    setExprSize(rhs.getExprSize());
    setupAggregationReferences();
    memcpy(_orderBy, rhs._orderBy, sizeof(_orderBy));
    return *this;
}

void Group::swap(Group & rhs)
{
    _id.swap(rhs._id);
    std::swap(_rank, rhs._rank);
    std::swap(_aggregationResults, rhs._aggregationResults);
    std::swap(_children, rhs._children);
    std::swap(_childInfo._childMap, rhs._childInfo._childMap);
    {
        int8_t tmp[sizeof(_orderBy)];
        memcpy(tmp, _orderBy, sizeof(_orderBy));
        memcpy(_orderBy, rhs._orderBy, sizeof(_orderBy));
        memcpy(rhs._orderBy, tmp, sizeof(_orderBy));
    }
    std::swap(_tag, rhs._tag);
    std::swap(_packedLength, rhs._packedLength);
}

template void Group::aggregate(const Grouping & grouping, uint32_t currentLevel, const DocId & doc, HitRank rank);
template void Group::aggregate(const Grouping & grouping, uint32_t currentLevel, const document::Document & doc, HitRank rank);

}
}

// this function was added by ../../forcelink.sh
void forcelink_file_searchlib_aggregation_group() {}
