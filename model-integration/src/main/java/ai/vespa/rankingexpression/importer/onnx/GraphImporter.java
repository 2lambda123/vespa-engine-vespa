// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

package ai.vespa.rankingexpression.importer.onnx;

import com.yahoo.searchlib.rankingexpression.evaluation.TensorValue;
import ai.vespa.rankingexpression.importer.IntermediateGraph;
import ai.vespa.rankingexpression.importer.OrderedTensorType;
import ai.vespa.rankingexpression.importer.operations.Argument;
import ai.vespa.rankingexpression.importer.operations.ConcatV2;
import ai.vespa.rankingexpression.importer.operations.Constant;
import ai.vespa.rankingexpression.importer.operations.Identity;
import ai.vespa.rankingexpression.importer.operations.IntermediateOperation;
import ai.vespa.rankingexpression.importer.operations.Join;
import ai.vespa.rankingexpression.importer.operations.Map;
import ai.vespa.rankingexpression.importer.operations.MatMul;
import ai.vespa.rankingexpression.importer.operations.NoOp;
import ai.vespa.rankingexpression.importer.operations.Reshape;
import ai.vespa.rankingexpression.importer.operations.Shape;
import com.yahoo.tensor.functions.ScalarFunctions;
import onnx.Onnx;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Converts an ONNX graph to a Vespa IntermediateGraph which is the basis
 * for generating Vespa ranking expressions.
 *
 * @author lesters
 */
class GraphImporter {

    private static IntermediateOperation mapOperation(Onnx.NodeProto node,
                                                     List<IntermediateOperation> inputs,
                                                     IntermediateGraph graph) {
        String modelName = graph.name();
        String nodeName = getNodeName(node);

        switch (node.getOpType().toLowerCase()) {
            case "abs":         return new Map(modelName, nodeName, inputs, ScalarFunctions.abs());
            case "add":         return new Join(modelName, nodeName, inputs, ScalarFunctions.add());
            case "acos":        return new Map(modelName, nodeName, inputs, ScalarFunctions.acos());
            case "asin":        return new Map(modelName, nodeName, inputs, ScalarFunctions.asin());
            case "atan":        return new Map(modelName, nodeName, inputs, ScalarFunctions.atan());
            case "ceil":        return new Map(modelName, nodeName, inputs, ScalarFunctions.ceil());
            case "concat":      return new ConcatV2(modelName, nodeName, inputs);
            case "cos":         return new Map(modelName, nodeName, inputs, ScalarFunctions.cos());
            case "div":         return new Join(modelName, nodeName, inputs, ScalarFunctions.divide());
            case "elu":         return new Map(modelName, nodeName, inputs, ScalarFunctions.elu());
            case "equal":       return new Join(modelName, nodeName, inputs, ScalarFunctions.equal());
            case "exp":         return new Map(modelName, nodeName, inputs, ScalarFunctions.exp());
            case "floor":       return new Map(modelName, nodeName, inputs, ScalarFunctions.floor());
            case "greater":     return new Join(modelName, nodeName, inputs, ScalarFunctions.greater());
            case "identity":    return new Identity(modelName, nodeName, inputs);
            case "less":        return new Join(modelName, nodeName, inputs, ScalarFunctions.less());
            case "log":         return new Map(modelName, nodeName, inputs, ScalarFunctions.log());
            case "matmul":      return new MatMul(modelName, nodeName, inputs);
            case "max":         return new Join(modelName, nodeName, inputs, ScalarFunctions.max());
            case "min":         return new Join(modelName, nodeName, inputs, ScalarFunctions.min());
            case "mean":        return new Join(modelName, nodeName, inputs, ScalarFunctions.mean());
            case "mul":         return new Join(modelName, nodeName, inputs, ScalarFunctions.multiply());
            case "neg":         return new Map(modelName, nodeName, inputs, ScalarFunctions.neg());
            case "pow":         return new Join(modelName, nodeName, inputs, ScalarFunctions.pow());
            case "reshape":     return new Reshape(modelName, nodeName, inputs);
            case "reciprocal":  return new Map(modelName, nodeName, inputs, ScalarFunctions.reciprocal());
            case "relu":        return new Map(modelName, nodeName, inputs, ScalarFunctions.relu());
            case "selu":        return new Map(modelName, nodeName, inputs, ScalarFunctions.selu());
            case "shape":       return new Shape(modelName, nodeName, inputs);
            case "sin":         return new Map(modelName, nodeName, inputs, ScalarFunctions.sin());
            case "sqrt":        return new Map(modelName, nodeName, inputs, ScalarFunctions.sqrt());
            case "sigmoid":     return new Map(modelName, nodeName, inputs, ScalarFunctions.sigmoid());
            case "sub":         return new Join(modelName, nodeName, inputs, ScalarFunctions.subtract());
            case "tan":         return new Map(modelName, nodeName, inputs, ScalarFunctions.tan());
            case "tanh":        return new Map(modelName, nodeName, inputs, ScalarFunctions.tanh());
        }

        IntermediateOperation op = new NoOp(modelName, nodeName, inputs);
        op.warning("Operation '" + node.getOpType() + "' is currently not implemented");
        return op;
    }

