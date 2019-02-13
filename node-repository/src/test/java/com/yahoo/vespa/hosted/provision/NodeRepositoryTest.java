// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision;

import com.yahoo.config.provision.NodeType;
import com.yahoo.vespa.hosted.provision.node.Agent;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * tests basic operation of the node repository
 * 
 * @author bratseth
 */
public class NodeRepositoryTest {

    @Test
    public void nodeRepositoryTest() {
        NodeRepositoryTester tester = new NodeRepositoryTester();
        assertEquals(0, tester.nodeRepository().getNodes().size());

        tester.addNode("id1", "host1", "default", NodeType.tenant);
        tester.addNode("id2", "host2", "default", NodeType.tenant);
        tester.addNode("id3", "host3", "default", NodeType.tenant);

        assertEquals(3, tester.nodeRepository().getNodes().size());
        
        tester.nodeRepository().park("host2", true, Agent.system, "Parking to unit test");
        tester.nodeRepository().removeRecursively("host2");

        assertEquals(2, tester.nodeRepository().getNodes().size());
    }

    @Test
    public void only_allow_docker_containers_remove_in_ready() {
        NodeRepositoryTester tester = new NodeRepositoryTester();
        tester.addNode("id1", "host1", "docker", NodeType.tenant);

        try {
            tester.nodeRepository().removeRecursively("host1"); // host1 is in state provisioned
            fail("Should not be able to delete docker container node by itself in state provisioned");
        } catch (IllegalArgumentException ignored) {
            // Expected
        }

        tester.nodeRepository().setReady("host1", Agent.system, getClass().getSimpleName());
        tester.nodeRepository().removeRecursively("host1");
    }

    @Test
    public void only_remove_tenant_docker_containers_for_new_allocations() {
        NodeRepositoryTester tester = new NodeRepositoryTester();
        tester.addNode("host1", "host1", "default", NodeType.tenant);
        tester.addNode("host2", "host2", "docker", NodeType.tenant);
        tester.addNode("cfg1", "cfg1", "docker", NodeType.config);

        tester.setNodeState("host1", Node.State.dirty);
        tester.setNodeState("host2", Node.State.dirty);
        tester.setNodeState("cfg1", Node.State.dirty);

        tester.nodeRepository().markNodeAvailableForNewAllocation("host1", Agent.system, getClass().getSimpleName());
        assertEquals(Node.State.ready, tester.nodeRepository().getNode("host1").get().state());

        tester.nodeRepository().markNodeAvailableForNewAllocation("host2", Agent.system, getClass().getSimpleName());
        assertFalse(tester.nodeRepository().getNode("host2").isPresent());

        tester.nodeRepository().markNodeAvailableForNewAllocation("cfg1", Agent.system, getClass().getSimpleName());
        assertEquals(Node.State.ready, tester.nodeRepository().getNode("cfg1").get().state());
    }

    @Test
    public void delete_host_only_after_all_the_children_have_been_deleted() {
        NodeRepositoryTester tester = new NodeRepositoryTester();

        tester.addNode("id1", "host1", "default", NodeType.host);
        tester.addNode("id2", "host2", "default", NodeType.host);
        tester.addNode("node10", "node10", "host1", "docker", NodeType.tenant);
        tester.addNode("node11", "node11", "host1", "docker", NodeType.tenant);
        tester.addNode("node12", "node12", "host1", "docker", NodeType.tenant);
        tester.addNode("node20", "node20", "host2", "docker", NodeType.tenant);
        assertEquals(6, tester.nodeRepository().getNodes().size());

        tester.setNodeState("node11", Node.State.dirty);

        try {
            tester.nodeRepository().removeRecursively("host1");
            fail("Should not be able to delete host node, one of the children is in state dirty");
        } catch (IllegalArgumentException ignored) {
            // Expected
        }
        assertEquals(6, tester.nodeRepository().getNodes().size());

        // Should be OK to delete host2 as both host2 and its only child, node20, are in state provisioned
        tester.nodeRepository().removeRecursively("host2");
        assertEquals(4, tester.nodeRepository().getNodes().size());

        // Now node10 and node12 are in provisioned, set node11 to ready, and it should be OK to delete host1
        tester.nodeRepository().setReady("node11", Agent.system, getClass().getSimpleName());
        tester.nodeRepository().removeRecursively("node11"); // Remove one of the children first instead
        assertEquals(3, tester.nodeRepository().getNodes().size());

        tester.nodeRepository().removeRecursively("host1");
        assertEquals(0, tester.nodeRepository().getNodes().size());
    }

    @Test
    public void dirty_host_only_if_we_can_dirty_children() {
        NodeRepositoryTester tester = new NodeRepositoryTester();

        tester.addNode("id1", "host1", "default", NodeType.host);
        tester.addNode("id2", "host2", "default", NodeType.host);
        tester.addNode("node10", "node10", "host1", "docker", NodeType.tenant);
        tester.addNode("node11", "node11", "host1", "docker", NodeType.tenant);
        tester.addNode("node12", "node12", "host1", "docker", NodeType.tenant);
        tester.addNode("node20", "node20", "host2", "docker", NodeType.tenant);

        tester.setNodeState("node11", Node.State.ready);
        tester.setNodeState("node12", Node.State.active);
        tester.setNodeState("node20", Node.State.failed);

        assertEquals(6, tester.nodeRepository().getNodes().size());

        // Should be OK to dirty host2 as it is in provisioned and its only child is in failed
        tester.nodeRepository().dirtyRecursively("host2", Agent.system, NodeRepositoryTest.class.getSimpleName());
        assertEquals(asSet("host2", "node20"), filterNodes(tester, node -> node.state() == Node.State.dirty));

        // Cant dirty host1, node11 is ready and node12 is active
        try {
            tester.nodeRepository().dirtyRecursively("host1", Agent.system, NodeRepositoryTest.class.getSimpleName());
            fail("Should not be able to dirty host1");
        } catch (IllegalArgumentException ignored) { } // Expected;

        assertEquals(asSet("host2", "node20"), filterNodes(tester, node -> node.state() == Node.State.dirty));
    }

    private static Set<String> asSet(String... elements) {
        return new HashSet<>(Arrays.asList(elements));
    }

    private static Set<String> filterNodes(NodeRepositoryTester tester, Predicate<Node> filter) {
        return tester.nodeRepository()
                .getNodes().stream()
                .filter(filter)
                .map(Node::hostname)
                .collect(Collectors.toSet());
    }

}
