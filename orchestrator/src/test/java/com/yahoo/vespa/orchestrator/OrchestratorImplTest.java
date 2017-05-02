// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.orchestrator;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.vespa.applicationmodel.ApplicationInstance;
import com.yahoo.vespa.applicationmodel.ApplicationInstanceReference;
import com.yahoo.vespa.applicationmodel.HostName;
import com.yahoo.vespa.applicationmodel.ServiceType;
import com.yahoo.vespa.orchestrator.controller.ClusterControllerClientFactoryMock;
import com.yahoo.vespa.orchestrator.policy.BatchHostStateChangeDeniedException;
import com.yahoo.vespa.orchestrator.policy.HostStateChangeDeniedException;
import com.yahoo.vespa.orchestrator.status.HostStatus;
import com.yahoo.vespa.orchestrator.status.InMemoryStatusService;
import com.yahoo.vespa.service.monitor.ServiceMonitorStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static com.yahoo.vespa.orchestrator.status.ApplicationInstanceStatus.ALLOWED_TO_BE_DOWN;
import static com.yahoo.vespa.orchestrator.status.ApplicationInstanceStatus.NO_REMARKS;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsCollectionContaining.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;

/**
 * Test Orchestrator with a mock backend (the InMemoryStatusService)
 *
 * @author smorgrav
 */
public class OrchestratorImplTest {

    private ApplicationId app1;
    private ApplicationId app2;
    private HostName app1_host1;

    private OrchestratorImpl orchestrator;
    private ClusterControllerClientFactoryMock clustercontroller;

    @Before
    public void setUp() throws Exception {
        // Extract applications and hosts from dummy instance lookup service
        Iterator<ApplicationInstance<ServiceMonitorStatus>> iterator = DummyInstanceLookupService.getApplications().iterator();
        ApplicationInstanceReference app1_ref = iterator.next().reference();
        app1 = OrchestratorUtil.toApplicationId(app1_ref);
        app1_host1 = DummyInstanceLookupService.getContentHosts(app1_ref).iterator().next();
        app2 = OrchestratorUtil.toApplicationId(iterator.next().reference());

        clustercontroller = new ClusterControllerClientFactoryMock();
        orchestrator = new OrchestratorImpl(
                clustercontroller,
                new InMemoryStatusService(),
                new OrchestratorConfig(new OrchestratorConfig.Builder()),
                new DummyInstanceLookupService());

        clustercontroller.setAllDummyNodesAsUp();
    }

    @After
    public void tearDown() throws Exception {
        orchestrator = null;
        clustercontroller = null;
    }

    @Test
    public void application_has_initially_no_remarks() throws Exception {
        assertThat(orchestrator.getApplicationInstanceStatus(app1), is(NO_REMARKS));
    }

    @Test
    public void application_can_be_set_in_suspend() throws Exception {
        orchestrator.suspend(app1);
        assertThat(orchestrator.getApplicationInstanceStatus(app1), is(ALLOWED_TO_BE_DOWN));
    }

    @Test
    public void application_can_be_removed_from_suspend() throws Exception {
        orchestrator.suspend(app1);
        orchestrator.resume(app1);
        assertThat(orchestrator.getApplicationInstanceStatus(app1), is(NO_REMARKS));
    }

    @Test
    public void appliations_list_returns_empty_initially() throws Exception {
        assertThat(orchestrator.getAllSuspendedApplications(), is(empty()));
    }

    @Test
    public void appliations_list_returns_suspended_apps() throws Exception {
        // One suspended app
        orchestrator.suspend(app1);
        assertThat(orchestrator.getAllSuspendedApplications().size(), is(1));
        assertThat(orchestrator.getAllSuspendedApplications(), hasItem(app1));

        // Two suspended apps
        orchestrator.suspend(app2);
        assertThat(orchestrator.getAllSuspendedApplications().size(), is(2));
        assertThat(orchestrator.getAllSuspendedApplications(), hasItem(app1));
        assertThat(orchestrator.getAllSuspendedApplications(), hasItem(app2));

        // Back to one when resetting one app to no_remarks
        orchestrator.resume(app1);
        assertThat(orchestrator.getAllSuspendedApplications().size(), is(1));
        assertThat(orchestrator.getAllSuspendedApplications(), hasItem(app2));
    }