    static IntermediateGraph importGraph(String modelName, Onnx.ModelProto model) {
        Onnx.GraphProto onnxGraph = model.getGraph();

        IntermediateGraph intermediateGraph = new IntermediateGraph(modelName);
        importOperations(onnxGraph, intermediateGraph);
        verifyOutputTypes(onnxGraph, intermediateGraph);

        return intermediateGraph;
    }

    private static void importOperations(Onnx.GraphProto onnxGraph, IntermediateGraph intermediateGraph) {
        for (Onnx.ValueInfoProto valueInfo : onnxGraph.getOutputList()) {
            importOperation(valueInfo.getName(), onnxGraph, intermediateGraph);
        }
    }

    private static IntermediateOperation importOperation(String name,
                                                         Onnx.GraphProto onnxGraph,
                                                         IntermediateGraph intermediateGraph) {
        if (intermediateGraph.alreadyImported(name)) {
            return intermediateGraph.get(name);
        }
        IntermediateOperation operation;
        if (isArgumentTensor(name, onnxGraph)) {
            Onnx.ValueInfoProto valueInfoProto = getArgumentTensor(name, onnxGraph);
            if (valueInfoProto == null)
                throw new IllegalArgumentException("Could not find argument tensor '" + name + "'");
            OrderedTensorType type = TypeConverter.typeFrom(valueInfoProto.getType());
            operation = new Argument(intermediateGraph.name(), valueInfoProto.getName(), type);

            intermediateGraph.inputs(intermediateGraph.defaultSignature())
                    .put(IntermediateOperation.namePartOf(name), operation.vespaName());

        } else if (isConstantTensor(name, onnxGraph)) {
            Onnx.TensorProto tensorProto = getConstantTensor(name, onnxGraph);
            OrderedTensorType defaultType = TypeConverter.typeFrom(tensorProto);
            operation = new Constant(intermediateGraph.name(), name, defaultType);
            operation.setConstantValueFunction(type -> new TensorValue(TensorConverter.toVespaTensor(tensorProto, type)));

        } else {
            Onnx.NodeProto node = getNodeFromGraph(name, onnxGraph);
            List<IntermediateOperation> inputs = importOperationInputs(node, onnxGraph, intermediateGraph);
            operation = mapOperation(node, inputs, intermediateGraph);

            if (isOutputNode(name, onnxGraph)) {
                intermediateGraph.outputs(intermediateGraph.defaultSignature())
                        .put(IntermediateOperation.namePartOf(name), operation.vespaName());
            }
        }
        intermediateGraph.put(operation.vespaName(), operation);

        return operation;
    }

    private static boolean isArgumentTensor(String name, Onnx.GraphProto graph) {
        Onnx.ValueInfoProto value = getArgumentTensor(name, graph);
        Onnx.TensorProto tensor = getConstantTensor(name, graph);
        return value != null && tensor == null;
    }

