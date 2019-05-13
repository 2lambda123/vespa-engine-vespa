// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.NodeType;
import com.yahoo.vespa.hosted.provision.LockedNodeList;
import com.yahoo.vespa.hosted.provision.Node;

/**
 * Capacity calculation for docker hosts.
 * <p>
 * The calculations is based on an immutable copy of nodes that represents
 * all capacities in the system - i.e. all nodes in the node repo give or take.
 *
 * @author smorgrav
 */
public class DockerHostCapacity {

    private final LockedNodeList allNodes;

    public DockerHostCapacity(LockedNodeList allNodes) {
        this.allNodes = allNodes;
    }

    /**
     * Compare hosts on free capacity.
     * Used in prioritizing hosts for allocation in <b>descending</b> order.
     */
    int compare(Node hostA, Node hostB) {
        int comp = compare(freeCapacityOf(hostB, false),
                           freeCapacityOf(hostA, false));
        if (comp == 0) {
            comp = compare(freeCapacityOf(hostB, false),
                           freeCapacityOf(hostA, false));
            if (comp == 0) {
                // If resources are equal - we want to assign to the one with the most IPaddresses free
                comp = freeIPs(hostB) - freeIPs(hostA);
            }
        }
        return comp;
    }

    int compareWithoutInactive(Node hostA, Node hostB) {
        int comp = compare(freeCapacityOf(hostB,  true),
                           freeCapacityOf(hostA, true));
        if (comp == 0) {
            comp = compare(freeCapacityOf(hostB, true),
                           freeCapacityOf(hostA, true));
            if (comp == 0) {
                // If resources are equal - we want to assign to the one with the most IPaddresses free
                comp = freeIPs(hostB) - freeIPs(hostA);
            }
        }
        return comp;
    }

    private int compare(ResourceCapacity a, ResourceCapacity b) {
        return ResourceCapacityComparator.defaultOrder().compare(a, b);
    }

    /**
     * Checks the node capacity and free ip addresses to see
     * if we could allocate a flavor on the docker host.
     */
    boolean hasCapacity(Node dockerHost, ResourceCapacity requestedCapacity) {
        return freeCapacityOf(dockerHost, false).hasCapacityFor(requestedCapacity) && freeIPs(dockerHost) > 0;
    }

    /**
     * Number of free (not allocated) IP addresses assigned to the dockerhost.
     */
    int freeIPs(Node dockerHost) {
        return dockerHost.ipConfig().pool().findUnused(allNodes).size();
    }

    public ResourceCapacity getFreeCapacityTotal() {
        return allNodes.asList().stream()
                .filter(n -> n.type().equals(NodeType.host))
                .map(n -> freeCapacityOf(n, false))
                .reduce(ResourceCapacity.NONE, ResourceCapacity::add);
    }

    public ResourceCapacity getCapacityTotal() {
        return allNodes.asList().stream()
                .filter(n -> n.type().equals(NodeType.host))
                .map(ResourceCapacity::of)
                .reduce(ResourceCapacity.NONE, ResourceCapacity::add);
    }


    public int freeCapacityInFlavorEquivalence(Flavor flavor) {
        return allNodes.asList().stream()
                .filter(n -> n.type().equals(NodeType.host))
                .map(n -> canFitNumberOf(n, flavor))
                .reduce(0, (a, b) -> a + b);
    }

    public long getNofHostsAvailableFor(Flavor flavor) {
        return allNodes.asList().stream()
                .filter(n -> n.type().equals(NodeType.host))
                .filter(n -> hasCapacity(n, ResourceCapacity.of(flavor)))
                .count();
    }

    private int canFitNumberOf(Node node, Flavor flavor) {
        ResourceCapacity freeCapacity = freeCapacityOf(node, false);
        int capacityFactor = freeCapacityInFlavorEquivalence(freeCapacity, flavor);
        int ips = freeIPs(node);
        return Math.min(capacityFactor, ips);
    }

    int freeCapacityInFlavorEquivalence(ResourceCapacity freeCapacity, Flavor flavor) {
        if ( ! freeCapacity.hasCapacityFor(ResourceCapacity.of(flavor))) return 0;

        double cpuFactor = Math.floor(freeCapacity.vcpu() / flavor.getMinCpuCores());
        double memoryFactor = Math.floor(freeCapacity.memoryGb() / flavor.getMinMainMemoryAvailableGb());
        double diskFactor =  Math.floor(freeCapacity.diskGb() / flavor.getMinDiskAvailableGb());

        return (int) Math.min(Math.min(memoryFactor, cpuFactor), diskFactor);
    }

    /**
     * Calculate the remaining capacity for the dockerHost.
     * @param dockerHost The host to find free capacity of.
     *
     * @return A default (empty) capacity if not a docker host, otherwise the free/unallocated/rest capacity
     */
    public ResourceCapacity freeCapacityOf(Node dockerHost, boolean treatInactiveOrRetiredAsUnusedCapacity) {
        // Only hosts have free capacity
        if (!dockerHost.type().equals(NodeType.host)) return ResourceCapacity.NONE;

        return allNodes.childrenOf(dockerHost).asList().stream()
                .filter(container -> !(treatInactiveOrRetiredAsUnusedCapacity && isInactiveOrRetired(container)))
                .map(ResourceCapacity::of)
                .reduce(ResourceCapacity.of(dockerHost), ResourceCapacity::subtract);
    }

    private boolean isInactiveOrRetired(Node node) {
        boolean isInactive = node.state().equals(Node.State.inactive);
        boolean isRetired = false;
        if (node.allocation().isPresent()) {
            isRetired = node.allocation().get().membership().retired();
        }

        return isInactive || isRetired;
    }

}
