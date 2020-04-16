// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Deployer;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import com.yahoo.jdisc.Metric;
import com.yahoo.transaction.Mutex;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.node.Agent;
import com.yahoo.vespa.hosted.provision.provisioning.DockerHostCapacity;
import com.yahoo.vespa.hosted.provision.provisioning.HostProvisioner;
import com.yahoo.vespa.hosted.provision.provisioning.HostResourcesCalculator;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * @author bratseth
 */
public class Rebalancer extends Maintainer {

    private final Deployer deployer;
    private final HostResourcesCalculator hostResourcesCalculator;
    private final Optional<HostProvisioner> hostProvisioner;
    private final Metric metric;
    private final Clock clock;

    public Rebalancer(Deployer deployer,
                      NodeRepository nodeRepository,
                      HostResourcesCalculator hostResourcesCalculator,
                      Optional<HostProvisioner> hostProvisioner,
                      Metric metric,
                      Clock clock,
                      Duration interval) {
        super(nodeRepository, interval);
        this.deployer = deployer;
        this.hostResourcesCalculator = hostResourcesCalculator;
        this.hostProvisioner = hostProvisioner;
        this.metric = metric;
        this.clock = clock;
    }

    @Override
    protected void maintain() {
        if (hostProvisioner.isPresent()) return; // All nodes will be allocated on new hosts, so rebalancing makes no sense
        if (nodeRepository().zone().environment().isTest()) return; // Test zones have short lived deployments, no need to rebalance

        // Work with an unlocked snapshot as this can take a long time and full consistency is not needed
        NodeList allNodes = nodeRepository().list();

        updateSkewMetric(allNodes);

        if ( ! zoneIsStable(allNodes)) return;

        Move bestMove = findBestMove(allNodes);
        if (bestMove == Move.none) return;
        deployTo(bestMove);
   }

    /** We do this here rather than in MetricsReporter because it is expensive and frequent updates are unnecessary */
    private void updateSkewMetric(NodeList allNodes) {
        DockerHostCapacity capacity = new DockerHostCapacity(allNodes, hostResourcesCalculator);
        double totalSkew = 0;
        int hostCount = 0;
        for (Node host : allNodes.nodeType((NodeType.host)).state(Node.State.active)) {
            hostCount++;
            totalSkew += Node.skew(host.flavor().resources(), capacity.freeCapacityOf(host));
        }
        metric.set("hostedVespa.docker.skew", totalSkew/hostCount, null);
    }

    private boolean zoneIsStable(NodeList allNodes) {
        NodeList active = allNodes.state(Node.State.active);
        if (active.stream().anyMatch(node -> node.allocation().get().membership().retired())) return false;
        if (active.stream().anyMatch(node -> node.status().wantToRetire())) return false;
        return true;
    }

    /**
     * Find the best move to reduce allocation skew and returns it.
     * Returns Move.none if no moves can be made to reduce skew.
     */
    private Move findBestMove(NodeList allNodes) {
        DockerHostCapacity capacity = new DockerHostCapacity(allNodes, hostResourcesCalculator);
        Move bestMove = Move.none;
        for (Node node : allNodes.nodeType(NodeType.tenant).state(Node.State.active)) {
            if (node.parentHostname().isEmpty()) continue;
            if (node.allocation().get().owner().instance().isTester()) continue;
            if (node.allocation().get().owner().application().value().equals("lsbe-dictionaries")) continue; // TODO: Remove
            for (Node toHost : allNodes.filter(nodeRepository()::canAllocateTenantNodeTo)) {
                if (toHost.hostname().equals(node.parentHostname().get())) continue;
                if ( ! capacity.freeCapacityOf(toHost).satisfies(node.flavor().resources())) continue;

                double skewReductionAtFromHost = skewReductionByRemoving(node, allNodes.parentOf(node).get(), capacity);
                double skewReductionAtToHost = skewReductionByAdding(node, toHost, capacity);
                double netSkewReduction = skewReductionAtFromHost + skewReductionAtToHost;
                if (netSkewReduction > bestMove.netSkewReduction)
                    bestMove = new Move(node, toHost, netSkewReduction);
            }
        }
        return bestMove;
    }

