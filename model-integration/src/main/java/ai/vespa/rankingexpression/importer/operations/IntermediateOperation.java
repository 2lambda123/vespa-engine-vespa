// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

package ai.vespa.rankingexpression.importer.operations;

import ai.vespa.rankingexpression.importer.DimensionRenamer;
import ai.vespa.rankingexpression.importer.IntermediateGraph;
import ai.vespa.rankingexpression.importer.OrderedTensorType;
import com.yahoo.searchlib.rankingexpression.Reference;
import com.yahoo.searchlib.rankingexpression.evaluation.Value;
import com.yahoo.searchlib.rankingexpression.rule.ExpressionNode;
import com.yahoo.searchlib.rankingexpression.rule.ReferenceNode;
import com.yahoo.searchlib.rankingexpression.rule.TensorFunctionNode;
import com.yahoo.tensor.TensorType;
import com.yahoo.tensor.evaluation.VariableTensor;
import com.yahoo.tensor.functions.TensorFunction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Wraps an imported operation node and produces the respective Vespa tensor
 * operation. During import, a graph of these operations are constructed. Then,
 * the types are used to deduce sensible dimension names using the
 * DimensionRenamer. After the types have been renamed, the proper Vespa
 * expressions can be extracted.
 *
 * @author lesters
 */
public abstract class IntermediateOperation {

    public final static String FUNCTION_PREFIX = "imported_ml_function_";

    protected final String name;
    protected final String modelName;
    protected final List<IntermediateOperation> inputs;
    protected final List<IntermediateOperation> outputs = new ArrayList<>();

    protected OrderedTensorType type;
    protected TensorFunction function;
    protected TensorFunction rankingExpressionFunction = null;

    private final List<String> importWarnings = new ArrayList<>();
    private Value constantValue = null;
    private List<IntermediateOperation> controlInputs = Collections.emptyList();

    protected Function<OrderedTensorType, Value> constantValueFunction = null;

    IntermediateOperation(String modelName, String name, List<IntermediateOperation> inputs) {
        this.name = name;
        this.modelName = modelName;
        this.inputs = Collections.unmodifiableList(inputs);
        this.inputs.forEach(i -> i.outputs.add(this));
    }

    protected abstract OrderedTensorType lazyGetType();
    protected abstract TensorFunction lazyGetFunction();

    public String modelName() { return modelName; }

    /** Returns the Vespa tensor type of this operation if it exists */
    public Optional<OrderedTensorType> type() {
        if (type == null) {
            type = lazyGetType();
        }
        return Optional.ofNullable(type);
    }

    /** Returns the Vespa tensor function implementing all operations from this node with inputs */
    public Optional<TensorFunction> function() {
        if (function == null) {
            if (isConstant()) {
                ExpressionNode constant = new ReferenceNode(Reference.simple("constant", vespaName()));
                function = new TensorFunctionNode.TensorFunctionExpressionNode(constant);
            } else if (outputs.size() > 1) {
                rankingExpressionFunction = lazyGetFunction();
                function = new VariableTensor(rankingExpressionFunctionName(), type.type());
            } else {
                function = lazyGetFunction();
            }
        }
        return Optional.ofNullable(function);
    }

    /** Returns original name of this operation node */
    public String name() { return name; }

    /** Return unmodifiable list of inputs */
    public List<IntermediateOperation> inputs() { return inputs; }

    /** Return unmodifiable list of outputs. If a node has multiple outputs, consider adding a function. */
    public List<IntermediateOperation> outputs() { return Collections.unmodifiableList(outputs); }

    /** Returns a function that should be added as a ranking expression function */
    public Optional<TensorFunction> rankingExpressionFunction() {
        return Optional.ofNullable(rankingExpressionFunction);
    }

    /** Add dimension name constraints for this operation */
    public void addDimensionNameConstraints(DimensionRenamer renamer) { }

    /** Convenience method to adds dimensions and constraints of the given tensor type */
    protected void addConstraintsFrom(OrderedTensorType type, DimensionRenamer renamer) {
        for (int i = 0; i < type.dimensions().size(); i++) {
            renamer.addDimension(type.dimensions().get(i).name());

            // Each dimension is distinct and ordered correctly:
            for (int j = i + 1; j < type.dimensions().size(); j++) {
                renamer.addConstraint(type.dimensions().get(i).name(), type.dimensions().get(j).name(),
                                      DimensionRenamer.Constraint.notEqual(false),
                                      this);
            }
        }
    }

