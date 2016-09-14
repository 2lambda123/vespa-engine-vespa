// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.node;

import com.google.common.collect.ImmutableList;
import com.yahoo.vespa.config.nodes.NodeRepositoryConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * A host flavor (type). This is a value object where the identity is the name.
 * Use {@link NodeFlavors} to create a flavor.
 *
 * @author bratseth
 */
public class Flavor {

    private final String name;
    private final int cost;
    private final Type type;
    private final double minCpuCores;
    private final double minMainMemoryAvailableGb;
    private final double minDiskAvailableGb;
    private final String description;
    private List<Flavor> replacesFlavors;

    /**
     * Creates a Flavor, but does not set the replacesFlavors.
     * @param flavorConfig config to be used for Flavor.
     */
    public Flavor(NodeRepositoryConfig.Flavor flavorConfig) {
        this.name = flavorConfig.name();
        this.replacesFlavors = new ArrayList<>();
        this.cost = flavorConfig.cost();
        this.type = Type.valueOf(flavorConfig.environment());
        this.minCpuCores = flavorConfig.minCpuCores();
        this.minMainMemoryAvailableGb = flavorConfig.minMainMemoryAvailableGb();
        this.minDiskAvailableGb = flavorConfig.minDiskAvailableGb();
        this.description = flavorConfig.description();
    }

    public String name() { return name; }

    /**
     * Get the monthly cost (total cost of ownership) in USD for this flavor, typically total cost
     * divided by 36 months.
     * @return Monthly cost in USD
     */
    public int cost() { return cost; }

    public double getMinMainMemoryAvailableGb() {
        return minMainMemoryAvailableGb;
    }

    public double getMinDiskAvailableGb() {
        return minDiskAvailableGb;
    }

    public double getMinCpuCores() {
        return minCpuCores;
    }

    public String getDescription() {
        return description;
    }

    public Type getType() {
        return type;
    }

    /**
     * Returns the canonical name of this flavor - which is the name which should be used as an interface to users.
     * The canonical name of this flavor is
     * <ul>
     *   <li>If it replaces one flavor, the canonical name of the flavor it replaces
     *   <li>If it replaces multiple or no flavors - itself
     * </ul>
     *
     * The logic is that we can use this to capture the gritty details of configurations in exact flavor names
     * but also encourage users to refer to them by a common name by letting such flavor variants declare that they
     * replace the canonical name we want. However, if a node replaces multiple names, it means that a former
     * flavor distinction has become obsolete so this name becomes one of the canonical names users should refer to.
     */
    public String canonicalName() {
        return replacesFlavors.size() == 1 ? replacesFlavors.get(0).canonicalName() : name;
    }

    /**
     * The flavors this (directly) replaces.
     * This is immutable if this is frozen, and a mutable list otherwise.
     */
    public List<Flavor> replaces() { return replacesFlavors; }

    /**
     * Returns whether this flavor satisfies the requested flavor, either directly
     * (by being the same), or by directly or indirectly replacing it
     */
    public boolean satisfies(Flavor flavor) {
        if (this.equals(flavor)) return true;
        for (Flavor replaces : replacesFlavors)
            if (replaces.satisfies(flavor))
                return true;
        return false;
    }

    /** Irreversibly freezes the content of this */
    public void freeze() {
        replacesFlavors = ImmutableList.copyOf(replacesFlavors);
    }

    @Override
    public int hashCode() { return name.hashCode(); }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if ( ! (other instanceof Flavor)) return false;
        return ((Flavor)other).name.equals(this.name);
    }

    @Override
    public String toString() { return "flavor '" + name + "'"; }

    public enum Type {
        undefined, // Deafult value in config (node-repository.def)
        BARE_METAL,
        VIRTUAL_MACHINE,
        DOCKER_CONTAINER
    }

}
