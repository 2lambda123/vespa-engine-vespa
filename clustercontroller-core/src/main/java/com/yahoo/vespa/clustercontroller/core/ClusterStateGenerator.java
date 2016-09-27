// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.clustercontroller.core;

import com.yahoo.vdslib.state.ClusterState;
import com.yahoo.vdslib.state.Node;
import com.yahoo.vdslib.state.NodeState;
import com.yahoo.vdslib.state.NodeType;
import com.yahoo.vdslib.state.State;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure functional cluster state generator which deterministically constructs a full
 * cluster state given the state of the content cluster, a set of cluster controller
 * configuration parameters and the current time.
 *
 * State version tracking is considered orthogonal to state generation. Therefore,
 * cluster state version is _not_ set here; its incrementing must be handled by the
 * caller.
 */
public class ClusterStateGenerator {

    static class Params {
        public ContentCluster cluster;
        public Map<NodeType, Integer> transitionTimes;
        public long currentTimeInMillis = 0;
        public int maxPrematureCrashes = 0;
        public int minStorageNodesUp = 1;
        public int minDistributorNodesUp = 1;
        public double minRatioOfStorageNodesUp = 0.0;
        public double minRatioOfDistributorNodesUp = 0.0;
        public double minNodeRatioPerGroup = 0.0;
        public int idealDistributionBits = 16;
        public int highestObservedDistributionBitCount = 16;
        public int lowestObservedDistributionBitCount = 16;
        public int maxInitProgressTime = 5000;

        Params() {
            this.transitionTimes = buildTransitionTimeMap(0, 0);
        }

        // FIXME de-dupe
        static Map<NodeType, Integer> buildTransitionTimeMap(int distributorTransitionTime, int storageTransitionTime) {
            Map<com.yahoo.vdslib.state.NodeType, java.lang.Integer> maxTransitionTime = new TreeMap<>();
            maxTransitionTime.put(com.yahoo.vdslib.state.NodeType.DISTRIBUTOR, distributorTransitionTime);
            maxTransitionTime.put(com.yahoo.vdslib.state.NodeType.STORAGE, storageTransitionTime);
            return maxTransitionTime;
        }

        Params cluster(ContentCluster cluster) {
            this.cluster = cluster;
            return this;
        }
        Params maxInitProgressTime(int maxTime) {
            this.maxInitProgressTime = maxTime;
            return this;
        }
        Params transitionTimes(int timeMs) {
            this.transitionTimes = buildTransitionTimeMap(timeMs, timeMs);
            return this;
        }
        Params transitionTimes(Map<NodeType, Integer> times) {
            this.transitionTimes = times;
            return this;
        }
        Params currentTimeInMilllis(long currentTimeMs) {
            this.currentTimeInMillis = currentTimeMs;
            return this;
        }
        Params maxPrematureCrashes(int count) {
            this.maxPrematureCrashes = count;
            return this;
        }
        Params minStorageNodesUp(int nodes) {
            this.minStorageNodesUp = nodes;
            return this;
        }
        Params minDistributorNodesUp(int nodes) {
            this.minDistributorNodesUp = nodes;
            return this;
        }
        Params minRatioOfStorageNodesUp(double minRatio) {
            this.minRatioOfStorageNodesUp = minRatio;
            return this;
        }
        Params minRatioOfDistributorNodesUp(double minRatio) {
            this.minRatioOfDistributorNodesUp = minRatio;
            return this;
        }
        Params minNodeRatioPerGroup(double minRatio) {
            this.minNodeRatioPerGroup = minRatio;
            return this;
        }
        Params idealDistributionBits(int distributionBits) {
            this.idealDistributionBits = distributionBits;
            return this;
        }
        Params highestObservedDistributionBitCount(int bitCount) {
            this.highestObservedDistributionBitCount = bitCount;
            return this;
        }
        Params lowestObservedDistributionBitCount(int bitCount) {
            this.lowestObservedDistributionBitCount = bitCount;
            return this;
        }

