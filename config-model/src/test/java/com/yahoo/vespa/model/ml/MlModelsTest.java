// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.ml;

import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.io.IOUtils;
import com.yahoo.path.Path;
import com.yahoo.vespa.config.search.RankProfilesConfig;
import com.yahoo.vespa.model.VespaModel;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

/**
 * Tests rank profile imported model evaluation
 *
 * @author bratseth
 */
public class MlModelsTest {

    @Test
    public void testMl_serving() throws IOException {
        Path appDir = Path.fromString("src/test/cfg/application/ml_models");
        Path storedAppDir = appDir.append("copy");
        try {
            ImportedModelTester tester = new ImportedModelTester("ml_models", appDir);
            verify(tester.createVespaModel());

            // At this point the expression is stored - copy application to another location which do not have a models dir
            storedAppDir.toFile().mkdirs();
            IOUtils.copy(appDir.append("services.xml").toString(), storedAppDir.append("services.xml").toString());
            IOUtils.copyDirectory(appDir.append(ApplicationPackage.MODELS_GENERATED_DIR).toFile(),
                                  storedAppDir.append(ApplicationPackage.MODELS_GENERATED_DIR).toFile());
            IOUtils.copyDirectory(appDir.append(ApplicationPackage.SEARCH_DEFINITIONS_DIR).toFile(),
                                  storedAppDir.append(ApplicationPackage.SEARCH_DEFINITIONS_DIR).toFile());
            ImportedModelTester storedTester = new ImportedModelTester("ml_models", storedAppDir);
            verify(storedTester.createVespaModel());
        }
        finally {
            IOUtils.recursiveDeleteDir(appDir.append(ApplicationPackage.MODELS_GENERATED_DIR).toFile());
            IOUtils.recursiveDeleteDir(storedAppDir.toFile());
        }
    }

    private void verify(VespaModel model) {
        assertEquals("Global models are created (although not used directly here",
                     4, model.rankProfileList().getRankProfiles().size());

        RankProfilesConfig.Builder builder = new RankProfilesConfig.Builder();
        model.getSearchClusters().get(0).getConfig(builder);
        RankProfilesConfig config = new RankProfilesConfig(builder);
        assertEquals(3, config.rankprofile().size());
        assertEquals("test", config.rankprofile(2).name());
        RankProfilesConfig.Rankprofile.Fef test = config.rankprofile(2).fef();

        // Compare profile content in a denser format than config:
        StringBuilder b = new StringBuilder();
        for (RankProfilesConfig.Rankprofile.Fef.Property p : test.property())
            b.append(p.name()).append(": ").append(p.value()).append("\n");
        assertEquals(testProfile, b.toString());
    }

    private final String testProfile =
            "rankingExpression(input).rankingScript: attribute(argument)\n" +
            "rankingExpression(input).type: tensor<float>(d0[],d1[784])\n" +
            "rankingExpression(Placeholder).rankingScript: attribute(argument)\n" +
            "rankingExpression(Placeholder).type: tensor<float>(d0[],d1[784])\n" +
            "rankingExpression(imported_ml_function_mnist_saved_dnn_hidden1_add).rankingScript: join(reduce(join(rename(rankingExpression(input), (d0, d1), (d0, d4)), constant(mnist_saved_dnn_hidden1_weights_read), f(a,b)(a * b)), sum, d4), constant(mnist_saved_dnn_hidden1_bias_read), f(a,b)(a + b))\n" +
            "rankingExpression(mnist_tensorflow).rankingScript: join(reduce(join(map(join(reduce(join(join(join(rankingExpression(imported_ml_function_mnist_saved_dnn_hidden1_add), 0.009999999776482582, f(a,b)(a * b)), rankingExpression(imported_ml_function_mnist_saved_dnn_hidden1_add), f(a,b)(max(a,b))), constant(mnist_saved_dnn_hidden2_weights_read), f(a,b)(a * b)), sum, d3), constant(mnist_saved_dnn_hidden2_bias_read), f(a,b)(a + b)), f(a)(1.0507009873554805 * if (a >= 0, a, 1.6732632423543772 * (exp(a) - 1)))), constant(mnist_saved_dnn_outputs_weights_read), f(a,b)(a * b)), sum, d2), constant(mnist_saved_dnn_outputs_bias_read), f(a,b)(a + b))\n" +
            "rankingExpression(mnist_softmax_tensorflow).rankingScript: join(reduce(join(rename(rankingExpression(Placeholder), (d0, d1), (d0, d2)), constant(mnist_softmax_saved_layer_Variable_read), f(a,b)(a * b)), sum, d2), constant(mnist_softmax_saved_layer_Variable_1_read), f(a,b)(a + b))\n" +
            "rankingExpression(mnist_softmax_onnx).rankingScript: join(reduce(join(rename(rankingExpression(Placeholder), (d0, d1), (d0, d2)), constant(mnist_softmax_Variable), f(a,b)(a * b)), sum, d2), constant(mnist_softmax_Variable_1), f(a,b)(a + b))\n" +
            "rankingExpression(my_xgboost).rankingScript: if (f29 < -0.1234567, if (f56 < -0.242398, 1.71218, -1.70044), if (f109 < 0.8723473, -1.94071, 1.85965)) + if (f60 < -0.482947, if (f29 < -4.2387498, 0.784718, -0.96853), -6.23624)\n" +
            "vespa.rank.firstphase: rankingExpression(firstphase)\n" +
            "rankingExpression(firstphase).rankingScript: rankingExpression(mnist_tensorflow) + rankingExpression(mnist_softmax_tensorflow) + rankingExpression(mnist_softmax_onnx) + rankingExpression(my_xgboost)\n" +
            "vespa.type.attribute.argument: tensor<float>(d0[],d1[784])\n";

}
