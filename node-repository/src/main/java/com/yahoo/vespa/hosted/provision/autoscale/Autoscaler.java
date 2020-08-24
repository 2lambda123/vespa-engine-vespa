// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.autoscale;

import com.yahoo.config.provision.ClusterResources;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.applications.Cluster;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The autoscaler makes decisions about the flavor and node count that should be allocated to a cluster
 * based on observed behavior.
 *
 * @author bratseth
 */
public class Autoscaler {

    /*
     TODO:
     - Scale group size
     - Consider taking spikes/variance into account
     - Measure observed regulation lag (startup+redistribution) and take it into account when deciding regulation observation window
     - Scale by performance not just load+cost
     */

    private static final int minimumMeasurements = 500; // TODO: Per node instead? Also say something about interval?

    /** What cost difference factor is worth a reallocation? */
    private static final double costDifferenceWorthReallocation = 0.1;
    /** What difference factor for a resource is worth a reallocation? */
    private static final double resourceDifferenceWorthReallocation = 0.1;

    private final NodeMetricsDb metricsDb;
    private final NodeRepository nodeRepository;
    private final AllocationOptimizer allocationOptimizer;

    public Autoscaler(NodeMetricsDb metricsDb, NodeRepository nodeRepository) {
        this.metricsDb = metricsDb;
        this.nodeRepository = nodeRepository;
        this.allocationOptimizer = new AllocationOptimizer(nodeRepository);
    }

    /**
     * Suggest a scaling of a cluster. This returns a better allocation (if found)
     * without taking min and max limits into account.
     *
     * @param clusterNodes the list of all the active nodes in a cluster
     * @return a new suggested allocation for this cluster, or empty if it should not be rescaled at this time
     */
    public Optional<ClusterResources> suggest(Cluster cluster, List<Node> clusterNodes) {
        return autoscale(clusterNodes, Limits.empty(), cluster.exclusive())
                       .map(AllocatableClusterResources::toAdvertisedClusterResources);

    }

    /**
     * Autoscale a cluster by load. This returns a better allocation (if found) inside the min and max limits.
     *
     * @param clusterNodes the list of all the active nodes in a cluster
     * @return a new suggested allocation for this cluster, or empty if it should not be rescaled at this time
     */
    public Optional<ClusterResources> autoscale(Cluster cluster, List<Node> clusterNodes) {
        if (cluster.minResources().equals(cluster.maxResources())) return Optional.empty(); // Shortcut
        return autoscale(clusterNodes, Limits.of(cluster), cluster.exclusive())
                       .map(AllocatableClusterResources::toAdvertisedClusterResources);
    }

    private Optional<AllocatableClusterResources> autoscale(List<Node> clusterNodes, Limits limits, boolean exclusive) {
        if (unstable(clusterNodes)) return Optional.empty();

        ClusterSpec.Type clusterType = clusterNodes.get(0).allocation().get().membership().cluster().type();
        AllocatableClusterResources currentAllocation = new AllocatableClusterResources(clusterNodes, nodeRepository);
        Optional<Double> cpuLoad    = averageLoad(Resource.cpu, clusterNodes, clusterType);
        Optional<Double> memoryLoad = averageLoad(Resource.memory, clusterNodes, clusterType);
        Optional<Double> diskLoad   = averageLoad(Resource.disk, clusterNodes, clusterType);
        if (cpuLoad.isEmpty() || memoryLoad.isEmpty() || diskLoad.isEmpty()) return Optional.empty();
        var target = ResourceTarget.idealLoad(cpuLoad.get(), memoryLoad.get(), diskLoad.get(), currentAllocation);

        Optional<AllocatableClusterResources> bestAllocation =
                allocationOptimizer.findBestAllocation(target, currentAllocation, limits, exclusive);
        if (bestAllocation.isEmpty()) return Optional.empty();
        if (similar(bestAllocation.get(), currentAllocation)) return Optional.empty();
        return bestAllocation;
    }

    /** Returns true if both total real resources and total cost are similar */
    private boolean similar(AllocatableClusterResources a, AllocatableClusterResources b) {
        return similar(a.cost(), b.cost(), costDifferenceWorthReallocation) &&
               similar(a.realResources().vcpu() * a.nodes(),
                       b.realResources().vcpu() * b.nodes(), resourceDifferenceWorthReallocation) &&
               similar(a.realResources().memoryGb() * a.nodes(),
                       b.realResources().memoryGb() * b.nodes(), resourceDifferenceWorthReallocation) &&
               similar(a.realResources().diskGb() * a.nodes(),
                       b.realResources().diskGb() * b.nodes(),
                       resourceDifferenceWorthReallocation);
    }

    private boolean similar(double r1, double r2, double threshold) {
        return Math.abs(r1 - r2) / r1 < threshold;
    }

    /**
     * Returns the average load of this resource in the measurement window,
     * or empty if we are not in a position to make decisions from these measurements at this time.
     */
    private Optional<Double> averageLoad(Resource resource, List<Node> clusterNodes, ClusterSpec.Type clusterType) {
        NodeMetricsDb.Window window = metricsDb.getWindow(nodeRepository.clock().instant().minus(scalingWindow(clusterType)),
                                                          resource,
                                                          clusterNodes.stream().map(Node::hostname).collect(Collectors.toList()));

        if (window.measurementCount() < minimumMeasurements) return Optional.empty();
        if (window.hostnames() != clusterNodes.size()) return Optional.empty(); // Regulate only when all nodes are measured

        return Optional.of(window.average());
    }

    /** The duration of the window we need to consider to make a scaling decision */
    private Duration scalingWindow(ClusterSpec.Type clusterType) {
        if (clusterType.isContent()) return Duration.ofHours(12); // Ideally we should use observed redistribution time
        return Duration.ofHours(12); // TODO: Measure much more often to get this down to minutes. And, ideally we should take node startup time into account
    }

    public static boolean unstable(List<Node> nodes) {
        return nodes.stream().anyMatch(node -> node.status().wantToRetire() ||
                                               node.allocation().get().membership().retired() ||
                                               node.allocation().get().isRemovable());
    }

}
