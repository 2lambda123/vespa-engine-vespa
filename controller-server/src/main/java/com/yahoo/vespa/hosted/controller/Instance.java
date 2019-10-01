// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller;

import com.google.common.collect.ImmutableMap;
import com.yahoo.component.Version;
import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.application.api.ValidationOverrides;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.api.integration.organization.IssueId;
import com.yahoo.vespa.hosted.controller.api.integration.organization.User;
import com.yahoo.vespa.hosted.controller.application.ApplicationActivity;
import com.yahoo.vespa.hosted.controller.application.AssignedRotation;
import com.yahoo.vespa.hosted.controller.application.Change;
import com.yahoo.vespa.hosted.controller.application.ClusterInfo;
import com.yahoo.vespa.hosted.controller.application.ClusterUtilization;
import com.yahoo.vespa.hosted.controller.application.Deployment;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs;
import com.yahoo.vespa.hosted.controller.application.DeploymentMetrics;
import com.yahoo.vespa.hosted.controller.application.EndpointId;
import com.yahoo.vespa.hosted.controller.application.EndpointList;
import com.yahoo.vespa.hosted.controller.application.JobStatus;
import com.yahoo.vespa.hosted.controller.application.TenantAndApplicationId;
import com.yahoo.vespa.hosted.controller.metric.ApplicationMetrics;
import com.yahoo.vespa.hosted.controller.rotation.RotationStatus;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
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
    private final Instant createdAt;
    private final DeploymentSpec deploymentSpec;
    private final ValidationOverrides validationOverrides;
    private final Map<ZoneId, Deployment> deployments;
    private final DeploymentJobs deploymentJobs;
    private final Change change;
    private final Change outstandingChange;
    private final Optional<IssueId> ownershipIssueId;
    private final Optional<User> owner;
    private final OptionalInt majorVersion;
    private final ApplicationMetrics metrics;
    private final Optional<String> pemDeployKey;
    private final List<AssignedRotation> rotations;
    private final RotationStatus rotationStatus;

    /** Creates an empty instance*/
    public Instance(ApplicationId id, Instant now) {
        this(id, now, DeploymentSpec.empty, ValidationOverrides.empty, Collections.emptyMap(),
             new DeploymentJobs(OptionalLong.empty(), Collections.emptyList(), Optional.empty(), false),
             Change.empty(), Change.empty(), Optional.empty(), Optional.empty(), OptionalInt.empty(),
             new ApplicationMetrics(0, 0),
             Optional.empty(), Collections.emptyList(), RotationStatus.EMPTY);
    }

    /** Creates an empty instance*/
    public Instance(ApplicationId id, List<Deployment> deployments, DeploymentJobs deploymentJobs,
                    List<AssignedRotation> rotations, RotationStatus rotationStatus) {
        this(id,
             Instant.EPOCH, DeploymentSpec.empty, ValidationOverrides.empty,
             deployments.stream().collect(Collectors.toMap(Deployment::zone, Function.identity())),
             deploymentJobs,
             Change.empty(), Change.empty(), Optional.empty(), Optional.empty(), OptionalInt.empty(),
             new ApplicationMetrics(0, 0), Optional.empty(),
             rotations, rotationStatus);
    }

    /** Used from persistence layer: Do not use */
    public Instance(ApplicationId id, Instant createdAt, DeploymentSpec deploymentSpec, ValidationOverrides validationOverrides,
                    List<Deployment> deployments, DeploymentJobs deploymentJobs, Change change,
                    Change outstandingChange, Optional<IssueId> ownershipIssueId, Optional<User> owner,
                    OptionalInt majorVersion, ApplicationMetrics metrics, Optional<String> pemDeployKey,
                    List<AssignedRotation> rotations, RotationStatus rotationStatus) {
        this(id, createdAt, deploymentSpec, validationOverrides,
             deployments.stream().collect(Collectors.toMap(Deployment::zone, Function.identity())),
             deploymentJobs, change, outstandingChange, ownershipIssueId, owner, majorVersion,
             metrics, pemDeployKey, rotations, rotationStatus);
    }

    Instance(ApplicationId id, Instant createdAt, DeploymentSpec deploymentSpec, ValidationOverrides validationOverrides,
             Map<ZoneId, Deployment> deployments, DeploymentJobs deploymentJobs, Change change,
             Change outstandingChange, Optional<IssueId> ownershipIssueId, Optional<User> owner,
             OptionalInt majorVersion, ApplicationMetrics metrics, Optional<String> pemDeployKey,
             List<AssignedRotation> rotations, RotationStatus rotationStatus) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.createdAt = Objects.requireNonNull(createdAt, "instant of creation cannot be null");
        this.deploymentSpec = Objects.requireNonNull(deploymentSpec, "deploymentSpec cannot be null");
        this.validationOverrides = Objects.requireNonNull(validationOverrides, "validationOverrides cannot be null");
        this.deployments = ImmutableMap.copyOf(Objects.requireNonNull(deployments, "deployments cannot be null"));
        this.deploymentJobs = Objects.requireNonNull(deploymentJobs, "deploymentJobs cannot be null");
        this.change = Objects.requireNonNull(change, "change cannot be null");
        this.outstandingChange = Objects.requireNonNull(outstandingChange, "outstandingChange cannot be null");
        this.ownershipIssueId = Objects.requireNonNull(ownershipIssueId, "ownershipIssueId cannot be null");
        this.owner = Objects.requireNonNull(owner, "owner cannot be null");
        this.majorVersion = Objects.requireNonNull(majorVersion, "majorVersion cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
        this.pemDeployKey = pemDeployKey;
        this.rotations = List.copyOf(Objects.requireNonNull(rotations, "rotations cannot be null"));
        this.rotationStatus = Objects.requireNonNull(rotationStatus, "rotationStatus cannot be null");
    }

    public Instance withJobPause(JobType jobType, OptionalLong pausedUntil) {
        return new Instance(id, createdAt, deploymentSpec, validationOverrides, deployments,
                            deploymentJobs.withPause(jobType, pausedUntil), change, outstandingChange,
                            ownershipIssueId, owner, majorVersion, metrics, pemDeployKey,
                            rotations, rotationStatus);
    }

    public Instance withJobCompletion(JobType jobType, JobStatus.JobRun completion,
                                      Optional<DeploymentJobs.JobError> jobError) {
        return new Instance(id, createdAt, deploymentSpec, validationOverrides, deployments,
                            deploymentJobs.withCompletion(jobType, completion, jobError),
                            change, outstandingChange, ownershipIssueId, owner, majorVersion, metrics,
                            pemDeployKey, rotations, rotationStatus);
    }

    public Instance withJobTriggering(JobType jobType, JobStatus.JobRun job) {
        return new Instance(id, createdAt, deploymentSpec, validationOverrides, deployments,
                            deploymentJobs.withTriggering(jobType, job), change, outstandingChange,
                            ownershipIssueId, owner, majorVersion, metrics, pemDeployKey,
                            rotations, rotationStatus);
    }

    public Instance withNewDeployment(ZoneId zone, ApplicationVersion applicationVersion, Version version,
                                      Instant instant, Map<DeploymentMetrics.Warning, Integer> warnings) {
        // Use info from previous deployment if available, otherwise create a new one.
        Deployment previousDeployment = deployments.getOrDefault(zone, new Deployment(zone, applicationVersion,
                                                                                      version, instant));
        Deployment newDeployment = new Deployment(zone, applicationVersion, version, instant,
                                                  previousDeployment.clusterUtils(),
                                                  previousDeployment.clusterInfo(),
                                                  previousDeployment.metrics().with(warnings),
                                                  previousDeployment.activity());
        return with(newDeployment);
    }

    public Instance withClusterUtilization(ZoneId zone, Map<ClusterSpec.Id, ClusterUtilization> clusterUtilization) {
        Deployment deployment = deployments.get(zone);
        if (deployment == null) return this;    // No longer deployed in this zone.
        return with(deployment.withClusterUtils(clusterUtilization));
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
        return new Instance(id, createdAt, deploymentSpec, validationOverrides, deployments,
                            deploymentJobs.without(jobType), change, outstandingChange,
                            ownershipIssueId, owner, majorVersion, metrics, pemDeployKey,
                            rotations, rotationStatus);
    }

    public Instance with(ApplicationMetrics metrics) {
        return new Instance(id, createdAt, deploymentSpec, validationOverrides, deployments,
                            deploymentJobs, change, outstandingChange, ownershipIssueId, owner, majorVersion,
                            metrics, pemDeployKey, rotations, rotationStatus);
    }

    public Instance with(List<AssignedRotation> assignedRotations) {
        return new Instance(id, createdAt, deploymentSpec, validationOverrides, deployments,
                            deploymentJobs, change, outstandingChange, ownershipIssueId, owner, majorVersion,
                            metrics, pemDeployKey, assignedRotations, rotationStatus);
    }

    public Instance with(RotationStatus rotationStatus) {
        return new Instance(id, createdAt, deploymentSpec, validationOverrides, deployments,
                            deploymentJobs, change, outstandingChange, ownershipIssueId, owner, majorVersion,
                            metrics, pemDeployKey, rotations, rotationStatus);
    }

    private Instance with(Deployment deployment) {
        Map<ZoneId, Deployment> deployments = new LinkedHashMap<>(this.deployments);
        deployments.put(deployment.zone(), deployment);
        return with(deployments);
    }

    private Instance with(Map<ZoneId, Deployment> deployments) {
        return new Instance(id, createdAt, deploymentSpec, validationOverrides, deployments,
                            deploymentJobs, change, outstandingChange, ownershipIssueId, owner, majorVersion,
                            metrics, pemDeployKey, rotations, rotationStatus);
    }

    public ApplicationId id() { return id; }

    public InstanceName name() { return id.instance(); }

    public Instant createdAt() { return createdAt; }

    /**
     * Returns the last deployed deployment spec of this application,
     * or the empty deployment spec if it has never been deployed
     */
    public DeploymentSpec deploymentSpec() { return deploymentSpec; }

    /**
     * Returns the last deployed validation overrides of this application,
     * or the empty validation overrides if it has never been deployed
     * (or was deployed with an empty/missing validation overrides)
     */
    public ValidationOverrides validationOverrides() { return validationOverrides; }

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

    /**
     * Returns base change for this application, i.e., the change that is deployed outside block windows.
     * This is empty when no change is currently under deployment.
     */
    public Change change() { return change; }

    /**
     * Returns whether this has an outstanding change (in the source repository), which
     * has currently not started deploying (because a deployment is (or was) already in progress
     */
    public Change outstandingChange() { return outstandingChange; }

    /** Returns ID of the last ownership issue filed for this */
    public Optional<IssueId> ownershipIssueId() {
        return ownershipIssueId;
    }

    public Optional<User> owner() {
        return owner;
    }

    /**
     * Overrides the system major version for this application. This override takes effect if the deployment
     * spec does not specify a major version.
     */
    public OptionalInt majorVersion() { return majorVersion; }

    /** Returns metrics for this */
    public ApplicationMetrics metrics() {
        return metrics;
    }

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

    public Optional<String> pemDeployKey() { return pemDeployKey; }

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
