// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.HostFilter;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.OutOfCapacityException;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.Zone;
import com.yahoo.transaction.NestedTransaction;
import com.yahoo.vespa.hosted.provision.Node;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Various allocation sequence scenarios
 *
 * @author bratseth
 */
public class ProvisionTest {

    @Test
    public void application_deployment_constant_application_size() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.prod, RegionName.from("us-east")));

        ApplicationId application1 = tester.makeApplicationId();
        ApplicationId application2 = tester.makeApplicationId();

        tester.makeReadyNodes(21, "default");

        // deploy
        SystemState state1 = prepare(application1, 2, 2, 3, 3, "default", tester);
        tester.activate(application1, state1.allHosts);

        // redeploy
        SystemState state2 = prepare(application1, 2, 2, 3, 3, "default", tester);
        state2.assertEquals(state1);
        tester.activate(application1, state2.allHosts);

        // deploy another application
        SystemState state1App2 = prepare(application2, 2, 2, 3, 3, "default", tester);
        assertFalse("Hosts to different apps are disjunct", state1App2.allHosts.removeAll(state1.allHosts));
        tester.activate(application2, state1App2.allHosts);

        // prepare twice
        SystemState state3 = prepare(application1, 2, 2, 3, 3, "default", tester);
        SystemState state4 = prepare(application1, 2, 2, 3, 3, "default", tester);
        state3.assertEquals(state2);
        state4.assertEquals(state3);
        tester.activate(application1, state4.allHosts);

        // remove nodes before deploying
        SystemState state5 = prepare(application1, 2, 2, 3, 3, "default", tester);
        HostSpec removed = tester.removeOne(state5.allHosts);
        tester.activate(application1, state5.allHosts);
        assertEquals(removed.hostname(), tester.nodeRepository().getNodes(application1, Node.State.inactive).get(0).hostname());

        // remove some of the clusters
        SystemState state6 = prepare(application1, 0, 2, 0, 3, "default", tester);
        tester.activate(application1, state6.allHosts);
        assertEquals(5, tester.getNodes(application1, Node.State.active).size());
        assertEquals(5, tester.getNodes(application1, Node.State.inactive).size());

        // delete app
        NestedTransaction removeTransaction = new NestedTransaction();
        tester.provisioner().remove(removeTransaction, application1);
        removeTransaction.commit();
        assertEquals(tester.toHostNames(state1.allHosts), tester.toHostNames(tester.nodeRepository().getNodes(application1, Node.State.inactive)));
        assertEquals(0, tester.getNodes(application1, Node.State.active).size());

        // other application is unaffected
        assertEquals(state1App2.hostNames(), tester.toHostNames(tester.nodeRepository().getNodes(application2, Node.State.active)));

        // fail a node from app2 and make sure it does not get inactive nodes from first
        HostSpec failed = tester.removeOne(state1App2.allHosts);
        tester.fail(failed);
        assertEquals(9, tester.getNodes(application2, Node.State.active).size());
        SystemState state2App2 = prepare(application2, 2, 2, 3, 3, "default", tester);
        assertFalse("Hosts to different apps are disjunct", state2App2.allHosts.removeAll(state1.allHosts));
        assertEquals("A new node was reserved to replace the failed one", 10, state2App2.allHosts.size());
        assertFalse("The new host is not the failed one", state2App2.allHosts.contains(failed));
        tester.activate(application2, state2App2.allHosts);

        // deploy first app again
        SystemState state7 = prepare(application1, 2, 2, 3, 3, "default", tester);
        state7.assertEquals(state1);
        tester.activate(application1, state7.allHosts);
        assertEquals(0, tester.getNodes(application1, Node.State.inactive).size());

        // restart
        HostFilter allFilter = HostFilter.all();
        HostFilter hostFilter = HostFilter.hostname(state6.allHosts.iterator().next().hostname());
        HostFilter clusterTypeFilter = HostFilter.clusterType(ClusterSpec.Type.container);
        HostFilter clusterIdFilter = HostFilter.clusterId(ClusterSpec.Id.from("container1"));

        tester.provisioner().restart(application1, allFilter);
        tester.provisioner().restart(application1, hostFilter);
        tester.provisioner().restart(application1, clusterTypeFilter);
        tester.provisioner().restart(application1, clusterIdFilter);
        tester.assertRestartCount(application1, allFilter, hostFilter, clusterTypeFilter, clusterIdFilter);
    }

    @Test
    public void application_deployment_variable_application_size() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.prod, RegionName.from("us-east")));

        ApplicationId application1 = tester.makeApplicationId();

        tester.makeReadyNodes(24, "default");

        // deploy
        SystemState state1 = prepare(application1, 2, 2, 3, 3, "default", tester);
        tester.activate(application1, state1.allHosts);

        // redeploy with increased sizes
        SystemState state2 = prepare(application1, 3, 4, 4, 5, "default", tester);
        state2.assertExtends(state1);
        assertEquals("New nodes are reserved", 6, tester.getNodes(application1, Node.State.reserved).size());
        tester.activate(application1, state2.allHosts);

        // decrease again
        SystemState state3 = prepare(application1, 2, 2, 3, 3, "default", tester);
        tester.activate(application1, state3.allHosts);
        assertEquals("Superfluous container nodes are deactivated",
                     3-2 + 4-2, tester.getNodes(application1, Node.State.inactive).size());
        assertEquals("Superfluous content nodes are retired",
                     4-3 + 5-3, tester.getNodes(application1, Node.State.active).retired().size());

        // increase even more, and remove one node before deploying
        SystemState state4 = prepare(application1, 4, 5, 5, 6, "default", tester);
        assertEquals("Inactive nodes are reused", 0, tester.getNodes(application1, Node.State.inactive).size());
        assertEquals("Earlier retired nodes are not unretired before activate",
                     4-3 + 5-3, tester.getNodes(application1, Node.State.active).retired().size());
        state4.assertExtends(state2);
        assertEquals("New and inactive nodes are reserved", 4 + 3, tester.getNodes(application1, Node.State.reserved).size());
        HostSpec removed = tester.removeOne(state4.allHosts);
        tester.activate(application1, state4.allHosts);
        assertEquals(removed.hostname(), tester.getNodes(application1, Node.State.inactive).asList().get(0).hostname());
        assertEquals("Earlier retired nodes are unretired on activate",
                     0, tester.getNodes(application1, Node.State.active).retired().size());

        // decrease again
        SystemState state5 = prepare(application1, 2, 2, 3, 3, "default", tester);
        tester.activate(application1, state5.allHosts);
        assertEquals("Superfluous container nodes are deactivated",
                     4-2 + 5-2, tester.getNodes(application1, Node.State.inactive).size());
        assertEquals("Superfluous content nodes are retired",
                     5-3 + 6-3, tester.getNodes(application1, Node.State.active).retired().size());

        // increase content slightly
        SystemState state6 = prepare(application1, 2, 2, 4, 3, "default", tester);
        tester.activate(application1, state6.allHosts);
        assertEquals("One content node is unretired",
                     5-4 + 6-3, tester.getNodes(application1, Node.State.active).retired().size());

        // Then reserve more
        SystemState state7 = prepare(application1, 8, 2, 2, 2, "default", tester);

        // delete app
        NestedTransaction removeTransaction = new NestedTransaction();
        tester.provisioner().remove(removeTransaction, application1);
        removeTransaction.commit();
        assertEquals(0, tester.getNodes(application1, Node.State.active).size());
        assertEquals(0, tester.getNodes(application1, Node.State.reserved).size());
    }

    @Test
    public void application_deployment_multiple_flavors() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.prod, RegionName.from("us-east")));

        ApplicationId application1 = tester.makeApplicationId();

        tester.makeReadyNodes(12, "small");
        tester.makeReadyNodes(16, "large");

        // deploy
        SystemState state1 = prepare(application1, 2, 2, 4, 4, "small", tester);
        tester.activate(application1, state1.allHosts);

        // redeploy with reduced size (to cause us to have retired nodes before switching flavor)
        SystemState state2 = prepare(application1, 2, 2, 3, 3, "small", tester);
        tester.activate(application1, state2.allHosts);

        // redeploy with increased sizes and new flavor
        SystemState state3 = prepare(application1, 3, 4, 4, 5, "large", tester);
        assertEquals("New nodes are reserved", 16, tester.nodeRepository().getNodes(application1, Node.State.reserved).size());
        tester.activate(application1, state3.allHosts);
        assertEquals("'small' container nodes are retired because we are swapping the entire cluster",
                     2 + 2, tester.getNodes(application1, Node.State.active).retired().type(ClusterSpec.Type.container).flavor("small").size());
        assertEquals("'small' content nodes are retired",
                     4 + 4, tester.getNodes(application1, Node.State.active).retired().type(ClusterSpec.Type.content).flavor("small").size());
        assertEquals("No 'large' content nodes are retired",
                     0, tester.getNodes(application1, Node.State.active).retired().flavor("large").size());
    }

    @Test
    public void application_deployment_multiple_flavors_default_per_type() {
        ConfigserverConfig.Builder config = new ConfigserverConfig.Builder();
        config.environment("prod");
        config.region("us-east");
        config.defaultFlavor("not-used");
        config.defaultContainerFlavor("small");
        config.defaultContentFlavor("large");
        ProvisioningTester tester = new ProvisioningTester(new Zone(new ConfigserverConfig(config)));

        ApplicationId application1 = tester.makeApplicationId();

        tester.makeReadyNodes(10, "small");
        tester.makeReadyNodes(9, "large");

        // deploy
        SystemState state1 = prepare(application1, 2, 3, 4, 5, null, tester);
        tester.activate(application1, state1.allHosts);
        assertEquals("'small' nodes are used for containers",
                     2 + 3, tester.getNodes(application1, Node.State.active).flavor("small").size());
        assertEquals("'large' nodes are used for content",
                     4 + 5, tester.getNodes(application1, Node.State.active).flavor("large").size());
    }

    @Test
    public void application_deployment_multiple_flavors_with_replacement() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.prod, RegionName.from("us-east")));

        ApplicationId application1 = tester.makeApplicationId();

        tester.makeReadyNodes(8, "large");
        tester.makeReadyNodes(8, "large-variant");

        // deploy with flavor which will be fulfilled by some old and new nodes
        SystemState state1 = prepare(application1, 2, 2, 4, 4, "old-large1", tester);
        tester.activate(application1, state1.allHosts);

        // redeploy with increased sizes, this will map to the remaining old/new nodes
        SystemState state2 = prepare(application1, 3, 4, 4, 5, "old-large2", tester);
        assertEquals("New nodes are reserved", 4, tester.getNodes(application1, Node.State.reserved).size());
        tester.activate(application1, state2.allHosts);
        assertEquals("All nodes are used",
                    16, tester.getNodes(application1, Node.State.active).size());
        assertEquals("No nodes are retired",
                     0, tester.getNodes(application1, Node.State.active).retired().size());

        // This is a noop as we are already using large nodes and nodes which replace large
        SystemState state3 = prepare(application1, 3, 4, 4, 5, "large", tester);
        assertEquals("Noop", 0, tester.getNodes(application1, Node.State.reserved).size());
        tester.activate(application1, state3.allHosts);

        try {
            SystemState state4 = prepare(application1, 3, 4, 4, 5, "large-variant", tester);
            org.junit.Assert.fail("Should fail as we don't have that many large-variant nodes");
        }
        catch (OutOfCapacityException expected) {
        }

        // make enough nodes to complete the switch to large-variant
        tester.makeReadyNodes(8, "large-variant");
        SystemState state4 = prepare(application1, 3, 4, 4, 5, "large-variant", tester);
        assertEquals("New 'large-variant' nodes are reserved", 8, tester.getNodes(application1, Node.State.reserved).size());
        tester.activate(application1, state4.allHosts);
        // (we can not check for the precise state here without carrying over from earlier as the distribution of
        // old and new on different clusters is unknown)
    }

    @Test
    public void application_deployment_above_then_at_capacity_limit() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.prod, RegionName.from("us-east")));

        ApplicationId application1 = tester.makeApplicationId();

        tester.makeReadyNodes(5, "default");

        // deploy
        SystemState state1 = prepare(application1, 2, 0, 3, 0, "default", tester);
        tester.activate(application1, state1.allHosts);

        // redeploy a too large application
        try {
            SystemState state2 = prepare(application1, 3, 0, 3, 0, "default", tester);
            org.junit.Assert.fail("Expected out of capacity exception");
        }
        catch (OutOfCapacityException expected) {
        }

        // deploy first state again
        SystemState state3 = prepare(application1, 2, 0, 3, 0, "default", tester);
        tester.activate(application1, state3.allHosts);
    }

    @Test
    public void dev_deployment_size() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.dev, RegionName.from("us-east")));

        ApplicationId application = tester.makeApplicationId();
        tester.makeReadyNodes(4, "default");
        SystemState state = prepare(application, 2, 2, 3, 3, "default", tester);
        assertEquals(4, state.allHosts.size());
        tester.activate(application, state.allHosts);
    }

    @Test
    public void test_deployment_size() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.test, RegionName.from("us-east")));

        ApplicationId application = tester.makeApplicationId();
        tester.makeReadyNodes(4, "default");
        SystemState state = prepare(application, 2, 2, 3, 3, "default", tester);
        assertEquals(4, state.allHosts.size());
        tester.activate(application, state.allHosts);
    }

    @Ignore // TODO: Re-activate when the check is reactivate in CapacityPolicies
    @Test(expected = IllegalArgumentException.class)
    public void prod_deployment_requires_redundancy() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.prod, RegionName.from("us-east")));

        ApplicationId application = tester.makeApplicationId();
        tester.makeReadyNodes(10, "default");
        prepare(application, 1, 2, 3, 3, "default", tester);
    }

    /** Dev always uses the zone default flavor */
    @Test
    public void dev_deployment_flavor() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.dev, RegionName.from("us-east")));

        ApplicationId application = tester.makeApplicationId();
        tester.makeReadyNodes(4, "default");
        SystemState state = prepare(application, 2, 2, 3, 3, "large", tester);
        assertEquals(4, state.allHosts.size());
        tester.activate(application, state.allHosts);
    }

    /** Test always uses the zone default flavor */
    @Test
    public void test_deployment_flavor() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.test, RegionName.from("us-east")));

        ApplicationId application = tester.makeApplicationId();
        tester.makeReadyNodes(4, "default");
        SystemState state = prepare(application, 2, 2, 3, 3, "large", tester);
        assertEquals(4, state.allHosts.size());
        tester.activate(application, state.allHosts);
    }

    @Test
    public void staging_deployment_size() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.staging, RegionName.from("us-east")));

        ApplicationId application = tester.makeApplicationId();
        tester.makeReadyNodes(14, "default");
        SystemState state = prepare(application, 1, 1, 1, 64, "default", tester); // becomes 1, 1, 1, 6
        assertEquals(9, state.allHosts.size());
        tester.activate(application, state.allHosts);
    }

    @Test
    public void activate_after_reservation_timeout() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.prod, RegionName.from("us-east")));

        tester.makeReadyNodes(10, "default");
        ApplicationId application = tester.makeApplicationId();
        SystemState state = prepare(application, 2, 2, 3, 3, "default", tester);

        // Simulate expiry
        NestedTransaction deactivateTransaction = new NestedTransaction();
        tester.nodeRepository().deactivate(application, deactivateTransaction);
        deactivateTransaction.commit();

        try {
            tester.activate(application, state.allHosts);
            org.junit.Assert.fail("Expected exception");
        }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().startsWith("Activation of " + application + " failed"));
        }
    }

    @Test
    public void out_of_capacity() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.prod, RegionName.from("us-east")));

        tester.makeReadyNodes(9, "default"); // need 2+2+3+3=10
        ApplicationId application = tester.makeApplicationId();
        try {
            prepare(application, 2, 2, 3, 3, "default", tester);
            org.junit.Assert.fail("Expected exception");
        }
        catch (OutOfCapacityException e) {
            assertTrue(e.getMessage().startsWith("Could not satisfy request"));
        }
    }

    @Test
    public void out_of_desired_flavor() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.prod, RegionName.from("us-east")));

        tester.makeReadyNodes(10, "small"); // need 2+2+3+3=10
        tester.makeReadyNodes( 9, "large"); // need 2+2+3+3=10
        ApplicationId application = tester.makeApplicationId();
        try {
            prepare(application, 2, 2, 3, 3, "large", tester);
            org.junit.Assert.fail("Expected exception");
        }
        catch (OutOfCapacityException e) {
            assertTrue(e.getMessage().startsWith("Could not satisfy request for 3 nodes of flavor 'large'"));
        }
    }

    @Test
    public void nonexisting_flavor() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.prod, RegionName.from("us-east")));

        ApplicationId application = tester.makeApplicationId();
        try {
            prepare(application, 2, 2, 3, 3, "nonexisting", tester);
            org.junit.Assert.fail("Expected exception");
        }
        catch (IllegalArgumentException e) {
            assertEquals("Unknown flavor 'nonexisting' Flavors are [default, docker1, large, old-large1, old-large2, small, v-4-8-100]", e.getMessage());
        }
    }

    @Test
    public void highest_node_indexes_are_retired_first() {
        ProvisioningTester tester = new ProvisioningTester(new Zone(Environment.prod, RegionName.from("us-east")));

        ApplicationId application1 = tester.makeApplicationId();

        tester.makeReadyNodes(10, "default");

        // deploy
        SystemState state1 = prepare(application1, 2, 2, 3, 3, "default", tester);
        tester.activate(application1, state1.allHosts);

        // decrease cluster sizes
        SystemState state2 = prepare(application1, 1, 1, 1, 1, "default", tester);
        tester.activate(application1, state2.allHosts);

        // group0
        assertFalse(state2.hostByMembership("test", 0, 0).membership().get().retired());
        assertTrue( state2.hostByMembership("test", 0, 1).membership().get().retired());
        assertTrue( state2.hostByMembership("test", 0, 2).membership().get().retired());

        // group1
        assertFalse(state2.hostByMembership("test", 1, 0).membership().get().retired());
        assertTrue( state2.hostByMembership("test", 1, 1).membership().get().retired());
        assertTrue( state2.hostByMembership("test", 1, 2).membership().get().retired());
    }

    private SystemState prepare(ApplicationId application, int container0Size, int container1Size, int group0Size, int group1Size, String flavor, ProvisioningTester tester) {
        // "deploy prepare" with a two container clusters and a storage cluster having of two groups
        ClusterSpec containerCluster0 = ClusterSpec.from(ClusterSpec.Type.container, ClusterSpec.Id.from("container0"), Optional.empty());
        ClusterSpec containerCluster1 = ClusterSpec.from(ClusterSpec.Type.container, ClusterSpec.Id.from("container1"), Optional.empty());
        ClusterSpec contentGroup0 = ClusterSpec.from(ClusterSpec.Type.content, ClusterSpec.Id.from("test"), Optional.of(ClusterSpec.Group.from("0")));
        ClusterSpec contentGroup1 = ClusterSpec.from(ClusterSpec.Type.content, ClusterSpec.Id.from("test"), Optional.of(ClusterSpec.Group.from("1")));

        Set<HostSpec> container0 = new HashSet<>(tester.prepare(application, containerCluster0, container0Size, 1, flavor));
        Set<HostSpec> container1 = new HashSet<>(tester.prepare(application, containerCluster1, container1Size, 1, flavor));
        Set<HostSpec> group0 = new HashSet<>(tester.prepare(application, contentGroup0, group0Size, 1, flavor));
        Set<HostSpec> group1 = new HashSet<>(tester.prepare(application, contentGroup1, group1Size, 1, flavor));

        Set<HostSpec> allHosts = new HashSet<>();
        allHosts.addAll(container0);
        allHosts.addAll(container1);
        allHosts.addAll(group0);
        allHosts.addAll(group1);

        int expectedContainer0Size = tester.capacityPolicies().decideSize(Capacity.fromNodeCount(container0Size));
        int expectedContainer1Size = tester.capacityPolicies().decideSize(Capacity.fromNodeCount(container1Size));
        int expectedGroup0Size = tester.capacityPolicies().decideSize(Capacity.fromNodeCount(group0Size));
        int expectedGroup1Size = tester.capacityPolicies().decideSize(Capacity.fromNodeCount(group1Size));

        assertEquals("Hosts in each group cluster is disjunct and the total number of unretired nodes is correct",
                     expectedContainer0Size + expectedContainer1Size + expectedGroup0Size + expectedGroup1Size,
                     tester.nonretired(allHosts).size());
        // Check cluster/group sizes
        assertEquals(expectedContainer0Size, tester.nonretired(container0).size());
        assertEquals(expectedContainer1Size, tester.nonretired(container1).size());
        assertEquals(expectedGroup0Size, tester.nonretired(group0).size());
        assertEquals(expectedGroup1Size, tester.nonretired(group1).size());
        // Check cluster membership
        tester.assertMembersOf(containerCluster0, container0);
        tester.assertMembersOf(containerCluster1, container1);
        tester.assertMembersOf(contentGroup0, group0);
        tester.assertMembersOf(contentGroup1, group1);

        return new SystemState(allHosts, container0, container1, group0, group1);
    }

    private static class SystemState {

        private Set<HostSpec> allHosts;
        private Set<HostSpec> container1;
        private Set<HostSpec> container2;
        private Set<HostSpec> group1;
        private Set<HostSpec> group2;

        public SystemState(Set<HostSpec> allHosts,
                           Set<HostSpec> container1,
                           Set<HostSpec> container2,
                           Set<HostSpec> group1,
                           Set<HostSpec> group2) {
            this.allHosts = allHosts;
            this.container1 = container1;
            this.container2 = container2;
            this.group1 = group1;
            this.group2 = group2;
        }
        
        /** Returns a host by cluster name and index, or null if there is no host with the given values in this */
        public HostSpec hostByMembership(String clusterId, int group, int index) {
            for (HostSpec host : allHosts) {
                if ( ! host.membership().isPresent()) continue;
                ClusterMembership membership = host.membership().get();
                if (membership.cluster().id().value().equals(clusterId) &&
                    groupMatches(membership.cluster().group(), group) &&
                    membership.index() == index)
                    return host;
            }
            return null;
        }
        
        private boolean groupMatches(Optional<ClusterSpec.Group> clusterGroup, int group) {
            if ( ! clusterGroup.isPresent()) return group==0;
            return Integer.parseInt(clusterGroup.get().value()) == group;
        }

        public Set<String> hostNames() {
            return allHosts.stream().map(HostSpec::hostname).collect(Collectors.toSet());
        }

        public void assertExtends(SystemState other) {
            assertTrue(this.allHosts.containsAll(other.allHosts));
            assertExtends(this.container1, other.container1);
            assertExtends(this.container2, other.container2);
            assertExtends(this.group1, other.group1);
            assertExtends(this.group2, other.group2);
        }

        private void assertExtends(Set<HostSpec> extension,
                                   Set<HostSpec> original) {
            for (HostSpec originalHost : original) {
                HostSpec newHost = findHost(originalHost.hostname(), extension);
                org.junit.Assert.assertEquals(newHost.membership(), originalHost.membership());
            }
        }

        private HostSpec findHost(String hostName, Set<HostSpec> hosts) {
            for (HostSpec host : hosts)
                if (host.hostname().equals(hostName))
                    return host;
            return null;
        }

        public void assertEquals(SystemState other) {
            org.junit.Assert.assertEquals(this.allHosts, other.allHosts);
            org.junit.Assert.assertEquals(this.container1, other.container1);
            org.junit.Assert.assertEquals(this.container2, other.container2);
            org.junit.Assert.assertEquals(this.group1, other.group1);
            org.junit.Assert.assertEquals(this.group2, other.group2);
        }

    }

}
