// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.NodeFlavors;
import com.yahoo.config.provision.OutOfCapacityException;
import com.yahoo.lang.MutableInteger;
import com.yahoo.transaction.Mutex;
import com.yahoo.vespa.flags.BooleanFlag;
import com.yahoo.vespa.flags.FetchVector;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.NodeRepository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Performs preparation of node activation changes for a single host group in an application.
 *
 * @author bratseth
 */
public class GroupPreparer {

    private final NodeRepository nodeRepository;
    private final Optional<HostProvisioner> hostProvisioner;
    private final BooleanFlag dynamicProvisioningEnabledFlag;

    public GroupPreparer(NodeRepository nodeRepository, Optional<HostProvisioner> hostProvisioner,
                         BooleanFlag dynamicProvisioningEnabledFlag) {
        this.nodeRepository = nodeRepository;
        this.hostProvisioner = hostProvisioner;
        this.dynamicProvisioningEnabledFlag = dynamicProvisioningEnabledFlag;
    }

    /**
     * Ensure sufficient nodes are reserved or active for the given application, group and cluster
     *
     * @param application        the application we are allocating to
     * @param cluster            the cluster and group we are allocating to
     * @param requestedNodes     a specification of the requested nodes
     * @param surplusActiveNodes currently active nodes which are available to be assigned to this group.
     *                           This method will remove from this list if it finds it needs additional nodes
     * @param highestIndex       the current highest node index among all active nodes in this cluster.
     *                           This method will increase this number when it allocates new nodes to the cluster.
     * @param spareCount         The number of spare docker hosts we want when dynamically allocate docker containers
     * @return the list of nodes this cluster group will have allocated if activated
     */
    // Note: This operation may make persisted changes to the set of reserved and inactive nodes,
    // but it may not change the set of active nodes, as the active nodes must stay in sync with the
    // active config model which is changed on activate
    public List<Node> prepare(ApplicationId application, ClusterSpec cluster, NodeSpec requestedNodes,
                              List<Node> surplusActiveNodes, MutableInteger highestIndex, int spareCount) {
        boolean dynamicProvisioningEnabled = hostProvisioner.isPresent() && dynamicProvisioningEnabledFlag
                .with(FetchVector.Dimension.APPLICATION_ID, application.serializedForm())
                .value();

        try (Mutex lock = nodeRepository.lock(application)) {

            // Lock ready pool to ensure that the same nodes are not simultaneously allocated by others
            try (Mutex allocationLock = nodeRepository.lockAllocation()) {

                // Create a prioritized set of nodes
                NodeList nodeList = nodeRepository.list();
                NodePrioritizer prioritizer = new NodePrioritizer(
                        nodeList, application, cluster, requestedNodes, spareCount, nodeRepository.nameResolver(),
                        nodeRepository.getAvailableFlavors());

                prioritizer.addApplicationNodes();
                prioritizer.addSurplusNodes(surplusActiveNodes);
                prioritizer.addReadyNodes();
                prioritizer.addNewDockerNodes(allocationLock, dynamicProvisioningEnabled);

                // Allocate from the prioritized list
                NodeAllocation allocation = new NodeAllocation(nodeList, application, cluster, requestedNodes,
                                                               highestIndex,  nodeRepository.getAvailableFlavors(),
                                                               nodeRepository.zone(), nodeRepository.clock());
                allocation.offer(prioritizer.prioritize());

                if (dynamicProvisioningEnabled) {
                    List<ProvisionedHost> provisionedHosts = allocation.getFulfilledDockerDeficit()
                            .map(deficit -> hostProvisioner.get().provisionHosts(nodeRepository.database().getProvisionIndexes(deficit.getCount()),
                                                                                 deficit.getFlavor()))
                            .orElseGet(List::of);

                    // At this point we have started provisioning of the hosts, the first priority is to make sure that
                    // the returned hosts are added to the node-repo so that they are tracked by the provision maintainers
                    List<Node> hosts = provisionedHosts.stream()
                            .map(ProvisionedHost::generateHost)
                            .collect(Collectors.toList());
                    nodeRepository.addNodes(hosts);

                    // Offer the nodes on the newly provisioned hosts, this should be enough to cover the deficit
                    List<PrioritizableNode> nodes = provisionedHosts.stream()
                            .map(provisionedHost -> new PrioritizableNode.Builder(provisionedHost.generateNode())
                                    .withParent(provisionedHost.generateHost())
                                    .withNewNode(true)
                                    .build())
                            .collect(Collectors.toList());
                    allocation.offer(nodes);
                }

                if (! allocation.fulfilled() && requestedNodes.canFail())
                    throw new OutOfCapacityException("Could not satisfy " + requestedNodes + " for " + cluster +
                                                     " in " + application.toShortString() +
                                                     outOfCapacityDetails(allocation));

                // Extend reservation for already reserved nodes
                nodeRepository.reserve(nodeRepository.getNodes(application, Node.State.reserved));

                // Carry out and return allocation
                nodeRepository.reserve(allocation.reservableNodes());
                nodeRepository.addDockerNodes(allocation.newNodes(), allocationLock);
                surplusActiveNodes.removeAll(allocation.surplusNodes());
                return allocation.finalNodes(surplusActiveNodes);
            }
        }
    }

    private static String outOfCapacityDetails(NodeAllocation allocation) {
        if (allocation.wouldBeFulfilledWithoutExclusivity())
            return ": Not enough nodes available due to host exclusivity constraints.";
        else if (allocation.wouldBeFulfilledWithClashingParentHost())
            return ": Not enough nodes available on separate physical hosts.";
        else if (allocation.wouldBeFulfilledWithRetiredNodes())
            return ": Not enough nodes available due to retirement.";
        else
            return ".";
    }
}
