// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.orchestrator.policy;

public interface ClusterPolicy {
    /**
     * There's an implicit group of nodes known to clusterApi.  This method answers whether
     * it would be fine, just looking at this cluster (and disregarding Cluster Controller/storage
     * which is handled separately), whether it would be fine to allow all services on all the nodes
     * in the group to go down.
     *
     * @param clusterApi
     * @throws HostStateChangeDeniedException
     */
    void verifyGroupGoingDownIsFineForCluster(ClusterApi clusterApi) throws HostStateChangeDeniedException;
}
