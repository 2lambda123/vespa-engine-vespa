// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.nodeagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.yahoo.config.provision.DockerImage;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.zone.ZoneApi;
import com.yahoo.log.LogLevel;
import com.yahoo.vespa.flags.DoubleFlag;
import com.yahoo.vespa.flags.FetchVector;
import com.yahoo.vespa.flags.FlagSource;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.hosted.dockerapi.Container;
import com.yahoo.vespa.hosted.dockerapi.ContainerResources;
import com.yahoo.vespa.hosted.dockerapi.ContainerStats;
import com.yahoo.vespa.hosted.dockerapi.exception.ContainerNotFoundException;
import com.yahoo.vespa.hosted.dockerapi.exception.DockerException;
import com.yahoo.vespa.hosted.dockerapi.exception.DockerExecTimeoutException;
import com.yahoo.vespa.hosted.dockerapi.metrics.DimensionMetrics;
import com.yahoo.vespa.hosted.dockerapi.metrics.Dimensions;
import com.yahoo.vespa.hosted.dockerapi.metrics.Metrics;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeAttributes;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeOwner;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeRepository;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeSpec;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeState;
import com.yahoo.vespa.hosted.node.admin.configserver.orchestrator.Orchestrator;
import com.yahoo.vespa.hosted.node.admin.configserver.orchestrator.OrchestratorException;
import com.yahoo.vespa.hosted.node.admin.docker.DockerOperations;
import com.yahoo.vespa.hosted.node.admin.maintenance.StorageMaintainer;
import com.yahoo.vespa.hosted.node.admin.maintenance.acl.AclMaintainer;
import com.yahoo.vespa.hosted.node.admin.maintenance.identity.CredentialsMaintainer;
import com.yahoo.vespa.hosted.node.admin.nodeadmin.ConvergenceException;
import com.yahoo.vespa.hosted.node.admin.util.SecretAgentCheckConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Logger;

import static com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentImpl.ContainerState.ABSENT;
import static com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentImpl.ContainerState.STARTING;
import static com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentImpl.ContainerState.UNKNOWN;

/**
 * @author dybis
 * @author bakksjo
 */
public class NodeAgentImpl implements NodeAgent {
    // This is used as a definition of 1 GB when comparing flavor specs in node-repo
    private static final long BYTES_IN_GB = 1_000_000_000L;

    private static final Logger logger = Logger.getLogger(NodeAgentImpl.class.getName());

    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private boolean hasResumedNode = false;
    private boolean hasStartedServices = true;

    private final NodeAgentContextSupplier contextSupplier;
    private final NodeRepository nodeRepository;
    private final Orchestrator orchestrator;
    private final DockerOperations dockerOperations;
    private final StorageMaintainer storageMaintainer;
    private final Optional<CredentialsMaintainer> credentialsMaintainer;
    private final Optional<AclMaintainer> aclMaintainer;
    private final Optional<HealthChecker> healthChecker;

    private final DoubleFlag containerCpuCap;

    private int numberOfUnhandledException = 0;
    private long currentRebootGeneration = 0;
    private Optional<Long> currentRestartGeneration = Optional.empty();

    private final Thread loopThread;


    /**
     * ABSENT means container is definitely absent - A container that was absent will not suddenly appear without
     * NodeAgent explicitly starting it.
     * STARTING state is set just before we attempt to start a container, if successful we move to the next state.
     * Otherwise we can't be certain. A container that was running a minute ago may no longer be running without
     * NodeAgent doing anything (container could have crashed). Therefore we always have to ask docker daemon
     * to get updated state of the container.
     */
    enum ContainerState {
        ABSENT,
        STARTING,
        UNKNOWN
    }

    private ContainerState containerState = UNKNOWN;

    private NodeSpec lastNode = null;
    private CpuUsageReporter lastCpuMetric = new CpuUsageReporter();

