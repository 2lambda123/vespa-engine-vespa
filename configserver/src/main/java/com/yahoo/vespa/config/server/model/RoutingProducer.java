// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.model;

import com.yahoo.cloud.config.RoutingConfig;
import com.yahoo.config.model.api.HostInfo;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.TenantName;
import com.yahoo.vespa.config.server.application.Application;

import java.util.Map;

/**
 * Create global config based on info from the zone application
 *
 * @author can
 * @since 5.60
 */
public class RoutingProducer implements RoutingConfig.Producer {

    private final Map<TenantName, Map<ApplicationId, Application>> models;

    public RoutingProducer(Map<TenantName, Map<ApplicationId, Application>> models) {
        this.models = models;
    }

    @Override
    public void getConfig(RoutingConfig.Builder builder) {
        for (Map<ApplicationId, Application> model : models.values()) {
            model.values().stream().filter(application -> application.getId().isHostedVespaRoutingApplication()).forEach(application -> {
                for (HostInfo host : application.getModel().getHosts()) {
                    builder.hosts(host.getHostname());
                }
            });
        }
    }
}
