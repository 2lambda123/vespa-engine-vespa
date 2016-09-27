// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
 * Declaration of the SimpleQueryStack dump iterator
 *
 *   Copyright (C) 1997-2003 Fast Search & Transfer ASA
 *   Copyright (C) 2003 Overture Services Norway AS
 *               ALL RIGHTS RESERVED
 */
#pragma once

#include <vespa/searchlib/parsequery/parse.h>
#include <vespa/searchlib/query/tree/predicate_query_term.h>
#include <vespa/vespalib/stllike/asciistream.h>
#include <vespa/vespalib/stllike/string.h>

namespace search {
/**
 * An iterator to be used on a buffer that is a stack dump
 * of a SimpleQueryStack.
 */
class SimpleQueryStackDumpIterator
{
private:
    SimpleQueryStackDumpIterator(const SimpleQueryStackDumpIterator &);
    SimpleQueryStackDumpIterator& operator=(const SimpleQueryStackDumpIterator &);

    /** Pointer to the start of the input buffer */
    const char *_buf;
    /** Pointer to just past the input buffer */
    const char *_bufEnd;
    /** Total length of the input buffer */
    size_t _bufLen;

    /** Pointer to the position of the current item in the buffer */
    const char *_currPos;
    /** Pointer to after the current item */
    const char *_currEnd;
    /** The type of the current item */
    ParseItem::ItemType _currType;
    ParseItem::ItemCreator _currCreator;
    /** Rank weight of current item **/
    query::Weight _currWeight;
    /** unique id of the current item **/
    uint32_t _currUniqueId;

    /** flags of the current item **/
    uint32_t _currFlags;

    /** The arity of the current item */
    uint32_t _currArity;
    /** The first argument of the current item (length of NEAR/ONEAR area for example) */
    uint32_t _currArg1;
    /** The second argument of the current item (score threshold of WAND for example) */
    double _currArg2;
    /** The third argument of the current item (threshold boost factor of WAND for example) */
    double _currArg3;
    /** The predicate query specification */
    query::PredicateQueryTerm::UP _predicate_query_term;
    /** Pointer to the position of the index name in the current item */
    const char *_currIndexName;
    /** The length of the index name in the current item */
    size_t _currIndexNameLen;
    /** Pointer to the position of the term in the current item */
    const char *_currTerm;
    /** The length of the term in the current item */
    size_t _currTermLen;
    vespalib::asciistream _generatedTerm;

    /** The number of the current item */
    int _currNum;

    vespalib::string readString(const char *&p);
    uint64_t readUint64(const char *&p);
    uint64_t readCompressedPositiveInt(const char *&p);

public:
    /**
     * Make an iterator on a buffer. To get the first item, next
     * must be called.
     *
     * @param buf A pointer to the buffer holding the stackdump
     * @param buflen The length of the buffer in bytes
     */
    SimpleQueryStackDumpIterator(const vespalib::stringref &buf);
    ~SimpleQueryStackDumpIterator();

    vespalib::stringref getStack() const { return vespalib::stringref(_buf, _bufLen); }
    size_t getPosition() const { return _currPos - _buf; }

    /**
     * Moves to the next item in the buffer.
     *
     * @return true if there is a new item, false if there are no more items
     * or if there was errors in extracting the next item.
     */
    bool next(void);

    /**
     * Get the number of the current item.
     *
     * @return The ordinal of the current item. -1 if at the start.
     */
    int getNum(void) const { return _currNum; }

    /**
     * Get the type of the current item.
     * @return the type.
     */
    ParseItem::ItemType getType(void) const { return _currType; }
    /**
     * Get the type of the current item.
     * @return the type.
     */
    ParseItem::ItemCreator getCreator(void) const { return _currCreator; }

    /**
     * Get the rank weight of the current item.
     *
     * @return rank weight.
     **/
    query::Weight GetWeight() const { return _currWeight; }

    /**
     * Get the unique id of the current item.
     *
     * @return unique id of current item
     **/
    uint32_t getUniqueId() const { return _currUniqueId; }

    /**
     * Get the term index of the current item.
     *
     * @return term index of current item
     **/
    uint32_t getTermIndex() const { return -1; }

    /**
     * Get the flags of the current item.
     *
     * @return flags of current item
     **/
    uint32_t getFlags() const { return _currFlags; }

    uint32_t getArity(void) const { return _currArity; }

    uint32_t getArg1(void) const { return _currArg1; }

    double getArg2() const { return _currArg2; }

    double getArg3() const { return _currArg3; }

    query::PredicateQueryTerm::UP getPredicateQueryTerm()
    { return std::move(_predicate_query_term); }

    /**
     * Get the type of the current item.
     * @return the type.
     */
    void getIndexName(const char **buf, size_t *buflen) const { *buf = _currIndexName; *buflen = _currIndexNameLen; }
    /**
     * Get the type of the current item.
     * @return the type.
     */
    void getTerm(const char **buf, size_t *buflen) const { *buf = _currTerm; *buflen = _currTermLen; }
};

}