    // Created in NodeAdminImpl
    public NodeAgentImpl(
            final NodeAgentContextSupplier contextSupplier,
            final NodeRepository nodeRepository,
            final Orchestrator orchestrator,
            final DockerOperations dockerOperations,
            final StorageMaintainer storageMaintainer,
            final FlagSource flagSource,
            final Optional<CredentialsMaintainer> credentialsMaintainer,
            final Optional<AclMaintainer> aclMaintainer,
            final Optional<HealthChecker> healthChecker) {
        this.contextSupplier = contextSupplier;
        this.nodeRepository = nodeRepository;
        this.orchestrator = orchestrator;
        this.dockerOperations = dockerOperations;
        this.storageMaintainer = storageMaintainer;
        this.credentialsMaintainer = credentialsMaintainer;
        this.aclMaintainer = aclMaintainer;
        this.healthChecker = healthChecker;

        this.containerCpuCap = Flags.CONTAINER_CPU_CAP.bindTo(flagSource)
                .with(FetchVector.Dimension.HOSTNAME, contextSupplier.currentContext().node().hostname());

        this.loopThread = new Thread(() -> {
            while (!terminated.get()) {
                try {
                    NodeAgentContext context = contextSupplier.nextContext();
                    converge(context);
                } catch (InterruptedException ignored) { }
            }
        });
        this.loopThread.setName("tick-" + contextSupplier.currentContext().hostname());
    }

    @Override
    public void start() {
        loopThread.start();
    }

    @Override
    public void stopForRemoval() {
        if (!terminated.compareAndSet(false, true)) {
            throw new RuntimeException("Can not re-stop a node agent.");
        }
        contextSupplier.interrupt();

        do {
            try {
                loopThread.join();
            } catch (InterruptedException ignored) { }
        } while (loopThread.isAlive());

        contextSupplier.currentContext().log(logger, "Stopped");
    }

    void startServicesIfNeeded(NodeAgentContext context) {
        if (!hasStartedServices) {
            context.log(logger, "Starting services");
            dockerOperations.startServices(context);
            hasStartedServices = true;
        }
    }

    void resumeNodeIfNeeded(NodeAgentContext context) {
        if (!hasResumedNode) {
            context.log(logger, LogLevel.DEBUG, "Starting optional node program resume command");
            dockerOperations.resumeNode(context);
            hasResumedNode = true;
        }
    }

    private void updateNodeRepoWithCurrentAttributes(NodeAgentContext context) {
        final NodeAttributes currentNodeAttributes = new NodeAttributes();
        final NodeAttributes newNodeAttributes = new NodeAttributes();

        if (context.node().wantedRestartGeneration().isPresent() &&
                !Objects.equals(context.node().currentRestartGeneration(), currentRestartGeneration)) {
            currentNodeAttributes.withRestartGeneration(context.node().currentRestartGeneration());
            newNodeAttributes.withRestartGeneration(currentRestartGeneration);
        }

        if (!Objects.equals(context.node().currentRebootGeneration(), currentRebootGeneration)) {
            currentNodeAttributes.withRebootGeneration(context.node().currentRebootGeneration());
            newNodeAttributes.withRebootGeneration(currentRebootGeneration);
        }

        Optional<DockerImage> actualDockerImage = context.node().wantedDockerImage().filter(n -> containerState == UNKNOWN);
        if (!Objects.equals(context.node().currentDockerImage(), actualDockerImage)) {
            DockerImage currentImage = context.node().currentDockerImage().orElse(DockerImage.EMPTY);
            DockerImage newImage = actualDockerImage.orElse(DockerImage.EMPTY);

            currentNodeAttributes.withDockerImage(currentImage);
            currentNodeAttributes.withVespaVersion(currentImage.tagAsVersion());
            newNodeAttributes.withDockerImage(newImage);
            newNodeAttributes.withVespaVersion(newImage.tagAsVersion());
        }

        publishStateToNodeRepoIfChanged(context, currentNodeAttributes, newNodeAttributes);
    }

    private void publishStateToNodeRepoIfChanged(NodeAgentContext context, NodeAttributes currentAttributes, NodeAttributes newAttributes) {
        if (!currentAttributes.equals(newAttributes)) {
            context.log(logger, "Publishing new set of attributes to node repo: %s -> %s",
                    currentAttributes, newAttributes);
            nodeRepository.updateNodeAttributes(context.hostname().value(), newAttributes);
        }
    }

