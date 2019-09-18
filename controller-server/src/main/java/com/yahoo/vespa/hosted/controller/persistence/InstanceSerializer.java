// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.persistence;

import com.yahoo.component.Version;
import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.application.api.ValidationOverrides;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.slime.ArrayTraverser;
import com.yahoo.slime.Cursor;
import com.yahoo.slime.Inspector;
import com.yahoo.slime.ObjectTraverser;
import com.yahoo.slime.Slime;
import com.yahoo.vespa.hosted.controller.Instance;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.SourceRevision;
import com.yahoo.vespa.hosted.controller.api.integration.organization.IssueId;
import com.yahoo.vespa.hosted.controller.api.integration.organization.User;
import com.yahoo.vespa.hosted.controller.application.AssignedRotation;
import com.yahoo.vespa.hosted.controller.application.Change;
import com.yahoo.vespa.hosted.controller.application.ClusterInfo;
import com.yahoo.vespa.hosted.controller.application.ClusterUtilization;
import com.yahoo.vespa.hosted.controller.application.Deployment;
import com.yahoo.vespa.hosted.controller.application.DeploymentActivity;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs.JobError;
import com.yahoo.vespa.hosted.controller.application.DeploymentMetrics;
import com.yahoo.vespa.hosted.controller.application.EndpointId;
import com.yahoo.vespa.hosted.controller.application.JobStatus;
import com.yahoo.vespa.hosted.controller.metric.ApplicationMetrics;
import com.yahoo.vespa.hosted.controller.rotation.RotationId;
import com.yahoo.vespa.hosted.controller.rotation.RotationState;
import com.yahoo.vespa.hosted.controller.rotation.RotationStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Collectors;

/**
 * Serializes {@link Instance} to/from slime.
 * This class is multithread safe.
 *
 * @author bratseth
 */
public class InstanceSerializer {

    // WARNING: Since there are multiple servers in a ZooKeeper cluster and they upgrade one by one
    //          (and rewrite all nodes on startup), changes to the serialized format must be made
    //          such that what is serialized on version N+1 can be read by version N:
    //          - ADDING FIELDS: Always ok
    //          - REMOVING FIELDS: Stop reading the field first. Stop writing it on a later version.
    //          - CHANGING THE FORMAT OF A FIELD: Don't do it bro.

    // Application fields
    private static final String idField = "id";
    private static final String createdAtField = "createdAt";
    private static final String deploymentSpecField = "deploymentSpecField";
    private static final String validationOverridesField = "validationOverrides";
    private static final String deploymentsField = "deployments";
    private static final String deploymentJobsField = "deploymentJobs";
    private static final String deployingField = "deployingField";
    private static final String pinnedField = "pinned";
    private static final String outstandingChangeField = "outstandingChangeField";
    private static final String ownershipIssueIdField = "ownershipIssueId";
    private static final String ownerField = "confirmedOwner";
    private static final String majorVersionField = "majorVersion";
    private static final String writeQualityField = "writeQuality";
    private static final String queryQualityField = "queryQuality";
    private static final String pemDeployKeyField = "pemDeployKey";
    private static final String assignedRotationsField = "assignedRotations";
    private static final String assignedRotationEndpointField = "endpointId";
    private static final String assignedRotationClusterField = "clusterId";
    private static final String assignedRotationRotationField = "rotationId";
    private static final String applicationCertificateField = "applicationCertificate";

    // Deployment fields
    private static final String zoneField = "zone";
    private static final String environmentField = "environment";
    private static final String regionField = "region";
    private static final String deployTimeField = "deployTime";
    private static final String applicationBuildNumberField = "applicationBuildNumber";
    private static final String applicationPackageRevisionField = "applicationPackageRevision";
    private static final String sourceRevisionField = "sourceRevision";
    private static final String repositoryField = "repositoryField";
    private static final String branchField = "branchField";
    private static final String commitField = "commitField";
    private static final String authorEmailField = "authorEmailField";
    private static final String compileVersionField = "compileVersion";
    private static final String buildTimeField = "buildTime";
    private static final String lastQueriedField = "lastQueried";
    private static final String lastWrittenField = "lastWritten";
    private static final String lastQueriesPerSecondField = "lastQueriesPerSecond";
    private static final String lastWritesPerSecondField = "lastWritesPerSecond";