        /**
         * Infer parameters from controller options. Important: does _not_ set cluster;
         * it must be explicitly set afterwards on the returned parameter object before
         * being used to compute states.
         */
        static Params fromOptions(FleetControllerOptions opts) {
            return new Params()
                    .maxPrematureCrashes(opts.maxPrematureCrashes)
                    .minStorageNodesUp(opts.minStorageNodesUp)
                    .minDistributorNodesUp(opts.minDistributorNodesUp)
                    .minRatioOfStorageNodesUp(opts.minRatioOfStorageNodesUp)
                    .minRatioOfDistributorNodesUp(opts.minRatioOfDistributorNodesUp)
                    .minNodeRatioPerGroup(opts.minNodeRatioPerGroup)
                    .idealDistributionBits(opts.distributionBits)
                    .transitionTimes(opts.maxTransitionTime);
        }
    }

    static AnnotatedClusterState generatedStateFrom(final Params params) {
        final ContentCluster cluster = params.cluster;
        final ClusterState workingState = ClusterStateUtil.emptyState();
        final Map<Node, NodeStateReason> nodeStateReasons = new HashMap<>();

        for (final NodeInfo nodeInfo : cluster.getNodeInfo()) {
            final NodeState nodeState = computeEffectiveNodeState(nodeInfo, params);
            workingState.setNodeState(nodeInfo.getNode(), nodeState);
        }

        takeDownGroupsWithTooLowAvailability(workingState, nodeStateReasons, params);

        final Optional<ClusterStateReason> reasonToBeDown = clusterDownReason(workingState, params);
        if (reasonToBeDown.isPresent()) {
            workingState.setClusterState(State.DOWN);
        }
        workingState.setDistributionBits(inferDistributionBitCount(cluster, workingState, params));

        return new AnnotatedClusterState(workingState, reasonToBeDown, nodeStateReasons);
    }

    private static boolean nodeIsConsideredTooUnstable(final NodeInfo nodeInfo, final Params params) {
        return (params.maxPrematureCrashes != 0
                && nodeInfo.getPrematureCrashCount() > params.maxPrematureCrashes);
    }

    private static void applyWantedStateToBaselineState(final NodeState baseline, final NodeState wanted) {
        // Only copy state and description from Wanted state; this preserves auxiliary
        // information such as disk states and startup timestamp.
        baseline.setState(wanted.getState());
        baseline.setDescription(wanted.getDescription());
    }

    private static NodeState computeEffectiveNodeState(final NodeInfo nodeInfo, final Params params) {
        final NodeState reported = nodeInfo.getReportedState();
        final NodeState wanted   = nodeInfo.getWantedState();
        final NodeState baseline = reported.clone();

        // TODO move shouldForceInitToDown into applyStorage...() ?
        if (nodeIsConsideredTooUnstable(nodeInfo, params) || shouldForceInitToDown(nodeInfo, reported)) {
            baseline.setState(State.DOWN);
        }
        if (startupTimestampAlreadyObservedByAllNodes(nodeInfo, baseline)) {
            baseline.setStartTimestamp(0);
        }
        if (nodeInfo.isStorage()) {
            applyStorageSpecificStateTransforms(nodeInfo, params, reported, wanted, baseline);
        }
        if (reported.above(wanted)) { // FIXME baseline vs reported here (maintenance <-- above --> down is relevant)
            applyWantedStateToBaselineState(baseline, wanted);
        }

        return baseline;
    }

    private static void applyStorageSpecificStateTransforms(NodeInfo nodeInfo, Params params, NodeState reported,
                                                            NodeState wanted, NodeState baseline)
    {
        if (reported.getState() == State.INITIALIZING) {
            if (timedOutWithoutNewInitProgress(reported, nodeInfo, params)
                    || nodeInfo.recentlyObservedUnstableDuringInit())
            {
                baseline.setState(State.DOWN);
            }
            if (shouldForceInitToMaintenance(reported, wanted)) {
                baseline.setState(State.MAINTENANCE);
            }
        }
        // TODO ensure that maintenance cannot override Down for any other cases
        if (withinTemporalMaintenancePeriod(nodeInfo, baseline, params) && wanted.getState() != State.DOWN) {
            baseline.setState(State.MAINTENANCE);
        }
    }

    // TODO remove notion of init timeout progress? Seems redundant when we've already got RPC timeouts
    private static boolean timedOutWithoutNewInitProgress(final NodeState reported, final NodeInfo nodeInfo, final Params params) {
        if (reported.getState() != State.INITIALIZING) {
            return false;
        }
        return nodeInfo.getInitProgressTime() + params.maxInitProgressTime <= params.currentTimeInMillis;
    }

