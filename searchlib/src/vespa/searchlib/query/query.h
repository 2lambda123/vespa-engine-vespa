// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "queryterm.h"
#include "base.h"
#include <vespa/searchlib/parsequery/stackdumpiterator.h>

namespace search
{

/**
   Base class for all N-ary query operators.
   Implements the width, depth, print, and collect all leafs operators(terms).
*/
class QueryConnector : public QueryNode, public QueryNodeList
{
public:
    DECLARE_IDENTIFIABLE_ABSTRACT_NS(search, QueryConnector);
    QueryConnector(const char * opName);
    ~QueryConnector();
    virtual const HitList & evaluateHits(HitList & hl) const;
    /// Will clear the results from the querytree.
    virtual void reset();
    /// Will get all leafnodes.
    virtual void getLeafs(QueryTermList & tl);
    virtual void getLeafs(ConstQueryTermList & tl) const;
    /// Gives you all phrases of this tree.
    virtual void getPhrases(QueryNodeRefList & tl);
    virtual void getPhrases(ConstQueryNodeRefList & tl) const;
    virtual size_t depth() const;
    virtual size_t width() const;
    virtual void visitMembers(vespalib::ObjectVisitor &visitor) const;
    virtual void setIndex(const vespalib::string & index) { _index = index; }
    virtual const vespalib::string & getIndex() const { return _index; }
    static QueryConnector * create(ParseItem::ItemType type);
    virtual bool isFlattenable(ParseItem::ItemType type) const { (void) type; return false; }
private:
    vespalib::string _opName;
    vespalib::string _index;
};

/**
   True operator. Matches everything.
*/
class TrueNode : public QueryConnector
{
public:
    DECLARE_IDENTIFIABLE_NS(search, TrueNode);
    TrueNode() : QueryConnector("AND") { }
    virtual bool evaluate() const;
};

/**
   N-ary Or operator that simply ANDs all the nodes together.
*/
class AndQueryNode : public QueryConnector
{
public:
    DECLARE_IDENTIFIABLE_NS(search, AndQueryNode);
    AndQueryNode() : QueryConnector("AND") { }
    AndQueryNode(const char * opName) : QueryConnector(opName) { }
    virtual bool evaluate() const;
    virtual bool isFlattenable(ParseItem::ItemType type) const { return type == ParseItem::ITEM_AND; }
};

/**
   N-ary special AndNot operator. n[0] & !n[1] & !n[2] .. & !n[j].
*/
class AndNotQueryNode : public QueryConnector
{
public:
    DECLARE_IDENTIFIABLE_NS(search, AndNotQueryNode);
    AndNotQueryNode() : QueryConnector("ANDNOT") { }
    virtual bool evaluate() const;
    virtual bool isFlattenable(ParseItem::ItemType type) const { return type == ParseItem::ITEM_NOT; }
};

/**
   N-ary Or operator that simply ORs all the nodes together.
*/
class OrQueryNode : public QueryConnector
{
public:
    DECLARE_IDENTIFIABLE_NS(search, OrQueryNode);
    OrQueryNode() : QueryConnector("OR") { }
    OrQueryNode(const char * opName) : QueryConnector(opName) { }
    virtual bool evaluate() const;
    virtual bool isFlattenable(ParseItem::ItemType type) const {
        return (type == ParseItem::ITEM_OR) ||
               (type == ParseItem::ITEM_DOT_PRODUCT) ||
               (type == ParseItem::ITEM_WAND) ||
               (type == ParseItem::ITEM_WEAK_AND);
    }
};

/**
   N-ary "EQUIV" operator that merges terms from nodes below.
*/
class EquivQueryNode : public OrQueryNode
{
public:
    DECLARE_IDENTIFIABLE_NS(search, EquivQueryNode);
    EquivQueryNode() : OrQueryNode("EQUIV") { }
    virtual bool evaluate() const;
    virtual bool isFlattenable(ParseItem::ItemType type) const {
        return (type == ParseItem::ITEM_EQUIV) ||
               (type == ParseItem::ITEM_WEIGHTED_SET);
    }
};

/**
   N-ary phrase operator. All terms must be satisfied and have the correct order
   with distance to next term equal to 1.
*/
class PhraseQueryNode : public AndQueryNode
{
public:
    DECLARE_IDENTIFIABLE_NS(search, PhraseQueryNode);
    PhraseQueryNode() : AndQueryNode("PHRASE"), _fieldInfo(32) { }
    virtual bool evaluate() const;
    virtual const HitList & evaluateHits(HitList & hl) const;
    virtual void getPhrases(QueryNodeRefList & tl);
    virtual void getPhrases(ConstQueryNodeRefList & tl) const;
    const QueryTerm::FieldInfo & getFieldInfo(size_t fid) const { return _fieldInfo[fid]; }
    size_t getFieldInfoSize() const { return _fieldInfo.size(); }
    virtual bool isFlattenable(ParseItem::ItemType type) const { return type == ParseItem::ITEM_NOT; }
private:
    mutable std::vector<QueryTerm::FieldInfo> _fieldInfo;
    void updateFieldInfo(size_t fid, size_t offset, size_t fieldLength) const;
#if WE_EVER_NEED_TO_CACHE_THIS_WE_MIGHT_WANT_SOME_CODE_HERE
    HitList _cachedHitList;
    bool    _evaluated;
#endif
};

/**
   Unary Not operator. Just inverts the nodes result.
*/
class NotQueryNode : public QueryConnector
{
public:
    DECLARE_IDENTIFIABLE_NS(search, NotQueryNode);
    NotQueryNode() : QueryConnector("NOT") { }
    virtual bool evaluate() const;
};

/**
   N-ary Near operator. All terms must be within the given distance.
*/
class NearQueryNode : public AndQueryNode
{
public:
    DECLARE_IDENTIFIABLE_NS(search, NearQueryNode);
    NearQueryNode() : AndQueryNode("NEAR"), _distance(0) { }
    NearQueryNode(const char * opName) : AndQueryNode(opName), _distance(0) { }
    virtual bool evaluate() const;
    void distance(size_t dist)       { _distance = dist; }
    size_t distance()          const { return _distance; }
    virtual void visitMembers(vespalib::ObjectVisitor &visitor) const;
    virtual bool isFlattenable(ParseItem::ItemType type) const { return type == ParseItem::ITEM_NOT; }
private:
    size_t _distance;
};

/**
   N-ary Ordered near operator. The terms must be in order and the distance between
   the first and last must not exceed the given distance.
*/
class ONearQueryNode : public NearQueryNode
{
public:
    DECLARE_IDENTIFIABLE_NS(search, ONearQueryNode);
    ONearQueryNode() : NearQueryNode("ONEAR") { }
    virtual ~ONearQueryNode() { }
    virtual bool evaluate() const;
};

/**
   Query packages the query tree. The usage pattern is like this.
   Construct the tree with the correct tree description.
   Get the leaf nodes and populate them with the term occurences.
   Then evaluate the query. This is repeated for each document or chunk that
   you want to process. The tree can also be printed. And you can read the
   width and depth properties.
*/
class Query : public vespalib::Identifiable
{
public:
    DECLARE_IDENTIFIABLE_NS(search, Query);
    Query();
    Query(const QueryNodeResultFactory & factory, const QueryPacketT & queryRep);
    virtual ~Query() { }
    /// Will build the query tree
    bool build(const QueryNodeResultFactory & factory, const QueryPacketT & queryRep);
    /// Will clear the results from the querytree.
    void reset();
    /// Will get all leafnodes.
    void getLeafs(QueryTermList & tl);
    void getLeafs(ConstQueryTermList & tl) const;
    /// Gives you all phrases of this tree.
    void getPhrases(QueryNodeRefList & tl);
    void getPhrases(ConstQueryNodeRefList & tl) const;
    bool evaluate() const;
    size_t depth() const;
    size_t width() const;
    bool valid() const { return _root.get() != NULL; }
    const QueryNode::LP & getRoot() const { return _root; }
    QueryNode::LP & getRoot() { return _root; }
private:
    virtual void visitMembers(vespalib::ObjectVisitor &visitor) const;
    QueryNode::LP _root;
};

}

