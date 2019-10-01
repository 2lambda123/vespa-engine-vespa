// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server;

import com.google.common.io.Files;
import com.google.inject.Inject;
import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.component.Version;
import com.yahoo.config.FileReference;
import com.yahoo.config.application.api.ApplicationFile;
import com.yahoo.config.application.api.ApplicationMetaData;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.config.model.api.HostInfo;
import com.yahoo.config.model.api.ServiceInfo;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.HostFilter;
import com.yahoo.config.provision.InfraDeployer;
import com.yahoo.config.provision.Provisioner;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.TenantName;
import com.yahoo.config.provision.Zone;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.io.IOUtils;
import com.yahoo.log.LogLevel;
import com.yahoo.path.Path;
import com.yahoo.slime.Slime;
import com.yahoo.transaction.NestedTransaction;
import com.yahoo.vespa.config.server.application.Application;
import com.yahoo.vespa.config.server.application.ApplicationSet;
import com.yahoo.vespa.config.server.application.CompressedApplicationInputStream;
import com.yahoo.vespa.config.server.application.ConfigConvergenceChecker;
import com.yahoo.vespa.config.server.application.FileDistributionStatus;
import com.yahoo.vespa.config.server.application.HttpProxy;
import com.yahoo.vespa.config.server.application.TenantApplications;
import com.yahoo.vespa.config.server.configchange.ConfigChangeActions;
import com.yahoo.vespa.config.server.configchange.RefeedActions;
import com.yahoo.vespa.config.server.configchange.RestartActions;
import com.yahoo.vespa.config.server.deploy.DeployHandlerLogger;
import com.yahoo.vespa.config.server.deploy.Deployment;
import com.yahoo.vespa.config.server.deploy.InfraDeployerProvider;
import com.yahoo.vespa.config.server.http.LogRetriever;
import com.yahoo.vespa.config.server.http.SimpleHttpFetcher;
import com.yahoo.vespa.config.server.http.v2.MetricsResponse;
import com.yahoo.vespa.config.server.http.v2.PrepareResult;
import com.yahoo.vespa.config.server.metrics.ApplicationMetricsRetriever;
import com.yahoo.vespa.config.server.provision.HostProvisionerProvider;
import com.yahoo.vespa.config.server.session.LocalSession;
import com.yahoo.vespa.config.server.session.LocalSessionRepo;
import com.yahoo.vespa.config.server.session.PrepareParams;
import com.yahoo.vespa.config.server.session.RemoteSession;
import com.yahoo.vespa.config.server.session.RemoteSessionRepo;
import com.yahoo.vespa.config.server.session.Session;
import com.yahoo.vespa.config.server.session.SessionFactory;
import com.yahoo.vespa.config.server.session.SilentDeployLogger;
import com.yahoo.vespa.config.server.tenant.Rotations;
import com.yahoo.vespa.config.server.tenant.Tenant;
import com.yahoo.vespa.config.server.tenant.TenantRepository;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.orchestrator.Orchestrator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.yahoo.config.model.api.container.ContainerServiceType.CLUSTERCONTROLLER_CONTAINER;
import static com.yahoo.config.model.api.container.ContainerServiceType.CONTAINER;
import static com.yahoo.config.model.api.container.ContainerServiceType.LOGSERVER_CONTAINER;
import static com.yahoo.vespa.config.server.tenant.TenantRepository.HOSTED_VESPA_TENANT;
import static java.nio.file.Files.readAttributes;

/**
 * The API for managing applications.
 *
 * @author bratseth
 */
// TODO: Move logic for dealing with applications here from the HTTP layer and make this the persistent component
//       owning the rest of the state
public class ApplicationRepository implements com.yahoo.config.provision.Deployer {

    private static final Logger log = Logger.getLogger(ApplicationRepository.class.getName());

    private final TenantRepository tenantRepository;
    private final Optional<Provisioner> hostProvisioner;
    private final Optional<InfraDeployer> infraDeployer;
    private final ConfigConvergenceChecker convergeChecker;
    private final HttpProxy httpProxy;
    private final Clock clock;
    private final DeployLogger logger = new SilentDeployLogger();
    private final ConfigserverConfig configserverConfig;
    private final FileDistributionStatus fileDistributionStatus;
    private final Orchestrator orchestrator;
    private final LogRetriever logRetriever;

