// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchlib.rankingexpression.rule;

import com.yahoo.searchlib.rankingexpression.evaluation.BooleanValue;
import com.yahoo.searchlib.rankingexpression.evaluation.Context;
import com.yahoo.searchlib.rankingexpression.evaluation.Value;

import java.util.*;

/**
 * A node which returns true or false depending on the outcome of a comparison.
 *
 * @author bratseth
 * @since  5.1.21
 */
public class ComparisonNode extends BooleanNode {

    /** The operator string of this condition. */
    private final TruthOperator operator;

    private final ExpressionNode leftCondition, rightCondition;

    public ComparisonNode(ExpressionNode leftCondition, TruthOperator operator, ExpressionNode rightCondition) {
        this.leftCondition = leftCondition;
        this.operator = operator;
        this.rightCondition = rightCondition;
    }

    @Override
    public List<ExpressionNode> children() {
        List<ExpressionNode> children = new ArrayList<>(2);
        children.add(leftCondition);
        children.add(rightCondition);
        return children;
    }

    public TruthOperator getOperator() { return operator; }

    public ExpressionNode getLeftCondition() { return leftCondition; }

    public ExpressionNode getRightCondition() { return rightCondition; }

    @Override
    public String toString(SerializationContext context, Deque<String> path, CompositeNode parent) {
        return leftCondition.toString(context, path, this) + " " + operator + " " +
               rightCondition.toString(context, path, this);
    }

    @Override
    public Value evaluate(Context context) {
        Value leftValue=leftCondition.evaluate(context);
        Value rightValue=rightCondition.evaluate(context);
        return new BooleanValue(leftValue.compare(operator,rightValue));
    }

    @Override
    public ComparisonNode setChildren(List<ExpressionNode> children) {
        if (children.size() != 2) throw new IllegalArgumentException("A comparison test must have 2 children");
        return new ComparisonNode(children.get(0), operator, children.get(1));
    }

}