    // DeploymentJobs fields
    private static final String projectIdField = "projectId";
    private static final String jobStatusField = "jobStatus";
    private static final String issueIdField = "jiraIssueId";
    private static final String builtInternallyField = "builtInternally";

    // JobStatus field
    private static final String jobTypeField = "jobType";
    private static final String errorField = "jobError";
    private static final String lastTriggeredField = "lastTriggered";
    private static final String lastCompletedField = "lastCompleted";
    private static final String firstFailingField = "firstFailing";
    private static final String lastSuccessField = "lastSuccess";
    private static final String pausedUntilField = "pausedUntil";

    // JobRun fields
    private static final String jobRunIdField = "id";
    private static final String versionField = "version";
    private static final String revisionField = "revision";
    private static final String sourceVersionField = "sourceVersion";
    private static final String sourceApplicationField = "sourceRevision";
    private static final String reasonField = "reason";
    private static final String atField = "at";

    // ClusterInfo fields
    private static final String clusterInfoField = "clusterInfo";
    private static final String clusterInfoFlavorField = "flavor";
    private static final String clusterInfoCostField = "cost";
    private static final String clusterInfoCpuField = "flavorCpu";
    private static final String clusterInfoMemField = "flavorMem";
    private static final String clusterInfoDiskField = "flavorDisk";
    private static final String clusterInfoTypeField = "clusterType";
    private static final String clusterInfoHostnamesField = "hostnames";

    // ClusterUtils fields
    private static final String clusterUtilsField = "clusterUtils";
    private static final String clusterUtilsCpuField = "cpu";
    private static final String clusterUtilsMemField = "mem";
    private static final String clusterUtilsDiskField = "disk";
    private static final String clusterUtilsDiskBusyField = "diskbusy";

    // Deployment metrics fields
    private static final String deploymentMetricsField = "metrics";
    private static final String deploymentMetricsQPSField = "queriesPerSecond";
    private static final String deploymentMetricsWPSField = "writesPerSecond";
    private static final String deploymentMetricsDocsField = "documentCount";
    private static final String deploymentMetricsQueryLatencyField = "queryLatencyMillis";
    private static final String deploymentMetricsWriteLatencyField = "writeLatencyMillis";
    private static final String deploymentMetricsUpdateTime = "lastUpdated";
    private static final String deploymentMetricsWarningsField = "warnings";

    // RotationStatus fields
    private static final String rotationStatusField = "rotationStatus2";
    private static final String rotationIdField = "rotationId";
    private static final String rotationStateField = "state";
    private static final String statusField = "status";

    // ------------------ Serialization

    public Slime toSlime(Instance instance) {
        Slime slime = new Slime();
        Cursor root = slime.setObject();
        root.setString(idField, instance.id().serializedForm());
        root.setLong(createdAtField, instance.createdAt().toEpochMilli());
        root.setString(deploymentSpecField, instance.deploymentSpec().xmlForm());
        root.setString(validationOverridesField, instance.validationOverrides().xmlForm());
        deploymentsToSlime(instance.deployments().values(), root.setArray(deploymentsField));
        toSlime(instance.deploymentJobs(), root.setObject(deploymentJobsField));
        toSlime(instance.change(), root, deployingField);
        toSlime(instance.outstandingChange(), root, outstandingChangeField);
        instance.ownershipIssueId().ifPresent(issueId -> root.setString(ownershipIssueIdField, issueId.value()));
        instance.owner().ifPresent(owner -> root.setString(ownerField, owner.username()));
        instance.majorVersion().ifPresent(majorVersion -> root.setLong(majorVersionField, majorVersion));
        root.setDouble(queryQualityField, instance.metrics().queryServiceQuality());
        root.setDouble(writeQualityField, instance.metrics().writeServiceQuality());
        instance.pemDeployKey().ifPresent(pemDeployKey -> root.setString(pemDeployKeyField, pemDeployKey));
        assignedRotationsToSlime(instance.rotations(), root, assignedRotationsField);
        toSlime(instance.rotationStatus(), root.setArray(rotationStatusField));
        return slime;
    }

    private void deploymentsToSlime(Collection<Deployment> deployments, Cursor array) {
        for (Deployment deployment : deployments)
            deploymentToSlime(deployment, array.addObject());
    }

