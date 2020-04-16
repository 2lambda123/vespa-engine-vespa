// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.orchestrator.model;

import com.yahoo.vespa.orchestrator.controller.ClusterControllerClientFactory;
import com.yahoo.vespa.orchestrator.status.ApplicationLock;

/**
 * @author mpolden
 */
public class ApplicationApiFactory {

    private final int numberOfConfigServers;

    public ApplicationApiFactory(int numberOfConfigServers) {
        this.numberOfConfigServers = numberOfConfigServers;
    }

    public ApplicationApi create(NodeGroup nodeGroup,
                                 ApplicationLock lock,
                                 ClusterControllerClientFactory clusterControllerClientFactory) {
        return new ApplicationApiImpl(nodeGroup, lock, clusterControllerClientFactory, numberOfConfigServers);
    }

}
