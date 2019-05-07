// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.OutOfCapacityException;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.Zone;
import com.yahoo.vespa.hosted.provision.Node;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Tests provisioning of virtual nodes
 *
 * @author hmusum
 * @author mpolden
 */
// Note: Some of the tests here should be moved to DockerProvisioningTest if we stop using VMs and want
// to remove these tests
public class VirtualNodeProvisioningTest {

    private static final NodeResources flavor = new NodeResources(4, 8, 100);
    private static final ClusterSpec contentClusterSpec = ClusterSpec.request(ClusterSpec.Type.content, ClusterSpec.Id.from("myContent"), Version.fromString("6.42"), false, Collections.emptySet());
    private static final ClusterSpec containerClusterSpec = ClusterSpec.request(ClusterSpec.Type.container, ClusterSpec.Id.from("myContainer"), Version.fromString("6.42"), false, Collections.emptySet());

    private ProvisioningTester tester = new ProvisioningTester.Builder().build();
    private ApplicationId applicationId = tester.makeApplicationId();

    @Test
    public void distinct_parent_host_for_each_node_in_a_cluster() {
        tester.makeReadyVirtualDockerNodes(2, flavor, "parentHost1");
        tester.makeReadyVirtualDockerNodes(2, flavor, "parentHost2");
        tester.makeReadyVirtualDockerNodes(2, flavor, "parentHost3");
        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost4");

        final int containerNodeCount = 4;
        final int contentNodeCount = 3;
        final int groups = 1;
        List<HostSpec> containerHosts = prepare(containerClusterSpec, containerNodeCount, groups);
        List<HostSpec> contentHosts = prepare(contentClusterSpec, contentNodeCount, groups);
        activate(containerHosts, contentHosts);

        final List<Node> nodes = getNodes(applicationId);
        assertEquals(contentNodeCount + containerNodeCount, nodes.size());
        assertDistinctParentHosts(nodes, ClusterSpec.Type.container, containerNodeCount);
        assertDistinctParentHosts(nodes, ClusterSpec.Type.content, contentNodeCount);

        // Go down to 3 nodes in container cluster
        List<HostSpec> containerHosts2 = prepare(containerClusterSpec, containerNodeCount - 1, groups);
        activate(containerHosts2);
        List<Node> nodes2 = getNodes(applicationId);
        assertDistinctParentHosts(nodes2, ClusterSpec.Type.container, containerNodeCount - 1);

        // Go up to 4 nodes again in container cluster
        List<HostSpec> containerHosts3 = prepare(containerClusterSpec, containerNodeCount, groups);
        activate(containerHosts3);
        List<Node> nodes3 = getNodes(applicationId);
        assertDistinctParentHosts(nodes3, ClusterSpec.Type.container, containerNodeCount);
    }

    @Test
    public void allow_same_parent_host_for_nodes_in_a_cluster_in_cd_and_non_prod() {
        final int containerNodeCount = 2;
        final int contentNodeCount = 2;
        final int groups = 1;

        // Allowed to use same parent host for several nodes in same cluster in dev
        {
            NodeResources flavor = new NodeResources(1, 1, 1);
            tester = new ProvisioningTester.Builder().zone(new Zone(Environment.dev, RegionName.from("us-east"))).build();
            tester.makeReadyVirtualDockerNodes(4, flavor, "parentHost1");

            List<HostSpec> containerHosts = prepare(containerClusterSpec, containerNodeCount, groups, flavor);
            List<HostSpec> contentHosts = prepare(contentClusterSpec, contentNodeCount, groups, flavor);
            activate(containerHosts, contentHosts);

            // downscaled to 1 node per cluster in dev, so 2 in total
            assertEquals(2, getNodes(applicationId).size());
        }

        // Allowed to use same parent host for several nodes in same cluster in CD (even if prod env)
        {
            tester = new ProvisioningTester.Builder().zone(new Zone(SystemName.cd, Environment.prod, RegionName.from("us-east"))).build();
            tester.makeReadyVirtualDockerNodes(4, flavor, "parentHost1");

            List<HostSpec> containerHosts = prepare(containerClusterSpec, containerNodeCount, groups);
            List<HostSpec> contentHosts = prepare(contentClusterSpec, contentNodeCount, groups);
            activate(containerHosts, contentHosts);

            assertEquals(4, getNodes(applicationId).size());
        }
    }

