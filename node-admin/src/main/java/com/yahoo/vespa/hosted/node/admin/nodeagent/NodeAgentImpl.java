// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.nodeagent;

import com.yahoo.vespa.hosted.dockerapi.Docker;
import com.yahoo.vespa.hosted.dockerapi.DockerImage;
import com.yahoo.vespa.hosted.dockerapi.metrics.Dimensions;
import com.yahoo.vespa.hosted.dockerapi.metrics.MetricReceiverWrapper;
import com.yahoo.vespa.hosted.node.admin.ContainerNodeSpec;
import com.yahoo.vespa.hosted.node.admin.docker.DockerOperations;
import com.yahoo.vespa.hosted.node.admin.maintenance.StorageMaintainer;
import com.yahoo.vespa.hosted.node.admin.noderepository.NodeRepository;
import com.yahoo.vespa.hosted.node.admin.noderepository.NodeRepositoryImpl;
import com.yahoo.vespa.hosted.node.admin.orchestrator.Orchestrator;
import com.yahoo.vespa.hosted.node.admin.util.PrefixLogger;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentImpl.ContainerState.ABSENT;
import static com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentImpl.ContainerState.RUNNING;
import static com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentImpl.ContainerState.RUNNING_HOWEVER_RESUME_SCRIPT_NOT_RUN;

/**
 * @author dybis
 * @author bakksjo
 */
public class NodeAgentImpl implements NodeAgent {

    private AtomicBoolean isFrozen = new AtomicBoolean(false);
    private AtomicBoolean wantFrozen = new AtomicBoolean(false);
    private AtomicBoolean terminated = new AtomicBoolean(false);

    private boolean workToDoNow = true;

    private final PrefixLogger logger;

    private DockerImage imageBeingDownloaded = null;

    private final String hostname;

    private final NodeRepository nodeRepository;
    private final Orchestrator orchestrator;
    private final DockerOperations dockerOperations;
    private final StorageMaintainer storageMaintainer;

    private final Object monitor = new Object();

    private final LinkedList<String> debugMessages = new LinkedList<>();
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private long delaysBetweenEachTickMillis;
    private int numberOfUnhandledException = 0;

    private Thread loopThread;

    public enum ContainerState {
        ABSENT,
        RUNNING_HOWEVER_RESUME_SCRIPT_NOT_RUN,
        RUNNING
    }
    ContainerState containerState = ABSENT;

    // The attributes of the last successful node repo attribute update for this node. Used to avoid redundant calls.
    private NodeAttributes lastAttributesSet = null;
    private ContainerNodeSpec lastNodeSpec = null;

    private final MetricReceiverWrapper metricReceiver;

    public NodeAgentImpl(
            final String hostName,
            final NodeRepository nodeRepository,
            final Orchestrator orchestrator,
            final DockerOperations dockerOperations,
            final StorageMaintainer storageMaintainer,
            final MetricReceiverWrapper metricReceiver) {
        this.nodeRepository = nodeRepository;
        this.orchestrator = orchestrator;
        this.hostname = hostName;
        this.dockerOperations = dockerOperations;
        this.storageMaintainer = storageMaintainer;
        this.logger = PrefixLogger.getNodeAgentLogger(NodeAgentImpl.class,
                NodeRepositoryImpl.containerNameFromHostName(hostName));

        this.metricReceiver = metricReceiver;
    }

    @Override
    public void freeze() {
        if (!wantFrozen.get()) {
            addDebugMessage("Freezing");
        }
        wantFrozen.set(true);
        signalWorkToBeDone();
    }

    @Override
    public void unfreeze() {
        if (wantFrozen.get()) {
            addDebugMessage("Unfreezing");
        }
        wantFrozen.set(false);
        signalWorkToBeDone();
    }

    @Override
    public boolean isFrozen() {
        return isFrozen.get();
    }

    private void addDebugMessage(String message) {
        synchronized (monitor) {
            while (debugMessages.size() > 100) {
                debugMessages.pop();
            }

            debugMessages.add("[" + sdf.format(new Date()) + "] " + message);
        }
    }

