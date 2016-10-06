// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.provider;

import com.yahoo.vespa.defaults.Defaults;
import com.yahoo.vespa.hosted.dockerapi.metrics.MetricReceiverWrapper;
import com.yahoo.vespa.hosted.node.admin.maintenance.StorageMaintainer;
import com.yahoo.vespa.hosted.node.admin.nodeadmin.NodeAdmin;
import com.yahoo.vespa.hosted.node.admin.nodeadmin.NodeAdminImpl;
import com.yahoo.vespa.hosted.node.admin.nodeadmin.NodeAdminStateUpdater;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgent;
import com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentImpl;
import com.yahoo.vespa.hosted.dockerapi.Docker;
import com.yahoo.vespa.hosted.node.admin.docker.DockerOperationsImpl;
import com.yahoo.vespa.hosted.node.admin.noderepository.NodeRepository;
import com.yahoo.vespa.hosted.node.admin.noderepository.NodeRepositoryImpl;
import com.yahoo.vespa.hosted.node.admin.orchestrator.Orchestrator;
import com.yahoo.vespa.hosted.node.admin.orchestrator.OrchestratorImpl;
import com.yahoo.vespa.hosted.node.admin.util.Environment;

import java.util.Set;
import java.util.function.Function;

/**
 * Set up node admin for production.
 *
 * @author dybis
 */
public class ComponentsProviderImpl implements ComponentsProvider {

    private final NodeAdminStateUpdater nodeAdminStateUpdater;
    private final MetricReceiverWrapper metricReceiverWrapper;

    private static final long INITIAL_SCHEDULER_DELAY_MILLIS = 1;
    private static final int NODE_AGENT_SCAN_INTERVAL_MILLIS = 30000;
    private static final int WEB_SERVICE_PORT = Defaults.getDefaults().vespaWebServicePort();
    private static final String ENV_HOSTNAME = "HOSTNAME";
    // We only scan for new nodes within a host every 5 minutes. This is only if new nodes are added or removed
    // which happens rarely. Changes of apps running etc it detected by the NodeAgent.
    private static final int NODE_ADMIN_STATE_INTERVAL_MILLIS = 5 * 60000;

    public ComponentsProviderImpl(final Docker docker, final MetricReceiverWrapper metricReceiver) {
        String baseHostName = java.util.Optional.ofNullable(System.getenv(ENV_HOSTNAME))
                .orElseThrow(() -> new IllegalStateException("Environment variable " + ENV_HOSTNAME + " unset"));

        Environment environment = new Environment();
        Set<String> configServerHosts = environment.getConfigServerHosts();

        Orchestrator orchestrator = new OrchestratorImpl(configServerHosts);
        NodeRepository nodeRepository = new NodeRepositoryImpl(configServerHosts, WEB_SERVICE_PORT, baseHostName);
        StorageMaintainer storageMaintainer = new StorageMaintainer();

        final Function<String, NodeAgent> nodeAgentFactory = (hostName) -> new NodeAgentImpl(hostName, nodeRepository,
                orchestrator, new DockerOperationsImpl(docker, environment), storageMaintainer, metricReceiver);
        final NodeAdmin nodeAdmin = new NodeAdminImpl(docker, nodeAgentFactory, storageMaintainer,
                NODE_AGENT_SCAN_INTERVAL_MILLIS, metricReceiver);
        nodeAdminStateUpdater = new NodeAdminStateUpdater(
                nodeRepository, nodeAdmin, INITIAL_SCHEDULER_DELAY_MILLIS, NODE_ADMIN_STATE_INTERVAL_MILLIS, orchestrator, baseHostName);

        metricReceiverWrapper = metricReceiver;
    }

    @Override
    public NodeAdminStateUpdater getNodeAdminStateUpdater() {
        return nodeAdminStateUpdater;
    }

    @Override
    public MetricReceiverWrapper getMetricReceiverWrapper() {
        return metricReceiverWrapper;
    }
}
