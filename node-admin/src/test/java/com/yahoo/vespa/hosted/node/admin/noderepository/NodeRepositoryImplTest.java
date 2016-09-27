// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

package com.yahoo.vespa.hosted.node.admin.noderepository;

import com.google.common.collect.Sets;
import com.yahoo.application.Networking;
import com.yahoo.application.container.JDisc;
import com.yahoo.vespa.hosted.node.admin.ContainerNodeSpec;
import com.yahoo.vespa.hosted.dockerapi.ContainerName;
import com.yahoo.vespa.hosted.dockerapi.DockerImage;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAttributes;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.testutils.ContainerConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/**
 * Tests the NodeRepository class used for talking to the node repository. It uses a mock from the node repository
 * which already contains some data.
 *
 * @author dybdahl
 */
public class NodeRepositoryImplTest {
    private JDisc container;
    private int port;
    private final Set<String> configServerHosts = Sets.newHashSet("127.0.0.1");


    private int findRandomOpenPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Starts NodeRepository with
     *   {@link com.yahoo.vespa.hosted.provision.testutils.MockNodeFlavors}
     *   {@link com.yahoo.vespa.hosted.provision.testutils.MockNodeRepository}
     *   {@link com.yahoo.vespa.hosted.provision.restapi.v2.NodesApiHandler}
     * These classes define some test data that is used in these tests.
     */
    @Before
    public void startContainer() throws Exception {
        port = findRandomOpenPort();
        System.err.println("PORT IS " + port);
        container = JDisc.fromServicesXml(ContainerConfig.servicesXmlV2(port), Networking.enable);
    }

    private void waitForJdiscContainerToServe() throws InterruptedException {
        Instant start = Instant.now();
        NodeRepository nodeRepositoryApi = new NodeRepositoryImpl(Sets.newHashSet("127.0.0.1"), port, "foobar");
        while (Instant.now().minusSeconds(120).isBefore(start)) {
            try {
                nodeRepositoryApi.getContainersToRun();
                return;
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        throw new RuntimeException("Could not get answer from container.");
    }

    @After
    public void stopContainer() {
        if (container != null) {
            container.close();
        }
    }

    @Test
    public void testGetContainersToRunAPi() throws IOException, InterruptedException {
        waitForJdiscContainerToServe();
        NodeRepository nodeRepositoryApi = new NodeRepositoryImpl(configServerHosts, port, "dockerhost4");
        final List<ContainerNodeSpec> containersToRun = nodeRepositoryApi.getContainersToRun();
        assertThat(containersToRun.size(), is(1));
        final ContainerNodeSpec nodeSpec = containersToRun.get(0);
        assertThat(nodeSpec.hostname, is("host4.yahoo.com"));
        assertThat(nodeSpec.wantedDockerImage.get(), is(new DockerImage("image-123")));
        assertThat(nodeSpec.containerName, is(new ContainerName("host4")));
        assertThat(nodeSpec.nodeState, is(Node.State.reserved));
        assertThat(nodeSpec.wantedRestartGeneration.get(), is(0L));
        assertThat(nodeSpec.currentRestartGeneration.get(), is(0L));
        assertThat(nodeSpec.minCpuCores.get(), is(2.0));
        assertThat(nodeSpec.minMainMemoryAvailableGb.get(), is(16.0));
        assertThat(nodeSpec.minDiskAvailableGb.get(), is(400.0));
    }

    @Test
    public void testGetContainers() throws InterruptedException, IOException {
        waitForJdiscContainerToServe();
        NodeRepository nodeRepositoryApi = new NodeRepositoryImpl(configServerHosts, port, "dockerhost4");
        String hostname = "host4.yahoo.com";
        Optional<ContainerNodeSpec> nodeSpec = nodeRepositoryApi.getContainerNodeSpec(hostname);
        assertThat(nodeSpec.isPresent(), is(true));
        assertThat(nodeSpec.get().hostname, is(hostname));
        assertThat(nodeSpec.get().containerName, is(new ContainerName("host4")));
    }

    @Test
    public void testUpdateNodeAttributes() throws InterruptedException, IOException {
        waitForJdiscContainerToServe();
        NodeRepository nodeRepositoryApi = new NodeRepositoryImpl(configServerHosts, port, "dockerhost4");
        String hostname = "host4.yahoo.com";
        nodeRepositoryApi.updateNodeAttributes(
                hostname,
                new NodeAttributes()
                        .withRestartGeneration(1L)
                        .withDockerImage(new DockerImage("image-1"))
                        .withVespaVersion("6.2.3"));
    }

    @Test(expected = RuntimeException.class)
    public void testUpdateNodeAttributesWithBadValue() throws InterruptedException, IOException {
        waitForJdiscContainerToServe();
        NodeRepository nodeRepositoryApi = new NodeRepositoryImpl(configServerHosts, port, "dockerhost4");
        String hostname = "host4.yahoo.com";
        nodeRepositoryApi.updateNodeAttributes(
                hostname,
                new NodeAttributes()
                        .withRestartGeneration(1L)
                        .withDockerImage(new DockerImage("image-1"))
                        .withVespaVersion("6.2.3\n"));
    }

    @Test
    public void testMarkAsReady() throws InterruptedException, IOException {
        NodeRepository nodeRepositoryApi = new NodeRepositoryImpl(configServerHosts, port, "dockerhost4");
        waitForJdiscContainerToServe();

        nodeRepositoryApi.markAsReady("host55.yahoo.com");

        try {
            nodeRepositoryApi.markAsReady("host1.yahoo.com");
            fail("Expected failure because host1 is not registered as provisioned, dirty, failed or parked");
        } catch (RuntimeException ignored) {
            // expected
        }

        try {
            nodeRepositoryApi.markAsReady("host101.yahoo.com");
            fail("Expected failure because host101 does not exist");
        } catch (RuntimeException ignored) {
            // expected
        }
    }
}
