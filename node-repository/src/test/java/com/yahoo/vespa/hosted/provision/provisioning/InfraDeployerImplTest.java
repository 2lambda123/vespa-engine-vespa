// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.provisioning;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.HostName;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.Provisioner;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.NodeRepositoryTester;
import com.yahoo.vespa.hosted.provision.maintenance.InfrastructureVersions;
import com.yahoo.vespa.hosted.provision.node.Agent;
import com.yahoo.vespa.hosted.provision.node.Allocation;
import com.yahoo.vespa.hosted.provision.node.Generation;
import com.yahoo.vespa.service.duper.ConfigServerApplication;
import com.yahoo.vespa.service.duper.ControllerApplication;
import com.yahoo.vespa.service.monitor.DuperModelInfraApi;
import com.yahoo.vespa.service.monitor.InfraApplicationApi;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.List;
import java.util.Optional;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author freva
 */
@RunWith(Parameterized.class)
public class InfraDeployerImplTest {


    @Parameterized.Parameters(name = "application={0}")
    public static Iterable<Object[]> parameters() {
        return List.of(
                new InfraApplicationApi[]{new ConfigServerApplication()},
                new InfraApplicationApi[]{new ControllerApplication()}
        );
    }

    private final NodeRepositoryTester tester = new NodeRepositoryTester();
    private final Provisioner provisioner = mock(Provisioner.class);
    private final NodeRepository nodeRepository = tester.nodeRepository();
    private final InfrastructureVersions infrastructureVersions = nodeRepository.infrastructureVersions();
    private final DuperModelInfraApi duperModelInfraApi = mock(DuperModelInfraApi.class);
    private final InfraDeployerImpl infraDeployer;

    private final HostName node1 = HostName.from("node-1");
    private final HostName node2 = HostName.from("node-2");
    private final HostName node3 = HostName.from("node-3");
    private final Version target = Version.fromString("6.123.456");
    private final Version oldVersion = Version.fromString("6.122.333");

    private final InfraApplicationApi application;
    private final NodeType nodeType;

    public InfraDeployerImplTest(InfraApplicationApi application) {
        when(duperModelInfraApi.getInfraApplication(eq(application.getApplicationId()))).thenReturn(Optional.of(application));
        this.application = application;
        this.nodeType = application.getCapacity().type();
        this.infraDeployer = new InfraDeployerImpl(nodeRepository, provisioner, duperModelInfraApi);
    }

    @Test
    public void remove_application_if_without_nodes() {
        remove_application_without_nodes(true);
    }

    @Test
    public void skip_remove_unless_active() {
        remove_application_without_nodes(false);
    }

    private void remove_application_without_nodes(boolean applicationIsActive) {
        infrastructureVersions.setTargetVersion(nodeType, target, false);
        addNode(1, Node.State.failed, Optional.of(target));
        addNode(2, Node.State.parked, Optional.empty());
        when(duperModelInfraApi.infraApplicationIsActive(eq(application.getApplicationId()))).thenReturn(applicationIsActive);
        infraDeployer.getDeployment(application.getApplicationId()).orElseThrow().activate();
        if (applicationIsActive) {
            verify(duperModelInfraApi).infraApplicationRemoved(application.getApplicationId());
            verifyRemoved(1);
        } else {
            verifyRemoved(0);
        }
    }

    @Test
    public void activate_when_no_op() {
        infrastructureVersions.setTargetVersion(nodeType, target, false);

        addNode(1, Node.State.failed, Optional.of(oldVersion));
        addNode(2, Node.State.parked, Optional.of(target));
        addNode(3, Node.State.active, Optional.of(target));
        addNode(4, Node.State.inactive, Optional.of(target));
        addNode(5, Node.State.dirty, Optional.empty());

        when(duperModelInfraApi.infraApplicationIsActive(eq(application.getApplicationId()))).thenReturn(true);

        infraDeployer.getDeployment(application.getApplicationId()).orElseThrow().activate();
        verify(duperModelInfraApi, never()).infraApplicationRemoved(any());
        verify(duperModelInfraApi).infraApplicationActivated(any(), any());
        verify(provisioner).activate(any(), any(), any());
    }

    @Test
    public void activates_after_target_has_been_set_the_first_time() {
        infrastructureVersions.setTargetVersion(nodeType, target, false);

        addNode(1, Node.State.inactive, Optional.empty());
        addNode(2, Node.State.parked, Optional.empty());
        addNode(3, Node.State.active, Optional.empty());
        addNode(4, Node.State.failed, Optional.empty());
        addNode(5, Node.State.dirty, Optional.empty());

        when(provisioner.prepare(any(), any(), any(), anyInt(), any())).thenReturn(List.of(
                new HostSpec(node1.value(), List.of()),
                new HostSpec(node3.value(), List.of())));

        infraDeployer.getDeployment(application.getApplicationId()).orElseThrow().activate();

        verify(provisioner).prepare(eq(application.getApplicationId()), any(), any(), anyInt(), any());
        verify(provisioner).activate(any(), eq(application.getApplicationId()), any());
        verify(duperModelInfraApi).infraApplicationActivated(application.getApplicationId(), List.of(node3, node1));
    }


