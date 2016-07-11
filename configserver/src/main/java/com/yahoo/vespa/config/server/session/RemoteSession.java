// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.session;

import com.yahoo.config.provision.*;
import com.yahoo.vespa.config.server.*;
import com.yahoo.log.LogLevel;
import com.yahoo.vespa.config.server.application.ApplicationSet;
import com.yahoo.vespa.config.server.modelfactory.ActivatedModelsBuilder;
import com.yahoo.vespa.config.server.tenant.Tenants;
import com.yahoo.vespa.curator.Curator;

import java.util.*;
import java.util.logging.Logger;

/**
 * A RemoteSession represents a session created on another config server. This session can
 * be regarded as read only, and this interface only allows reading information about a session.
 *
 * @author lulf
 * @since 5.1
 */
public class RemoteSession extends Session {

    private static final Logger log = Logger.getLogger(RemoteSession.class.getName());
    private volatile ApplicationSet applicationSet = null;
    private final SessionZooKeeperClient zooKeeperClient;
    private final ActivatedModelsBuilder applicationLoader;

    /**
     * Creates a session. This involves loading the application, validating it and distributing it.
     *
     * @param tenant The name of the tenant creating session
     * @param sessionId The session id for this session.
     * @param globalComponentRegistry a registry of global components
     * @param zooKeeperClient a SessionZooKeeperClient instance
     */
    public RemoteSession(TenantName tenant,
                         long sessionId,
                         GlobalComponentRegistry globalComponentRegistry,
                         SessionZooKeeperClient zooKeeperClient) {
        super(tenant, sessionId);
        this.zooKeeperClient = zooKeeperClient;
        this.applicationLoader = new ActivatedModelsBuilder(tenant, sessionId, zooKeeperClient, globalComponentRegistry);
    }

    public void loadPrepared() {
        Curator.CompletionWaiter waiter = zooKeeperClient.getPrepareWaiter();
        ensureApplicationLoaded();
        waiter.notifyCompletion();
    }

    private ApplicationSet loadApplication() {
        return ApplicationSet.fromList(applicationLoader.buildModels(zooKeeperClient.readApplicationId(getTenant()),
                                                                     zooKeeperClient.loadApplicationPackage()));
    }

    public ApplicationSet ensureApplicationLoaded() {
        if (applicationSet == null) {
            applicationSet = loadApplication();
        }
        return applicationSet;
    }

    public Session.Status getStatus() {
        return zooKeeperClient.readStatus();
    }

    public void deactivate() {
        applicationSet = null;
    }

    public void makeActive(ReloadHandler reloadHandler) {
        Curator.CompletionWaiter waiter = zooKeeperClient.getActiveWaiter();
        log.log(LogLevel.DEBUG, logPre()+"Getting session from repo: " + getSessionId());
        ApplicationSet app = ensureApplicationLoaded();
        log.log(LogLevel.DEBUG, logPre() + "Reloading config for " + app);
        reloadHandler.reloadConfig(app);
        log.log(LogLevel.DEBUG, logPre() + "Notifying " + waiter);
        waiter.notifyCompletion();
        log.log(LogLevel.DEBUG, logPre() + "Session activated: " + app);
    }
    
    @Override
    public String logPre() {
        if (applicationSet != null) {
            return Tenants.logPre(applicationSet.getForVersionOrLatest(Optional.empty()).getId());
        }

        return Tenants.logPre(getTenant());
    }

    public void confirmUpload() {
        Curator.CompletionWaiter waiter = zooKeeperClient.getUploadWaiter();
        log.log(LogLevel.DEBUG, "Notifying upload waiter for session " + getSessionId());
        waiter.notifyCompletion();
        log.log(LogLevel.DEBUG, "Done notifying for session " + getSessionId());
    }

}
