// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.deploy;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.config.application.api.ApplicationFile;
import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.config.model.builder.xml.XmlHelper;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.HostFilter;
import com.yahoo.config.provision.Provisioner;
import com.yahoo.config.provision.ProvisionInfo;
import com.yahoo.log.LogLevel;
import com.yahoo.path.Path;
import com.yahoo.text.XML;
import com.yahoo.transaction.NestedTransaction;
import com.yahoo.transaction.Transaction;
import com.yahoo.vespa.config.server.tenant.ActivateLock;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.config.server.TimeoutBudget;
import com.yahoo.vespa.config.server.http.InternalServerException;
import com.yahoo.vespa.config.server.session.LocalSession;
import com.yahoo.vespa.config.server.session.LocalSessionRepo;
import com.yahoo.vespa.config.server.session.PrepareParams;
import com.yahoo.vespa.config.server.session.Session;
import com.yahoo.vespa.config.server.session.SilentDeployLogger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.FileNotFoundException;
import java.io.Reader;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * The process of deploying an application.
 * Deployments are created by a {@link ApplicationRepository}.
 * Instances of this are not multithread safe.
 *
 * @author lulf
 * @author bratseth
 */
public class Deployment implements com.yahoo.config.provision.Deployment {

    private static final Logger log = Logger.getLogger(Deployment.class.getName());

    /** The session containing the application instance to activate */
    private final LocalSession session;
    private final LocalSessionRepo localSessionRepo;
    /** The path to the tenant, or null if not available (only used during prepare) */
    private final Path tenantPath;
    /** The config server config, or null if not available (only used during prepare) */
    private final ConfigserverConfig configserverConfig;
    private final Optional<Provisioner> hostProvisioner;
    private final ActivateLock activateLock;
    private final Duration timeout;
    private final Clock clock;
    private final DeployLogger logger = new SilentDeployLogger();

    private boolean prepared = false;
    
    /** Whether this model should be validated (only takes effect if prepared=false) */
    private boolean validate;

    private boolean ignoreLockFailure = false;
    private boolean ignoreSessionStaleFailure = false;

    private Deployment(LocalSession session, LocalSessionRepo localSessionRepo, Path tenantPath, ConfigserverConfig configserverConfig,
                       Optional<Provisioner> hostProvisioner, ActivateLock activateLock,
                       Duration timeout, Clock clock, boolean prepared, boolean validate) {
        this.session = session;
        this.localSessionRepo = localSessionRepo;
        this.tenantPath = tenantPath;
        this.configserverConfig = configserverConfig;
        this.hostProvisioner = hostProvisioner;
        this.activateLock = activateLock;
        this.timeout = timeout;
        this.clock = clock;
        this.prepared = prepared;
        this.validate = validate;
    }

    public static Deployment unprepared(LocalSession session, LocalSessionRepo localSessionRepo, Path tenantPath, ConfigserverConfig configserverConfig,
                                        Optional<Provisioner> hostProvisioner, ActivateLock activateLock,
                                        Duration timeout, Clock clock, boolean validate) {
        return new Deployment(session, localSessionRepo, tenantPath, configserverConfig, hostProvisioner, activateLock,
                              timeout, clock, false, validate);
    }

    public static Deployment prepared(LocalSession session, LocalSessionRepo localSessionRepo,
                                      Optional<Provisioner> hostProvisioner, ActivateLock activateLock,
                                      Duration timeout, Clock clock) {
        return new Deployment(session, localSessionRepo, null, null, hostProvisioner, activateLock,
                              timeout, clock, true, true);
    }

    public Deployment setIgnoreLockFailure(boolean ignoreLockFailure) {
        this.ignoreLockFailure = ignoreLockFailure;
        return this;
    }

    public Deployment setIgnoreSessionStaleFailure(boolean ignoreSessionStaleFailure) {
        this.ignoreSessionStaleFailure = ignoreSessionStaleFailure;
        return this;
    }

    /** Prepares this. This does nothing if this is already prepared */
    @Override
    public void prepare() {
        if (prepared) return;
        TimeoutBudget timeoutBudget = new TimeoutBudget(clock, timeout);
        session.prepare(logger,
                        /** Assumes that session has already set application id, see {@link com.yahoo.vespa.config.server.session.SessionFactoryImpl}. */
                        new PrepareParams(configserverConfig).applicationId(session.getApplicationId()).timeoutBudget(timeoutBudget).ignoreValidationErrors( ! validate),
                        Optional.empty(),
                        tenantPath);
        this.prepared = true;
    }

