// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.testutils;

import com.yahoo.vespa.config.nodes.NodeRepositoryConfig;
import com.yahoo.vespa.hosted.provision.node.Flavor;
import com.yahoo.vespa.hosted.provision.node.NodeFlavors;

/**
 * A mock repository prepopulated with flavors, to avoid having config.
 * Instantiated by DI from application package above.
 */
public class MockNodeFlavors extends NodeFlavors {

    public MockNodeFlavors() {
        super(createConfig());
    }

    private static NodeRepositoryConfig createConfig() {
        FlavorConfigBuilder b = new FlavorConfigBuilder();
        b.addFlavor("default", 2., 16., 400, Flavor.Type.BARE_METAL);
        b.addFlavor("medium-disk", 6., 12., 56, Flavor.Type.BARE_METAL);
        b.addFlavor("large", 4., 32., 1600, Flavor.Type.BARE_METAL);
        b.addFlavor("docker", 0.2, 0.5, 100, Flavor.Type.DOCKER_CONTAINER);
        NodeRepositoryConfig.Flavor.Builder largeVariant = b.addFlavor("large-variant", 64, 128, 2000, Flavor.Type.BARE_METAL);
        b.addReplaces("large", largeVariant);
        NodeRepositoryConfig.Flavor.Builder expensiveFlavor = b.addFlavor("expensive", 0, 0, 0, Flavor.Type.BARE_METAL);
        b.addReplaces("default", expensiveFlavor);
        b.addCost(200, expensiveFlavor);

        return b.build();
    }

}