    @Test
    public void will_retire_clashing_active() {
        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost1");
        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost2");
        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost3");
        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost4");
        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost5");
        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost6");

        int containerNodeCount = 2;
        int contentNodeCount = 2;
        int groups = 1;
        List<HostSpec> containerHosts = prepare(containerClusterSpec, containerNodeCount, groups);
        List<HostSpec> contentHosts = prepare(contentClusterSpec, contentNodeCount, groups);
        activate(containerHosts, contentHosts);

        List<Node> nodes = getNodes(applicationId);
        assertEquals(4, nodes.size());
        assertDistinctParentHosts(nodes, ClusterSpec.Type.container, containerNodeCount);
        assertDistinctParentHosts(nodes, ClusterSpec.Type.content, contentNodeCount);

        for (Node n : nodes) {
          tester.patchNode(n.withParentHostname("clashing"));
        }
        containerHosts = prepare(containerClusterSpec, containerNodeCount, groups);
        contentHosts = prepare(contentClusterSpec, contentNodeCount, groups);
        activate(containerHosts, contentHosts);

        nodes = getNodes(applicationId);
        assertEquals(6, nodes.size());
        assertEquals(2, nodes.stream().filter(n -> n.allocation().get().membership().retired()).count());
    }

    @Test
    public void fail_when_all_hosts_become_clashing() {
        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost1");
        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost2");
        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost3");
        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost4");

        int containerNodeCount = 2;
        int contentNodeCount = 2;
        int groups = 1;
        List<HostSpec> containerHosts = prepare(containerClusterSpec, containerNodeCount, groups);
        List<HostSpec> contentHosts = prepare(contentClusterSpec, contentNodeCount, groups);
        activate(containerHosts, contentHosts);

        List<Node> nodes = getNodes(applicationId);
        assertEquals(4, nodes.size());
        assertDistinctParentHosts(nodes, ClusterSpec.Type.container, containerNodeCount);
        assertDistinctParentHosts(nodes, ClusterSpec.Type.content, contentNodeCount);

        for (Node n : nodes) {
          tester.patchNode(n.withParentHostname("clashing"));
        }
        OutOfCapacityException expected = null;
        try {
          containerHosts = prepare(containerClusterSpec, containerNodeCount, groups);
        } catch (OutOfCapacityException e) {
          expected = e;
        }
        assertNotNull(expected);
    }

    @Test(expected = OutOfCapacityException.class)
    public void fail_when_too_few_distinct_parent_hosts() {
        tester.makeReadyVirtualDockerNodes(2, flavor, "parentHost1");
        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost2");

        int contentNodeCount = 3;
        List<HostSpec> hosts = prepare(contentClusterSpec, contentNodeCount, 1);
        activate(hosts);

        List<Node> nodes = getNodes(applicationId);
        assertDistinctParentHosts(nodes, ClusterSpec.Type.content, contentNodeCount);
    }

    @Test
    public void incomplete_parent_hosts_has_distinct_distribution() {
        tester.makeReadyVirtualDockerNode(1, flavor, "parentHost1");
        tester.makeReadyVirtualDockerNode(1, flavor, "parentHost2");
        tester.makeReadyVirtualNodes(1, flavor);

        final int contentNodeCount = 3;
        final int groups = 1;
        final List<HostSpec> contentHosts = prepare(contentClusterSpec, contentNodeCount, groups);
        activate(contentHosts);
        assertEquals(3, getNodes(applicationId).size());

        tester.makeReadyVirtualDockerNode(2, flavor, "parentHost1");
        tester.makeReadyVirtualDockerNode(2, flavor, "parentHost2");

        assertEquals(contentHosts, prepare(contentClusterSpec, contentNodeCount, groups));
    }