    @Test
    public void application_operations_are_idempotent() throws Exception {
        // Two suspends
        orchestrator.suspend(app1);
        orchestrator.suspend(app1);
        assertThat(orchestrator.getApplicationInstanceStatus(app1), is(ALLOWED_TO_BE_DOWN));
        assertThat(orchestrator.getApplicationInstanceStatus(app2), is(NO_REMARKS));

        // Three no_remarks
        orchestrator.resume(app1);
        orchestrator.resume(app1);
        orchestrator.resume(app1);
        assertThat(orchestrator.getApplicationInstanceStatus(app1), is(NO_REMARKS));
        assertThat(orchestrator.getApplicationInstanceStatus(app2), is(NO_REMARKS));

        // Two suspends and two on two applications interleaved
        orchestrator.suspend(app2);
        orchestrator.resume(app1);
        orchestrator.suspend(app2);
        orchestrator.resume(app1);
        assertThat(orchestrator.getApplicationInstanceStatus(app1), is(NO_REMARKS));
        assertThat(orchestrator.getApplicationInstanceStatus(app2), is(ALLOWED_TO_BE_DOWN));
    }


    @Test
    public void application_suspend_sets_application_nodes_in_maintenance_and_allowed_to_be_down() throws Exception {
        // Pre condition
        assertEquals(NO_REMARKS, orchestrator.getApplicationInstanceStatus(app1));
        assertEquals(HostStatus.NO_REMARKS, orchestrator.getNodeStatus(app1_host1));
        assertFalse(isInMaintenance(app1, app1_host1));

        orchestrator.suspend(app1);

        assertEquals(HostStatus.ALLOWED_TO_BE_DOWN, orchestrator.getNodeStatus(app1_host1));
        assertTrue(isInMaintenance(app1, app1_host1));
    }

    @Test
    public void node_suspend_while_app_is_resumed_set_allowed_to_be_down_and_set_it_in_maintenance() throws Exception {
        // Pre condition
        assertEquals(NO_REMARKS, orchestrator.getApplicationInstanceStatus(app1));
        assertEquals(HostStatus.NO_REMARKS, orchestrator.getNodeStatus(app1_host1));
        assertFalse(isInMaintenance(app1, app1_host1));

        orchestrator.suspend(app1_host1);

        assertEquals(HostStatus.ALLOWED_TO_BE_DOWN, orchestrator.getNodeStatus(app1_host1));
        assertTrue(isInMaintenance(app1, app1_host1));
    }

    @Test
    public void node_suspend_while_app_is_suspended_does_nothing() throws Exception {
        // Pre condition
        orchestrator.suspend(app1);
        assertEquals(ALLOWED_TO_BE_DOWN, orchestrator.getApplicationInstanceStatus(app1));
        assertEquals(HostStatus.ALLOWED_TO_BE_DOWN, orchestrator.getNodeStatus(app1_host1));
        assertTrue(isInMaintenance(app1, app1_host1));

        orchestrator.suspend(app1_host1);

        // Should not change anything
        assertEquals(ALLOWED_TO_BE_DOWN, orchestrator.getApplicationInstanceStatus(app1));
        assertEquals(HostStatus.ALLOWED_TO_BE_DOWN, orchestrator.getNodeStatus(app1_host1));
        assertTrue(isInMaintenance(app1, app1_host1));
    }

    @Test
    public void node_resume_after_app_is_resumed_removes_allowed_be_down_and_set_it_up() throws Exception {
        // Pre condition
        orchestrator.suspend(app1);
        assertEquals(ALLOWED_TO_BE_DOWN, orchestrator.getApplicationInstanceStatus(app1));
        assertEquals(HostStatus.ALLOWED_TO_BE_DOWN, orchestrator.getNodeStatus(app1_host1));
        assertTrue(isInMaintenance(app1, app1_host1));

        orchestrator.resume(app1);
        orchestrator.resume(app1_host1);

        assertEquals(HostStatus.NO_REMARKS, orchestrator.getNodeStatus(app1_host1));
        assertFalse(isInMaintenance(app1, app1_host1));
    }

