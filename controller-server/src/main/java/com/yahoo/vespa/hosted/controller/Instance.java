// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller;

import com.google.common.collect.ImmutableMap;
import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.application.AssignedRotation;
import com.yahoo.vespa.hosted.controller.application.ClusterInfo;
import com.yahoo.vespa.hosted.controller.application.Deployment;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs;
import com.yahoo.vespa.hosted.controller.application.DeploymentMetrics;
import com.yahoo.vespa.hosted.controller.application.EndpointId;
import com.yahoo.vespa.hosted.controller.application.EndpointList;
import com.yahoo.vespa.hosted.controller.application.JobStatus;
import com.yahoo.vespa.hosted.controller.rotation.RotationStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An instance of an application.
 *
 * This is immutable.
 *
 * @author bratseth
 */
public class Instance {

    private final ApplicationId id;
    private final Map<ZoneId, Deployment> deployments;
    private final DeploymentJobs deploymentJobs;
    private final List<AssignedRotation> rotations;
    private final RotationStatus rotationStatus;

    /** Creates an empty instance */
    public Instance(ApplicationId id) {
        this(id, Set.of(), new DeploymentJobs(List.of()),
             List.of(), RotationStatus.EMPTY);
    }

    /** Creates an empty instance*/
    public Instance(ApplicationId id, Collection<Deployment> deployments, DeploymentJobs deploymentJobs,
                    List<AssignedRotation> rotations, RotationStatus rotationStatus) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.deployments = ImmutableMap.copyOf(Objects.requireNonNull(deployments, "deployments cannot be null").stream()
                                                      .collect(Collectors.toMap(Deployment::zone, Function.identity())));
        this.deploymentJobs = Objects.requireNonNull(deploymentJobs, "deploymentJobs cannot be null");
        this.rotations = List.copyOf(Objects.requireNonNull(rotations, "rotations cannot be null"));
        this.rotationStatus = Objects.requireNonNull(rotationStatus, "rotationStatus cannot be null");
    }

    public Instance withJobPause(JobType jobType, OptionalLong pausedUntil) {
        return new Instance(id, deployments.values(), deploymentJobs.withPause(jobType, pausedUntil),
                            rotations, rotationStatus);
    }

    public Instance withJobCompletion(JobType jobType, JobStatus.JobRun completion, Optional<DeploymentJobs.JobError> jobError) {
        return new Instance(id, deployments.values(), deploymentJobs.withCompletion(jobType, completion, jobError),
                            rotations, rotationStatus);
    }

    public Instance withJobTriggering(JobType jobType, JobStatus.JobRun job) {
        return new Instance(id, deployments.values(), deploymentJobs.withTriggering(jobType, job),
                            rotations, rotationStatus);
    }

    public Instance withNewDeployment(ZoneId zone, ApplicationVersion applicationVersion, Version version,
                                      Instant instant, Map<DeploymentMetrics.Warning, Integer> warnings) {
        // Use info from previous deployment if available, otherwise create a new one.
        Deployment previousDeployment = deployments.getOrDefault(zone, new Deployment(zone, applicationVersion,
                                                                                      version, instant));
        Deployment newDeployment = new Deployment(zone, applicationVersion, version, instant,
                                                  previousDeployment.clusterInfo(),
                                                  previousDeployment.metrics().with(warnings),
                                                  previousDeployment.activity());
        return with(newDeployment);
    }

    public Instance withClusterInfo(ZoneId zone, Map<ClusterSpec.Id, ClusterInfo> clusterInfo) {
        Deployment deployment = deployments.get(zone);
        if (deployment == null) return this;    // No longer deployed in this zone.
        return with(deployment.withClusterInfo(clusterInfo));
    }

    public Instance recordActivityAt(Instant instant, ZoneId zone) {
        Deployment deployment = deployments.get(zone);
        if (deployment == null) return this;
        return with(deployment.recordActivityAt(instant));
    }

    public Instance with(ZoneId zone, DeploymentMetrics deploymentMetrics) {
        Deployment deployment = deployments.get(zone);
        if (deployment == null) return this;    // No longer deployed in this zone.
        return with(deployment.withMetrics(deploymentMetrics));
    }

    public Instance withoutDeploymentIn(ZoneId zone) {
        Map<ZoneId, Deployment> deployments = new LinkedHashMap<>(this.deployments);
        deployments.remove(zone);
        return with(deployments);
    }

    public Instance withoutDeploymentJob(JobType jobType) {
        return new Instance(id, deployments.values(), deploymentJobs.without(jobType),
                            rotations, rotationStatus);
    }

    public Instance with(List<AssignedRotation> assignedRotations) {
        return new Instance(id, deployments.values(), deploymentJobs,
                            assignedRotations, rotationStatus);
    }

    public Instance with(RotationStatus rotationStatus) {
        return new Instance(id, deployments.values(), deploymentJobs,
                            rotations, rotationStatus);
    }

    private Instance with(Deployment deployment) {
        Map<ZoneId, Deployment> deployments = new LinkedHashMap<>(this.deployments);
        deployments.put(deployment.zone(), deployment);
        return with(deployments);
    }

    private Instance with(Map<ZoneId, Deployment> deployments) {
        return new Instance(id, deployments.values(), deploymentJobs,
                            rotations, rotationStatus);
    }

    public ApplicationId id() { return id; }

    public InstanceName name() { return id.instance(); }

    /** Returns an immutable map of the current deployments of this */
    public Map<ZoneId, Deployment> deployments() { return deployments; }

    /**
     * Returns an immutable map of the current *production* deployments of this
     * (deployments also includes manually deployed environments)
     */
    public Map<ZoneId, Deployment> productionDeployments() {
        return ImmutableMap.copyOf(deployments.values().stream()
                                           .filter(deployment -> deployment.zone().environment() == Environment.prod)
                                           .collect(Collectors.toMap(Deployment::zone, Function.identity())));
    }

    public DeploymentJobs deploymentJobs() { return deploymentJobs; }

    /** Returns all rotations assigned to this */
    public List<AssignedRotation> rotations() {
        return rotations;
    }

    /** Returns the default global endpoints for this in given system - for a given endpoint ID */
    public EndpointList endpointsIn(SystemName system, EndpointId endpointId) {
        if (rotations.isEmpty()) return EndpointList.EMPTY;
        return EndpointList.create(id, endpointId, system);
    }

    /** Returns the default global endpoints for this in given system */
    public EndpointList endpointsIn(SystemName system) {
        if (rotations.isEmpty()) return EndpointList.EMPTY;
        final var endpointStream = rotations.stream()
                .flatMap(rotation -> EndpointList.create(id, rotation.endpointId(), system).asList().stream());
        return EndpointList.of(endpointStream);
    }

    /** Returns the status of the global rotation(s) assigned to this */
    public RotationStatus rotationStatus() {
        return rotationStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (! (o instanceof Instance)) return false;

        Instance that = (Instance) o;

        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "application '" + id + "'";
    }

}