    @Inject
    public ApplicationRepository(TenantRepository tenantRepository,
                                 HostProvisionerProvider hostProvisionerProvider,
                                 InfraDeployerProvider infraDeployerProvider,
                                 ConfigConvergenceChecker configConvergenceChecker,
                                 HttpProxy httpProxy,
                                 ConfigserverConfig configserverConfig,
                                 Orchestrator orchestrator) {
        this(tenantRepository,
             hostProvisionerProvider.getHostProvisioner(),
             infraDeployerProvider.getInfraDeployer(),
             configConvergenceChecker,
             httpProxy,
             configserverConfig,
             orchestrator,
             new LogRetriever(),
             new FileDistributionStatus(),
             Clock.systemUTC());
    }

    // For testing
    public ApplicationRepository(TenantRepository tenantRepository,
                                 Provisioner hostProvisioner,
                                 Orchestrator orchestrator,
                                 Clock clock) {
        this(tenantRepository,
             hostProvisioner,
             orchestrator,
             new ConfigserverConfig(new ConfigserverConfig.Builder()),
             new LogRetriever(),
             clock);
    }

    // For testing
    public ApplicationRepository(TenantRepository tenantRepository,
                                 Provisioner hostProvisioner,
                                 Orchestrator orchestrator,
                                 LogRetriever logRetriever,
                                 Clock clock) {
        this(tenantRepository,
             hostProvisioner,
             orchestrator,
             new ConfigserverConfig(new ConfigserverConfig.Builder()),
             logRetriever,
             clock);
    }

    // For testing
    public ApplicationRepository(TenantRepository tenantRepository,
                                 Provisioner hostProvisioner,
                                 Orchestrator orchestrator,
                                 ConfigserverConfig configserverConfig,
                                 LogRetriever logRetriever,
                                 Clock clock) {
        this(tenantRepository,
             Optional.of(hostProvisioner),
             Optional.empty(),
             new ConfigConvergenceChecker(),
             new HttpProxy(new SimpleHttpFetcher()),
             configserverConfig,
             orchestrator,
             logRetriever,
             new FileDistributionStatus(),
             clock);
    }

    private ApplicationRepository(TenantRepository tenantRepository,
                                  Optional<Provisioner> hostProvisioner,
                                  Optional<InfraDeployer> infraDeployer,
                                  ConfigConvergenceChecker configConvergenceChecker,
                                  HttpProxy httpProxy,
                                  ConfigserverConfig configserverConfig,
                                  Orchestrator orchestrator,
                                  LogRetriever logRetriever,
                                  FileDistributionStatus fileDistributionStatus,
                                  Clock clock) {
        this.tenantRepository = tenantRepository;
        this.hostProvisioner = hostProvisioner;
        this.infraDeployer = infraDeployer;
        this.convergeChecker = configConvergenceChecker;
        this.httpProxy = httpProxy;
        this.configserverConfig = configserverConfig;
        this.orchestrator = orchestrator;
        this.logRetriever = logRetriever;
        this.fileDistributionStatus = fileDistributionStatus;
        this.clock = clock;
    }

    // ---------------- Deploying ----------------------------------------------------------------

    public PrepareResult prepare(Tenant tenant, long sessionId, PrepareParams prepareParams, Instant now) {
        validateThatLocalSessionIsNotActive(tenant, sessionId);
        LocalSession session = getLocalSession(tenant, sessionId);
        ApplicationId applicationId = prepareParams.getApplicationId();
        Optional<ApplicationSet> currentActiveApplicationSet = getCurrentActiveApplicationSet(tenant, applicationId);
        Slime deployLog = createDeployLog();
        DeployLogger logger = new DeployHandlerLogger(deployLog.get().setArray("log"), prepareParams.isVerbose(), applicationId);
        ConfigChangeActions actions = session.prepare(logger, prepareParams, currentActiveApplicationSet, tenant.getPath(), now);
        logConfigChangeActions(actions, logger);
        log.log(LogLevel.INFO, TenantRepository.logPre(applicationId) + "Session " + sessionId + " prepared successfully. ");
        return new PrepareResult(sessionId, actions, deployLog);
    }

    public PrepareResult prepareAndActivate(Tenant tenant, long sessionId, PrepareParams prepareParams,
                                            boolean ignoreSessionStaleFailure, Instant now) {
        PrepareResult result = prepare(tenant, sessionId, prepareParams, now);
        activate(tenant, sessionId, prepareParams.getTimeoutBudget(), ignoreSessionStaleFailure);
        return result;
    }