    /** Performs dimension rename for this operation */
    public void renameDimensions(DimensionRenamer renamer) { type = type.rename(renamer); }

    /** Return true for operations that are inputs to the model itself (as opposed to inputs to the operation) */
    public boolean isInput() { return false; }

    /** Return true if this node is constant */
    public boolean isConstant() { return inputs.stream().allMatch(IntermediateOperation::isConstant); }

    /** Sets the constant value */
    public void setConstantValue(Value value) { constantValue = value; }

    /** Gets the constant value if it exists */
    public Optional<Value> getConstantValue() {
        if (constantValue != null) {
            return Optional.of(constantValue);
        }
        if (constantValueFunction != null) {
            return Optional.of(constantValueFunction.apply(type));
        }
        return Optional.empty();
    }

    /** Set the constant value function */
    public void setConstantValueFunction(Function<OrderedTensorType, Value> func) { this.constantValueFunction = func; }

    /** Sets the external control inputs */
    public void setControlInputs(List<IntermediateOperation> inputs) { this.controlInputs = inputs; }

    /** Retrieve the control inputs for this operation */
    public List<IntermediateOperation> getControlInputs() { return Collections.unmodifiableList(this.controlInputs); }

    /** Retrieve the valid Vespa name of this node */
    public String vespaName() { return vespaName(name); }
    public String vespaName(String name) { return name != null ? namePartOf(name).replace('/', '_') : null; }

    /** Retrieve the valid Vespa name of this node if it is a ranking expression function */
    public String rankingExpressionFunctionName() {
        return vespaName() != null ? FUNCTION_PREFIX + modelName + "_" + vespaName() : null;
    }

    /** Retrieve the list of warnings produced during its lifetime */
    public List<String> warnings() { return Collections.unmodifiableList(importWarnings); }

    /** Set an input warning */
    public void warning(String warning) { importWarnings.add(warning); }

    boolean verifyInputs(int expected, Function<IntermediateOperation, Optional<?>> func) {
        if (inputs.size() != expected) {
            throw new IllegalArgumentException("Expected " + expected + " inputs for '" +
                                               name + "', got " + inputs.size());
        }
        return inputs.stream().map(func).allMatch(Optional::isPresent);
    }

    boolean allInputTypesPresent(int expected) {
        return verifyInputs(expected, IntermediateOperation::type);
    }

    boolean allInputFunctionsPresent(int expected) {
        return verifyInputs(expected, IntermediateOperation::function);
    }

    /**
     * Returns the largest value type among the input value types.
     * This should only be called after it has been verified that input types are available.
     *
     * @throws IllegalArgumentException if a type cannot be uniquely determined
     * @throws RuntimeException if called when input types are not available
     */
    TensorType.Value resultValueType() {
        return TensorType.Value.largestOf(inputs.stream()
                                                .map(input -> input.type().get().type().valueType())
                                                .collect(Collectors.toList()));
    }

    public abstract IntermediateOperation withInputs(List<IntermediateOperation> inputs);

    String asString(Optional<OrderedTensorType> type) {
        return type.map(t -> t.toString()).orElse("(unknown)");
    }

    /**
     * A method signature input and output has the form name:index.
     * This returns the name part without the index.
     */
    public static String namePartOf(String name) {
        name = name.startsWith("^") ? name.substring(1) : name;
        return name.split(":")[0];
    }

    /**
     * This return the output index part. Indexes are used for nodes with
     * multiple outputs.
     */
    public static int indexPartOf(String name) {
        int i = name.indexOf(":");
        return i < 0 ? 0 : Integer.parseInt(name.substring(i + 1));
    }

    public abstract String operationName();

    @Override
    public String toString() {
        return operationName() + "(" +
               inputs().stream().map(input -> asString(input.type())).collect(Collectors.joining(", ")) +
               ")";
    }

    public String toFullString() {
        return "\t" + type + ":\t" + operationName() + "(" +
               inputs().stream().map(input -> input.toFullString()).collect(Collectors.joining(", ")) +
               ")";
    }

    /**
     * An interface mapping operation attributes to Vespa Values.
     * Adapter for differences in different model types.
     */
    public interface AttributeMap {
        Optional<Value> get(String key);
        Optional<Value> get(String key, OrderedTensorType type);
        Optional<List<Value>> getList(String key);
    }

}
