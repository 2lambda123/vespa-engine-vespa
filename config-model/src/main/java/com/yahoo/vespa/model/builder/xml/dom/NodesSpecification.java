// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.builder.xml.dom;

import com.yahoo.component.Version;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.config.model.ConfigModelContext;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.RotationName;
import com.yahoo.vespa.model.HostResource;
import com.yahoo.vespa.model.HostSystem;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A common utility class to represent a requirement for nodes during model building.
 * Such a requirement is commonly specified in services.xml as a <code>nodes</code> element.
 *
 * @author bratseth
 */
public class NodesSpecification {

    private final boolean dedicated;

    private final int count;

    private final int groups;

    /** The Vespa version we want the nodes to run */
    private Version version;

    /** 
     * Whether the capacity amount specified is required or can it be relaxed 
     * at the discretion of the component fulfilling it
     */
    private final boolean required;

    private final boolean canFail;
    
    private final boolean exclusive;

    /** The resources each node should have, or empty to use the default */
    private final Optional<NodeResources> resources;

    /** The identifier of the custom docker image layer to use (not supported yet) */
    private final Optional<String> dockerImage;

    private NodesSpecification(boolean dedicated, int count, int groups, Version version,
                               boolean required, boolean canFail, boolean exclusive,
                               Optional<NodeResources> resources, Optional<String> dockerImage) {
        this.dedicated = dedicated;
        this.count = count;
        this.groups = groups;
        this.version = version;
        this.required = required;
        this.canFail = canFail;
        this.exclusive = exclusive;
        this.resources = resources;
        this.dockerImage = dockerImage;
    }

    private NodesSpecification(boolean dedicated, boolean canFail, Version version, ModelElement nodesElement) {
        this(dedicated,
             nodesElement.requiredIntegerAttribute("count"),
             nodesElement.integerAttribute("groups", 1),
             version,
             nodesElement.booleanAttribute("required", false),
             canFail,
             nodesElement.booleanAttribute("exclusive", false),
             getFlavor(nodesElement),
             Optional.ofNullable(nodesElement.stringAttribute("docker-image")));
    }

    /** Returns a requirement for dedicated nodes taken from the given <code>nodes</code> element */
    public static NodesSpecification from(ModelElement nodesElement, ConfigModelContext context) {
        return new NodesSpecification(true,
                                      ! context.getDeployState().getProperties().isBootstrap(),
                                      context.getDeployState().getWantedNodeVespaVersion(),
                                      nodesElement);
    }

    /**
     * Returns a requirement for dedicated nodes taken from the <code>nodes</code> element
     * contained in the given parent element, or empty if the parent element is null, or the nodes elements
     * is not present.
     */
    public static Optional<NodesSpecification> fromParent(ModelElement parentElement, ConfigModelContext context) {
        if (parentElement == null) return Optional.empty();
        ModelElement nodesElement = parentElement.child("nodes");
        if (nodesElement == null) return Optional.empty();
        return Optional.of(from(nodesElement, context));
    }

    /**
     * Returns a requirement for undedicated or dedicated nodes taken from the <code>nodes</code> element
     * contained in the given parent element, or empty if the parent element is null, or the nodes elements
     * is not present.
     */
    public static Optional<NodesSpecification> optionalDedicatedFromParent(ModelElement parentElement,
                                                                           ConfigModelContext context) {
        if (parentElement == null) return Optional.empty();
        ModelElement nodesElement = parentElement.child("nodes");
        if (nodesElement == null) return Optional.empty();
        return Optional.of(new NodesSpecification(nodesElement.booleanAttribute("dedicated", false),
                                                  ! context.getDeployState().getProperties().isBootstrap(),
                                                  context.getDeployState().getWantedNodeVespaVersion(),
                                                  nodesElement));
    }

    /** Returns a requirement from <code>count</code> nondedicated nodes in one group */
    public static NodesSpecification nonDedicated(int count, ConfigModelContext context) {
        return new NodesSpecification(false,
                                      count,
                                      1,
                                      context.getDeployState().getWantedNodeVespaVersion(),
                                      false,
                                      ! context.getDeployState().getProperties().isBootstrap(),
                                      false,
                                      Optional.empty(),
                                      Optional.empty());
    }