    /** Activates this. If it is not already prepared, this will call prepare first. */
    @Override
    public void activate() {
        if (! prepared)
            prepare();

        TimeoutBudget timeoutBudget = new TimeoutBudget(clock, timeout);
        long sessionId = session.getSessionId();
        validateSessionStatus(session);
        try {
            log.log(LogLevel.DEBUG, "Trying to acquire lock " + activateLock + " for session " + sessionId);
            boolean acquired = activateLock.acquire(timeoutBudget, ignoreLockFailure);
            if ( ! acquired) {
                log.log(LogLevel.DEBUG, "Acquiring " + activateLock + " for session " + sessionId + " returned false");
            }

            log.log(LogLevel.DEBUG, "Lock acquired " + activateLock + " for session " + sessionId);
            NestedTransaction transaction = new NestedTransaction();
            transaction.add(deactivateCurrentActivateNew(localSessionRepo.getActiveSession(session.getApplicationId()), session, ignoreSessionStaleFailure));

            // TODO: (October 2016) Remove the second part of this if statement as soon as all zone applications stop using hosts.xml for routing nodes
            log.log(LogLevel.INFO, "Activating " + session.getProvisionInfo().getHosts() + ". isHostedRoutingApplicationUsingRoutingNodesInNodeRepo:" + isHostedRoutingApplicationUsingRoutingNodesInNodeRepo(session));
            if (hostProvisioner.isPresent() &&
                    (isNotHostedRoutingApplication(session.getApplicationId()) || isHostedRoutingApplicationUsingRoutingNodesInNodeRepo(session))) {
                hostProvisioner.get().activate(transaction, session.getApplicationId(), session.getProvisionInfo().getHosts());
            }
            transaction.commit();
            session.waitUntilActivated(timeoutBudget);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new InternalServerException("Error activating application", e);
        } finally {
            log.log(LogLevel.DEBUG, "Trying to release lock " + activateLock + " for session " + sessionId);
            activateLock.release();
            log.log(LogLevel.DEBUG, "Lock released " + activateLock + " for session " + sessionId);
        }
        log.log(LogLevel.INFO, session.logPre() + "Session " + sessionId + 
                               " activated successfully using " +
                               ( hostProvisioner.isPresent() ? hostProvisioner.get() : "no host provisioner" ) +
                               ". Config generation " + session.getMetaData().getGeneration());
    }

    /**
     * Request a restart of services of this application on hosts matching the filter.
     * This is sometimes needed after activation, but can also be requested without
     * doing prepare and activate in the same session.
     */
    public void restart(HostFilter filter) {
        hostProvisioner.get().restart(session.getApplicationId(), filter);
    }

    private long validateSessionStatus(LocalSession localSession) {
        long sessionId = localSession.getSessionId();
        if (Session.Status.NEW.equals(localSession.getStatus())) {
            throw new IllegalStateException(localSession.logPre() + "Session " + sessionId + " is not prepared");
        } else if (Session.Status.ACTIVATE.equals(localSession.getStatus())) {
            throw new IllegalArgumentException(localSession.logPre() + "Session " + sessionId + " is already active");
        }
        return sessionId;
    }

    private Transaction deactivateCurrentActivateNew(LocalSession currentActiveSession, LocalSession session, boolean ignoreStaleSessionFailure) {
        Transaction transaction = session.createActivateTransaction();
        if (isValidSession(currentActiveSession)) {
            checkIfActiveHasChanged(session, currentActiveSession, ignoreStaleSessionFailure);
            checkIfActiveIsNewerThanSessionToBeActivated(session.getSessionId(), currentActiveSession.getSessionId());
            transaction.add(currentActiveSession.createDeactivateTransaction().operations());
        }
        return transaction;
    }

    private boolean isValidSession(LocalSession session) {
        return session != null;
    }

    private void checkIfActiveHasChanged(LocalSession session, LocalSession currentActiveSession, boolean ignoreStaleSessionFailure) {
        long activeSessionAtCreate = session.getActiveSessionAtCreate();
        log.log(LogLevel.DEBUG, currentActiveSession.logPre() + "active session id at create time=" + activeSessionAtCreate);
        if (activeSessionAtCreate == 0) return; // No active session at create

        long sessionId = session.getSessionId();
        long currentActiveSessionSessionId = currentActiveSession.getSessionId();
        log.log(LogLevel.DEBUG, currentActiveSession.logPre() + "sessionId=" + sessionId + 
                                ", current active session=" + currentActiveSessionSessionId);
        if (currentActiveSession.isNewerThan(activeSessionAtCreate) &&
                currentActiveSessionSessionId != sessionId) {
            String errMsg = currentActiveSession.logPre()+"Cannot activate session " +
                            sessionId + " because the currently active session (" +
                            currentActiveSessionSessionId + ") has changed since session " + sessionId +
                            " was created (was " + activeSessionAtCreate + " at creation time)";
            if (ignoreStaleSessionFailure) {
                log.warning(errMsg + " (Continuing because of force.)");
            } else {
                throw new IllegalStateException(errMsg);
            }
        }
    }

    // As of now, config generation is based on session id, and config generation must be an monotonically
    // increasing number
    private void checkIfActiveIsNewerThanSessionToBeActivated(long sessionId, long currentActiveSessionId) {
        if (sessionId < currentActiveSessionId) {
            throw new IllegalArgumentException("It is not possible to activate session " + sessionId +
                                               ", because it is older than current active session (" + 
                                               currentActiveSessionId + ")");
        }
    }

    private boolean isNotHostedRoutingApplication(ApplicationId applicationId) {
        return ! applicationId.isHostedVespaRoutingApplication();
    }

    // Precondition: session is for a hosted routing application
    boolean isHostedRoutingApplicationUsingRoutingNodesInNodeRepo(LocalSession session) {
        Path servicesPath = Path.fromString(".preprocessed/" + ApplicationPackage.SERVICES);
        ApplicationFile services = session.getApplicationFile(servicesPath, LocalSession.Mode.READ);

        if ( ! services.exists()) return false;

        try {
            return usesRoutingNodesInNodeRepo(services.createReader());
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Could not create reader for " + servicesPath + " for '" + session.getApplicationId() + "'");
        }
    }

    // TODO: Copied verbatim from VespaModelFactory, since we need it now and it is not available for all model versions yet.
    //       Remove or use the one from VespaModelFactory as soon as possible
    private boolean usesRoutingNodesInNodeRepo(Reader servicesReader) {
        Document services = XmlHelper.getDocument(servicesReader);

        Element jdisc = XML.getChild(services.getDocumentElement(), "jdisc");
        if (jdisc == null) return false;

        Element nodes = XML.getChild(jdisc, "nodes");
        if (nodes == null) return false;

        return nodes.hasAttribute("type");
    }

}