    private void deploymentToSlime(Deployment deployment, Cursor object) {
        zoneIdToSlime(deployment.zone(), object.setObject(zoneField));
        object.setString(versionField, deployment.version().toString());
        object.setLong(deployTimeField, deployment.at().toEpochMilli());
        toSlime(deployment.applicationVersion(), object.setObject(applicationPackageRevisionField));
        clusterInfoToSlime(deployment.clusterInfo(), object);
        clusterUtilsToSlime(deployment.clusterUtils(), object);
        deploymentMetricsToSlime(deployment.metrics(), object);
        deployment.activity().lastQueried().ifPresent(instant -> object.setLong(lastQueriedField, instant.toEpochMilli()));
        deployment.activity().lastWritten().ifPresent(instant -> object.setLong(lastWrittenField, instant.toEpochMilli()));
        deployment.activity().lastQueriesPerSecond().ifPresent(value -> object.setDouble(lastQueriesPerSecondField, value));
        deployment.activity().lastWritesPerSecond().ifPresent(value -> object.setDouble(lastWritesPerSecondField, value));
    }

    private void deploymentMetricsToSlime(DeploymentMetrics metrics, Cursor object) {
        Cursor root = object.setObject(deploymentMetricsField);
        root.setDouble(deploymentMetricsQPSField, metrics.queriesPerSecond());
        root.setDouble(deploymentMetricsWPSField, metrics.writesPerSecond());
        root.setDouble(deploymentMetricsDocsField, metrics.documentCount());
        root.setDouble(deploymentMetricsQueryLatencyField, metrics.queryLatencyMillis());
        root.setDouble(deploymentMetricsWriteLatencyField, metrics.writeLatencyMillis());
        metrics.instant().ifPresent(instant -> root.setLong(deploymentMetricsUpdateTime, instant.toEpochMilli()));
        if (!metrics.warnings().isEmpty()) {
            Cursor warningsObject = root.setObject(deploymentMetricsWarningsField);
            metrics.warnings().forEach((warning, count) -> warningsObject.setLong(warning.name(), count));
        }
    }

    private void clusterInfoToSlime(Map<ClusterSpec.Id, ClusterInfo> clusters, Cursor object) {
        Cursor root = object.setObject(clusterInfoField);
        for (Map.Entry<ClusterSpec.Id, ClusterInfo> entry : clusters.entrySet()) {
            toSlime(entry.getValue(), root.setObject(entry.getKey().value()));
        }
    }

    private void toSlime(ClusterInfo info, Cursor object) {
        object.setString(clusterInfoFlavorField, info.getFlavor());
        object.setLong(clusterInfoCostField, info.getFlavorCost());
        object.setDouble(clusterInfoCpuField, info.getFlavorCPU());
        object.setDouble(clusterInfoMemField, info.getFlavorMem());
        object.setDouble(clusterInfoDiskField, info.getFlavorDisk());
        object.setString(clusterInfoTypeField, info.getClusterType().name());
        Cursor array = object.setArray(clusterInfoHostnamesField);
        for (String host : info.getHostnames()) {
            array.addString(host);
        }
    }

    private void clusterUtilsToSlime(Map<ClusterSpec.Id, ClusterUtilization> clusters, Cursor object) {
        Cursor root = object.setObject(clusterUtilsField);
        for (Map.Entry<ClusterSpec.Id, ClusterUtilization> entry : clusters.entrySet()) {
            toSlime(entry.getValue(), root.setObject(entry.getKey().value()));
        }
    }

    private void toSlime(ClusterUtilization utils, Cursor object) {
        object.setDouble(clusterUtilsCpuField, utils.getCpu());
        object.setDouble(clusterUtilsMemField, utils.getMemory());
        object.setDouble(clusterUtilsDiskField, utils.getDisk());
        object.setDouble(clusterUtilsDiskBusyField, utils.getDiskBusy());
    }

    private void zoneIdToSlime(ZoneId zone, Cursor object) {
        object.setString(environmentField, zone.environment().value());
        object.setString(regionField, zone.region().value());
    }

    private void toSlime(ApplicationVersion applicationVersion, Cursor object) {
        if (applicationVersion.buildNumber().isPresent() && applicationVersion.source().isPresent()) {
            object.setLong(applicationBuildNumberField, applicationVersion.buildNumber().getAsLong());
            toSlime(applicationVersion.source().get(), object.setObject(sourceRevisionField));
            applicationVersion.authorEmail().ifPresent(email -> object.setString(authorEmailField, email));
            applicationVersion.compileVersion().ifPresent(version -> object.setString(compileVersionField, version.toString()));
            applicationVersion.buildTime().ifPresent(time -> object.setLong(buildTimeField, time.toEpochMilli()));
        }
    }

