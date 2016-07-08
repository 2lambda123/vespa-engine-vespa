// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.container;

import com.yahoo.cloud.config.ClusterInfoConfig;
import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.cloud.config.RoutingProviderConfig;
import com.yahoo.config.model.deploy.DeployProperties;
import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.config.model.test.MockRoot;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.Zone;
import com.yahoo.container.handler.ThreadpoolConfig;
import com.yahoo.container.jdisc.config.MetricDefaultsConfig;
import com.yahoo.search.config.QrStartConfig;
import com.yahoo.vespa.model.Host;
import com.yahoo.vespa.model.HostResource;
import com.yahoo.vespa.model.admin.clustercontroller.ClusterControllerContainer;
import com.yahoo.vespa.model.container.docproc.ContainerDocproc;
import com.yahoo.vespa.model.container.search.ContainerSearch;
import com.yahoo.vespa.model.container.search.searchchain.SearchChains;
import org.junit.Test;

import java.util.Collections;
import java.util.Iterator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author <a href="mailto:simon@yahoo-inc.com">Simon Thoresen Hult</a>
 */
public class ContainerClusterTest {

    @Test
    public void requireThatDefaultMetricConsumerFactoryCanBeConfigured() {
        ContainerCluster cluster = newContainerCluster();
        cluster.setDefaultMetricConsumerFactory(MetricDefaultsConfig.Factory.Enum.YAMAS_SCOREBOARD);
        assertEquals(MetricDefaultsConfig.Factory.Enum.YAMAS_SCOREBOARD,
                     getMetricDefaultsConfig(cluster).factory());
    }

    @Test
    public void requireThatDefaultMetricConsumerFactoryMatchesConfigDefault() {
        ContainerCluster cluster = newContainerCluster();
        assertEquals(new MetricDefaultsConfig(new MetricDefaultsConfig.Builder()).factory(),
                     getMetricDefaultsConfig(cluster).factory());
    }

    @Test
    public void requireThatClusterInfoIsPopulated() {
        ContainerCluster cluster = newContainerCluster();
        ClusterInfoConfig config = getClusterInfoConfig(cluster);
        assertEquals("name", config.clusterId());
        assertEquals(2, config.nodeCount());
        assertEquals(2, config.services().size());

        Iterator<ClusterInfoConfig.Services> iterator = config.services().iterator();
        ClusterInfoConfig.Services service = iterator.next();
        assertEquals("host-c1", service.hostname());
        assertEquals(0, service.index());
        assertEquals(4, service.ports().size());

        service = iterator.next();
        assertEquals("host-c2", service.hostname());
        assertEquals(1, service.index());
        assertEquals(4, service.ports().size());
    }

    @Test
    public void requreThatWeCanGetTheZoneConfig() {
        DeployState state = new DeployState.Builder().properties(new DeployProperties.Builder().hostedVespa(true).build())
                                                     .zone(new Zone(Environment.test, RegionName.from("some-region"))).build();
        MockRoot root = new MockRoot("foo", state);
        ContainerCluster cluster = new ContainerCluster(root, "container0", "container1");
        ConfigserverConfig.Builder builder = new ConfigserverConfig.Builder();
        cluster.getConfig(builder);
        ConfigserverConfig config = new ConfigserverConfig(builder);
        assertEquals(Environment.test.value(), config.environment());
        assertEquals("some-region", config.region());
    }

    private ContainerCluster createContainerCluster(boolean isHosted) {
        DeployState state = new DeployState.Builder().properties(new DeployProperties.Builder().hostedVespa(isHosted).build()).build();
        MockRoot root = new MockRoot("foo", state);
        ContainerCluster cluster = new ContainerCluster(root, "container0", "container1");
        cluster.setSearch(new ContainerSearch(cluster, new SearchChains(cluster, "search-chain"), new ContainerSearch.Options()));
        return cluster;
    }
    private void verifyHeapSizeAsPercentageOfPhysicalMemory(boolean isHosted, int percentage) {
        ContainerCluster cluster = createContainerCluster(isHosted);

        QrStartConfig.Builder qsB = new QrStartConfig.Builder();
        cluster.getSearch().getConfig(qsB);
        QrStartConfig qsC= new QrStartConfig(qsB);
        assertEquals(percentage, qsC.jvm().heapSizeAsPercentageOfPhysicalMemory());
    }

    @Test
    public void requireThatHeapSizeAsPercentageOfPhysicalMemoryForHostedAndNot() {
        verifyHeapSizeAsPercentageOfPhysicalMemory(true, 33);
        verifyHeapSizeAsPercentageOfPhysicalMemory(false, 0);
    }