    @Test
    public void node_resume_while_app_is_suspended_does_nothing() throws Exception {
        orchestrator.suspend(app1_host1);
        orchestrator.suspend(app1);

        orchestrator.resume(app1_host1);

        assertEquals(HostStatus.ALLOWED_TO_BE_DOWN, orchestrator.getNodeStatus(app1_host1));
        assertTrue(isInMaintenance(app1, app1_host1));
    }

    @Test
    public void applicationReferenceHasTenantAndAppInstance() {
        InstanceLookupService service = new DummyInstanceLookupService();
        String applicationInstanceId = service.findInstanceByHost(DummyInstanceLookupService.TEST1_HOST_NAME).get()
                .reference().toString();
        assertEquals("test-tenant-id:application:prod:utopia-1:instance", applicationInstanceId);
    }

    @Test
    public void sortHostNamesForSuspend() throws Exception {
        HostName parentHostName = new HostName("parentHostName");
        List<HostName> expectedOrder = Arrays.asList(
                DummyInstanceLookupService.TEST3_HOST_NAME,
                DummyInstanceLookupService.TEST1_HOST_NAME);

        assertEquals(expectedOrder, orchestrator.sortHostNamesForSuspend(Arrays.asList(
                DummyInstanceLookupService.TEST1_HOST_NAME,
                DummyInstanceLookupService.TEST3_HOST_NAME)));

        assertEquals(expectedOrder, orchestrator.sortHostNamesForSuspend(Arrays.asList(
                DummyInstanceLookupService.TEST3_HOST_NAME,
                DummyInstanceLookupService.TEST1_HOST_NAME)));
    }

    @Test
    public void partialSuspension() throws Exception {
        // A spy is preferential because suspendAll() relies on delegating the hard work to suspend() and resume().
        OrchestratorImpl orchestrator = spy(this.orchestrator);

        doNothing().when(orchestrator).suspendGroup(DummyInstanceLookupService.TEST3_NODE_GROUP);

        Throwable supensionFailure = new HostStateChangeDeniedException(
                DummyInstanceLookupService.TEST6_HOST_NAME,
                "some-constraint",
                new ServiceType("foo"),
                "error message");
        doThrow(supensionFailure).when(orchestrator).suspendGroup(DummyInstanceLookupService.TEST6_NODE_GROUP);

        try {
            orchestrator.suspendAll(
                    new HostName("parentHostname"),
                    Arrays.asList(
                            DummyInstanceLookupService.TEST1_HOST_NAME,
                            DummyInstanceLookupService.TEST3_HOST_NAME,
                            DummyInstanceLookupService.TEST6_HOST_NAME));
            fail();
        } catch (BatchHostStateChangeDeniedException e) {
            assertEquals("Failed to suspend "
                    + "NodeGroup{application=tenant-id-3:application-instance-3:prod:utopia-1:default, "
                    + "hostNames=[test6.prod.us-east-1.vespahosted.ne1.yahoo.com]} with parent host parentHostname: "
                    + "Changing the state of test6.prod.us-east-1.vespahosted.ne1.yahoo.com would violate "
                    + "some-constraint for service type foo: error message",
                    e.getMessage());
        }

        // As of 2016-06-07:
        //   TEST1_HOST_NAME: test-tenant-id:application:instance
        //   TEST3_HOST_NAME: mediasearch:imagesearch:default
        //   TEST6_HOST_NAME: tenant-id-3:application-instance-3:default
        // Meaning the order is 3, 6, then 1. For rollback/resume the order is reversed.
        InOrder order = inOrder(orchestrator);
        order.verify(orchestrator).suspendGroup(DummyInstanceLookupService.TEST3_NODE_GROUP);
        order.verify(orchestrator).suspendGroup(DummyInstanceLookupService.TEST6_NODE_GROUP);
        order.verifyNoMoreInteractions();
    }

    private boolean isInMaintenance(ApplicationId appId, HostName hostName) throws ApplicationIdNotFoundException {
        for (ApplicationInstance<ServiceMonitorStatus> app : DummyInstanceLookupService.getApplications()) {
            if (app.reference().equals(OrchestratorUtil.toApplicationInstanceReference(appId,new DummyInstanceLookupService()))) {
                return clustercontroller.isInMaintenance(app, hostName);
            }
        }
        throw new ApplicationIdNotFoundException();
    }
}