    private void toSlime(SourceRevision sourceRevision, Cursor object) {
        object.setString(repositoryField, sourceRevision.repository());
        object.setString(branchField, sourceRevision.branch());
        object.setString(commitField, sourceRevision.commit());
    }

    private void toSlime(DeploymentJobs deploymentJobs, Cursor cursor) {
        deploymentJobs.projectId().ifPresent(projectId -> cursor.setLong(projectIdField, projectId));
        jobStatusToSlime(deploymentJobs.jobStatus().values(), cursor.setArray(jobStatusField));
        deploymentJobs.issueId().ifPresent(jiraIssueId -> cursor.setString(issueIdField, jiraIssueId.value()));
        cursor.setBool(builtInternallyField, deploymentJobs.deployedInternally());
    }

    private void jobStatusToSlime(Collection<JobStatus> jobStatuses, Cursor jobStatusArray) {
        for (JobStatus jobStatus : jobStatuses)
            toSlime(jobStatus, jobStatusArray.addObject());
    }

    private void toSlime(JobStatus jobStatus, Cursor object) {
        object.setString(jobTypeField, jobStatus.type().jobName());
        if (jobStatus.jobError().isPresent())
            object.setString(errorField, jobStatus.jobError().get().name());

        jobStatus.lastTriggered().ifPresent(run -> jobRunToSlime(run, object, lastTriggeredField));
        jobStatus.lastCompleted().ifPresent(run -> jobRunToSlime(run, object, lastCompletedField));
        jobStatus.lastSuccess().ifPresent(run -> jobRunToSlime(run, object, lastSuccessField));
        jobStatus.firstFailing().ifPresent(run -> jobRunToSlime(run, object, firstFailingField));
        jobStatus.pausedUntil().ifPresent(until -> object.setLong(pausedUntilField, until));
    }

    private void jobRunToSlime(JobStatus.JobRun jobRun, Cursor parent, String jobRunObjectName) {
        Cursor object = parent.setObject(jobRunObjectName);
        object.setLong(jobRunIdField, jobRun.id());
        object.setString(versionField, jobRun.platform().toString());
        toSlime(jobRun.application(), object.setObject(revisionField));
        jobRun.sourcePlatform().ifPresent(version -> object.setString(sourceVersionField, version.toString()));
        jobRun.sourceApplication().ifPresent(version -> toSlime(version, object.setObject(sourceApplicationField)));
        object.setString(reasonField, jobRun.reason());
        object.setLong(atField, jobRun.at().toEpochMilli());
    }

    private void toSlime(Change deploying, Cursor parentObject, String fieldName) {
        if (deploying.isEmpty()) return;

        Cursor object = parentObject.setObject(fieldName);
        if (deploying.platform().isPresent())
            object.setString(versionField, deploying.platform().get().toString());
        if (deploying.application().isPresent())
            toSlime(deploying.application().get(), object);
        if (deploying.isPinned())
            object.setBool(pinnedField, true);
    }

    private void toSlime(RotationStatus status, Cursor array) {
        status.asMap().forEach((rotationId, zoneStatus) -> {
            Cursor rotationObject = array.addObject();
            rotationObject.setString(rotationIdField, rotationId.asString());
            Cursor statusArray = rotationObject.setArray(statusField);
            zoneStatus.forEach((zone, state) -> {
                Cursor statusObject = statusArray.addObject();
                zoneIdToSlime(zone, statusObject);
                statusObject.setString(rotationStateField, state.name());
            });
        });
    }

    private void assignedRotationsToSlime(List<AssignedRotation> rotations, Cursor parent, String fieldName) {
        var rotationsArray = parent.setArray(fieldName);
        for (var rotation : rotations) {
            var object = rotationsArray.addObject();
            object.setString(assignedRotationEndpointField, rotation.endpointId().id());
            object.setString(assignedRotationRotationField, rotation.rotationId().asString());
            object.setString(assignedRotationClusterField, rotation.clusterId().value());
        }
    }

    // ------------------ Deserialization