    private void startContainer(NodeAgentContext context) {
        ContainerData containerData = createContainerData(context);
        dockerOperations.createContainer(context, containerData, getContainerResources(context));
        dockerOperations.startContainer(context);
        lastCpuMetric = new CpuUsageReporter();

        hasStartedServices = true; // Automatically started with the container
        hasResumedNode = false;
        context.log(logger, "Container successfully started, new containerState is " + containerState);
    }

    private Optional<Container> removeContainerIfNeededUpdateContainerState(
            NodeAgentContext context, Optional<Container> existingContainer) {
        if (existingContainer.isPresent()) {
            Optional<String> reason = shouldRemoveContainer(context, existingContainer.get());
            if (reason.isPresent()) {
                removeContainer(context, existingContainer.get(), reason.get(), false);
                return Optional.empty();
            }

            shouldRestartServices(context.node()).ifPresent(restartReason -> {
                context.log(logger, "Will restart services: " + restartReason);
                restartServices(context, existingContainer.get());
                currentRestartGeneration = context.node().wantedRestartGeneration();
            });
        }

        return existingContainer;
    }

    private Optional<String> shouldRestartServices(NodeSpec node) {
        if (!node.wantedRestartGeneration().isPresent()) return Optional.empty();

        // Restart generation is only optional because it does not exist for unallocated nodes
        if (currentRestartGeneration.get() < node.wantedRestartGeneration().get()) {
            return Optional.of("Restart requested - wanted restart generation has been bumped: "
                    + currentRestartGeneration.get() + " -> " + node.wantedRestartGeneration().get());
        }
        return Optional.empty();
    }

    private void restartServices(NodeAgentContext context, Container existingContainer) {
        if (existingContainer.state.isRunning() && context.node().state() == NodeState.active) {
            context.log(logger, "Restarting services");
            // Since we are restarting the services we need to suspend the node.
            orchestratorSuspendNode(context);
            dockerOperations.restartVespa(context);
        }
    }

    private void stopServices() {
        NodeAgentContext context = contextSupplier.currentContext();
        context.log(logger, "Stopping services");
        if (containerState == ABSENT) return;
        try {
            hasStartedServices = hasResumedNode = false;
            dockerOperations.stopServices(context);
        } catch (ContainerNotFoundException e) {
            containerState = ABSENT;
        }
    }

    @Override
    public void stopForHostSuspension() {
        NodeAgentContext context = contextSupplier.currentContext();
        getContainer(context).ifPresent(container -> removeContainer(context, container, "suspending host", true));
    }

    public void suspend() {
        NodeAgentContext context = contextSupplier.currentContext();
        context.log(logger, "Suspending services on node");
        if (containerState == ABSENT) return;
        try {
            hasResumedNode = false;
            dockerOperations.suspendNode(context);
        } catch (ContainerNotFoundException e) {
            containerState = ABSENT;
        } catch (RuntimeException e) {
            // It's bad to continue as-if nothing happened, but on the other hand if we do not proceed to
            // remove container, we will not be able to upgrade to fix any problems in the suspend logic!
            context.log(logger, LogLevel.WARNING, "Failed trying to suspend container", e);
        }
    }