    private void verifyJvmArgs(boolean isHosted, boolean hasDocproc, String expectedArgs, String jvmArgs) {
        if (isHosted && hasDocproc) {
            String defaultHostedJVMArgs = "-XX:+UseOSErrorReporting -XX:+SuppressFatalErrorMessage";
            if ( ! "".equals(expectedArgs)) {
                defaultHostedJVMArgs = defaultHostedJVMArgs + " ";
            }
            assertEquals(defaultHostedJVMArgs + expectedArgs, jvmArgs);
        } else {
            assertEquals(expectedArgs, jvmArgs);
        }
    }
    private void verifyJvmArgs(boolean isHosted, boolean hasDocProc) {
        ContainerCluster cluster = createContainerCluster(isHosted);
        if (hasDocProc) {
            cluster.setDocproc(new ContainerDocproc(cluster, null));
        }
        addContainer(cluster, "c1", "host-c1");
        assertEquals(1, cluster.getContainers().size());
        Container container = cluster.getContainers().get(0);
        verifyJvmArgs(isHosted, hasDocProc, "", container.getJvmArgs());
        container.setJvmArgs("initial");
        verifyJvmArgs(isHosted, hasDocProc, "initial", container.getJvmArgs());
        container.prependJvmArgs("ignored");
        verifyJvmArgs(isHosted, hasDocProc, "ignored initial", container.getJvmArgs());
        container.appendJvmArgs("override");
        verifyJvmArgs(isHosted, hasDocProc, "ignored initial override", container.getJvmArgs());
        container.setJvmArgs(null);
        verifyJvmArgs(isHosted, hasDocProc, "", container.getJvmArgs());
    }

    @Test
    public void testClusterControllerResourceUsage() {
        boolean isHosted = false;
        ContainerCluster cluster = createContainerCluster(isHosted);
        addClusterController(cluster, "host-c1");
        assertEquals(1, cluster.getContainers().size());
        ClusterControllerContainer container = (ClusterControllerContainer) cluster.getContainers().get(0);
        QrStartConfig.Builder qrBuilder = new QrStartConfig.Builder();
        container.getConfig(qrBuilder);
        QrStartConfig qrStartConfig = new QrStartConfig(qrBuilder);
        assertEquals(512, qrStartConfig.jvm().heapsize());

        ThreadpoolConfig.Builder tpBuilder = new ThreadpoolConfig.Builder();
        container.getConfig(tpBuilder);
        ThreadpoolConfig threadpoolConfig = new ThreadpoolConfig(tpBuilder);
        assertEquals(10, threadpoolConfig.maxthreads());
    }

    @Test
    public void requireThatJvmArgsControlWorksForHostedAndNot() {
        verifyJvmArgs(true, false);
        verifyJvmArgs(true, true);
        verifyJvmArgs(false, false);
        verifyJvmArgs(false, true);
    }

    @Test
    public void requireThatWeCanhandleNull() {
        ContainerCluster cluster = createContainerCluster(false);
        addContainer(cluster, "c1", "host-c1");
        Container container = cluster.getContainers().get(0);
        container.setJvmArgs("");
        String empty = container.getJvmArgs();
        container.setJvmArgs(null);
        assertEquals(empty, container.getJvmArgs());
    }

    @Test
    public void requireThatRoutingProviderIsDisabledForNonHosted() {
        DeployState state = new DeployState.Builder().properties(new DeployProperties.Builder().hostedVespa(false).build()).build();
        MockRoot root = new MockRoot("foo", state);
        ContainerCluster cluster = new ContainerCluster(root, "container0", "container1");
        RoutingProviderConfig.Builder builder = new RoutingProviderConfig.Builder();
        cluster.getConfig(builder);
        RoutingProviderConfig config = new RoutingProviderConfig(builder);
        assertFalse(config.enabled());
        assertEquals(0, cluster.getAllComponents().stream().map(c -> c.getClassId().getName()).filter(c -> c.equals("com.yahoo.jdisc.http.filter.security.RoutingConfigProvider")).count());
    }

    private static void addContainer(ContainerCluster cluster, String name, String hostName) {
        Container container = new Container(cluster, name, 0);
        container.setHostResource(new HostResource(new Host(null, hostName)));
        container.initService();
        cluster.addContainer(container);
    }

    private static void addClusterController(ContainerCluster cluster, String hostName) {
        Container container = new ClusterControllerContainer(cluster, 1, false);
        container.setHostResource(new HostResource(new Host(null, hostName)));
        container.initService();
        cluster.addContainer(container);
    }

    private static ContainerCluster newContainerCluster() {
        ContainerCluster cluster = new ContainerCluster(null, "subId", "name");
        addContainer(cluster, "c1", "host-c1");
        addContainer(cluster, "c2", "host-c2");
        return cluster;
    }

    private static MetricDefaultsConfig getMetricDefaultsConfig(ContainerCluster cluster) {
        MetricDefaultsConfig.Builder builder = new MetricDefaultsConfig.Builder();
        cluster.getConfig(builder);
        return new MetricDefaultsConfig(builder);
    }

    private static ClusterInfoConfig getClusterInfoConfig(ContainerCluster cluster) {
        ClusterInfoConfig.Builder builder = new ClusterInfoConfig.Builder();
        cluster.getConfig(builder);
        return new ClusterInfoConfig(builder);
    }

}