    private static boolean initializingNodeIsListingBuckets(final NodeState reported) {
        return reported.getInitProgress() <= NodeState.getListingBucketsInitProgressLimit() + 0.00001;
    }

    // Init while listing buckets should be treated as Down, as distributors expect a storage node
    // in Init mode to have a bucket set readily available. Clients also expect a node in Init to
    // be able to receive operations.
    private static boolean shouldForceInitToDown(final NodeInfo nodeInfo, final NodeState reported) {
        if (nodeInfo.isDistributor()) {
            return false;
        }
        return reported.getState() == State.INITIALIZING && initializingNodeIsListingBuckets(reported);
    }

    // Special case: since each node is published with a single state, if we let a Retired node
    // be published with Initializing, it'd start receiving feed and merges. Avoid this by
    // having it be in maintenance instead for the duration of the init period.
    private static boolean shouldForceInitToMaintenance(final NodeState reported, final NodeState wanted) {
        return reported.getState() == State.INITIALIZING && wanted.getState() == State.RETIRED;
    }

    private static boolean startupTimestampAlreadyObservedByAllNodes(final NodeInfo nodeInfo, final NodeState baseline) {
        return baseline.getStartTimestamp() == nodeInfo.getStartTimestamp(); // TODO rename NodeInfo getter/setter
    }

    /**
     * Determines whether a given storage node should be implicitly set as being
     * in a maintenance state despite its reported state being Down. This is
     * predominantly a case when contact has just been lost with a node, but we
     * do not want to immediately set it to Down just yet (where "yet" is a configurable
     * amount of time; see params.transitionTime). This is to prevent common node
     * restart/upgrade scenarios from triggering redistribution and data replication
     * that would be useless work if the node comes back up immediately afterwards.
     *
     * Only makes sense to call for storage nodes, since distributors don't support
     * being in maintenance mode.
     */
    private static boolean withinTemporalMaintenancePeriod(final NodeInfo nodeInfo,
                                                           final NodeState baseline,
                                                           final Params params)
    {
        final Integer transitionTime = params.transitionTimes.get(nodeInfo.getNode().getType());
        if (transitionTime == 0 || !baseline.getState().oneOf("sd")) {
            return false;
        }
        return nodeInfo.getTransitionTime() + transitionTime > params.currentTimeInMillis;
    }

    private static void takeDownGroupsWithTooLowAvailability(final ClusterState workingState,
                                                             Map<Node, NodeStateReason> nodeStateReasons,
                                                             final Params params)
    {
        final GroupAvailabilityCalculator calc = new GroupAvailabilityCalculator.Builder()
                .withMinNodeRatioPerGroup(params.minNodeRatioPerGroup)
                .withDistribution(params.cluster.getDistribution())
                .build();
        final Set<Integer> nodesToTakeDown = calc.nodesThatShouldBeDown(workingState);

        for (Integer idx : nodesToTakeDown) {
            final Node node = storageNode(idx);
            final NodeState newState = new NodeState(NodeType.STORAGE, State.DOWN);
            newState.setDescription("group node availability below configured threshold");
            workingState.setNodeState(node, newState);
            nodeStateReasons.put(node, NodeStateReason.GROUP_IS_DOWN);
        }
    }

    private static Node storageNode(int index) {
        return new Node(NodeType.STORAGE, index);
    }

    // TODO we'll want to explicitly persist a bit upper bound in ZooKeeper and ensure we
    // never go below it (this is _not_ the case today). Nodes that have min bits lower than
    // this will just have to start splitting out in the background before being allowed
    // to join the cluster.

    private static int inferDistributionBitCount(final ContentCluster cluster,
                                                 final ClusterState state,
                                                 final Params params)
    {
        int bitCount = params.idealDistributionBits;
        final Optional<Integer> minBits = cluster.getConfiguredNodes().values().stream()
                .map(configuredNode -> cluster.getNodeInfo(storageNode(configuredNode.index())))
                .filter(node -> state.getNodeState(node.getNode()).getState().oneOf("iur"))
                .map(nodeInfo -> nodeInfo.getReportedState().getMinUsedBits())
                .min(Integer::compare);

        if (minBits.isPresent() && minBits.get() < bitCount) {
            bitCount = minBits.get();
        }
        if (bitCount > params.lowestObservedDistributionBitCount && bitCount < params.idealDistributionBits) {
            bitCount = params.lowestObservedDistributionBitCount;
        }

        return bitCount;
    }

