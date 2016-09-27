// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "node.h"
#include "querybuilder.h"
#include "term.h"
#include <vespa/vespalib/stllike/asciistream.h>
#include <string>
#include <vespa/searchlib/parsequery/stackdumpiterator.h>
#include <algorithm>

namespace search {
namespace query {

/**
 * Creates a query tree from a stack dump.
 */
template <class NodeTypes>
class StackDumpQueryCreator {
public:
    static Node::UP create(search::SimpleQueryStackDumpIterator &queryStack)
    {
        QueryBuilder<NodeTypes> builder;

        while (!builder.hasError() && queryStack.next()) {
            Term *t = createQueryTerm(queryStack, builder);
            if (!builder.hasError() && t) {
                t->setTermIndex(queryStack.getTermIndex());
                if (queryStack.getFlags() & ParseItem::IFLAG_NORANK) {
                    t->setRanked(false);
                }
                if (queryStack.getFlags() & ParseItem::IFLAG_NOPOSITIONDATA) {
                    t->setPositionData(false);
                }
            }
        }
        if (builder.hasError()) {
            LOG(error, "Unable to create query tree from stack dump. %s", builder.error().c_str());
        }
        return builder.build();
    }

private: 
    /**
     * If changing this class note:
     * Note that this method must return a reference into the existing querystack.
     * This is necessary to use the non-copying stringref noted in the create method.
     */
    static vespalib::stringref readString(SimpleQueryStackDumpIterator &queryStack, void (SimpleQueryStackDumpIterator::*f)(const char **, size_t *) const)
    {
        const char *p;
        size_t len;
        (queryStack.*f)(&p, &len);
        return vespalib::stringref(p, len);
    }

    static Term * createQueryTerm(search::SimpleQueryStackDumpIterator &queryStack, QueryBuilder<NodeTypes> & builder) {
        // Make sure that the life time of what pureTermView refers to exceeds that of pureTermView.
        // Especially make sure that do not create any stack local objects like vespalib::string
        // with smaller scope, that you refer with pureTermView.
        vespalib::stringref pureTermView;
        uint32_t arity = queryStack.getArity();
        uint32_t arg1 = queryStack.getArg1();
        double arg2 = queryStack.getArg2();
        double arg3 = queryStack.getArg3();
        ParseItem::ItemType type = queryStack.getType();
        Node::UP node;
        Term *t = 0;
        if (type == ParseItem::ITEM_AND) {
            builder.addAnd(arity);
        } else if (type == ParseItem::ITEM_RANK) {
            builder.addRank(arity);
        } else if (type == ParseItem::ITEM_OR) {
            builder.addOr(arity);
        } else if (type == ParseItem::ITEM_WORD_ALTERNATIVES) {
            vespalib::stringref view = readString(queryStack, &SimpleQueryStackDumpIterator::getIndexName);
            int32_t id = queryStack.getUniqueId();
            Weight weight = queryStack.GetWeight();
            builder.addEquiv(arity, id, weight);
            pureTermView = view;
        } else if (type == ParseItem::ITEM_WEAK_AND) {
            vespalib::stringref view = readString(queryStack, &SimpleQueryStackDumpIterator::getIndexName);
            builder.addWeakAnd(arity, arg1, view);
            pureTermView = view;
        } else if (type == ParseItem::ITEM_EQUIV) {
            int32_t id = queryStack.getUniqueId();
            Weight weight = queryStack.GetWeight();
            builder.addEquiv(arity, id, weight);
        } else if (type == ParseItem::ITEM_NEAR) {
            builder.addNear(arity, arg1);
        } else if (type == ParseItem::ITEM_ONEAR) {
            builder.addONear(arity, arg1);
        } else if (type == ParseItem::ITEM_PHRASE) {
            vespalib::stringref view = readString(queryStack, &SimpleQueryStackDumpIterator::getIndexName);
            int32_t id = queryStack.getUniqueId();
            Weight weight = queryStack.GetWeight();
            t = &builder.addPhrase(arity, view, id, weight);
            pureTermView = view;
        } else if (type == ParseItem::ITEM_WEIGHTED_SET) {
            vespalib::stringref view = readString(queryStack, &SimpleQueryStackDumpIterator::getIndexName);
            int32_t id = queryStack.getUniqueId();
            Weight weight = queryStack.GetWeight();
            t = &builder.addWeightedSetTerm(arity, view, id, weight);
            pureTermView = vespalib::stringref();
        } else if (type == ParseItem::ITEM_DOT_PRODUCT) {
            vespalib::stringref view = readString(queryStack, &SimpleQueryStackDumpIterator::getIndexName);
            int32_t id = queryStack.getUniqueId();
            Weight weight = queryStack.GetWeight();
            t = &builder.addDotProduct(arity, view, id, weight);
            pureTermView = vespalib::stringref();
        } else if (type == ParseItem::ITEM_WAND) {
            vespalib::stringref view = readString(queryStack, &SimpleQueryStackDumpIterator::getIndexName);
            int32_t id = queryStack.getUniqueId();
            Weight weight = queryStack.GetWeight();
            t = &builder.addWandTerm(arity, view, id, weight, arg1, arg2, arg3);
            pureTermView = vespalib::stringref();
        } else if (type == ParseItem::ITEM_NOT) {
            builder.addAndNot(arity);
        } else {
            vespalib::stringref term = readString(queryStack, &SimpleQueryStackDumpIterator::getTerm);
            vespalib::stringref view = readString(queryStack, &SimpleQueryStackDumpIterator::getIndexName);
            int32_t id = queryStack.getUniqueId();
            Weight weight = queryStack.GetWeight();

            if (type == ParseItem::ITEM_TERM) {
                t = &builder.addStringTerm(term, view, id, weight);
            } else if (type == ParseItem::ITEM_PURE_WEIGHTED_STRING) {
                t = &builder.addStringTerm(term, pureTermView, id, weight);
            } else if (type == ParseItem::ITEM_PURE_WEIGHTED_LONG) {
                t = &builder.addNumberTerm(term, pureTermView, id, weight);
            } else if (type == ParseItem::ITEM_PREFIXTERM) {
                t = &builder.addPrefixTerm(term, view, id, weight);
            } else if (type == ParseItem::ITEM_SUBSTRINGTERM) {
                t = &builder.addSubstringTerm(term, view, id, weight);
            } else if (type == ParseItem::ITEM_EXACTSTRINGTERM) {
                t = &builder.addStringTerm(term, view, id, weight);
            } else if (type == ParseItem::ITEM_SUFFIXTERM) {
                t = &builder.addSuffixTerm(term, view, id, weight);
            } else if (type == ParseItem::ITEM_NUMTERM) {
                if (term[0] == '[' || term[0] == '<' || term[0] == '>') {
                    Range range(term);
                    t = &builder.addRangeTerm(range, view, id, weight);
                } else if (term[0] == '(') {
                    Location loc(term);
                    t = &builder.addLocationTerm(loc, view, id, weight);
                } else {
                    t = &builder.addNumberTerm(term, view, id, weight);
                }
            } else if (type == ParseItem::ITEM_PREDICATE_QUERY) {
                t = &builder.addPredicateQuery(queryStack.getPredicateQueryTerm(), view, id, weight);
            } else if (type == ParseItem::ITEM_REGEXP) {
                t = &builder.addRegExpTerm(term, view, id, weight);
            } else {
                LOG(error, "Unable to create query tree from stack dump. node type = %d.", type);
            }
        }
        return t;
    }
};

}  // namespace query
}  // namespace search

