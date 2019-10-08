// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.application;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ClusterSpec.Id;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.config.provision.zone.ZoneId;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * A deployment of an application in a particular zone.
 * 
 * @author bratseth
 * @author smorgrav
 */
public class Deployment {

    private final ZoneId zone;
    private final ApplicationVersion applicationVersion;
    private final Version version;
    private final Instant deployTime;
    private final Map<Id, ClusterInfo> clusterInfo;
    private final DeploymentMetrics metrics;
    private final DeploymentActivity activity;

    public Deployment(ZoneId zone, ApplicationVersion applicationVersion, Version version, Instant deployTime) {
        this(zone, applicationVersion, version, deployTime, Collections.emptyMap(),
             DeploymentMetrics.none, DeploymentActivity.none);
    }

    public Deployment(ZoneId zone, ApplicationVersion applicationVersion, Version version, Instant deployTime,
                      Map<Id, ClusterInfo> clusterInfo,
                      DeploymentMetrics metrics,
                      DeploymentActivity activity) {
        this.zone = Objects.requireNonNull(zone, "zone cannot be null");
        this.applicationVersion = Objects.requireNonNull(applicationVersion, "applicationVersion cannot be null");
        this.version = Objects.requireNonNull(version, "version cannot be null");
        this.deployTime = Objects.requireNonNull(deployTime, "deployTime cannot be null");
        this.clusterInfo = Map.copyOf(Objects.requireNonNull(clusterInfo, "clusterInfo cannot be null"));
        this.metrics = Objects.requireNonNull(metrics, "deploymentMetrics cannot be null");
        this.activity = Objects.requireNonNull(activity, "activity cannot be null");
    }

    /** Returns the zone this was deployed to */
    public ZoneId zone() { return zone; }

    /** Returns the deployed application version */
    public ApplicationVersion applicationVersion() { return applicationVersion; }
    
    /** Returns the deployed Vespa version */
    public Version version() { return version; }

    /** Returns the time this was deployed */
    public Instant at() { return deployTime; }

    /** Returns metrics for this */
    public DeploymentMetrics metrics() {
        return metrics;
    }

    /** Returns activity for this */
    public DeploymentActivity activity() { return activity; }

    /** Returns information about the clusters allocated to this */
    public Map<Id, ClusterInfo> clusterInfo() {
        return clusterInfo;
    }

    public Deployment recordActivityAt(Instant instant) {
        return new Deployment(zone, applicationVersion, version, deployTime, clusterInfo, metrics,
                              activity.recordAt(instant, metrics));
    }

    public Deployment withClusterUtils() {
        return new Deployment(zone, applicationVersion, version, deployTime, clusterInfo, metrics,
                              activity);
    }

    public Deployment withClusterInfo(Map<Id, ClusterInfo> newClusterInfo) {
        return new Deployment(zone, applicationVersion, version, deployTime, newClusterInfo, metrics,
                              activity);
    }

    public Deployment withMetrics(DeploymentMetrics metrics) {
        return new Deployment(zone, applicationVersion, version, deployTime, clusterInfo, metrics,
                              activity);
    }

    @Override
    public String toString() {
        return "deployment to " + zone + " of " + applicationVersion + " on version " + version + " at " + deployTime;
    }

}