    @Override
    public Map<String, Object> debugInfo() {
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("Hostname", hostname);
        debug.put("isFrozen", isFrozen());
        debug.put("wantFrozen", wantFrozen.get());
        debug.put("terminated", terminated.get());
        debug.put("workToDoNow", workToDoNow);
        synchronized (monitor) {
            debug.put("History", new LinkedList<>(debugMessages));
            debug.put("Node repo state", lastNodeSpec.nodeState.name());
        }
        return debug;
    }

    @Override
    public void start(int intervalMillis) {
        addDebugMessage("Starting with interval " + intervalMillis + "ms");
        delaysBetweenEachTickMillis = intervalMillis;
        if (loopThread != null) {
            throw new RuntimeException("Can not restart a node agent.");
        }
        loopThread = new Thread(this::loop);
        loopThread.setName("loop-" + hostname);
        loopThread.start();
    }

    @Override
    public void stop() {
        addDebugMessage("Stopping");
        if (!terminated.compareAndSet(false, true)) {
            throw new RuntimeException("Can not re-stop a node agent.");
        }
        signalWorkToBeDone();
        try {
            loopThread.join(10000);
            if (loopThread.isAlive()) {
                logger.error("Could not stop host thread " + hostname);
            }
        } catch (InterruptedException e1) {
            logger.error("Interrupted; Could not stop host thread " + hostname);
        }
    }

    private void runLocalResumeScriptIfNeeded(final ContainerNodeSpec nodeSpec) {
        if (containerState != RUNNING_HOWEVER_RESUME_SCRIPT_NOT_RUN) {
            return;
        }
        addDebugMessage("Starting optional node program resume command");
        logger.info("Starting optional node program resume command");
        dockerOperations.executeResume(nodeSpec.containerName);
        containerState = RUNNING;
    }

    private void updateNodeRepoAndMarkNodeAsReady(ContainerNodeSpec nodeSpec) throws IOException {
        publishStateToNodeRepoIfChanged(
                nodeSpec.hostname,
                // Clear current Docker image and vespa version, as nothing is running on this node
                new NodeAttributes()
                        .withDockerImage(new DockerImage(""))
                        .withVespaVersion(""));
        nodeRepository.markAsReady(nodeSpec.hostname);
    }

    private void updateNodeRepoWithCurrentAttributes(final ContainerNodeSpec nodeSpec) throws IOException {
        final String containerVespaVersion = dockerOperations.getVespaVersionOrNull(nodeSpec.containerName);
        final NodeAttributes nodeAttributes = new NodeAttributes()
                .withRestartGeneration(nodeSpec.wantedRestartGeneration.get())
                .withDockerImage(nodeSpec.wantedDockerImage.get())
                .withVespaVersion(containerVespaVersion);

        publishStateToNodeRepoIfChanged(nodeSpec.hostname, nodeAttributes);
    }

    private void publishStateToNodeRepoIfChanged(String hostName, NodeAttributes currentAttributes) throws IOException {
        // TODO: We should only update if the new current values do not match the node repo's current values
        if (!currentAttributes.equals(lastAttributesSet)) {
            logger.info("Publishing new set of attributes to node repo: "
                                + lastAttributesSet + " -> " + currentAttributes);
            addDebugMessage("Publishing new set of attributes to node repo: {" +
                                    lastAttributesSet + "} -> {" + currentAttributes + "}");
            nodeRepository.updateNodeAttributes(hostName, currentAttributes);
            lastAttributesSet = currentAttributes;
        }
    }

    private void startContainerIfNeeded(final ContainerNodeSpec nodeSpec) {
        if (dockerOperations.startContainerIfNeeded(nodeSpec)) {
            addDebugMessage("startContainerIfNeeded: containerState " + containerState + " -> " +
                            RUNNING_HOWEVER_RESUME_SCRIPT_NOT_RUN);
            containerState = RUNNING_HOWEVER_RESUME_SCRIPT_NOT_RUN;
        } else {
            // In case container was already running on startup, we found the container, but should call
            if (containerState == ABSENT) {
                addDebugMessage("startContainerIfNeeded: was already running, containerState set to " +
                        RUNNING_HOWEVER_RESUME_SCRIPT_NOT_RUN);
                containerState = RUNNING_HOWEVER_RESUME_SCRIPT_NOT_RUN;
            }
        }
    }