    public PrepareResult deploy(CompressedApplicationInputStream in, PrepareParams prepareParams) {
        return deploy(in, prepareParams, false, clock.instant());
    }

    public PrepareResult deploy(CompressedApplicationInputStream in, PrepareParams prepareParams,
                                boolean ignoreSessionStaleFailure, Instant now) {
        File tempDir = Files.createTempDir();
        PrepareResult prepareResult;
        try {
            prepareResult = deploy(decompressApplication(in, tempDir), prepareParams, ignoreSessionStaleFailure, now);
        } finally {
            cleanupTempDirectory(tempDir);
        }
        return prepareResult;
    }

    public PrepareResult deploy(File applicationPackage, PrepareParams prepareParams) {
        return deploy(applicationPackage, prepareParams, false, Instant.now());
    }

    public PrepareResult deploy(File applicationPackage, PrepareParams prepareParams,
                                boolean ignoreSessionStaleFailure, Instant now) {
        ApplicationId applicationId = prepareParams.getApplicationId();
        long sessionId = createSession(applicationId, prepareParams.getTimeoutBudget(), applicationPackage);
        Tenant tenant = tenantRepository.getTenant(applicationId.tenant());
        return prepareAndActivate(tenant, sessionId, prepareParams, ignoreSessionStaleFailure, now);
    }

    /**
     * Creates a new deployment from the active application, if available.
     * This is used for system internal redeployments, not on application package changes.
     *
     * @param application the active application to be redeployed
     * @return a new deployment from the local active, or empty if a local active application
     *         was not present for this id (meaning it either is not active or active on another
     *         node in the config server cluster)
     */
    @Override
    public Optional<com.yahoo.config.provision.Deployment> deployFromLocalActive(ApplicationId application) {
        return deployFromLocalActive(application, false);
    }

    /**
     * Creates a new deployment from the active application, if available.
     * This is used for system internal redeployments, not on application package changes.
     *
     * @param application the active application to be redeployed
     * @param bootstrap the deployment is done when bootstrapping
     * @return a new deployment from the local active, or empty if a local active application
     *         was not present for this id (meaning it either is not active or active on another
     *         node in the config server cluster)
     */
    @Override
    public Optional<com.yahoo.config.provision.Deployment> deployFromLocalActive(ApplicationId application,
                                                                                 boolean bootstrap) {
        return deployFromLocalActive(application,
                                     Duration.ofSeconds(configserverConfig.zookeeper().barrierTimeout()).plus(Duration.ofSeconds(5)),
                                     bootstrap);
    }

    /**
     * Creates a new deployment from the active application, if available.
     * This is used for system internal redeployments, not on application package changes.
     *
     * @param application the active application to be redeployed
     * @param timeout the timeout to use for each individual deployment operation
     * @param bootstrap the deployment is done when bootstrapping
     * @return a new deployment from the local active, or empty if a local active application
     *         was not present for this id (meaning it either is not active or active on another
     *         node in the config server cluster)
     */
    @Override
    public Optional<com.yahoo.config.provision.Deployment> deployFromLocalActive(ApplicationId application,
                                                                                 Duration timeout,
                                                                                 boolean bootstrap) {
        Optional<com.yahoo.config.provision.Deployment> infraDeployment = infraDeployer.flatMap(d -> d.getDeployment(application));
        if (infraDeployment.isPresent()) return infraDeployment;

        Tenant tenant = tenantRepository.getTenant(application.tenant());
        if (tenant == null) return Optional.empty();
        LocalSession activeSession = getActiveSession(tenant, application);
        if (activeSession == null) return Optional.empty();
        TimeoutBudget timeoutBudget = new TimeoutBudget(clock, timeout);
        LocalSession newSession = tenant.getSessionFactory().createSessionFromExisting(activeSession, logger, true, timeoutBudget);
        tenant.getLocalSessionRepo().addSession(newSession);

        return Optional.of(Deployment.unprepared(newSession, this, hostProvisioner, tenant, timeout, clock,
                                                 false /* don't validate as this is already deployed */, newSession.getVespaVersion(),
                                                 bootstrap));
    }

    @Override
    public Optional<Instant> lastDeployTime(ApplicationId application) {
        Tenant tenant = tenantRepository.getTenant(application.tenant());
        if (tenant == null) return Optional.empty();
        LocalSession activeSession = getActiveSession(tenant, application);
        if (activeSession == null) return Optional.empty();
        return Optional.of(Instant.ofEpochSecond(activeSession.getCreateTime()));
    }

