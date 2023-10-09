// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.tensor.functions;

import com.yahoo.tensor.DimensionSizes;
import com.yahoo.tensor.IndexedTensor;
import com.yahoo.tensor.Tensor;
import com.yahoo.tensor.TensorType;
import com.yahoo.tensor.evaluation.EvaluationContext;
import com.yahoo.tensor.evaluation.Name;
import com.yahoo.tensor.evaluation.TypeContext;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * An indexed tensor whose values are generated by a function
 *
 * @author bratseth
 */
public class Generate<NAMETYPE extends Name> extends PrimitiveTensorFunction<NAMETYPE> {

    private final TensorType type;

    // One of these are null
    private final Function<List<Long>, Double> freeGenerator;
    private final ScalarFunction<NAMETYPE> boundGenerator;

    /** The same as Generate.free */
    public Generate(TensorType type, Function<List<Long>, Double> generator) {
        this(type, Objects.requireNonNull(generator), null);
    }

    /**
     * Creates a generated tensor from a free function
     *
     * @param type the type of the tensor
     * @param generator the function generating values from a list of numbers specifying the indexes of the
     *                  tensor cell which will receive the value
     * @throws IllegalArgumentException if any of the tensor dimensions are not indexed bound
     */
    public static <NAMETYPE extends Name> Generate<NAMETYPE> free(TensorType type, Function<List<Long>, Double> generator) {
        return new Generate<>(type, Objects.requireNonNull(generator), null);
    }

    /**
     * Creates a generated tensor from a bound function
     *
     * @param type the type of the tensor
     * @param generator the function generating values from a list of numbers specifying the indexes of the
     *                  tensor cell which will receive the value
     * @throws IllegalArgumentException if any of the tensor dimensions are not indexed bound
     */
    public static <NAMETYPE extends Name> Generate<NAMETYPE> bound(TensorType type, ScalarFunction<NAMETYPE> generator) {
        return new Generate<>(type, null, Objects.requireNonNull(generator));
    }

    private Generate(TensorType type, Function<List<Long>, Double> freeGenerator, ScalarFunction<NAMETYPE> boundGenerator) {
        Objects.requireNonNull(type, "The argument tensor type cannot be null");
        validateType(type);
        this.type = type;
        this.freeGenerator = freeGenerator;
        this.boundGenerator = boundGenerator;
    }

    private void validateType(TensorType type) {
        for (TensorType.Dimension dimension : type.dimensions())
            if (dimension.type() != TensorType.Dimension.Type.indexedBound)
                throw new IllegalArgumentException("A generated tensor can only have indexed bound dimensions");
    }

    @Override
    public List<TensorFunction<NAMETYPE>> arguments() {
        return boundGenerator != null && boundGenerator.asTensorFunction().isPresent()
               ? List.of(boundGenerator.asTensorFunction().get())
               : List.of();
    }

    @Override
    public TensorFunction<NAMETYPE> withArguments(List<TensorFunction<NAMETYPE>> arguments) {
        if ( arguments.size() > 1)
            throw new IllegalArgumentException("Generate must have 0 or 1 arguments, got " + arguments.size());
        if (arguments.isEmpty()) return this;

        if (arguments.get(0).asScalarFunction().isEmpty())
            throw new IllegalArgumentException("The argument to generate must be convertible to a tensor function, " +
                                               "but got " + arguments.get(0));

        return new Generate<>(type, null, arguments.get(0).asScalarFunction().get());
    }

    @Override
    public PrimitiveTensorFunction<NAMETYPE> toPrimitive() { return this; }

    @Override
    public TensorType type(TypeContext<NAMETYPE> context) { return type; }

    @Override
    public Tensor evaluate(EvaluationContext<NAMETYPE> context) {
        Tensor.Builder builder = Tensor.Builder.of(type);
        IndexedTensor.Indexes indexes = IndexedTensor.Indexes.of(dimensionSizes(type));
        GenerateEvaluationContext generateContext = new GenerateEvaluationContext(type, context);
        for (int i = 0; i < indexes.size(); i++) {
            indexes.next();
            builder.cell(generateContext.apply(indexes), indexes.indexesForReading());
        }
        return builder.build();
    }

    private DimensionSizes dimensionSizes(TensorType type) {
        DimensionSizes.Builder b = new DimensionSizes.Builder(type.dimensions().size());
        for (int i = 0; i < b.dimensions(); i++)
            b.set(i, type.dimensions().get(i).size().get());
        return b.build();
    }

    @Override
    public String toString(ToStringContext<NAMETYPE> context) { return type + "(" + generatorToString(context) + ")"; }

    private String generatorToString(ToStringContext<NAMETYPE> context) {
        if (freeGenerator != null)
            return freeGenerator.toString();
        else
            return boundGenerator.toString(new GenerateToStringContext(context));
    }

    @Override
    public int hashCode() { return Objects.hash("generate", type, freeGenerator, boundGenerator); }

    /**
     * A context for generating all the values of a tensor produced by evaluating Generate.
     * This returns all the current index values as variables and falls back to delivering from the given
     * evaluation context.
     */
    private class GenerateEvaluationContext implements EvaluationContext<NAMETYPE> {

        private final TensorType type;
        private final EvaluationContext<NAMETYPE> context;

        private IndexedTensor.Indexes indexes;

        GenerateEvaluationContext(TensorType type, EvaluationContext<NAMETYPE> context) {
            this.type = type;
            this.context = context;
        }

        double apply(IndexedTensor.Indexes indexes) {
            if (freeGenerator != null) {
                return freeGenerator.apply(indexes.toList());
            }
            else {
                this.indexes = indexes;
                return boundGenerator.apply(this);
            }
        }

        @Override
        public Tensor getTensor(String name) {
            Optional<Integer> index = type.indexOfDimension(name);
            if (index.isPresent()) // this is the name of a dimension
                return Tensor.from(indexes.indexesForReading()[index.get()]);
            else
                return context.getTensor(name);
        }

        @Override
        public TensorType getType(NAMETYPE name) {
            Optional<Integer> index = type.indexOfDimension(name.name());
            if (index.isPresent()) // this is the name of a dimension
                return TensorType.empty;
            else
                return context.getType(name);
        }

        @Override
        public TensorType getType(String name) {
            Optional<Integer> index = type.indexOfDimension(name);
            if (index.isPresent()) // this is the name of a dimension
                return TensorType.empty;
            else
                return context.getType(name);
        }

    }

    /** A context which adds the bindings of the generate dimension names to the given context. */
    private class GenerateToStringContext implements ToStringContext<NAMETYPE> {

        private final ToStringContext<NAMETYPE> context;

        public GenerateToStringContext(ToStringContext<NAMETYPE> context) {
            this.context = context;
        }

        @Override
        public String getBinding(String identifier) {
            if (type.dimension(identifier).isPresent())
                return identifier; // dimension names are bound but not substituted in the generate context
            else
                return context.getBinding(identifier);
        }

        @Override
        public ToStringContext<NAMETYPE> parent() { return context; }

    }

}