    private static boolean isConstantTensor(String name, Onnx.GraphProto graph) {
        Onnx.ValueInfoProto value = getArgumentTensor(name, graph);
        Onnx.TensorProto tensor = getConstantTensor(name, graph);
        return value != null && tensor != null;
    }

    private static Onnx.ValueInfoProto getArgumentTensor(String name, Onnx.GraphProto graph) {
        for (Onnx.ValueInfoProto valueInfo : graph.getInputList()) {
            if (valueInfo.getName().equals(name)) {
                return valueInfo;
            }
        }
        return null;
    }

    private static Onnx.TensorProto getConstantTensor(String name, Onnx.GraphProto graph) {
        for (Onnx.TensorProto tensorProto : graph.getInitializerList()) {
            if (tensorProto.getName().equals(name)) {
                return tensorProto;
            }
        }
        return null;
    }

    private static boolean isOutputNode(String name, Onnx.GraphProto graph) {
        return getOutputNode(name, graph) != null;
    }

    private static Onnx.ValueInfoProto getOutputNode(String name, Onnx.GraphProto graph) {
        for (Onnx.ValueInfoProto valueInfo : graph.getOutputList()) {
            if (valueInfo.getName().equals(name)) {
                return valueInfo;
            }
            String nodeName = IntermediateOperation.namePartOf(valueInfo.getName());
            if (nodeName.equals(name)) {
                return valueInfo;
            }
        }
        return null;
    }

    private static List<IntermediateOperation> importOperationInputs(Onnx.NodeProto node,
                                                                     Onnx.GraphProto onnxGraph,
                                                                     IntermediateGraph intermediateGraph) {
        return node.getInputList().stream()
                .map(nodeName -> importOperation(nodeName, onnxGraph, intermediateGraph))
                .collect(Collectors.toList());
    }

    private static void verifyOutputTypes(Onnx.GraphProto onnxGraph, IntermediateGraph intermediateGraph) {
        for (String outputName : intermediateGraph.outputs(intermediateGraph.defaultSignature()).values()) {
            IntermediateOperation operation = intermediateGraph.get(outputName);
            Onnx.ValueInfoProto onnxNode = getOutputNode(outputName, onnxGraph);
            OrderedTensorType type = operation.type().orElseThrow(
                        () -> new IllegalArgumentException("Output of '" + outputName + "' has no type."));
            TypeConverter.verifyType(onnxNode.getType(), type);
        }
    }

    private static Onnx.NodeProto getNodeFromGraph(String nodeName, Onnx.GraphProto graph) {
        Optional<Onnx.NodeProto> node;
        if (nodeName.contains(":")) {
            node = getNodeFromGraphOutputs(nodeName, graph);
        } else {
            node = getNodeFromGraphNames(nodeName, graph);
            if (node.isEmpty()) {
                node = getNodeFromGraphOutputs(nodeName, graph);
            }
        }
        return node.orElseThrow(() -> new IllegalArgumentException("Node '" + nodeName + "' not found in ONNX graph"));
    }

    private static Optional<Onnx.NodeProto> getNodeFromGraphOutputs(String nodeName, Onnx.GraphProto graph) {
        for (Onnx.NodeProto node : graph.getNodeList()) {
            for (String outputName : node.getOutputList()) {
                if (outputName.equals(nodeName)) {
                    return Optional.of(node);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Onnx.NodeProto> getNodeFromGraphNames(String nodeName, Onnx.GraphProto graph) {
        for (Onnx.NodeProto node : graph.getNodeList()) {
            if (node.getName().equals(nodeName)) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    private static String getNodeName(Onnx.NodeProto node) {
        String nodeName = node.getName();
        if (nodeName.length() > 0)
            return nodeName;
        if (node.getOutputCount() == 1)
            return node.getOutput(0);
        throw new IllegalArgumentException("Unable to find a suitable name for node '" + node.toString() + "'. " +
                "Either no explicit name given or no single output name.");
    }


}