    private Optional<String> shouldRemoveContainer(NodeAgentContext context, Container existingContainer) {
        final NodeState nodeState = context.node().state();
        if (nodeState == NodeState.dirty || nodeState == NodeState.provisioned) {
            return Optional.of("Node in state " + nodeState + ", container should no longer be running");
        }
        if (context.node().wantedDockerImage().isPresent() &&
                !context.node().wantedDockerImage().get().equals(existingContainer.image)) {
            return Optional.of("The node is supposed to run a new Docker image: "
                    + existingContainer.image.asString() + " -> " + context.node().wantedDockerImage().get().asString());
        }
        if (!existingContainer.state.isRunning()) {
            return Optional.of("Container no longer running");
        }

        if (currentRebootGeneration < context.node().wantedRebootGeneration()) {
            return Optional.of(String.format("Container reboot wanted. Current: %d, Wanted: %d",
                    currentRebootGeneration, context.node().wantedRebootGeneration()));
        }

        // Even though memory can be easily changed with docker update, we need to restart the container
        // for proton to pick up the change. If/when proton could detect available memory correctly (rather than reading
        // VESPA_TOTAL_MEMORY_MB env. variable set in DockerOperation), it would be enough with a services restart
        ContainerResources wantedContainerResources = getContainerResources(context);
        if (!wantedContainerResources.equalsMemory(existingContainer.resources)) {
            return Optional.of("Container should be running with different memory allocation, wanted: " +
                    wantedContainerResources.toStringMemory() + ", actual: " + existingContainer.resources.toStringMemory());
        }

        if (containerState == STARTING) return Optional.of("Container failed to start");
        return Optional.empty();
    }

    private void removeContainer(NodeAgentContext context, Container existingContainer, String reason, boolean alreadySuspended) {
        context.log(logger, "Will remove container: " + reason);

        if (existingContainer.state.isRunning()) {
            if (!alreadySuspended) {
                orchestratorSuspendNode(context);
            }

            try {
                if (context.node().state() != NodeState.dirty) {
                    suspend();
                }
                stopServices();
            } catch (Exception e) {
                context.log(logger, LogLevel.WARNING, "Failed stopping services, ignoring", e);
            }
        }

        storageMaintainer.handleCoreDumpsForContainer(context, Optional.of(existingContainer));
        dockerOperations.removeContainer(context, existingContainer);
        currentRebootGeneration = context.node().wantedRebootGeneration();
        containerState = ABSENT;
        context.log(logger, "Container successfully removed, new containerState is " + containerState);
    }


    private void updateContainerIfNeeded(NodeAgentContext context, Container existingContainer) {
        ContainerResources wantedContainerResources = getContainerResources(context);
        if (wantedContainerResources.equalsCpu(existingContainer.resources)) return;
        context.log(logger, "Container should be running with different CPU allocation, wanted: %s, current: %s",
                wantedContainerResources.toStringCpu(), existingContainer.resources.toStringCpu());

        orchestratorSuspendNode(context);

        dockerOperations.updateContainer(context, wantedContainerResources);
    }

    private ContainerResources getContainerResources(NodeAgentContext context) {
        double cpuCap = noCpuCap(context.zone()) ?
                0 :
                context.node().owner()
                        .map(NodeOwner::asApplicationId)
                        .map(appId -> containerCpuCap.with(FetchVector.Dimension.APPLICATION_ID, appId.serializedForm()))
                        .orElse(containerCpuCap)
                        .value() * context.node().vcpus();

        return ContainerResources.from(cpuCap, context.node().vcpus(), context.node().memoryGb());
    }

    private boolean noCpuCap(ZoneApi zone) {
        return zone.getEnvironment() == Environment.dev
                || (zone.getSystemName().isCd() && zone.getEnvironment() != Environment.prod);
    }

    private boolean downloadImageIfNeeded(NodeSpec node, Optional<Container> container) {
        if (node.wantedDockerImage().equals(container.map(c -> c.image))) return false;

        return node.wantedDockerImage().map(dockerOperations::pullImageAsyncIfNeeded).orElse(false);
    }

    public void converge(NodeAgentContext context) {
        try {
            doConverge(context);
        } catch (ConvergenceException e) {
            context.log(logger, e.getMessage());
        } catch (ContainerNotFoundException e) {
            containerState = ABSENT;
            context.log(logger, LogLevel.WARNING, "Container unexpectedly gone, resetting containerState to " + containerState);
        } catch (DockerException e) {
            numberOfUnhandledException++;
            context.log(logger, LogLevel.ERROR, "Caught a DockerException", e);
        } catch (Throwable e) {
            numberOfUnhandledException++;
            context.log(logger, LogLevel.ERROR, "Unhandled exception, ignoring", e);
        }
    }