    /** Returns a requirement from <code>count</code> dedicated nodes in one group */
    public static NodesSpecification dedicated(int count, ConfigModelContext context) {
        return new NodesSpecification(true,
                                      count,
                                      1,
                                      context.getDeployState().getWantedNodeVespaVersion(),
                                      false,
                                      ! context.getDeployState().getProperties().isBootstrap(),
                                      false,
                                      Optional.empty(),
                                      Optional.empty());
    }

    /**
     * Returns whether this requires dedicated nodes.
     * Otherwise the model encountering this request should reuse nodes requested for other purposes whenever possible.
     */
    public boolean isDedicated() { return dedicated; }

    /**
     * Returns whether the physical hosts running the nodes of this application can
     * also run nodes of other applications. Using exclusive nodes for containers increases security
     * and increases cost.
     */
    public boolean isExclusive() { return exclusive; }

    /** Returns the number of nodes required */
    public int count() { return count; }

    /** Returns the number of host groups this specifies. Default is 1 */
    public int groups() { return groups; }

    public Map<HostResource, ClusterMembership> provision(HostSystem hostSystem,
                                                          ClusterSpec.Type clusterType,
                                                          ClusterSpec.Id clusterId,
                                                          DeployLogger logger) {
        return provision(hostSystem, clusterType, clusterId, logger, Collections.emptySet());
    }

    public Map<HostResource, ClusterMembership> provision(HostSystem hostSystem,
                                                          ClusterSpec.Type clusterType,
                                                          ClusterSpec.Id clusterId,
                                                          DeployLogger logger,
                                                          Set<RotationName> rotations) {
        ClusterSpec cluster = ClusterSpec.request(clusterType, clusterId, version, exclusive, rotations);
        return hostSystem.allocateHosts(cluster, Capacity.fromCount(count, resources, required, canFail), groups, logger);
    }

    private static Optional<NodeResources> getFlavor(ModelElement nodesElement) {
        ModelElement flavor = nodesElement.child("resources");
        if (flavor != null) {
            return Optional.of(new NodeResources(flavor.requiredDoubleAttribute("vcpu"),
                                                 parseGbAmount(flavor.requiredStringAttribute("memory")),
                                                 parseGbAmount(flavor.requiredStringAttribute("disk"))));
        }
        else if (nodesElement.stringAttribute("flavor") != null) { // legacy fallback
            return Optional.of(NodeResources.fromLegacyName(nodesElement.stringAttribute("flavor")));
        }
        else { // Get the default
            return Optional.empty();
        }
    }

    private static double parseGbAmount(String byteAmount) {
        byteAmount = byteAmount.strip();
        byteAmount = byteAmount.toUpperCase();
        if (byteAmount.endsWith("B"))
            byteAmount = byteAmount.substring(0, byteAmount.length() -1);

        double multiplier = 1/Math.pow(1000, 3);
        if (byteAmount.endsWith("K"))
            multiplier = 1/Math.pow(1000, 2);
        else if (byteAmount.endsWith("M"))
            multiplier = 1/1000;
        else if (byteAmount.endsWith("G"))
            multiplier = 1;
        else if (byteAmount.endsWith("T"))
            multiplier = 1000;
        else if (byteAmount.endsWith("P"))
            multiplier = Math.pow(1000, 2);
        else if (byteAmount.endsWith("E"))
            multiplier = Math.pow(1000, 3);
        else if (byteAmount.endsWith("Z"))
            multiplier = Math.pow(1000, 4);
        else if (byteAmount.endsWith("Y"))
            multiplier = Math.pow(1000, 5);

        byteAmount = byteAmount.substring(0, byteAmount.length() -1 ).strip();
        try {
            return Double.parseDouble(byteAmount) * multiplier;
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid byte amount '" + byteAmount +
                                               "': Must be a floating point number " +
                                               "optionally followed by k, M, G, T, P, E, Z or Y");
        }
    }

    @Override
    public String toString() {
        return "specification of " + count + (dedicated ? " dedicated " : " ") + "nodes" +
               (resources.isPresent() ? " with resources " + resources.get() : "") +
               (groups > 1 ? " in " + groups + " groups" : "");
    }

}