    public ApplicationId activate(Tenant tenant,
                                  long sessionId,
                                  TimeoutBudget timeoutBudget,
                                  boolean ignoreSessionStaleFailure) {
        LocalSession localSession = getLocalSession(tenant, sessionId);
        Deployment deployment = deployFromPreparedSession(localSession, tenant, timeoutBudget.timeLeft());
        deployment.setIgnoreSessionStaleFailure(ignoreSessionStaleFailure);
        deployment.activate();
        ApplicationId applicationId = localSession.getApplicationId();
        return applicationId;
    }

    private Deployment deployFromPreparedSession(LocalSession session, Tenant tenant, Duration timeout) {
        return Deployment.prepared(session, this, hostProvisioner, tenant, timeout, clock, false);
    }

    // ---------------- Application operations ----------------------------------------------------------------

    /**
     * Deletes an application
     *
     * @return true if the application was found and deleted, false if it was not present
     * @throws RuntimeException if the delete transaction fails. This method is exception safe.
     */
    public boolean delete(ApplicationId applicationId) {
        Tenant tenant = tenantRepository.getTenant(applicationId.tenant());
        if (tenant == null) return false;

        TenantApplications tenantApplications = tenant.getApplicationRepo();
        try (Lock lock = tenantApplications.lock(applicationId)) {
            if ( ! tenantApplications.exists(applicationId)) return false;

            // Deleting an application is done by deleting the remote session and waiting
            // until the config server where the deployment happened picks it up and deletes
            // the local session
            boolean sessionDeleted = tenantApplications.activeSessionOf(applicationId).map(sessionId -> {
                RemoteSession remoteSession = getRemoteSession(tenant, sessionId);
                remoteSession.createDeleteTransaction().commit();
                log.log(LogLevel.INFO, TenantRepository.logPre(applicationId) + "Waiting for session " + sessionId + " to be deleted");
                // TODO: Add support for timeout in request
                Duration waitTime = Duration.ofSeconds(60);
                if (localSessionHasBeenDeleted(applicationId, sessionId, waitTime)) {
                    log.log(LogLevel.INFO, TenantRepository.logPre(applicationId) + "Session " + sessionId + " deleted");
                    return true;
                } else {
                    log.log(LogLevel.ERROR, TenantRepository.logPre(applicationId) + "Session " + sessionId + " was not deleted (waited " + waitTime + ")");
                    return false;
                }
            }).orElse(true);

            NestedTransaction transaction = new NestedTransaction();
            transaction.add(new Rotations(tenant.getCurator(), tenant.getPath()).delete(applicationId)); // TODO: Not unit tested
            // (When rotations are updated in zk, we need to redeploy the zone app, on the right config server
            // this is done asynchronously in application maintenance by the node repository)
            transaction.add(tenantApplications.createDeleteTransaction(applicationId));

            hostProvisioner.ifPresent(provisioner -> provisioner.remove(transaction, applicationId));
            transaction.onCommitted(() -> log.log(LogLevel.INFO, "Deleted " + applicationId));
            transaction.commit();
            return sessionDeleted;
        }
    }

    public HttpResponse clusterControllerStatusPage(ApplicationId applicationId, String hostName, String pathSuffix) {
        // WARNING: pathSuffix may be given by the external user. Make sure no security issues arise...
        // We should be OK here, because at most, pathSuffix may change the parent path, but cannot otherwise
        // change the hostname and port. Exposing other paths on the cluster controller should be fine.
        // TODO: It would be nice to have a simple check to verify pathSuffix doesn't contain /../ components.
        String relativePath = "clustercontroller-status/" + pathSuffix;

        return httpProxy.get(getApplication(applicationId), hostName,
                             CLUSTERCONTROLLER_CONTAINER.serviceName, relativePath);
    }

    public Long getApplicationGeneration(ApplicationId applicationId) {
        return getApplication(applicationId).getApplicationGeneration();
    }

    public void restart(ApplicationId applicationId, HostFilter hostFilter) {
        hostProvisioner.ifPresent(provisioner -> provisioner.restart(applicationId, hostFilter));
    }

    public boolean isSuspended(ApplicationId application) {
        return orchestrator.getAllSuspendedApplications().contains(application);
    }