    private void removeContainerIfNeededUpdateContainerState(ContainerNodeSpec nodeSpec) throws Exception {
        if (dockerOperations.removeContainerIfNeeded(nodeSpec, hostname, orchestrator)) {
            addDebugMessage("removeContainerIfNeededUpdateContainerState: containerState " + containerState + " -> ABSENT");
            containerState = ABSENT;
        }
    }

    private void scheduleDownLoadIfNeeded(ContainerNodeSpec nodeSpec) {
        if (dockerOperations.shouldScheduleDownloadOfImage(nodeSpec.wantedDockerImage.get())) {
            if (imageBeingDownloaded == nodeSpec.wantedDockerImage.get()) {
                // Downloading already scheduled, but not done.
                return;
            }
            imageBeingDownloaded = nodeSpec.wantedDockerImage.get();
            // Create a signalWorkToBeDone when download is finished.
            dockerOperations.scheduleDownloadOfImage(nodeSpec, this::signalWorkToBeDone);
        } else if (imageBeingDownloaded != null) { // Image was downloading, but now its ready
            imageBeingDownloaded = null;
        }
    }

    @Override
    public void signalWorkToBeDone() {
        if (!workToDoNow) {
            addDebugMessage("Signaling work to be done");
        }

        synchronized (monitor) {
            workToDoNow = true;
            monitor.notifyAll();
        }
    }

    private void loop() {
        while (! terminated.get()) {
            synchronized (monitor) {
                long waittimeLeft = delaysBetweenEachTickMillis;
                while (waittimeLeft > 1 && !workToDoNow) {
                    Instant start = Instant.now();
                    try {
                        monitor.wait(waittimeLeft);
                    } catch (InterruptedException e) {
                        logger.error("Interrupted, but ignoring this: " + hostname);
                        continue;
                    }
                    waittimeLeft -= Duration.between(start, Instant.now()).toMillis();
                }
                workToDoNow = false;
            }
            isFrozen.set(wantFrozen.get());
            if (isFrozen.get()) {
                addDebugMessage("loop: isFrozen");
            } else {
                try {
                    tick();
                } catch (Exception e) {
                    numberOfUnhandledException++;
                    logger.error("Unhandled exception, ignoring.", e);
                    addDebugMessage(e.getMessage());
                } catch (Throwable t) {
                    logger.error("Unhandled throwable, taking down system.", t);
                    System.exit(234);
                }
            }
        }

        metricReceiver.unsetMetricsForContainer(hostname);
    }

    // Public for testing
    public void tick() throws Exception {
        final ContainerNodeSpec nodeSpec = nodeRepository.getContainerNodeSpec(hostname)
                .orElseThrow(() ->
                        new IllegalStateException(String.format("Node '%s' missing from node repository.", hostname)));

        synchronized (monitor) {
            if (!nodeSpec.equals(lastNodeSpec)) {
                addDebugMessage("Loading new node spec: " + nodeSpec.toString());
                lastNodeSpec = nodeSpec;
            }
        }

        switch (nodeSpec.nodeState) {
            case ready:
                removeContainerIfNeededUpdateContainerState(nodeSpec);
                break;
            case reserved:
                removeContainerIfNeededUpdateContainerState(nodeSpec);
                break;
            case active:
                storageMaintainer.removeOldFilesFromNode(nodeSpec.containerName);
                scheduleDownLoadIfNeeded(nodeSpec);
                if (imageBeingDownloaded != null) {
                    addDebugMessage("Waiting for image to download " + imageBeingDownloaded.asString());
                    return;
                }
                removeContainerIfNeededUpdateContainerState(nodeSpec);

                startContainerIfNeeded(nodeSpec);
                runLocalResumeScriptIfNeeded(nodeSpec);
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
                updateNodeRepoWithCurrentAttributes(nodeSpec);
                logger.info("Call resume against Orchestrator");
                orchestrator.resume(nodeSpec.hostname);
                updateContainerNodeMetrics(nodeSpec);
                break;
            case inactive:
                storageMaintainer.removeOldFilesFromNode(nodeSpec.containerName);
                removeContainerIfNeededUpdateContainerState(nodeSpec);
                break;
            case provisioned:
            case dirty:
                storageMaintainer.removeOldFilesFromNode(nodeSpec.containerName);
                removeContainerIfNeededUpdateContainerState(nodeSpec);
                logger.info("State is " + nodeSpec.nodeState + ", will delete application storage and mark node as ready");
                storageMaintainer.deleteContainerStorage(nodeSpec.containerName);
                updateNodeRepoAndMarkNodeAsReady(nodeSpec);
                break;
            case parked:
            case failed:
                removeContainerIfNeededUpdateContainerState(nodeSpec);
                break;
            default:
                throw new RuntimeException("UNKNOWN STATE " + nodeSpec.nodeState.name());
        }
    }

