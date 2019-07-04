// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.rankingexpression.importer.tensorflow;

import com.yahoo.searchlib.rankingexpression.RankingExpression;
import com.yahoo.searchlib.rankingexpression.evaluation.Context;
import com.yahoo.searchlib.rankingexpression.evaluation.ContextIndex;
import com.yahoo.searchlib.rankingexpression.evaluation.ExpressionOptimizer;
import com.yahoo.searchlib.rankingexpression.evaluation.MapContext;
import com.yahoo.searchlib.rankingexpression.evaluation.TensorValue;
import ai.vespa.rankingexpression.importer.ImportedModel;
import com.yahoo.searchlib.rankingexpression.rule.CompositeNode;
import com.yahoo.searchlib.rankingexpression.rule.ExpressionNode;
import com.yahoo.searchlib.rankingexpression.rule.ReferenceNode;
import com.yahoo.tensor.Tensor;
import com.yahoo.tensor.TensorType;
import org.tensorflow.SavedModelBundle;
import org.tensorflow.Session;

import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Helper for TensorFlow import tests: Imports a model and provides asserts on it.
 * This currently assumes the TensorFlow model takes a single input of type tensor(d0[1],d1[784])
 *
 * @author bratseth
 */
public class TestableTensorFlowModel {

    private SavedModelBundle tensorFlowModel;
    private ImportedModel model;

    // Sizes of the input vector
    private int d0Size = 1;
    private int d1Size = 784;

    public TestableTensorFlowModel(String modelName, String modelDir) {
        tensorFlowModel = SavedModelBundle.load(modelDir, "serve");
        model = new TensorFlowImporter().importModel(modelName, modelDir, tensorFlowModel);
    }

    public TestableTensorFlowModel(String modelName, String modelDir, int d0Size, int d1Size) {
        this(modelName, modelDir);
        this.d0Size = d0Size;
        this.d1Size = d1Size;
    }

    public ImportedModel get() { return model; }

    /** Compare that summing the tensors produce the same result to within some tolerance delta */
    public void assertEqualResultSum(String inputName, String operationName, double delta) {
        Tensor tfResult = tensorFlowExecute(tensorFlowModel, inputName, operationName);
        Context context = contextFrom(model);
        Tensor placeholder = placeholderArgument();
        context.put(inputName, new TensorValue(placeholder));

        model.functions().forEach((k, v) -> evaluateFunction(context, model, k));

        RankingExpression expression = model.expressions().get(operationName);
        ExpressionOptimizer optimizer = new ExpressionOptimizer();
        optimizer.optimize(expression, (ContextIndex)context);

        Tensor vespaResult = expression.evaluate(context).asTensor();
        assertEquals("Operation '" + operationName + "' produces equal results",
                     tfResult.sum().asDouble(), vespaResult.sum().asDouble(), delta);
    }

    /** Compare tensors 100% exactly */
    public void assertEqualResult(String inputName, String operationName) {
        Tensor tfResult = tensorFlowExecute(tensorFlowModel, inputName, operationName);
        Context context = contextFrom(model);
        Tensor placeholder = placeholderArgument();
        context.put(inputName, new TensorValue(placeholder));

        model.functions().forEach((k, v) -> evaluateFunction(context, model, k));

        RankingExpression expression = model.expressions().get(operationName);
        ExpressionOptimizer optimizer = new ExpressionOptimizer();
        optimizer.optimize(expression, (ContextIndex)context);

        Tensor vespaResult = expression.evaluate(context).asTensor();
        assertEquals("Operation '" + operationName + "' produces equal results", tfResult, vespaResult);
    }

    private Tensor tensorFlowExecute(SavedModelBundle model, String inputName, String operationName) {
        Session.Runner runner = model.session().runner();
        FloatBuffer fb = FloatBuffer.allocate(d0Size * d1Size);
        for (int i = 0; i < d1Size; ++i) {
            fb.put(i, (float)(i * 1.0 / d1Size));
        }
        org.tensorflow.Tensor<?> placeholder = org.tensorflow.Tensor.create(new long[]{ d0Size, d1Size }, fb);
        runner.feed(inputName, placeholder);
        List<org.tensorflow.Tensor<?>> results = runner.fetch(operationName).run();
        assertEquals(1, results.size());
        return TensorConverter.toVespaTensor(results.get(0));
    }

    static Context contextFrom(ImportedModel result) {
        TestableModelContext context = new TestableModelContext();
        result.largeConstants().forEach((name, tensor) -> context.put("constant(" + name + ")", new TensorValue(Tensor.from(tensor))));
        result.smallConstants().forEach((name, tensor) -> context.put("constant(" + name + ")", new TensorValue(Tensor.from(tensor))));
        return context;
    }

    private Tensor placeholderArgument() {
        Tensor.Builder b = Tensor.Builder.of(new TensorType.Builder().indexed("d0", d0Size).indexed("d1", d1Size).build());
        for (int d0 = 0; d0 < d0Size; d0++)
            for (int d1 = 0; d1 < d1Size; d1++)
                b.cell(d1 * 1.0 / d1Size, d0, d1);
        return b.build();
    }

    private void evaluateFunction(Context context, ImportedModel model, String functionName) {
        if (!context.names().contains(functionName)) {
            RankingExpression e = RankingExpression.from(model.functions().get(functionName));
            evaluateFunctionDependencies(context, model, e.getRoot());
            context.put(functionName, new TensorValue(e.evaluate(context).asTensor()));
        }
    }

    private void evaluateFunctionDependencies(Context context, ImportedModel model, ExpressionNode node) {
        if (node instanceof ReferenceNode) {
            String name = node.toString();
            if (model.functions().containsKey(name)) {
                evaluateFunction(context, model, name);
            }
        }
        else if (node instanceof CompositeNode) {
            for (ExpressionNode child : ((CompositeNode)node).children()) {
                evaluateFunctionDependencies(context, model, child);
            }
        }
    }

    private static class TestableModelContext extends MapContext implements ContextIndex {
        @Override
        public int size() {
            return bindings().size();
        }
        @Override
        public int getIndex(String name) {
            throw new UnsupportedOperationException(this + " does not support index lookup by name");
        }
    }

}