    public Instance fromSlime(Slime slime) {
        Inspector root = slime.get();

        ApplicationId id = ApplicationId.fromSerializedForm(root.field(idField).asString());
        Instant createdAt = Instant.ofEpochMilli(root.field(createdAtField).asLong());
        DeploymentSpec deploymentSpec = DeploymentSpec.fromXml(root.field(deploymentSpecField).asString(), false);
        ValidationOverrides validationOverrides = ValidationOverrides.fromXml(root.field(validationOverridesField).asString());
        List<Deployment> deployments = deploymentsFromSlime(root.field(deploymentsField));
        DeploymentJobs deploymentJobs = deploymentJobsFromSlime(root.field(deploymentJobsField));
        Change deploying = changeFromSlime(root.field(deployingField));
        Change outstandingChange = changeFromSlime(root.field(outstandingChangeField));
        Optional<IssueId> ownershipIssueId = Serializers.optionalString(root.field(ownershipIssueIdField)).map(IssueId::from);
        Optional<User> owner = Serializers.optionalString(root.field(ownerField)).map(User::from);
        OptionalInt majorVersion = Serializers.optionalInteger(root.field(majorVersionField));
        ApplicationMetrics metrics = new ApplicationMetrics(root.field(queryQualityField).asDouble(),
                                                            root.field(writeQualityField).asDouble());
        Optional<String> pemDeployKey = Serializers.optionalString(root.field(pemDeployKeyField));
        List<AssignedRotation> assignedRotations = assignedRotationsFromSlime(deploymentSpec, root);
        RotationStatus rotationStatus = rotationStatusFromSlime(root);

        return new Instance(id, createdAt, deploymentSpec, validationOverrides, deployments, deploymentJobs,
                            deploying, outstandingChange, ownershipIssueId, owner, majorVersion, metrics,
                            pemDeployKey, assignedRotations, rotationStatus);
    }

    private List<Deployment> deploymentsFromSlime(Inspector array) {
        List<Deployment> deployments = new ArrayList<>();
        array.traverse((ArrayTraverser) (int i, Inspector item) -> deployments.add(deploymentFromSlime(item)));
        return deployments;
    }

    private Deployment deploymentFromSlime(Inspector deploymentObject) {
        return new Deployment(zoneIdFromSlime(deploymentObject.field(zoneField)),
                              applicationVersionFromSlime(deploymentObject.field(applicationPackageRevisionField)),
                              Version.fromString(deploymentObject.field(versionField).asString()),
                              Instant.ofEpochMilli(deploymentObject.field(deployTimeField).asLong()),
                              clusterUtilsMapFromSlime(deploymentObject.field(clusterUtilsField)),
                              clusterInfoMapFromSlime(deploymentObject.field(clusterInfoField)),
                              deploymentMetricsFromSlime(deploymentObject.field(deploymentMetricsField)),
                              DeploymentActivity.create(Serializers.optionalInstant(deploymentObject.field(lastQueriedField)),
                                                        Serializers.optionalInstant(deploymentObject.field(lastWrittenField)),
                                                        Serializers.optionalDouble(deploymentObject.field(lastQueriesPerSecondField)),
                                                        Serializers.optionalDouble(deploymentObject.field(lastWritesPerSecondField))));
    }

    private DeploymentMetrics deploymentMetricsFromSlime(Inspector object) {
        Optional<Instant> instant = object.field(deploymentMetricsUpdateTime).valid() ?
                Optional.of(Instant.ofEpochMilli(object.field(deploymentMetricsUpdateTime).asLong())) :
                Optional.empty();
        return new DeploymentMetrics(object.field(deploymentMetricsQPSField).asDouble(),
                                     object.field(deploymentMetricsWPSField).asDouble(),
                                     object.field(deploymentMetricsDocsField).asDouble(),
                                     object.field(deploymentMetricsQueryLatencyField).asDouble(),
                                     object.field(deploymentMetricsWriteLatencyField).asDouble(),
                                     instant,
                                     deploymentWarningsFrom(object.field(deploymentMetricsWarningsField)));
    }

    private Map<DeploymentMetrics.Warning, Integer> deploymentWarningsFrom(Inspector object) {
        Map<DeploymentMetrics.Warning, Integer> warnings = new HashMap<>();
        object.traverse((ObjectTraverser) (name, value) -> warnings.put(DeploymentMetrics.Warning.valueOf(name),
                                                                        (int) value.asLong()));
        return Collections.unmodifiableMap(warnings);
    }

