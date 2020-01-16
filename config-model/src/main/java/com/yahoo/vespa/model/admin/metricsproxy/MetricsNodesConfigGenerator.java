// Copyright 2020 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

package com.yahoo.vespa.model.admin.metricsproxy;

import ai.vespa.metricsproxy.http.application.MetricsNodesConfig;
import ai.vespa.metricsproxy.http.metrics.MetricsHandler;
import com.yahoo.config.provision.ClusterMembership;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author gjoranv
 */
public class MetricsNodesConfigGenerator {

    public static List<MetricsNodesConfig.Node.Builder> generate(List<MetricsProxyContainer> containers) {
        return containers.stream()
                .map(MetricsNodesConfigGenerator::toNodeBuilder)
                .collect(Collectors.toList());
    }

    private static MetricsNodesConfig.Node.Builder toNodeBuilder(MetricsProxyContainer container) {
        var builder = new MetricsNodesConfig.Node.Builder()
                .nodeId(container.getHost().getConfigId())
                .hostname(container.getHostName())
                .metricsPort(MetricsProxyContainer.BASEPORT)
                .metricsPath(MetricsHandler.VALUES_PATH);

        if (container.isHostedVespa)
            container.getHostResource().spec().membership()
                    .map(ClusterMembership::stringValue)
                    .ifPresent(builder::nodeId);

        return builder;
    }

}
