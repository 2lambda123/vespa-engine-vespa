// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchlib.rankingexpression.rule;

import com.google.common.collect.ImmutableList;
import com.yahoo.searchlib.rankingexpression.evaluation.Context;
import com.yahoo.searchlib.rankingexpression.evaluation.MapContext;
import com.yahoo.searchlib.rankingexpression.evaluation.Value;
import com.yahoo.tensor.TensorType;

import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.*;

/**
 * A tensor generating function, whose arguments are determined by a tensor type
 *
 * @author bratseth
 */
public class GeneratorLambdaFunctionNode extends CompositeNode {

    private final TensorType type;
    private final ExpressionNode generator;

    public GeneratorLambdaFunctionNode(TensorType type, ExpressionNode generator) {
        if ( ! type.dimensions().stream().allMatch(d -> d.size().isPresent()))
            throw new IllegalArgumentException("A tensor generator function can only generate tensors with bound " +
                                               "dimensions, but tried to generate " + type);
        // TODO: Verify that the function only accesses the given arguments
        this.type = type;
        this.generator = generator;
    }

    @Override
    public List<ExpressionNode> children() {
        return Collections.singletonList(generator);
    }

    @Override
    public CompositeNode setChildren(List<ExpressionNode> children) {
        if ( children.size() != 1)
            throw new IllegalArgumentException("A lambda function must have a single child expression");
        return new GeneratorLambdaFunctionNode(type, children.get(0));
    }

    @Override
    public String toString(SerializationContext context, Deque<String> path, CompositeNode parent) {
        return generator.toString(context, path, this);
    }

    /** Evaluate this in a context which must have the arguments bound */
    @Override
    public Value evaluate(Context context) {
        return generator.evaluate(context);
    }

    /**
     * Returns this as an operator which converts a list of integers into a double
     */
    public IntegerListToDoubleLambda asIntegerListToDoubleOperator() {
        return new IntegerListToDoubleLambda();
    }

    private class IntegerListToDoubleLambda implements java.util.function.Function<List<Integer>, Double> {

        @Override
        public Double apply(List<Integer> arguments) {
            MapContext context = new MapContext();
            for (int i = 0; i < type.dimensions().size(); i++)
                context.put(type.dimensions().get(i).name(), arguments.get(i));
            return evaluate(context).asDouble();
        }

        @Override
        public String toString() {
            return GeneratorLambdaFunctionNode.this.toString();
        }

    }

}