    /** Returns true only if this operation changes the state of the wantToRetire flag */
    private boolean markWantToRetire(Node node, boolean wantToRetire) {
        try (Mutex lock = nodeRepository().lock(node)) {
            Optional<Node> nodeToMove = nodeRepository().getNode(node.hostname());
            if (nodeToMove.isEmpty()) return false;
            if (nodeToMove.get().state() != Node.State.active) return false;

            if (nodeToMove.get().status().wantToRetire() == wantToRetire) return false;

            nodeRepository().write(nodeToMove.get().withWantToRetire(wantToRetire, Agent.Rebalancer, clock.instant()), lock);
            return true;
        }
    }

    /**
     * Try a redeployment to effect the chosen move.
     * If it can be done, that's ok; we'll try this or another move later.
     *
     * @return true if the move was done, false if it couldn't be
     */
    private boolean deployTo(Move move) {
        ApplicationId application = move.node.allocation().get().owner();
        try (MaintenanceDeployment deployment = new MaintenanceDeployment(application, deployer, metric, nodeRepository())) {
            if ( ! deployment.isValid()) return false;

            boolean couldMarkRetiredNow = markWantToRetire(move.node, true);
            if ( ! couldMarkRetiredNow) return false;

            Optional<Node> expectedNewNode = Optional.empty();
            try {
                if ( ! deployment.prepare()) return false;
                expectedNewNode =
                        nodeRepository().getNodes(application, Node.State.reserved).stream()
                                        .filter(node -> !node.hostname().equals(move.node.hostname()))
                                        .filter(node -> node.allocation().get().membership().cluster().id().equals(move.node.allocation().get().membership().cluster().id()))
                                        .findAny();
                if (expectedNewNode.isEmpty()) return false;
                if ( ! expectedNewNode.get().hasParent(move.toHost.hostname())) return false;
                if ( ! deployment.activate()) return false;

                log.info("Rebalancer redeployed " + application + " to " + move);
                return true;
            }
            finally {
                markWantToRetire(move.node, false); // Necessary if this failed, no-op otherwise

                // Immediately clean up if we reserved the node but could not activate or reserved a node on the wrong host
                expectedNewNode.flatMap(node -> nodeRepository().getNode(node.hostname(), Node.State.reserved))
                               .ifPresent(node -> nodeRepository().setDirty(node, Agent.Rebalancer, "Expired by Rebalancer"));
            }
        }
    }

    private double skewReductionByRemoving(Node node, Node fromHost, DockerHostCapacity capacity) {
        NodeResources freeHostCapacity = capacity.freeCapacityOf(fromHost);
        double skewBefore = Node.skew(fromHost.flavor().resources(), freeHostCapacity);
        double skewAfter = Node.skew(fromHost.flavor().resources(), freeHostCapacity.add(node.flavor().resources().justNumbers()));
        return skewBefore - skewAfter;
    }

    private double skewReductionByAdding(Node node, Node toHost, DockerHostCapacity capacity) {
        NodeResources freeHostCapacity = capacity.freeCapacityOf(toHost);
        double skewBefore = Node.skew(toHost.flavor().resources(), freeHostCapacity);
        double skewAfter = Node.skew(toHost.flavor().resources(), freeHostCapacity.subtract(node.flavor().resources().justNumbers()));
        return skewBefore - skewAfter;
    }

    private static class Move {

        static final Move none = new Move(null, null, 0);

        final Node node;
        final Node toHost;
        final double netSkewReduction;

        Move(Node node, Node toHost, double netSkewReduction) {
            this.node = node;
            this.toHost = toHost;
            this.netSkewReduction = netSkewReduction;
        }

        @Override
        public String toString() {
            return "move " +
                   ( node == null ? "none" :
                                    (node.hostname() + " to " + toHost + " [skew reduction "  + netSkewReduction + "]"));
        }

    }

}