    public HttpResponse filedistributionStatus(ApplicationId applicationId, Duration timeout) {
        return fileDistributionStatus.status(getApplication(applicationId), timeout);
    }

    public Set<String> deleteUnusedFiledistributionReferences(File fileReferencesPath, Duration keepFileReferences) {
        if (!fileReferencesPath.isDirectory()) throw new RuntimeException(fileReferencesPath + " is not a directory");

        Set<String> fileReferencesInUse = new HashSet<>();
        // Intentionally skip applications that we for some reason do not find
        listApplications().stream()
                .map(this::getOptionalApplication)
                .map(Optional::get)
                .forEach(application -> fileReferencesInUse.addAll(application.getModel().fileReferences().stream()
                                                                           .map(FileReference::value)
                                                                           .collect(Collectors.toSet())));
        log.log(LogLevel.DEBUG, "File references in use : " + fileReferencesInUse);

        // Find those on disk that are not in use
        Set<String> fileReferencesOnDisk = new HashSet<>();
        File[] filesOnDisk = fileReferencesPath.listFiles();
        if (filesOnDisk != null)
            fileReferencesOnDisk.addAll(Arrays.stream(filesOnDisk).map(File::getName).collect(Collectors.toSet()));
        log.log(LogLevel.DEBUG, "File references on disk (in " + fileReferencesPath + "): " + fileReferencesOnDisk);

        Instant instant = Instant.now().minus(keepFileReferences);
        Set<String> fileReferencesToDelete = fileReferencesOnDisk
                .stream()
                .filter(fileReference -> ! fileReferencesInUse.contains(fileReference))
                .filter(fileReference -> isFileLastModifiedBefore(new File(fileReferencesPath, fileReference), instant))
                .collect(Collectors.toSet());
        if (fileReferencesToDelete.size() > 0) {
            log.log(LogLevel.INFO, "Will delete file references not in use: " + fileReferencesToDelete);
            fileReferencesToDelete.forEach(fileReference -> {
                File file = new File(fileReferencesPath, fileReference);
                if ( ! IOUtils.recursiveDeleteDir(file))
                    log.log(LogLevel.WARNING, "Could not delete " + file.getAbsolutePath());
            });
        }
        return fileReferencesToDelete;
    }

    public Set<FileReference> getFileReferences(ApplicationId applicationId) {
        return getOptionalApplication(applicationId).map(app -> app.getModel().fileReferences()).orElse(Set.of());
    }

    public ApplicationFile getApplicationFileFromSession(TenantName tenantName, long sessionId, String path, LocalSession.Mode mode) {
        Tenant tenant = tenantRepository.getTenant(tenantName);
        return getLocalSession(tenant, sessionId).getApplicationFile(Path.fromString(path), mode);
    }

    private Application getApplication(ApplicationId applicationId) {
        return getApplication(applicationId, Optional.empty());
    }

    private Application getApplication(ApplicationId applicationId, Optional<Version> version) {
        try {
            Tenant tenant = tenantRepository.getTenant(applicationId.tenant());
            if (tenant == null) throw new NotFoundException("Tenant '" + applicationId.tenant() + "' not found");
            long sessionId = getSessionIdForApplication(tenant, applicationId);
            RemoteSession session = tenant.getRemoteSessionRepo().getSession(sessionId);
            return session.ensureApplicationLoaded().getForVersionOrLatest(version, clock.instant());
        } catch (NotFoundException e) {
            log.log(LogLevel.WARNING, "Failed getting application for '" + applicationId + "': " + e.getMessage());
            throw e;
        } catch (Exception e) {
            log.log(LogLevel.WARNING, "Failed getting application for '" + applicationId + "'", e);
            throw e;
        }
    }

