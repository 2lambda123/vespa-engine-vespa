// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server;

import com.google.inject.Inject;
import com.yahoo.component.AbstractComponent;
import com.yahoo.config.provision.Deployer;
import com.yahoo.log.LogLevel;
import com.yahoo.vespa.config.server.rpc.RpcServer;
import com.yahoo.vespa.config.server.tenant.Tenants;
import com.yahoo.vespa.config.server.version.VersionState;

/**
 * Main component that bootstraps and starts config server threads.
 *
 * @author lulf
 * @since 5.1
 */
public class ConfigServerBootstrap extends AbstractComponent implements Runnable {

    private static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(ConfigServerBootstrap.class.getName());
    private final RpcServer server;
    private final Thread serverThread;

    // The tenants object is injected so that all initial requests handlers are
    // added to the rpcserver before it starts answering rpc requests.
    @SuppressWarnings("UnusedParameters")
    @Inject
    public ConfigServerBootstrap(Tenants tenants, RpcServer server, Deployer deployer, VersionState versionState) {
        this.server = server;
        if (versionState.isUpgraded()) {
            log.log(LogLevel.INFO, "Configserver upgraded from " + versionState.storedVersion() + " to " + versionState.currentVersion() + ". Redeploying all applications");
            tenants.redeployApplications(deployer);
            log.log(LogLevel.INFO, "All applications redeployed");
        }
        versionState.saveNewVersion();
        this.serverThread = new Thread(this, "configserver main");
        serverThread.start();
    }

    @Override
    public void deconstruct() {
        log.log(LogLevel.INFO, "Stopping config server");
        server.stop();
        try {
            serverThread.join();
        } catch (InterruptedException e) {
            log.log(LogLevel.WARNING, "Error joining server thread on shutdown: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        log.log(LogLevel.DEBUG, "Starting RPC server");
        server.run();
        log.log(LogLevel.DEBUG, "RPC server stopped");
    }

}

