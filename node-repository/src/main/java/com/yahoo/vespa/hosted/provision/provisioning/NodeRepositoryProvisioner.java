// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.google.inject.Inject;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.HostFilter;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.NodeFlavors;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.ProvisionLogger;
import com.yahoo.config.provision.Provisioner;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.Zone;
import com.yahoo.log.LogLevel;
import com.yahoo.transaction.NestedTransaction;
import com.yahoo.vespa.flags.FlagSource;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.node.Allocation;
import com.yahoo.vespa.hosted.provision.node.filter.ApplicationFilter;
import com.yahoo.vespa.hosted.provision.node.filter.NodeHostFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of the host provisioner API for hosted Vespa, using the node repository to allocate nodes.
 * Does not allocate hosts for the routing application, see VespaModelFactory.createHostProvisioner
 *
 * @author bratseth
 */
public class NodeRepositoryProvisioner implements Provisioner {

    private static final Logger log = Logger.getLogger(NodeRepositoryProvisioner.class.getName());
    private static final int SPARE_CAPACITY_PROD = 0;
    private static final int SPARE_CAPACITY_NONPROD = 0;

    private final NodeRepository nodeRepository;
    private final CapacityPolicies capacityPolicies;
    private final Zone zone;
    private final Preparer preparer;
    private final Activator activator;
    private final Optional<LoadBalancerProvisioner> loadBalancerProvisioner;

    int getSpareCapacityProd() {
        return SPARE_CAPACITY_PROD;
    }

    @Inject
    public NodeRepositoryProvisioner(NodeRepository nodeRepository, NodeFlavors flavors, Zone zone,
                                     ProvisionServiceProvider provisionServiceProvider, FlagSource flagSource) {
        this.nodeRepository = nodeRepository;
        this.capacityPolicies = new CapacityPolicies(zone, flavors);
        this.zone = zone;
        this.preparer = new Preparer(nodeRepository,
                zone.environment().equals(Environment.prod) ? SPARE_CAPACITY_PROD : SPARE_CAPACITY_NONPROD,
                provisionServiceProvider.getHostProvisioner(),
                Flags.ENABLE_DYNAMIC_PROVISIONING.bindTo(flagSource));
        this.activator = new Activator(nodeRepository);
        this.loadBalancerProvisioner = provisionServiceProvider.getLoadBalancerService().map(lbService ->
                new LoadBalancerProvisioner(nodeRepository, lbService));
    }

    /**
     * Returns a list of nodes in the prepared or active state, matching the given constraints.
     * The nodes are ordered by increasing index number.
     */
    @Override
    public List<HostSpec> prepare(ApplicationId application, ClusterSpec cluster, Capacity requestedCapacity, 
                                  int wantedGroups, ProvisionLogger logger) {
        if (cluster.group().isPresent()) throw new IllegalArgumentException("Node requests cannot specify a group");
        if (requestedCapacity.nodeCount() > 0 && requestedCapacity.nodeCount() % wantedGroups != 0)
            throw new IllegalArgumentException("Requested " + requestedCapacity.nodeCount() + " nodes in " + wantedGroups + " groups, " +
                                               "which doesn't allow the nodes to be divided evenly into groups");

        log.log(zone.system() == SystemName.cd ? Level.INFO : LogLevel.DEBUG,
                () -> "Received deploy prepare request for " + requestedCapacity + " in " +
                      wantedGroups + " groups for application " + application + ", cluster " + cluster);

        int effectiveGroups;
        NodeSpec requestedNodes;
        if ( requestedCapacity.type() == NodeType.tenant) {
            int nodeCount = application.instance().isTester() ? 1 : capacityPolicies.decideSize(requestedCapacity, cluster.type());

            if (zone.environment().isManuallyDeployed() && nodeCount < requestedCapacity.nodeCount())
                logger.log(Level.INFO, "Requested " + requestedCapacity.nodeCount() + " nodes for " + cluster +
                                       ", downscaling to " + nodeCount + " nodes in " + zone.environment());
            NodeResources flavor = capacityPolicies.decideFlavor(requestedCapacity, cluster);
            log.log(LogLevel.DEBUG, () -> "Decided flavor for requested tenant nodes: " + flavor);
            boolean exclusive = capacityPolicies.decideExclusivity(cluster.isExclusive());
            effectiveGroups = wantedGroups > nodeCount ? nodeCount : wantedGroups; // cannot have more groups than nodes
            requestedNodes = NodeSpec.from(nodeCount, flavor, exclusive, requestedCapacity.canFail());
        }
        else {
            requestedNodes = NodeSpec.from(requestedCapacity.type());
            effectiveGroups = 1; // type request with multiple groups is not supported
        }

        return asSortedHosts(preparer.prepare(application, cluster, requestedNodes, effectiveGroups));
    }

    @Override
    public void activate(NestedTransaction transaction, ApplicationId application, Collection<HostSpec> hosts) {
        validate(hosts);
        activator.activate(application, hosts, transaction);
        transaction.onCommitted(() -> {
            try {
                loadBalancerProvisioner.ifPresent(lbProvisioner -> lbProvisioner.provision(application));
            } catch (Exception e) {
                log.log(LogLevel.ERROR, "Failed to provision load balancer for application " +
                                        application.toShortString(), e);
            }
        });
    }

    @Override
    public void restart(ApplicationId application, HostFilter filter) {
        nodeRepository.restart(ApplicationFilter.from(application, NodeHostFilter.from(filter)));
    }

    @Override
    public void remove(NestedTransaction transaction, ApplicationId application) {
        nodeRepository.deactivate(application, transaction);
        loadBalancerProvisioner.ifPresent(lbProvisioner -> lbProvisioner.deactivate(application, transaction));
    }

    private List<HostSpec> asSortedHosts(List<Node> nodes) {
        nodes.sort(Comparator.comparingInt(node -> node.allocation().get().membership().index()));
        List<HostSpec> hosts = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            log.log(LogLevel.DEBUG, () -> "Prepared node " + node.hostname() + " - " + node.flavor());
            Allocation nodeAllocation = node.allocation().orElseThrow(IllegalStateException::new);
            hosts.add(new HostSpec(node.hostname(),
                                   Collections.emptyList(),
                                   Optional.of(node.flavor()),
                                   Optional.of(nodeAllocation.membership()),
                                   node.status().vespaVersion(),
                                   nodeAllocation.networkPorts()));
            if (nodeAllocation.networkPorts().isPresent()) {
                log.log(LogLevel.DEBUG, () -> "Prepared node " + node.hostname() + " has port allocations");
            }
        }
        return hosts;
    }

    private void validate(Collection<HostSpec> hosts) {
        for (HostSpec host : hosts) {
            if ( ! host.membership().isPresent())
                throw new IllegalArgumentException("Hosts must be assigned a cluster when activating, but got " + host);
            if ( ! host.membership().get().cluster().group().isPresent())
                throw new IllegalArgumentException("Hosts must be assigned a group when activating, but got " + host);
        }
    }

}