    private Optional<Application> getOptionalApplication(ApplicationId applicationId) {
        try {
            return Optional.of(getApplication(applicationId));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    Set<ApplicationId> listApplications() {
        return tenantRepository.getAllTenants().stream()
                .flatMap(tenant -> tenant.getApplicationRepo().activeApplications().stream())
                .collect(Collectors.toSet());
    }

    private boolean isFileLastModifiedBefore(File fileReference, Instant instant) {
        BasicFileAttributes fileAttributes;
        try {
            fileAttributes = readAttributes(fileReference.toPath(), BasicFileAttributes.class);
            return fileAttributes.lastModifiedTime().toInstant().isBefore(instant);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean localSessionHasBeenDeleted(ApplicationId applicationId, long sessionId, Duration waitTime) {
        RemoteSessionRepo remoteSessionRepo = tenantRepository.getTenant(applicationId.tenant()).getRemoteSessionRepo();
        Instant end = Instant.now().plus(waitTime);
        do {
            if (remoteSessionRepo.getSession(sessionId) == null) return true;
            try { Thread.sleep(10); } catch (InterruptedException e) { /* ignored */}
        } while (Instant.now().isBefore(end));

        return false;
    }

    // ---------------- Convergence ----------------------------------------------------------------

    public HttpResponse checkServiceForConfigConvergence(ApplicationId applicationId, String hostAndPort, URI uri,
                                                         Duration timeout, Optional<Version> vespaVersion) {
        return convergeChecker.checkService(getApplication(applicationId, vespaVersion), hostAndPort, uri, timeout);
    }

    public HttpResponse servicesToCheckForConfigConvergence(ApplicationId applicationId, URI uri,
                                                            Duration timeoutPerService, Optional<Version> vespaVersion) {
        return convergeChecker.servicesToCheck(getApplication(applicationId, vespaVersion), uri, timeoutPerService);
    }

    // ---------------- Logs ----------------------------------------------------------------

    public HttpResponse getLogs(ApplicationId applicationId, Optional<String> hostname, String apiParams) {
        String logServerURI = getLogServerURI(applicationId, hostname) + apiParams;
        return logRetriever.getLogs(logServerURI);
    }

    // ---------------- Session operations ----------------------------------------------------------------

    /**
     * Gets the active Session for the given application id.
     *
     * @return the active session, or null if there is no active session for the given application id.
     */
    public LocalSession getActiveSession(ApplicationId applicationId) {
        return getActiveSession(tenantRepository.getTenant(applicationId.tenant()), applicationId);
    }

    public long getSessionIdForApplication(ApplicationId applicationId) {
        Tenant tenant = tenantRepository.getTenant(applicationId.tenant());
        if (tenant == null) throw new NotFoundException("Tenant '" + applicationId.tenant() + "' not found");
        return getSessionIdForApplication(tenant, applicationId);
    }

    private long getSessionIdForApplication(Tenant tenant, ApplicationId applicationId) {
        TenantApplications applicationRepo = tenant.getApplicationRepo();
        if (applicationRepo == null)
            throw new NotFoundException("Application repo for tenant '" + tenant.getName() + "' not found");

        return applicationRepo.requireActiveSessionOf(applicationId);
    }

    public void validateThatRemoteSessionIsNotActive(Tenant tenant, long sessionId) {
        RemoteSession session = getRemoteSession(tenant, sessionId);
        if (Session.Status.ACTIVATE.equals(session.getStatus())) {
            throw new IllegalStateException("Session is active: " + sessionId);
        }
    }

    public void validateThatRemoteSessionIsPrepared(Tenant tenant, long sessionId) {
        RemoteSession session = getRemoteSession(tenant, sessionId);
        if ( ! Session.Status.PREPARE.equals(session.getStatus()))
            throw new IllegalStateException("Session not prepared: " + sessionId);
    }

    public long createSessionFromExisting(ApplicationId applicationId,
                                          DeployLogger logger,
                                          boolean internalRedeploy,
                                          TimeoutBudget timeoutBudget) {
        Tenant tenant = tenantRepository.getTenant(applicationId.tenant());
        LocalSessionRepo localSessionRepo = tenant.getLocalSessionRepo();
        SessionFactory sessionFactory = tenant.getSessionFactory();
        LocalSession fromSession = getExistingSession(tenant, applicationId);
        LocalSession session = sessionFactory.createSessionFromExisting(fromSession, logger, internalRedeploy, timeoutBudget);
        localSessionRepo.addSession(session);
        return session.getSessionId();
    }

    public long createSession(ApplicationId applicationId, TimeoutBudget timeoutBudget, InputStream in, String contentType) {
        File tempDir = Files.createTempDir();
        long sessionId;
        try {
            sessionId = createSession(applicationId, timeoutBudget, decompressApplication(in, contentType, tempDir));
        } finally {
            cleanupTempDirectory(tempDir);
        }
        return sessionId;
    }

    public long createSession(ApplicationId applicationId, TimeoutBudget timeoutBudget, File applicationDirectory) {
        Tenant tenant = tenantRepository.getTenant(applicationId.tenant());
        tenant.getApplicationRepo().createApplication(applicationId);
        LocalSessionRepo localSessionRepo = tenant.getLocalSessionRepo();
        SessionFactory sessionFactory = tenant.getSessionFactory();
        LocalSession session = sessionFactory.createSession(applicationDirectory, applicationId, timeoutBudget);
        localSessionRepo.addSession(session);
        return session.getSessionId();
    }

    public void deleteExpiredLocalSessions() {
        tenantRepository.getAllTenants().forEach(tenant -> tenant.getLocalSessionRepo().purgeOldSessions());
    }

    public int deleteExpiredRemoteSessions(Duration expiryTime) {
        return tenantRepository.getAllTenants()
                .stream()
                .map(tenant -> tenant.getRemoteSessionRepo().deleteExpiredSessions(expiryTime))
                .mapToInt(i -> i)
                .sum();
    }

    // ---------------- Tenant operations ----------------------------------------------------------------


    public TenantRepository tenantRepository() {
        return tenantRepository;
    }

    public Set<TenantName> deleteUnusedTenants(Duration ttlForUnusedTenant, Instant now) {
        return tenantRepository.getAllTenantNames().stream()
                .filter(tenantName -> activeApplications(tenantName).isEmpty())
                .filter(tenantName -> !tenantName.equals(TenantName.defaultName())) // Not allowed to remove 'default' tenant
                .filter(tenantName -> !tenantName.equals(HOSTED_VESPA_TENANT)) // Not allowed to remove 'hosted-vespa' tenant
                .filter(tenantName -> tenantRepository.getTenant(tenantName).getCreatedTime().isBefore(now.minus(ttlForUnusedTenant)))
                .peek(tenantRepository::deleteTenant)
                .collect(Collectors.toSet());
    }

    public void deleteTenant(TenantName tenantName) {
        List<ApplicationId> activeApplications = activeApplications(tenantName);
        if (activeApplications.isEmpty())
            tenantRepository.deleteTenant(tenantName);
        else
            throw new IllegalArgumentException("Cannot delete tenant '" + tenantName + "', it has active applications: " + activeApplications);
    }

    private List<ApplicationId> activeApplications(TenantName tenantName) {
        return tenantRepository.getTenant(tenantName).getApplicationRepo().activeApplications();
    }

    // ---------------- Metrics ------------------------------------------------------------------------

    public MetricsResponse getMetrics(ApplicationId applicationId) {
        Application application = getApplication(applicationId);
        ApplicationMetricsRetriever applicationMetricsRetriever = new ApplicationMetricsRetriever();
        return applicationMetricsRetriever.getMetrics(application);
    }

    // ---------------- Misc operations ----------------------------------------------------------------

    public ApplicationMetaData getMetadataFromSession(Tenant tenant, long sessionId) {
        return getLocalSession(tenant, sessionId).getMetaData();
    }

    public ConfigserverConfig configserverConfig() {
        return configserverConfig;
    }

    private void validateThatLocalSessionIsNotActive(Tenant tenant, long sessionId) {
        LocalSession session = getLocalSession(tenant, sessionId);
        if (Session.Status.ACTIVATE.equals(session.getStatus())) {
            throw new IllegalStateException("Session is active: " + sessionId);
        }
    }

    private LocalSession getLocalSession(Tenant tenant, long sessionId) {
        LocalSession session = tenant.getLocalSessionRepo().getSession(sessionId);
        if (session == null) throw new NotFoundException("Session " + sessionId + " was not found");

        return session;
    }

    private RemoteSession getRemoteSession(Tenant tenant, long sessionId) {
        RemoteSession session = tenant.getRemoteSessionRepo().getSession(sessionId);
        if (session == null) throw new NotFoundException("Session " + sessionId + " was not found");

        return session;
    }

    private Optional<ApplicationSet> getCurrentActiveApplicationSet(Tenant tenant, ApplicationId appId) {
        Optional<ApplicationSet> currentActiveApplicationSet = Optional.empty();
        TenantApplications applicationRepo = tenant.getApplicationRepo();
        try {
            long currentActiveSessionId = applicationRepo.requireActiveSessionOf(appId);
            RemoteSession currentActiveSession = getRemoteSession(tenant, currentActiveSessionId);
            if (currentActiveSession != null) {
                currentActiveApplicationSet = Optional.ofNullable(currentActiveSession.ensureApplicationLoaded());
            }
        } catch (IllegalArgumentException e) {
            // Do nothing if we have no currently active session
        }
        return currentActiveApplicationSet;
    }

    private File decompressApplication(InputStream in, String contentType, File tempDir) {
        try (CompressedApplicationInputStream application =
                     CompressedApplicationInputStream.createFromCompressedStream(in, contentType)) {
            return decompressApplication(application, tempDir);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to decompress data in body", e);
        }
    }

    private File decompressApplication(CompressedApplicationInputStream in, File tempDir) {
        try {
            return in.decompress(tempDir);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to decompress stream", e);
        }
    }

    private void cleanupTempDirectory(File tempDir) {
        logger.log(LogLevel.DEBUG, "Deleting tmp dir '" + tempDir + "'");
        if (!IOUtils.recursiveDeleteDir(tempDir)) {
            logger.log(LogLevel.WARNING, "Not able to delete tmp dir '" + tempDir + "'");
        }
    }

    private LocalSession getExistingSession(Tenant tenant, ApplicationId applicationId) {
        TenantApplications applicationRepo = tenant.getApplicationRepo();
        return getLocalSession(tenant, applicationRepo.requireActiveSessionOf(applicationId));
    }

    private LocalSession getActiveSession(Tenant tenant, ApplicationId applicationId) {
        TenantApplications applicationRepo = tenant.getApplicationRepo();
        if (applicationRepo.activeApplications().contains(applicationId)) {
            return tenant.getLocalSessionRepo().getSession(applicationRepo.requireActiveSessionOf(applicationId));
        }
        return null;
    }

    private static void logConfigChangeActions(ConfigChangeActions actions, DeployLogger logger) {
        RestartActions restartActions = actions.getRestartActions();
        if ( ! restartActions.isEmpty()) {
            logger.log(Level.WARNING, "Change(s) between active and new application that require restart:\n" +
                    restartActions.format());
        }
        RefeedActions refeedActions = actions.getRefeedActions();
        if ( ! refeedActions.isEmpty()) {
            boolean allAllowed = refeedActions.getEntries().stream().allMatch(RefeedActions.Entry::allowed);
            logger.log(allAllowed ? Level.INFO : Level.WARNING,
                       "Change(s) between active and new application that may require re-feed:\n" +
                               refeedActions.format());
        }
    }

    private String getLogServerURI(ApplicationId applicationId, Optional<String> hostname) {
        // Allow to get logs from a given hostname if the application is under the hosted-vespa tenant.
        // We make no validation that the hostname is actually allocated to the given application since
        // most applications under hosted-vespa are not known to the model and its OK for a user to get
        // logs for any host if they are authorized for the hosted-vespa tenant.
        if (hostname.isPresent()) {
            if (HOSTED_VESPA_TENANT.equals(applicationId.tenant()))
                return "http://" + hostname.get() + ":8080/logs";
            else throw new IllegalArgumentException("Only hostname paramater unsupported for application " + applicationId);
        }

        Application application = getApplication(applicationId);
        Collection<HostInfo> hostInfos = application.getModel().getHosts();

        HostInfo logServerHostInfo = hostInfos.stream()
                .filter(host -> host.getServices().stream()
                        .anyMatch(serviceInfo -> serviceInfo.getServiceType().equalsIgnoreCase("logserver")))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Could not find HostInfo for LogServer"));

        ServiceInfo serviceInfo = logServerHostInfo.getServices().stream().filter(service -> List.of(LOGSERVER_CONTAINER.serviceName, CONTAINER.serviceName).contains(service.getServiceType()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No container running on logserver host"));
        int port = servicePort(serviceInfo);
        return "http://" + logServerHostInfo.getHostname() + ":" + port + "/logs";
    }

    private int servicePort(ServiceInfo serviceInfo) {
        int port = serviceInfo.getPorts().stream()
                .filter(portInfo -> portInfo.getTags().stream().anyMatch(tag -> tag.equalsIgnoreCase("http")))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Could not find HTTP port"))
                .getPort();
        return port;
    }

    public Slime createDeployLog() {
        Slime deployLog = new Slime();
        deployLog.setObject();
        return deployLog;
    }

    public Zone zone() {
        return new Zone(SystemName.from(configserverConfig.system()),
                        Environment.from(configserverConfig.environment()),
                        RegionName.from(configserverConfig.region()));
    }

}