    // Public for testing
    void doConverge(NodeAgentContext context) {
        NodeSpec node = context.node();
        Optional<Container> container = getContainer(context);
        if (!node.equals(lastNode)) {
            logChangesToNodeSpec(context, lastNode, node);

            // Current reboot generation uninitialized or incremented from outside to cancel reboot
            if (currentRebootGeneration < node.currentRebootGeneration())
                currentRebootGeneration = node.currentRebootGeneration();

            // Either we have changed allocation status (restart gen. only available to allocated nodes), or
            // restart generation has been incremented from outside to cancel restart
            if (currentRestartGeneration.isPresent() != node.currentRestartGeneration().isPresent() ||
                    currentRestartGeneration.map(current -> current < node.currentRestartGeneration().get()).orElse(false))
                currentRestartGeneration = node.currentRestartGeneration();

            // Every time the node spec changes, we should clear the metrics for this container as the dimensions
            // will change and we will be reporting duplicate metrics.
            if (container.map(c -> c.state.isRunning()).orElse(false)) {
                storageMaintainer.writeMetricsConfig(context);
            }

            lastNode = node;
        }

        switch (node.state()) {
            case ready:
            case reserved:
            case parked:
            case failed:
            case inactive:
                removeContainerIfNeededUpdateContainerState(context, container);
                updateNodeRepoWithCurrentAttributes(context);
                break;
            case active:
                storageMaintainer.handleCoreDumpsForContainer(context, container);

                storageMaintainer.getDiskUsageFor(context)
                        .map(diskUsage -> (double) diskUsage / BYTES_IN_GB / node.diskGb())
                        .filter(diskUtil -> diskUtil >= 0.8)
                        .ifPresent(diskUtil -> storageMaintainer.removeOldFilesFromNode(context));

                if (downloadImageIfNeeded(node, container)) {
                    context.log(logger, "Waiting for image to download " + context.node().wantedDockerImage().get().asString());
                    return;
                }
                container = removeContainerIfNeededUpdateContainerState(context, container);
                credentialsMaintainer.ifPresent(maintainer -> maintainer.converge(context));
                if (! container.isPresent()) {
                    containerState = STARTING;
                    startContainer(context);
                    containerState = UNKNOWN;
                } else {
                    updateContainerIfNeeded(context, container.get());
                }

                aclMaintainer.ifPresent(maintainer -> maintainer.converge(context));
                startServicesIfNeeded(context);
                resumeNodeIfNeeded(context);
                healthChecker.ifPresent(checker -> checker.verifyHealth(context));

                // Because it's more important to stop a bad release from rolling out in prod,
                // we put the resume call last. So if we fail after updating the node repo attributes
                // but before resume, the app may go through the tenant pipeline but will halt in prod.
                //
                // Note that this problem exists only because there are 2 different mechanisms
                // that should really be parts of a single mechanism:
                //  - The content of node repo is used to determine whether a new Vespa+application
                //    has been successfully rolled out.
                //  - Slobrok and internal orchestrator state is used to determine whether
                //    to allow upgrade (suspend).
                updateNodeRepoWithCurrentAttributes(context);
                context.log(logger, "Call resume against Orchestrator");
                orchestrator.resume(context.hostname().value());
                break;
            case provisioned:
                nodeRepository.setNodeState(context.hostname().value(), NodeState.dirty);
                break;
            case dirty:
                removeContainerIfNeededUpdateContainerState(context, container);
                context.log(logger, "State is " + node.state() + ", will delete application storage and mark node as ready");
                credentialsMaintainer.ifPresent(maintainer -> maintainer.clearCredentials(context));
                storageMaintainer.archiveNodeStorage(context);
                updateNodeRepoWithCurrentAttributes(context);
                nodeRepository.setNodeState(context.hostname().value(), NodeState.ready);
                break;
            default:
                throw new ConvergenceException("UNKNOWN STATE " + node.state().name());
        }
    }