    @Test
    public void indistinct_distribution_with_known_ready_nodes() {
        tester.makeReadyVirtualNodes(3, flavor);

        final int contentNodeCount = 3;
        final int groups = 1;
        final List<HostSpec> contentHosts = prepare(contentClusterSpec, contentNodeCount, groups);
        activate(contentHosts);

        List<Node> nodes = getNodes(applicationId);
        assertEquals(3, nodes.size());

        // Set indistinct parents
        tester.patchNode(nodes.get(0).withParentHostname("parentHost1"));
        tester.patchNode(nodes.get(1).withParentHostname("parentHost1"));
        tester.patchNode(nodes.get(2).withParentHostname("parentHost2"));
        nodes = getNodes(applicationId);
        assertEquals(3, nodes.stream().filter(n -> n.parentHostname().isPresent()).count());

        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost1");
        tester.makeReadyVirtualDockerNodes(2, flavor, "parentHost2");

        OutOfCapacityException expectedException = null;
        try {
            prepare(contentClusterSpec, contentNodeCount, groups);
        } catch (OutOfCapacityException e) {
            expectedException = e;
        }
        assertNotNull(expectedException);
    }

    @Test
    public void unknown_distribution_with_known_ready_nodes() {
        tester.makeReadyVirtualNodes(3, flavor);

        final int contentNodeCount = 3;
        final int groups = 1;
        final List<HostSpec> contentHosts = prepare(contentClusterSpec, contentNodeCount, groups);
        activate(contentHosts);
        assertEquals(3, getNodes(applicationId).size());

        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost1");
        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost2");
        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost3");
        assertEquals(contentHosts, prepare(contentClusterSpec, contentNodeCount, groups));
    }

    @Test
    public void unknown_distribution_with_known_and_unknown_ready_nodes() {
        tester.makeReadyVirtualNodes(3, flavor);

        int contentNodeCount = 3;
        int groups = 1;
        List<HostSpec> contentHosts = prepare(contentClusterSpec, contentNodeCount, groups);
        activate(contentHosts);
        assertEquals(3, getNodes(applicationId).size());

        tester.makeReadyVirtualDockerNodes(1, flavor, "parentHost1");
        tester.makeReadyVirtualNodes(1, flavor);
        assertEquals(contentHosts, prepare(contentClusterSpec, contentNodeCount, groups));
    }

    private void assertDistinctParentHosts(List<Node> nodes, ClusterSpec.Type clusterType, int expectedCount) {
        List<String> parentHosts = getParentHostsFromNodes(nodes, Optional.of(clusterType));

        assertEquals(expectedCount, parentHosts.size());
        assertEquals(expectedCount, getDistinctParentHosts(parentHosts).size());
    }

    private List<String> getParentHostsFromNodes(List<Node> nodes, Optional<ClusterSpec.Type> clusterType) {
        List<String> parentHosts = new ArrayList<>();
        for (Node node : nodes) {
            if (node.parentHostname().isPresent() && (clusterType.isPresent() && clusterType.get() == node.allocation().get().membership().cluster().type())) {
                parentHosts.add(node.parentHostname().get());
            }
        }
        return parentHosts;
    }

    private Set<String> getDistinctParentHosts(List<String> hostnames) {
        return hostnames.stream()
                .distinct()
                .collect(Collectors.<String>toSet());
    }

    private List<Node> getNodes(ApplicationId applicationId) {
        return tester.getNodes(applicationId, Node.State.active).asList();
    }

    private List<HostSpec> prepare(ClusterSpec clusterSpec, int nodeCount, int groups) {
        return tester.prepare(applicationId, clusterSpec, nodeCount, groups, flavor);
    }

    private List<HostSpec> prepare(ClusterSpec clusterSpec, int nodeCount, int groups, NodeResources flavor) {
        return tester.prepare(applicationId, clusterSpec, nodeCount, groups, flavor);
    }

    @SafeVarargs
    private final void activate(List<HostSpec>... hostLists) {
        HashSet<HostSpec> hosts = new HashSet<>();
        for (List<HostSpec> h : hostLists) {
           hosts.addAll(h);
        }
        tester.activate(applicationId, hosts);
    }

}
