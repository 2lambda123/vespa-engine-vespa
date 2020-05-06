// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.maintenance;

import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.curator.Curator;

import java.time.Duration;

/**
 * Removes inactive sessions
 * <p>
 * Note: Unit test is in ApplicationRepositoryTest
 *
 * @author hmusum
 */
public class SessionsMaintainer extends ConfigServerMaintainer {
    private final boolean hostedVespa;

    SessionsMaintainer(ApplicationRepository applicationRepository, Curator curator, Duration interval) {
        // Start this maintainer immediately. It frees disk space, so if disk goes full and config server
        // restarts this makes sure that cleanup will happen as early as possible
        super(applicationRepository, curator, Duration.ZERO, interval);
        this.hostedVespa = applicationRepository.configserverConfig().hostedVespa();
    }

    @Override
    protected void maintain() {
        applicationRepository.deleteExpiredLocalSessions();

        // Expired remote sessions are sessions that belong to an application that have external deployments that
        // are no longer active
        if (hostedVespa) {
            // TODO: Reduce to 7 days in steps, otherwise startup of config server takes a long time
            Duration expiryTime = Duration.ofDays(25);
            applicationRepository.deleteExpiredRemoteSessions(expiryTime);
        }
    }
}