    @Test
    public void always_activates_for_dupermodel() {
        infrastructureVersions.setTargetVersion(nodeType, target, false);

        addNode(1, Node.State.active, Optional.of(target));

        when(duperModelInfraApi.infraApplicationIsActive(eq(application.getApplicationId()))).thenReturn(false);
        when(provisioner.prepare(any(), any(), any(), anyInt(), any())).thenReturn(List.of(
                new HostSpec(node1.value(), List.of())));

        infraDeployer.getDeployment(application.getApplicationId()).orElseThrow().activate();

        verify(provisioner, never()).prepare(any(), any(), any(), anyInt(), any());
        verify(provisioner, never()).activate(any(), any(), any());
        verify(duperModelInfraApi, times(1)).infraApplicationActivated(application.getApplicationId(), List.of(node1));

        infraDeployer.getDeployment(application.getApplicationId()).orElseThrow().activate();

        verify(provisioner, never()).prepare(any(), any(), any(), anyInt(), any());
        verify(provisioner, never()).activate(any(), any(), any());
        verify(duperModelInfraApi, times(2)).infraApplicationActivated(application.getApplicationId(), List.of(node1));
    }

    @Test
    public void provision_usable_nodes_on_old_version() {
        infrastructureVersions.setTargetVersion(nodeType, target, false);

        addNode(1, Node.State.failed, Optional.of(oldVersion));
        addNode(2, Node.State.inactive, Optional.of(target));
        addNode(3, Node.State.active, Optional.of(oldVersion));

        when(duperModelInfraApi.getSupportedInfraApplications()).thenReturn(List.of(application));
        when(provisioner.prepare(any(), any(), any(), anyInt(), any())).thenReturn(List.of(
                new HostSpec(node2.value(), List.of()),
                new HostSpec(node3.value(), List.of())));

        infraDeployer.getDeployment(application.getApplicationId()).orElseThrow().activate();

        verify(provisioner).prepare(eq(application.getApplicationId()), any(), any(), anyInt(), any());
        verify(provisioner).activate(any(), eq(application.getApplicationId()), any());
        verify(duperModelInfraApi).infraApplicationActivated(application.getApplicationId(), List.of(node3, node2));
    }

    @Test
    public void provision_with_usable_node_without_version() {
        infrastructureVersions.setTargetVersion(nodeType, target, false);

        addNode(1, Node.State.failed, Optional.of(oldVersion));
        addNode(2, Node.State.ready, Optional.empty());
        addNode(3, Node.State.active, Optional.of(target));

        when(provisioner.prepare(any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(
                        new HostSpec(node2.value(), List.of()),
                        new HostSpec(node3.value(), List.of())));

        infraDeployer.getDeployment(application.getApplicationId()).orElseThrow().activate();

        verify(provisioner).prepare(eq(application.getApplicationId()), any(), any(), anyInt(), any());
        verify(provisioner).activate(any(), eq(application.getApplicationId()), any());
        verify(duperModelInfraApi).infraApplicationActivated(application.getApplicationId(), List.of(node2, node3));
    }

    @Test
    public void avoid_provisioning_if_no_usable_nodes() {
        when(duperModelInfraApi.infraApplicationIsActive(eq(application.getApplicationId()))).thenReturn(true);
        infrastructureVersions.setTargetVersion(nodeType, target, false);

        infraDeployer.getDeployment(application.getApplicationId()).orElseThrow().activate();
        verifyRemoved(1);

        // Add nodes in non-provisionable states
        addNode(1, Node.State.dirty, Optional.empty());
        addNode(2, Node.State.failed, Optional.empty());

        infraDeployer.getDeployment(application.getApplicationId()).orElseThrow().activate();
        verifyRemoved(2);
    }

    private void verifyRemoved(int removedCount) {
        verify(provisioner, times(removedCount)).remove(any(), any());
        verify(duperModelInfraApi, times(removedCount)).infraApplicationRemoved(any());
    }

    private Node addNode(int id, Node.State state, Optional<Version> wantedVespaVersion) {
        Node node = tester.addNode("id-" + id, "node-" + id, "default", nodeType);
        Optional<Node> nodeWithAllocation = wantedVespaVersion.map(version -> {
            ClusterSpec clusterSpec = ClusterSpec.from(ClusterSpec.Type.admin, new ClusterSpec.Id("clusterid"), ClusterSpec.Group.from(0), version, false);
            ClusterMembership membership = ClusterMembership.from(clusterSpec, 1);
            Allocation allocation = new Allocation(application.getApplicationId(), membership, new Generation(0, 0), false);
            return node.with(allocation);
        });
        return nodeRepository.database().writeTo(state, nodeWithAllocation.orElse(node), Agent.system, Optional.empty());
    }

}
