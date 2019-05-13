// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.NodeFlavors;
import com.yahoo.config.provision.NodeType;
import com.yahoo.vespa.hosted.provision.LockedNodeList;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.node.IP;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author smorgrav
 */
public class DockerHostCapacityTest {

    private DockerHostCapacity capacity;
    private List<Node> nodes;
    private Node host1, host2, host3;
    private Flavor flavorDocker, flavorDocker2;

    @Before
    public void setup() {
        // Create flavors
        NodeFlavors nodeFlavors = FlavorConfigBuilder.createDummies("host", "docker", "docker2");
        flavorDocker = nodeFlavors.getFlavorOrThrow("docker");
        flavorDocker2 = nodeFlavors.getFlavorOrThrow("docker2");

        // Create three docker hosts
        host1 = Node.create("host1", new IP.Config(Set.of("::1"), generateIPs(2, 4)), "host1", Optional.empty(), Optional.empty(), nodeFlavors.getFlavorOrThrow("host"), NodeType.host);
        host2 = Node.create("host2", new IP.Config(Set.of("::11"), generateIPs(12, 3)), "host2", Optional.empty(), Optional.empty(), nodeFlavors.getFlavorOrThrow("host"), NodeType.host);
        host3 = Node.create("host3", new IP.Config(Set.of("::21"), generateIPs(22, 1)), "host3", Optional.empty(), Optional.empty(), nodeFlavors.getFlavorOrThrow("host"), NodeType.host);

        // Add two containers to host1
        var nodeA = Node.create("nodeA", new IP.Config(Set.of("::2"), Set.of()), "nodeA", Optional.of("host1"), Optional.empty(), flavorDocker, NodeType.tenant);
        var nodeB = Node.create("nodeB", new IP.Config(Set.of("::3"), Set.of()), "nodeB", Optional.of("host1"), Optional.empty(), flavorDocker, NodeType.tenant);

        // Add two containers to host 2 (same as host 1)
        var nodeC = Node.create("nodeC", new IP.Config(Set.of("::12"), Set.of()), "nodeC", Optional.of("host2"), Optional.empty(), flavorDocker, NodeType.tenant);
        var nodeD = Node.create("nodeD", new IP.Config(Set.of("::13"), Set.of()), "nodeD", Optional.of("host2"), Optional.empty(), flavorDocker, NodeType.tenant);

        // Add a larger container to host3
        var nodeE = Node.create("nodeE", new IP.Config(Set.of("::22"), Set.of()), "nodeE", Optional.of("host3"), Optional.empty(), flavorDocker2, NodeType.tenant);

        // init docker host capacity
        nodes = new ArrayList<>();
        Collections.addAll(nodes, host1, host2, host3, nodeA, nodeB, nodeC, nodeD, nodeE);
        capacity = new DockerHostCapacity(new LockedNodeList(nodes, () -> {}));
    }

    @Test
    public void compare_used_to_sort_in_decending_order() {
        assertEquals(host1, nodes.get(0)); //Make sure it is unsorted here

        Collections.sort(nodes, capacity::compare);
        assertEquals(host3, nodes.get(0));
        assertEquals(host1, nodes.get(1));
        assertEquals(host2, nodes.get(2));
    }

    @Test
    public void hasCapacity() {
        assertTrue(capacity.hasCapacity(host1, ResourceCapacity.of(flavorDocker)));
        assertTrue(capacity.hasCapacity(host1, ResourceCapacity.of(flavorDocker2)));
        assertTrue(capacity.hasCapacity(host2, ResourceCapacity.of(flavorDocker)));
        assertTrue(capacity.hasCapacity(host2, ResourceCapacity.of(flavorDocker2)));
        assertFalse(capacity.hasCapacity(host3, ResourceCapacity.of(flavorDocker)));  // No ip available
        assertFalse(capacity.hasCapacity(host3, ResourceCapacity.of(flavorDocker2))); // No ip available

        // Add a new node to host1 to deplete the memory resource
        Node nodeF = Node.create("nodeF", new IP.Config(Set.of("::6"), Set.of()),
                "nodeF", Optional.of("host1"), Optional.empty(), flavorDocker, NodeType.tenant);
        nodes.add(nodeF);
        capacity = new DockerHostCapacity(new LockedNodeList(nodes, () -> {}));
        assertFalse(capacity.hasCapacity(host1, ResourceCapacity.of(flavorDocker)));
        assertFalse(capacity.hasCapacity(host1, ResourceCapacity.of(flavorDocker2)));
    }

    @Test
    public void freeIPs() {
        assertEquals(2, capacity.freeIPs(host1));
        assertEquals(1, capacity.freeIPs(host2));
        assertEquals(0, capacity.freeIPs(host3));
    }

    @Test
    public void getCapacityTotal() {
        ResourceCapacity total = capacity.getCapacityTotal();
        assertEquals(21.0, total.vcpu(), 0.1);
        assertEquals(30.0, total.memoryGb(), 0.1);
        assertEquals(36.0, total.diskGb(), 0.1);
    }

    @Test
    public void getFreeCapacityTotal() {
        ResourceCapacity totalFree = capacity.getFreeCapacityTotal();
        assertEquals(15.0, totalFree.vcpu(), 0.1);
        assertEquals(14.0, totalFree.memoryGb(), 0.1);
        assertEquals(24.0, totalFree.diskGb(), 0.1);
    }

    @Test
    public void freeCapacityInFlavorEquivalence() {
        assertEquals(2, capacity.freeCapacityInFlavorEquivalence(flavorDocker));
        assertEquals(2, capacity.freeCapacityInFlavorEquivalence(flavorDocker2));
    }

    @Test
    public void getNofHostsAvailableFor() {
        assertEquals(2, capacity.getNofHostsAvailableFor(flavorDocker));
        assertEquals(2, capacity.getNofHostsAvailableFor(flavorDocker2));
    }

    private Set<String> generateIPs(int start, int count) {
        // Allow 4 containers
        Set<String> ipAddressPool = new LinkedHashSet<>();
        for (int i = start; i < (start + count); i++) {
            ipAddressPool.add("::" + i);
        }
        return ipAddressPool;
    }

}
