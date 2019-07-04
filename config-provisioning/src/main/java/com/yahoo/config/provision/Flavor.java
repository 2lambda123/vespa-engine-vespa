// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.provision;

import com.yahoo.config.provisioning.FlavorsConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A host or node flavor.
 * *Host* flavors come from a configured set which corresponds to the actual flavors available in a zone.
 * *Node* flavors are simply a wrapper of a NodeResources object (for now (May 2019) with the exception of some
 *        legacy behavior where nodes are allocated by specifying a physical host flavor directly).
 *
 * @author bratseth
 */
public class Flavor {

    private boolean configured;
    private final String name;
    private final int cost;
    private final boolean isStock;
    private final Type type;
    private final double bandwidth;
    private final boolean retired;
    private List<Flavor> replacesFlavors;

    /** The hardware resources of this flavor */
    private NodeResources resources;

    /** Creates a *host* flavor from configuration */
    public Flavor(FlavorsConfig.Flavor flavorConfig) {
        this.configured = true;
        this.name = flavorConfig.name();
        this.cost = flavorConfig.cost();
        this.isStock = flavorConfig.stock();
        this.type = Type.valueOf(flavorConfig.environment());
        this.resources = new NodeResources(flavorConfig.minCpuCores(),
                                           flavorConfig.minMainMemoryAvailableGb(),
                                           flavorConfig.minDiskAvailableGb(),
                                           flavorConfig.fastDisk() ? NodeResources.DiskSpeed.fast : NodeResources.DiskSpeed.slow);
        this.bandwidth = flavorConfig.bandwidth();
        this.retired = flavorConfig.retired();
        this.replacesFlavors = new ArrayList<>();
    }

    /** Creates a *node* flavor from a node resources spec */
    public Flavor(NodeResources resources) {
        Objects.requireNonNull(resources, "Resources cannot be null");
        if (resources.allocateByLegacyName())
            throw new IllegalArgumentException("Can not create flavor '" + resources.legacyName() + "' from a flavor: " +
                                               "Non-docker flavors must be of a configured flavor");
        this.configured = false;
        this.name = resources.legacyName().orElse(resources.toString());
        this.cost = 0;
        this.isStock = true;
        this.type = Type.DOCKER_CONTAINER;
        this.bandwidth = 1;
        this.retired = false;
        this.replacesFlavors = List.of();
        this.resources = resources;
    }

    /** Returns the unique identity of this flavor if it is configured, or the resource spec string otherwise */
    public String name() { return name; }

    /**
     * Get the monthly cost (total cost of ownership) in USD for this flavor, typically total cost
     * divided by 36 months.
     * 
     * @return monthly cost in USD
     */
    public int cost() { return cost; }
    
    public boolean isStock() { return isStock; }

    /**
     * True if this is a configured flavor used for hosts,
     * false if it is a virtual flavor created on the fly from node resources
     */
    public boolean isConfigured() { return configured; }

    public NodeResources resources() { return resources; }

    public double getMinMainMemoryAvailableGb() { return resources.memoryGb(); }

    public double getMinDiskAvailableGb() { return resources.diskGb(); }

    public boolean hasFastDisk() { return resources.diskSpeed() == NodeResources.DiskSpeed.fast; }

    public double getBandwidth() { return bandwidth; }

    public double getMinCpuCores() { return resources.vcpu(); }

    /** Returns whether the flavor is retired */
    public boolean isRetired() {
        return retired;
    }

    public Type getType() { return type; }
    
    /** Convenience, returns getType() == Type.DOCKER_CONTAINER */
    public boolean isDocker() { return type == Type.DOCKER_CONTAINER; }

    /**
     * Returns the canonical name of this flavor - which is the name which should be used as an interface to users.
     * The canonical name of this flavor is:
     * <ul>
     *   <li>If it replaces one flavor, the canonical name of the flavor it replaces
     *   <li>If it replaces multiple or no flavors - itself
     * </ul>
     *
     * The logic is that we can use this to capture the gritty details of configurations in exact flavor names
     * but also encourage users to refer to them by a common name by letting such flavor variants declare that they
     * replace the canonical name we want. However, if a node replaces multiple names, we have no basis for choosing one
     * of them as the canonical, so we return the current as canonical.
     */
    public String canonicalName() {
        return isCanonical() ? name : replacesFlavors.get(0).canonicalName();
    }
    
    /** Returns whether this is a canonical flavor */
    public boolean isCanonical() {
        return replacesFlavors.size() != 1;
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
        if (this.equals(flavor)) {
            return true;
        }
        if (this.retired) {
            return false;
        }
        for (Flavor replaces : replacesFlavors)
            if (replaces.satisfies(flavor))
                return true;
        return false;
    }

    /** Irreversibly freezes the content of this */
    public void freeze() {
        replacesFlavors = List.copyOf(replacesFlavors);
    }

    @Override
    public int hashCode() { return name.hashCode(); }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if ( ! (o instanceof Flavor)) return false;
        Flavor other = (Flavor)o;
        if (configured)
            return other.name.equals(this.name);
        else
            return this.resources.equals(other.resources);
    }

    @Override
    public String toString() {
        if (isConfigured())
            return "flavor '" + name + "'";
        else
            return name;
    }

    public enum Type {
        undefined, // Default value in config (flavors.def)
        BARE_METAL,
        VIRTUAL_MACHINE,
        DOCKER_CONTAINER
    }

}