    private static boolean nodeStateIsConsideredAvailable(final NodeState ns) {
        return (ns.getState() == State.UP
                || ns.getState() == State.RETIRED
                || ns.getState() == State.INITIALIZING);
    }

    private static long countAvailableNodesOfType(final NodeType type,
                                                  final ContentCluster cluster,
                                                  final ClusterState state)
    {
        return cluster.getConfiguredNodes().values().stream()
                .map(node -> state.getNodeState(new Node(type, node.index())))
                .filter(ClusterStateGenerator::nodeStateIsConsideredAvailable)
                .count();
    }

    private static Optional<ClusterStateReason> clusterDownReason(final ClusterState state, final Params params) {
        final ContentCluster cluster = params.cluster;

        final long upStorageCount = countAvailableNodesOfType(NodeType.STORAGE, cluster, state);
        final long upDistributorCount = countAvailableNodesOfType(NodeType.DISTRIBUTOR, cluster, state);
        // There's a 1-1 relationship between distributors and storage nodes, so don't need to
        // keep track of separate node counts for computing availability ratios.
        final long nodeCount = cluster.getConfiguredNodes().size();

        if (upStorageCount < params.minStorageNodesUp) {
            return Optional.of(ClusterStateReason.TOO_FEW_STORAGE_NODES_AVAILABLE);
        }
        if (upDistributorCount < params.minDistributorNodesUp) {
            return Optional.of(ClusterStateReason.TOO_FEW_DISTRIBUTOR_NODES_AVAILABLE);
        }
        if (params.minRatioOfStorageNodesUp * nodeCount > upStorageCount) {
            return Optional.of(ClusterStateReason.TOO_LOW_AVAILABLE_STORAGE_NODE_RATIO);
        }
        if (params.minRatioOfDistributorNodesUp * nodeCount > upDistributorCount) {
            return Optional.of(ClusterStateReason.TOO_LOW_AVAILABLE_DISTRIBUTOR_NODE_RATIO);
        }
        return Optional.empty();
    }

    /**
     * TODO must take the following into account:
     *  - DONE - cluster bootstrap with all nodes down
     *  - DONE - node (distr+storage) reported states
     *  - DONE - node (distr+storage) "worse" wanted states
     *  - DONE - wanted state (distr+storage) cannot make generated state "better"
     *  - DONE - storage maintenance wanted state overrides all other states
     *  - DONE - wanted state description carries over to generated state
     *  - DONE - reported disk state not overridden by wanted state
     *  - DONE - implicit wanted state retired through config is applied
     *  - DONE - retired node in init is set to maintenance to inhibit load+merges
     *  - DONE - max transition time (reported down -> implicit generated maintenance -> generated down)
     *  - DONE - no maintenance transition for distributor nodes
     *  - DONE - max premature crashes (reported up/down cycle -> generated down)
     *  - DONE - node startup timestamp inclusion if not all distributors have observed timestamps
     *  - DONE - min node count (distributor, storage) in state up for cluster to be up
     *  - DONE - min node ratio (distributor, storage) in state up for cluster to be up
     *  - DONE - implicit group node availability (only down-edge has to be considered, huzzah!)
     *  - DONE - distribution bits inferred from storage nodes
     *  - DONE - distribution bits inferred from nodes in states IUR only
     *  - DONE - distribution bits inferred from config
     *  - DONE - generation of accurate edge events for state deltas <- this is a sneaky one
     *  - max init progress time (reported init -> generated down)
     *  - slobrok disconnect grace period (reported down -> generated down)
     *  - reported init progress (current code implies this causes new state versions; why should it do that?)
     *  - DONE - reverse init progress(?) <-- implicit; handled in StateChangeHandler
     *  - DONE - mark "listing buckets" stage as down, since node can't receive load yet at that point
     *  - DONE interaction with ClusterStateView
     *
     * Features such as minimum time before/between cluster state broadcasts are handled outside
     * the state generator.
     * Cluster state version management is also handled outside the generator.
     *
     * TODO move generator logic into own sub-package?
     */
}
