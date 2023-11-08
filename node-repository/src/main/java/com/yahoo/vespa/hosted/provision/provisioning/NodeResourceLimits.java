// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.Zone;
import com.yahoo.vespa.flags.FetchVector;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.flags.IntFlag;
import com.yahoo.vespa.hosted.provision.NodeRepository;

import java.util.Locale;

/**
 * Defines the resource limits for nodes in various zones
 *
 * @author bratseth
 * @see CapacityPolicies
 */
public class NodeResourceLimits {

    private final NodeRepository nodeRepository;
    private final IntFlag minExclusiveAdvertisedMemoryGbFlag;

    public NodeResourceLimits(NodeRepository nodeRepository) {
        this.nodeRepository = nodeRepository;
        this.minExclusiveAdvertisedMemoryGbFlag = Flags.MIN_EXCLUSIVE_ADVERTISED_MEMORY_GB.bindTo(nodeRepository.flagSource());
    }

    /** Validates the resources applications ask for (which are in "advertised" resource space) */
    public void ensureWithinAdvertisedLimits(AllocationParams params, String type, NodeResources requested) {
        boolean exclusive = params.exclusiveAllocation();
        if (! requested.vcpuIsUnspecified() && requested.vcpu() < minAdvertisedVcpu(params.application(), params.cluster(), exclusive))
            illegal(type, "vcpu", "", params.cluster(), requested.vcpu(), minAdvertisedVcpu(params.application(), params.cluster(), exclusive));
        if (! requested.memoryGbIsUnspecified() && requested.memoryGb() < minAdvertisedMemoryGb(params.application(), params.cluster(), exclusive))
            illegal(type, "memoryGb", "Gb", params.cluster(), requested.memoryGb(), minAdvertisedMemoryGb(params.application(), params.cluster(), exclusive));
        if (! requested.diskGbIsUnspecified() && requested.diskGb() < minAdvertisedDiskGb(requested, exclusive))
            illegal(type, "diskGb", "Gb", params.cluster(), requested.diskGb(), minAdvertisedDiskGb(requested, exclusive));
    }

    // TODO: Remove this when we are ready to fail, not just warn on this. */
    public boolean isWithinAdvertisedDiskLimits(NodeResources requested, ClusterSpec cluster) {
        if (requested.diskGbIsUnspecified() || requested.memoryGbIsUnspecified()) return true;
        return requested.diskGb() >= minAdvertisedDiskGb(requested, cluster);
    }

    /** Returns whether the real resources we'll end up with on a given tenant node are within limits */
    public boolean isWithinRealLimits(AllocationParams params, NodeCandidate candidateNode, ApplicationId applicationId, ClusterSpec cluster) {
        if (candidateNode.type() != NodeType.tenant) return true; // Resource limits only apply to tenant nodes
        return isWithinRealLimits(params, nodeRepository.resourcesCalculator().realResourcesOf(candidateNode, nodeRepository),
                                  applicationId, cluster);
    }

    /** Returns whether the real resources we'll end up with on a given tenant node are within limits */
    public boolean isWithinRealLimits(AllocationParams params, NodeResources realResources, ApplicationId applicationId, ClusterSpec cluster) {
        if (realResources.isUnspecified()) return true;

        if (realResources.vcpu() < minRealVcpu(params)) return false;
        if (realResources.memoryGb() < minRealMemoryGb(cluster)) return false;
        if (realResources.diskGb() < minRealDiskGb()) return false;
       return true;
    }

    public NodeResources enlargeToLegal(NodeResources requested, ApplicationId applicationId, ClusterSpec cluster, boolean exclusive, boolean followRecommendations) {
        if (requested.isUnspecified()) return requested;

        if (followRecommendations) // TODO: Do unconditionally when we enforce this limit
            requested = requested.withDiskGb(Math.max(minAdvertisedDiskGb(requested, cluster), requested.diskGb()));

        return requested.withVcpu(Math.max(minAdvertisedVcpu(applicationId, cluster, exclusive), requested.vcpu()))
                        .withMemoryGb(Math.max(minAdvertisedMemoryGb(applicationId, cluster, exclusive), requested.memoryGb()))
                        .withDiskGb(Math.max(minAdvertisedDiskGb(requested, exclusive), requested.diskGb()));
    }

    private double minAdvertisedVcpu(ApplicationId applicationId, ClusterSpec cluster, boolean exclusive) {
        if (cluster.type() == ClusterSpec.Type.admin) return 0.1;
        if (zone().environment().isProduction() && ! zone().system().isCd() &&
                exclusive && ! applicationId.instance().isTester()) return 2;
        if (zone().environment().isProduction() && cluster.type().isContent()) return 1.0;
        if (zone().environment() == Environment.dev && ! exclusive) return 0.1;
        return 0.5;
    }

    private double minAdvertisedMemoryGb(ApplicationId applicationId, ClusterSpec cluster, boolean exclusive) {
        if (cluster.type() == ClusterSpec.Type.admin) return 1;
        if (!exclusive) return 4;
        return minExclusiveAdvertisedMemoryGbFlag
                .with(FetchVector.Dimension.INSTANCE_ID, applicationId.serializedForm())
                .with(FetchVector.Dimension.CLUSTER_ID, cluster.id().value())
                .with(FetchVector.Dimension.CLUSTER_TYPE, cluster.type().name())
                .value();
    }

    private double minAdvertisedDiskGb(NodeResources requested, boolean exclusive) {
        return minRealDiskGb() + reservedDiskSpaceGb(requested.storageType(), exclusive);
    }

    // TODO: Move this check into the above when we are ready to fail, not just warn on this. */
    private static double minAdvertisedDiskGb(NodeResources requested, ClusterSpec cluster) {
        return requested.memoryGb() * switch (cluster.type()) {
            case combined, content -> 3;
            case container -> 2;
            default -> 0; // No constraint on other types
        };
    }

    // Note: Assumes node type 'host'
    private long reservedDiskSpaceGb(NodeResources.StorageType storageType, boolean exclusive) {
        if (storageType == NodeResources.StorageType.local && ! zone().cloud().allowHostSharing())
            return nodeRepository.resourcesCalculator().reservedDiskSpaceInBase2Gb(NodeType.host, ! exclusive);
        else
            return 4;
    }

    private double minRealVcpu(AllocationParams params) {
        return minAdvertisedVcpu(params.application(), params.cluster(), params.exclusiveAllocation());
    }

    private static double minRealMemoryGb(ClusterSpec cluster) {
        if (cluster.type() == ClusterSpec.Type.admin) return 0.95; // TODO: Increase to 1.05 after March 2023
        return 2.3;
    }

    private static double minRealDiskGb() { return 6; }

    private Zone zone() { return nodeRepository.zone(); }

    private static void illegal(String type, String resource, String unit, ClusterSpec cluster, double requested, double minAllowed) {
        if ( ! unit.isEmpty())
            unit = " " + unit;
        String message = String.format(Locale.ENGLISH,
                                       "%s cluster '%s': " + type + " " + resource +
                                       " size is %.2f%s but must be at least %.2f%s",
                                       cluster.type().name(), cluster.id().value(), requested, unit, minAllowed, unit);
        throw new IllegalArgumentException(message);
    }

}
