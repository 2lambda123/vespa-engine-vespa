// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.ParentHostUnavailableException;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.Zone;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests deployment to docker images which share the same physical host.
 *
 * @author bratseth
 */
public class DockerProvisioningTest {

    private static final NodeResources dockerFlavor = new NodeResources(1, 1, 1);

    @Test
    public void docker_application_deployment() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).build();
        ApplicationId application1 = tester.makeApplicationId();

        for (int i = 1; i < 10; i++)
            tester.makeReadyVirtualDockerNodes(1, dockerFlavor, "dockerHost" + i);

        Version wantedVespaVersion = Version.fromString("6.39");
        int nodeCount = 7;
        List<HostSpec> hosts = tester.prepare(application1,
                                              ClusterSpec.request(ClusterSpec.Type.content, ClusterSpec.Id.from("myContent"), wantedVespaVersion, false),
                                              nodeCount, 1, dockerFlavor);
        tester.activate(application1, new HashSet<>(hosts));

        NodeList nodes = tester.getNodes(application1, Node.State.active);
        assertEquals(nodeCount, nodes.size());
        assertEquals(dockerFlavor, nodes.asList().get(0).flavor().resources());

        // Upgrade Vespa version on nodes
        Version upgradedWantedVespaVersion = Version.fromString("6.40");
        List<HostSpec> upgradedHosts = tester.prepare(application1,
                                                      ClusterSpec.request(ClusterSpec.Type.content, ClusterSpec.Id.from("myContent"), upgradedWantedVespaVersion, false),
                                                      nodeCount, 1, dockerFlavor);
        tester.activate(application1, new HashSet<>(upgradedHosts));
        NodeList upgradedNodes = tester.getNodes(application1, Node.State.active);
        assertEquals(nodeCount, upgradedNodes.size());
        assertEquals(dockerFlavor, upgradedNodes.asList().get(0).flavor().resources());
        assertEquals(hosts, upgradedHosts);
    }

    @Test
    public void refuses_to_activate_on_non_active_host() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).build();

        ApplicationId zoneApplication = tester.makeApplicationId();
        List<Node> parents = tester.makeDockerHosts(10, new NodeResources(2, 2, 2));
        for (Node parent : parents)
            tester.makeReadyVirtualDockerNodes(1, dockerFlavor, parent.hostname());

        ApplicationId application1 = tester.makeApplicationId();
        Version wantedVespaVersion = Version.fromString("6.39");
        int nodeCount = 7;
        List<HostSpec> nodes = tester.prepare(application1,
                ClusterSpec.request(ClusterSpec.Type.content, ClusterSpec.Id.from("myContent"), wantedVespaVersion, false),
                nodeCount, 1, dockerFlavor);
        try {
            tester.activate(application1, new HashSet<>(nodes));
            fail("Expected the allocation to fail due to parent hosts not being active yet");
        } catch (ParentHostUnavailableException ignored) { }

        // Activate the zone-app, thereby allocating the parents
        List<HostSpec> hosts = tester.prepare(zoneApplication,
                ClusterSpec.request(ClusterSpec.Type.container, ClusterSpec.Id.from("zone-app"), wantedVespaVersion, false),
                Capacity.fromRequiredNodeType(NodeType.host), 1);
        tester.activate(zoneApplication, hosts);

        // Try allocating tenants again
        nodes = tester.prepare(application1,
                ClusterSpec.request(ClusterSpec.Type.content, ClusterSpec.Id.from("myContent"), wantedVespaVersion, false),
                nodeCount, 1, dockerFlavor);
        tester.activate(application1, new HashSet<>(nodes));

        NodeList activeNodes = tester.getNodes(application1, Node.State.active);
        assertEquals(nodeCount, activeNodes.size());
    }

    /** Exclusive app first, then non-exclusive: Should give the same result as below */
    @Test
    public void docker_application_deployment_with_exclusive_app_first() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).build();
        for (int i = 1; i <= 4; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host1");
        for (int i = 5; i <= 8; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host2");
        for (int i = 9; i <= 12; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host3");
        for (int i = 13; i <= 16; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host4");

        ApplicationId application1 = tester.makeApplicationId();
        prepareAndActivate(application1, 2, true, tester);
        assertEquals(Set.of("host1", "host2"), hostsOf(tester.getNodes(application1, Node.State.active)));

        ApplicationId application2 = tester.makeApplicationId();
        prepareAndActivate(application2, 2, false, tester);
        assertEquals("Application is assigned to separate hosts",
                     Set.of("host3", "host4"), hostsOf(tester.getNodes(application2, Node.State.active)));
    }

    /** Non-exclusive app first, then an exclusive: Should give the same result as above */
    @Test
    public void docker_application_deployment_with_exclusive_app_last() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).build();
        for (int i = 1; i <= 4; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host1");
        for (int i = 5; i <= 8; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host2");
        for (int i = 9; i <= 12; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host3");
        for (int i = 13; i <= 16; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host4");

        ApplicationId application1 = tester.makeApplicationId();
        prepareAndActivate(application1, 2, false, tester);
        assertEquals(Set.of("host1", "host2"), hostsOf(tester.getNodes(application1, Node.State.active)));

        ApplicationId application2 = tester.makeApplicationId();
        prepareAndActivate(application2, 2, true, tester);
        assertEquals("Application is assigned to separate hosts",
                     Set.of("host3", "host4"), hostsOf(tester.getNodes(application2, Node.State.active)));
    }

    /** Test making an application exclusive */
    @Test
    public void docker_application_deployment_change_to_exclusive_and_back() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).build();
        for (int i = 1; i <= 4; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host1");
        for (int i = 5; i <= 8; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host2");
        for (int i = 9; i <= 12; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host3");
        for (int i = 13; i <= 16; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host4");

        ApplicationId application1 = tester.makeApplicationId();
        prepareAndActivate(application1, 2, false, tester);
        for (Node node : tester.getNodes(application1, Node.State.active))
            assertFalse(node.allocation().get().membership().cluster().isExclusive());

        prepareAndActivate(application1, 2, true, tester);
        assertEquals(Set.of("host1", "host2"), hostsOf(tester.getNodes(application1, Node.State.active)));
        for (Node node : tester.getNodes(application1, Node.State.active))
            assertTrue(node.allocation().get().membership().cluster().isExclusive());

        prepareAndActivate(application1, 2, false, tester);
        assertEquals(Set.of("host1", "host2"), hostsOf(tester.getNodes(application1, Node.State.active)));
        for (Node node : tester.getNodes(application1, Node.State.active))
            assertFalse(node.allocation().get().membership().cluster().isExclusive());
    }

    /** Non-exclusive app first, then an exclusive: Should give the same result as above */
    @Test
    public void docker_application_deployment_with_exclusive_app_causing_allocation_failure() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.prod, RegionName.from("us-east"))).build();
        for (int i = 1; i <= 4; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host1");
        for (int i = 5; i <= 8; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host2");
        for (int i = 9; i <= 12; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host3");
        for (int i = 13; i <= 16; i++)
            tester.makeReadyVirtualDockerNode(i, dockerFlavor, "host4");

        ApplicationId application1 = tester.makeApplicationId();
        prepareAndActivate(application1, 2, true, tester);
        assertEquals(Set.of("host1", "host2"), hostsOf(tester.getNodes(application1, Node.State.active)));

        try {
            ApplicationId application2 = ApplicationId.from("tenant1", "app1", "default");
            prepareAndActivate(application2, 3, false, tester);
            fail("Expected allocation failure");
        }
        catch (Exception e) {
            assertEquals("No room for 3 nodes as 2 of 4 hosts are exclusive",
                         "Could not satisfy request for 3 nodes with [vcpu: 1.0, memory: 1.0 Gb, disk 1.0 Gb] for container cluster 'myContainer' group 0 6.39 in tenant1.app1: Not enough nodes available due to host exclusivity constraints.",
                         e.getMessage());
        }

        // Adding 3 nodes of another application for the same tenant works
        ApplicationId application3 = ApplicationId.from(application1.tenant(), ApplicationName.from("app3"), InstanceName.from("default"));
        prepareAndActivate(application3, 2, true, tester);
    }

    // In dev, test and staging you get nodes with default flavor, but we should get specified flavor for docker nodes
    @Test
    public void get_specified_flavor_not_default_flavor_for_docker() {
        ProvisioningTester tester = new ProvisioningTester.Builder().zone(new Zone(Environment.test, RegionName.from("corp-us-east-1"))).build();
        ApplicationId application1 = tester.makeApplicationId();
        tester.makeReadyVirtualDockerNodes(1, dockerFlavor, "dockerHost");

        List<HostSpec> hosts = tester.prepare(application1, ClusterSpec.request(ClusterSpec.Type.content, ClusterSpec.Id.from("myContent"), Version.fromString("6.42"), false), 1, 1, dockerFlavor);
        tester.activate(application1, new HashSet<>(hosts));

        NodeList nodes = tester.getNodes(application1, Node.State.active);
        assertEquals(1, nodes.size());
        assertEquals("[vcpu: 1.0, memory: 1.0 Gb, disk 1.0 Gb]", nodes.asList().get(0).flavor().canonicalName());
    }

    private Set<String> hostsOf(NodeList nodes) {
        return nodes.asList().stream().map(Node::parentHostname).map(Optional::get).collect(Collectors.toSet());
    }

    private void prepareAndActivate(ApplicationId application, int nodeCount, boolean exclusive, ProvisioningTester tester) {
        Set<HostSpec> hosts = new HashSet<>(tester.prepare(application,
                                            ClusterSpec.request(ClusterSpec.Type.container, ClusterSpec.Id.from("myContainer"), Version.fromString("6.39"), exclusive),
                                            Capacity.fromCount(nodeCount, Optional.of(dockerFlavor), false, true),
                                            1));
        tester.activate(application, hosts);
    }

}