    private static void logChangesToNodeSpec(NodeAgentContext context, NodeSpec lastNode, NodeSpec node) {
        StringBuilder builder = new StringBuilder();
        appendIfDifferent(builder, "state", lastNode, node, NodeSpec::state);
        if (builder.length() > 0) {
            context.log(logger, LogLevel.INFO, "Changes to node: " + builder.toString());
        }
    }

    private static <T> String fieldDescription(T value) {
        return value == null ? "[absent]" : value.toString();
    }

    private static <T> void appendIfDifferent(StringBuilder builder, String name, NodeSpec oldNode, NodeSpec newNode, Function<NodeSpec, T> getter) {
        T oldValue = oldNode == null ? null : getter.apply(oldNode);
        T newValue = getter.apply(newNode);
        if (!Objects.equals(oldValue, newValue)) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(name).append(" ").append(fieldDescription(oldValue)).append(" -> ").append(fieldDescription(newValue));
        }
    }

    @SuppressWarnings("unchecked")
    public void updateContainerNodeMetrics() {
        if (containerState != UNKNOWN) return;
        final NodeAgentContext context = contextSupplier.currentContext();
        final NodeSpec node = context.node();

        Optional<ContainerStats> containerStats = dockerOperations.getContainerStats(context);
        if (!containerStats.isPresent()) return;

        Dimensions.Builder dimensionsBuilder = new Dimensions.Builder()
                .add("host", context.hostname().value())
                .add("role", SecretAgentCheckConfig.nodeTypeToRole(context.nodeType()))
                .add("state", node.state().toString());
        node.parentHostname().ifPresent(parent -> dimensionsBuilder.add("parentHostname", parent));
        node.allowedToBeDown().ifPresent(allowed ->
                dimensionsBuilder.add("orchestratorState", allowed ? "ALLOWED_TO_BE_DOWN" : "NO_REMARKS"));
        Dimensions dimensions = dimensionsBuilder.build();

        ContainerStats stats = containerStats.get();
        final String APP = Metrics.APPLICATION_NODE;
        final int totalNumCpuCores = stats.getCpuStats().getOnlineCpus();
        final long memoryTotalBytes = stats.getMemoryStats().getLimit();
        final long memoryTotalBytesUsage = stats.getMemoryStats().getUsage();
        final long memoryTotalBytesCache = stats.getMemoryStats().getCache();
        final long diskTotalBytes = (long) (node.diskGb() * BYTES_IN_GB);
        final Optional<Long> diskTotalBytesUsed = storageMaintainer.getDiskUsageFor(context);

        lastCpuMetric.updateCpuDeltas(stats.getCpuStats());

        // Ratio of CPU cores allocated to this container to total number of CPU cores on this host
        final double allocatedCpuRatio = node.vcpus() / totalNumCpuCores;
        double cpuUsageRatioOfAllocated = lastCpuMetric.getCpuUsageRatio() / allocatedCpuRatio;
        double cpuKernelUsageRatioOfAllocated = lastCpuMetric.getCpuKernelUsageRatio() / allocatedCpuRatio;
        double cpuThrottledTimeRate = lastCpuMetric.getThrottledTimeRate();
        double cpuThrottledCpuTimeRate = lastCpuMetric.getThrottledCpuTimeRate();

        long memoryTotalBytesUsed = memoryTotalBytesUsage - memoryTotalBytesCache;
        double memoryUsageRatio = (double) memoryTotalBytesUsed / memoryTotalBytes;
        double memoryTotalUsageRatio = (double) memoryTotalBytesUsage / memoryTotalBytes;
        Optional<Double> diskUsageRatio = diskTotalBytesUsed.map(used -> (double) used / diskTotalBytes);

        List<DimensionMetrics> metrics = new ArrayList<>();
        DimensionMetrics.Builder systemMetricsBuilder = new DimensionMetrics.Builder(APP, dimensions)
                .withMetric("mem.limit", memoryTotalBytes)
                .withMetric("mem.used", memoryTotalBytesUsed)
                .withMetric("mem.util", 100 * memoryUsageRatio)
                .withMetric("mem_total.used", memoryTotalBytesUsage)
                .withMetric("mem_total.util", 100 * memoryTotalUsageRatio)
                .withMetric("cpu.util", 100 * cpuUsageRatioOfAllocated)
                .withMetric("cpu.sys.util", 100 * cpuKernelUsageRatioOfAllocated)
                .withMetric("cpu.throttled_time.rate", cpuThrottledTimeRate)
                .withMetric("cpu.throttled_cpu_time.rate", cpuThrottledCpuTimeRate)
                .withMetric("cpu.vcpus", node.vcpus())
                .withMetric("disk.limit", diskTotalBytes);

        diskTotalBytesUsed.ifPresent(diskUsed -> systemMetricsBuilder.withMetric("disk.used", diskUsed));
        diskUsageRatio.ifPresent(diskRatio -> systemMetricsBuilder.withMetric("disk.util", 100 * diskRatio));
        metrics.add(systemMetricsBuilder.build());

        stats.getNetworks().forEach((interfaceName, interfaceStats) -> {
            Dimensions netDims = dimensionsBuilder.add("interface", interfaceName).build();
            DimensionMetrics networkMetrics = new DimensionMetrics.Builder(APP, netDims)
                    .withMetric("net.in.bytes", interfaceStats.getRxBytes())
                    .withMetric("net.in.errors", interfaceStats.getRxErrors())
                    .withMetric("net.in.dropped", interfaceStats.getRxDropped())
                    .withMetric("net.out.bytes", interfaceStats.getTxBytes())
                    .withMetric("net.out.errors", interfaceStats.getTxErrors())
                    .withMetric("net.out.dropped", interfaceStats.getTxDropped())
                    .build();
            metrics.add(networkMetrics);
        });

        pushMetricsToContainer(context, metrics);
    }

    private void pushMetricsToContainer(NodeAgentContext context, List<DimensionMetrics> metrics) {
        StringBuilder params = new StringBuilder();
        try {
            for (DimensionMetrics dimensionMetrics : metrics) {
                params.append(dimensionMetrics.toSecretAgentReport());
            }
            String wrappedMetrics = "s:" + params.toString();

            // Push metrics to the metrics proxy in each container.
            // TODO Remove port selection logic when all hosted apps have upgraded to Vespa 7.
            int port = context.node().currentVespaVersion().map(version -> version.getMajor() == 6).orElse(false) ? 19091 : 19095;
            String[] command = {"vespa-rpc-invoke",  "-t", "2",  "tcp/localhost:" + port,  "setExtraMetrics", wrappedMetrics};
            dockerOperations.executeCommandInContainerAsRoot(context, 5L, command);
        } catch (JsonProcessingException | DockerExecTimeoutException  e) {
            context.log(logger, LogLevel.WARNING, "Failed to push metrics to container", e);
        }

    }

    private Optional<Container> getContainer(NodeAgentContext context) {
        if (containerState == ABSENT) return Optional.empty();
        Optional<Container> container = dockerOperations.getContainer(context);
        if (! container.isPresent()) containerState = ABSENT;
        return container;
    }

    @Override
    public int getAndResetNumberOfUnhandledExceptions() {
        int temp = numberOfUnhandledException;
        numberOfUnhandledException = 0;
        return temp;
    }

    class CpuUsageReporter {
        private static final double PERIOD_IN_NANOSECONDS = 1_000d * ContainerResources.CPU_PERIOD_US;
        private long containerKernelUsage = 0;
        private long totalContainerUsage = 0;
        private long totalSystemUsage = 0;
        private long throttledTime = 0;
        private long throttlingActivePeriods = 0;
        private long throttledPeriods = 0;

        private long deltaContainerKernelUsage;
        private long deltaContainerUsage;
        private long deltaSystemUsage;
        private long deltaThrottledTime;
        private long deltaThrottlingActivePeriods;
        private long deltaThrottledPeriods;

        private void updateCpuDeltas(ContainerStats.CpuStats cpuStats) {
            // Do not calculate delta during the first tick - that will result in a metric value that is
            // average since container start
            if (totalSystemUsage != 0) {
                deltaSystemUsage = cpuStats.getSystemCpuUsage() - totalSystemUsage;
                deltaContainerUsage = cpuStats.getTotalUsage() - totalContainerUsage;
                deltaContainerKernelUsage = cpuStats.getUsageInKernelMode() - containerKernelUsage;
                deltaThrottledTime = cpuStats.getThrottledTime() - throttledTime;
                deltaThrottlingActivePeriods = cpuStats.getThrottlingActivePeriods() - throttlingActivePeriods;
                deltaThrottledPeriods = cpuStats.getThrottledPeriods() - throttledPeriods;
            }

            totalSystemUsage = cpuStats.getSystemCpuUsage();
            totalContainerUsage = cpuStats.getTotalUsage();
            containerKernelUsage = cpuStats.getUsageInKernelMode();
            throttledTime = cpuStats.getThrottledTime();
            throttlingActivePeriods = cpuStats.getThrottlingActivePeriods();
            throttledPeriods = cpuStats.getThrottledPeriods();
        }

        /**
         * Returns the CPU usage ratio for the docker container that this NodeAgent is managing
         * in the time between the last two times updateCpuDeltas() was called. This is calculated
         * by dividing the CPU time used by the container with the CPU time used by the entire system.
         */
        double getCpuUsageRatio() {
            return deltaSystemUsage == 0 ? Double.NaN : (double) deltaContainerUsage / deltaSystemUsage;
        }

        double getCpuKernelUsageRatio() {
            return deltaSystemUsage == 0 ? Double.NaN : (double) deltaContainerKernelUsage / deltaSystemUsage;
        }

        double getThrottledTimeRate() {
            return deltaThrottlingActivePeriods == 0 ? Double.NaN :
                    (double) deltaThrottledPeriods / deltaThrottlingActivePeriods;
        }

        double getThrottledCpuTimeRate() {
            return deltaThrottlingActivePeriods == 0 ? Double.NaN :
                    deltaThrottledTime / (PERIOD_IN_NANOSECONDS * deltaThrottlingActivePeriods);
        }
    }

    // TODO: Also skip orchestration if we're downgrading in test/staging
    // How to implement:
    //  - test/staging: We need to figure out whether we're in test/staging, zone is available in Environment
    //  - downgrading: Impossible to know unless we look at the hosted version, which is
    //    not available in the docker image (nor its name). Not sure how to solve this. Should
    //    the node repo return the hosted version or a downgrade bit in addition to
    //    wanted docker image etc?
    // Should the tenant pipeline instead use BCP tool to upgrade faster!?
    //
    // More generally, the node repo response should contain sufficient info on what the docker image is,
    // to allow the node admin to make decisions that depend on the docker image. Or, each docker image
    // needs to contain routines for drain and suspend. For many images, these can just be dummy routines.
    private void orchestratorSuspendNode(NodeAgentContext context) {
        if (context.node().state() != NodeState.active) return;

        context.log(logger, "Ask Orchestrator for permission to suspend node");
        try {
            orchestrator.suspend(context.hostname().value());
        } catch (OrchestratorException e) {
            // Ensure the ACLs are up to date: The reason we're unable to suspend may be because some other
            // node is unable to resume because the ACL rules of SOME Docker container is wrong...
            try {
                aclMaintainer.ifPresent(maintainer -> maintainer.converge(context));
            } catch (RuntimeException suppressed) {
                logger.log(LogLevel.WARNING, "Suppressing ACL update failure: " + suppressed);
                e.addSuppressed(suppressed);
            }

            throw e;
        }
    }

    protected ContainerData createContainerData(NodeAgentContext context) {
        return new ContainerData() {
            @Override
            public void addFile(Path pathInContainer, String data) {
                throw new UnsupportedOperationException("addFile not implemented");
            }

            @Override
            public void createSymlink(Path symlink, Path target) {
                throw new UnsupportedOperationException("createSymlink not implemented");
            }
        };
    }
}
