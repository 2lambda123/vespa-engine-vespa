// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.handler;

import com.google.inject.Inject;
import com.yahoo.component.AbstractComponent;

import java.util.HashMap;
import java.util.Map;

/**
 * A component which tracks the up/down status of any clusters which should influence
 * the up down status of this container itself, as well as the separate fact that such clusters are present.
 *
 * This is a separate component which has <b>no dependencies</b> such that the status tracked in this
 * will survive reconfiguration events and inform other components even immediately after a reconfiguration
 * (where the true statue of clusters may not yet be available).
 *
 * @author bratseth
 */
public class ClustersStatus extends AbstractComponent {

    // NO DEPENDENCIES: Do not add dependencies here
    @Inject
    public ClustersStatus() { }

    /** Are there any (in-service influencing) clusters in this container? */
    private boolean containerHasClusters;

    /** If we have no clusters, what should we answer? */
    private boolean receiveTrafficByDefault;

    private final Object mutex = new Object();

    /** The status of clusters, when known. Note that clusters may exist for which there is no knowledge yet. */
    private final Map<Object, Boolean> clusterStatus = new HashMap<>();

    public void setContainerHasClusters(boolean containerHasClusters) {
        synchronized (mutex) {
            this.containerHasClusters = containerHasClusters;
            if ( ! containerHasClusters)
                clusterStatus.clear(); // forget container clusters which was configured away
        }
    }

    public void setReceiveTrafficByDefault(boolean receiveTrafficByDefault) {
        synchronized (mutex) {
            this.receiveTrafficByDefault = receiveTrafficByDefault;
        }
    }

    public void setUp(Object clusterIdentifier) {
        synchronized (mutex) {
            clusterStatus.put(clusterIdentifier, Boolean.TRUE);
        }
    }

    public void setDown(Object clusterIdentifier) {
        synchronized (mutex) {
            clusterStatus.put(clusterIdentifier, Boolean.FALSE);
        }
    }

    /** Returns whether this container should receive traffic based on the state of this */
    public boolean containerShouldReceiveTraffic() {
        synchronized (mutex) {
            if (containerHasClusters) {
                // Should receive traffic when at least one cluster is up
                return clusterStatus.values().stream().anyMatch(status -> status==true);
            }
            else {
                return receiveTrafficByDefault;
            }
        }
    }

}