    @SuppressWarnings("unchecked")
    void updateContainerNodeMetrics(ContainerNodeSpec nodeSpec) {
        Docker.ContainerStats stats = dockerOperations.getContainerStats(nodeSpec.containerName);
        Dimensions.Builder dimensionsBuilder = new Dimensions.Builder()
                .add("host", hostname)
                .add("role", "tenants")
                .add("flavor", nodeSpec.nodeFlavor)
                .add("state", nodeSpec.nodeState);

        if (nodeSpec.owner.isPresent()) {
            dimensionsBuilder
                    .add("tenantName", nodeSpec.owner.get().tenant)
                    .add("app", nodeSpec.owner.get().application);
        }
        if (nodeSpec.membership.isPresent()) {
            dimensionsBuilder
                    .add("clustertype", nodeSpec.membership.get().clusterType)
                    .add("clusterid", nodeSpec.membership.get().clusterId);
        }

        if (nodeSpec.vespaVersion.isPresent()) dimensionsBuilder.add("vespaVersion", nodeSpec.vespaVersion.get());
        Dimensions dimensions = dimensionsBuilder.build();

        Map<String, Object> throttledData = (Map<String, Object>) stats.getCpuStats().get("throttling_data");
        Map<String, Object> cpuUsage = (Map<String, Object>) stats.getCpuStats().get("cpu_usage");
        if (throttledData != null && throttledData.containsKey("throttled_data")) {
            metricReceiver.declareGauge(dimensions, "node.cpu.throttled_time")
                    .sample(((Number) throttledData.get("throttled_time")).doubleValue());
        }
        if (cpuUsage != null && cpuUsage.containsKey("total_usage")) {
            metricReceiver.declareGauge(dimensions, "node.cpu.total_usage")
                    .sample(((Number) cpuUsage.get("total_usage")).doubleValue());
        }
        if (stats.getCpuStats().containsKey("system_cpu_usage")) {
            metricReceiver.declareGauge(dimensions, "node.cpu.system_cpu_usage")
                    .sample(((Number) stats.getCpuStats().get("system_cpu_usage")).doubleValue());
        }
        if (stats.getMemoryStats().containsKey("limit")) {
            metricReceiver.declareGauge(dimensions, "node.memory.limit")
                    .sample(((Number) stats.getMemoryStats().get("limit")).doubleValue());
        }
        if (stats.getMemoryStats().containsKey("usage")) {
            metricReceiver.declareGauge(dimensions, "node.memory.usage")
                    .sample(((Number) stats.getMemoryStats().get("usage")).doubleValue());
        }

        storageMaintainer.updateIfNeededAndGetDiskMetricsFor(nodeSpec.containerName).forEach(
                (metricName, metricValue) -> metricReceiver.declareGauge(dimensions, metricName).sample(metricValue.doubleValue()));

        stats.getNetworks().forEach((interfaceName, interfaceStats) -> {
            Dimensions netDims = dimensionsBuilder.add("interface", interfaceName).build();
            Map<String, Object> intStats = (Map<String, Object>) interfaceStats;

            metricReceiver.declareGauge(netDims, "node.network.bytes_rcvd")
                    .sample(((Number) intStats.get("rx_bytes")).doubleValue());
            metricReceiver.declareGauge(netDims, "node.network.bytes_sent")
                   .sample(((Number) intStats.get("tx_bytes")).doubleValue());
        });
    }

    public Optional<ContainerNodeSpec> getContainerNodeSpec() {
        synchronized (monitor) {
            return Optional.ofNullable(lastNodeSpec);
        }
    }

    @Override
    public boolean isDownloadingImage() {
        return imageBeingDownloaded != null;
    }

    @Override
    public int getAndResetNumberOfUnhandledExceptions() {
        int temp = numberOfUnhandledException;
        numberOfUnhandledException = 0;
        return temp;
    }
}