    private RotationStatus rotationStatusFromSlime(Inspector parentObject) {
        var object = parentObject.field(rotationStatusField);
        var statusMap = new LinkedHashMap<RotationId, Map<ZoneId, RotationState>>();
        object.traverse((ArrayTraverser) (idx, statusObject) -> statusMap.put(new RotationId(statusObject.field(rotationIdField).asString()),
                                                                              singleRotationStatusFromSlime(statusObject.field(statusField))));
        return RotationStatus.from(statusMap);
    }

    private Map<ZoneId, RotationState> singleRotationStatusFromSlime(Inspector object) {
        if (!object.valid()) {
            return Collections.emptyMap();
        }
        Map<ZoneId, RotationState> rotationStatus = new LinkedHashMap<>();
        object.traverse((ArrayTraverser) (idx, statusObject) -> {
            var zone = zoneIdFromSlime(statusObject);
            var status = RotationState.valueOf(statusObject.field(rotationStateField).asString());
            rotationStatus.put(zone, status);
        });
        return Collections.unmodifiableMap(rotationStatus);
    }

    private Map<ClusterSpec.Id, ClusterInfo> clusterInfoMapFromSlime   (Inspector object) {
        Map<ClusterSpec.Id, ClusterInfo> map = new HashMap<>();
        object.traverse((String name, Inspector value) -> map.put(new ClusterSpec.Id(name), clusterInfoFromSlime(value)));
        return map;
    }

    private Map<ClusterSpec.Id, ClusterUtilization> clusterUtilsMapFromSlime(Inspector object) {
        Map<ClusterSpec.Id, ClusterUtilization> map = new HashMap<>();
        object.traverse((String name, Inspector value) -> map.put(new ClusterSpec.Id(name), clusterUtililzationFromSlime(value)));
        return map;
    }

    private ClusterUtilization clusterUtililzationFromSlime(Inspector object) {
        double cpu = object.field(clusterUtilsCpuField).asDouble();
        double mem = object.field(clusterUtilsMemField).asDouble();
        double disk = object.field(clusterUtilsDiskField).asDouble();
        double diskBusy = object.field(clusterUtilsDiskBusyField).asDouble();

        return new ClusterUtilization(mem, cpu, disk, diskBusy);
    }

    private ClusterInfo clusterInfoFromSlime(Inspector inspector) {
        String flavor = inspector.field(clusterInfoFlavorField).asString();
        int cost = (int)inspector.field(clusterInfoCostField).asLong();
        String type = inspector.field(clusterInfoTypeField).asString();
        double flavorCpu = inspector.field(clusterInfoCpuField).asDouble();
        double flavorMem = inspector.field(clusterInfoMemField).asDouble();
        double flavorDisk = inspector.field(clusterInfoDiskField).asDouble();

        List<String> hostnames = new ArrayList<>();
        inspector.field(clusterInfoHostnamesField).traverse((ArrayTraverser)(int index, Inspector value) -> hostnames.add(value.asString()));
        return new ClusterInfo(flavor, cost, flavorCpu, flavorMem, flavorDisk, ClusterSpec.Type.from(type), hostnames);
    }

    private ZoneId zoneIdFromSlime(Inspector object) {
        return ZoneId.from(object.field(environmentField).asString(), object.field(regionField).asString());
    }

    private ApplicationVersion applicationVersionFromSlime(Inspector object) {
        if ( ! object.valid()) return ApplicationVersion.unknown;
        OptionalLong applicationBuildNumber = Serializers.optionalLong(object.field(applicationBuildNumberField));
        Optional<SourceRevision> sourceRevision = sourceRevisionFromSlime(object.field(sourceRevisionField));
        if (sourceRevision.isEmpty() || applicationBuildNumber.isEmpty()) {
            return ApplicationVersion.unknown;
        }
        Optional<String> authorEmail = Serializers.optionalString(object.field(authorEmailField));
        Optional<Version> compileVersion = Serializers.optionalString(object.field(compileVersionField)).map(Version::fromString);
        Optional<Instant> buildTime = Serializers.optionalInstant(object.field(buildTimeField));

        if (authorEmail.isEmpty())
            return ApplicationVersion.from(sourceRevision.get(), applicationBuildNumber.getAsLong());

        if (compileVersion.isEmpty() || buildTime.isEmpty())
            return ApplicationVersion.from(sourceRevision.get(), applicationBuildNumber.getAsLong(), authorEmail.get());

        return ApplicationVersion.from(sourceRevision.get(), applicationBuildNumber.getAsLong(), authorEmail.get(),
                                       compileVersion.get(), buildTime.get());
    }

