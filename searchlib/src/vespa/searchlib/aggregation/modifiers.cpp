// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "modifiers.h"
#include "grouping.h"
#include <vespa/searchlib/expression/multiargfunctionnode.h>
#include <vespa/searchlib/expression/attributenode.h>
#include <vespa/searchlib/expression/attribute_keyed_node.h>
#include <vespa/searchlib/expression/documentfieldnode.h>

using namespace search::expression;

namespace search::aggregation {

bool AttributeNodeReplacer::check(const vespalib::Identifiable &obj) const
{
    return obj.getClass().inherits(GroupingLevel::classId) || obj.getClass().inherits(AggregationResult::classId) || obj.getClass().inherits(MultiArgFunctionNode::classId);
}

void AttributeNodeReplacer::execute(vespalib::Identifiable &obj)
{
    if (obj.getClass().inherits(GroupingLevel::classId)) {
        GroupingLevel & g(static_cast<GroupingLevel &>(obj));
        if (g.getExpression().getRoot()->inherits(AttributeNode::classId)) {
            auto replacementNode = getReplacementNode(static_cast<const AttributeNode &>(*g.getExpression().getRoot()));
            if (replacementNode) {
                g.setExpression(std::move(replacementNode));
            }
        } else {
            g.getExpression().getRoot()->select(*this, *this);
        }
        g.groupPrototype().select(*this, *this);
    } else if(obj.getClass().inherits(AggregationResult::classId)) {
        AggregationResult & a(static_cast<AggregationResult &>(obj));
        ExpressionNode * e(a.getExpression());
        if (e) {
            if (e->inherits(AttributeNode::classId)) {
                auto replacementNode = getReplacementNode(static_cast<const AttributeNode &>(*e));
                if (replacementNode) {
                    a.setExpression(std::move(replacementNode));
                }
            } else {
                e->select(*this, *this);
            }
        }
    } else if(obj.getClass().inherits(MultiArgFunctionNode::classId)) {
        MultiArgFunctionNode::ExpressionNodeVector & v(static_cast<MultiArgFunctionNode &>(obj).expressionNodeVector());
        for(size_t i(0), m(v.size()); i < m; i++) {
            ExpressionNode::CP & e(v[i]);
            if (e->inherits(AttributeNode::classId)) {
                auto replacementNode = getReplacementNode(static_cast<const AttributeNode &>(*e));
                if (replacementNode) {
                    e = std::move(replacementNode);
                }
            } else {
                e->select(*this, *this);
            }
        }
    }
}

std::unique_ptr<ExpressionNode>
Attribute2DocumentAccessor::getReplacementNode(const AttributeNode &attributeNode)
{
    return std::make_unique<DocumentFieldNode>(attributeNode.getAttributeName());
}

std::unique_ptr<ExpressionNode>
Attribute2AttributeKeyed::getReplacementNode(const AttributeNode &attributeNode)
{
    const vespalib::string &attributeName = attributeNode.getAttributeName();
    auto lBracePos = attributeName.find('{');
    if (attributeNode.isKeyed() || lBracePos == vespalib::string::npos) {
        return std::unique_ptr<ExpressionNode>();
    } else {
        return std::make_unique<AttributeKeyedNode>(attributeName);
    }
}

}

// this function was added by ../../forcelink.sh
void forcelink_file_searchlib_aggregation_modifiers() {}
