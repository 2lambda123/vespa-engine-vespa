// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.testutils;

import com.yahoo.vespa.config.nodes.NodeRepositoryConfig;
import com.yahoo.vespa.hosted.provision.node.Flavor;
import com.yahoo.vespa.hosted.provision.node.NodeFlavors;

/**
 * Simplifies creation of a node-repository config containing flavors.
 * This is needed because the config builder API is inconvenient.
 *
 * @author bratseth
 */
public class FlavorConfigBuilder {

    private NodeRepositoryConfig.Builder builder = new NodeRepositoryConfig.Builder();

    public NodeRepositoryConfig build() {
        return new NodeRepositoryConfig(builder);
    }

    public NodeRepositoryConfig.Flavor.Builder addFlavor(String flavorName, double cpu, double mem, double disk, Flavor.Type type) {
        NodeRepositoryConfig.Flavor.Builder flavor = new NodeRepositoryConfig.Flavor.Builder();
        flavor.name(flavorName);
        flavor.description("Flavor-name-is-" + flavorName);
        flavor.minDiskAvailableGb(disk);
        flavor.minCpuCores(cpu);
        flavor.minMainMemoryAvailableGb(mem);
        flavor.environment(type.name());
        builder.flavor(flavor);
        return flavor;
    }

    public void addReplaces(String replaces, NodeRepositoryConfig.Flavor.Builder flavor) {
        NodeRepositoryConfig.Flavor.Replaces.Builder flavorReplaces = new NodeRepositoryConfig.Flavor.Replaces.Builder();
        flavorReplaces.name(replaces);
        flavor.replaces(flavorReplaces);
    }

    public void addCost(int cost, NodeRepositoryConfig.Flavor.Builder flavor) {
        flavor.cost(cost);
    }

    /** Convenience method which creates a node flavors instance from a list of flavor names */
    public static NodeFlavors createDummies(String... flavors) {

        FlavorConfigBuilder flavorConfigBuilder = new FlavorConfigBuilder();
        for (String flavorName : flavors) {
            if (flavorName.equals("docker"))
                flavorConfigBuilder.addFlavor(flavorName, 1. /* cpu*/, 3. /* mem GB*/, 2. /*disk GB*/, Flavor.Type.DOCKER_CONTAINER);
            else
                flavorConfigBuilder.addFlavor(flavorName, 1. /* cpu*/, 3. /* mem GB*/, 2. /*disk GB*/, Flavor.Type.BARE_METAL);
        }
        return new NodeFlavors(flavorConfigBuilder.build());
    }
}