    private Optional<SourceRevision> sourceRevisionFromSlime(Inspector object) {
        if ( ! object.valid()) return Optional.empty();
        return Optional.of(new SourceRevision(object.field(repositoryField).asString(),
                                              object.field(branchField).asString(),
                                              object.field(commitField).asString()));
    }

    private DeploymentJobs deploymentJobsFromSlime(Inspector object) {
        OptionalLong projectId = Serializers.optionalLong(object.field(projectIdField));
        List<JobStatus> jobStatusList = jobStatusListFromSlime(object.field(jobStatusField));
        Optional<IssueId> issueId = Serializers.optionalString(object.field(issueIdField)).map(IssueId::from);
        boolean builtInternally = object.field(builtInternallyField).asBool();

        return new DeploymentJobs(projectId, jobStatusList, issueId, builtInternally);
    }

    private Change changeFromSlime(Inspector object) {
        if ( ! object.valid()) return Change.empty();
        Inspector versionFieldValue = object.field(versionField);
        Change change = Change.empty();
        if (versionFieldValue.valid())
            change = Change.of(Version.fromString(versionFieldValue.asString()));
        if (object.field(applicationBuildNumberField).valid())
            change = change.with(applicationVersionFromSlime(object));
        if (object.field(pinnedField).asBool())
            change = change.withPin();
        return change;
    }

    private List<JobStatus> jobStatusListFromSlime(Inspector array) {
        List<JobStatus> jobStatusList = new ArrayList<>();
        array.traverse((ArrayTraverser) (int i, Inspector item) -> jobStatusFromSlime(item).ifPresent(jobStatusList::add));
        return jobStatusList;
    }

    private Optional<JobStatus> jobStatusFromSlime(Inspector object) {
        // if the job type has since been removed, ignore it
        Optional<JobType> jobType =
                JobType.fromOptionalJobName(object.field(jobTypeField).asString());
        if (jobType.isEmpty()) return Optional.empty();

        Optional<JobError> jobError = Optional.empty();
        if (object.field(errorField).valid())
            jobError = Optional.of(JobError.valueOf(object.field(errorField).asString()));

        return Optional.of(new JobStatus(jobType.get(),
                                         jobError,
                                         jobRunFromSlime(object.field(lastTriggeredField)),
                                         jobRunFromSlime(object.field(lastCompletedField)),
                                         jobRunFromSlime(object.field(firstFailingField)),
                                         jobRunFromSlime(object.field(lastSuccessField)),
                                         Serializers.optionalLong(object.field(pausedUntilField))));
    }

    private Optional<JobStatus.JobRun> jobRunFromSlime(Inspector object) {
        if ( ! object.valid()) return Optional.empty();
        return Optional.of(new JobStatus.JobRun(object.field(jobRunIdField).asLong(),
                                                new Version(object.field(versionField).asString()),
                                                applicationVersionFromSlime(object.field(revisionField)),
                                                Serializers.optionalString(object.field(sourceVersionField)).map(Version::fromString),
                                                Optional.of(object.field(sourceApplicationField)).filter(Inspector::valid).map(this::applicationVersionFromSlime),
                                                object.field(reasonField).asString(),
                                                Instant.ofEpochMilli(object.field(atField).asLong())));
    }

    private List<AssignedRotation> assignedRotationsFromSlime(DeploymentSpec deploymentSpec, Inspector root) {
        var assignedRotations = new LinkedHashMap<EndpointId, AssignedRotation>();

        root.field(assignedRotationsField).traverse((ArrayTraverser) (idx, inspector) -> {
            var clusterId = new ClusterSpec.Id(inspector.field(assignedRotationClusterField).asString());
            var endpointId = EndpointId.of(inspector.field(assignedRotationEndpointField).asString());
            var rotationId = new RotationId(inspector.field(assignedRotationRotationField).asString());
            var regions = deploymentSpec.endpoints().stream()
                                        .filter(endpoint -> endpoint.endpointId().equals(endpointId.id()))
                                        .flatMap(endpoint -> endpoint.regions().stream())
                                        .collect(Collectors.toSet());
            assignedRotations.putIfAbsent(endpointId, new AssignedRotation(clusterId, endpointId, rotationId, regions));
        });

        return List.copyOf(assignedRotations.values());
    }

}
