// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Environment;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.integration.zone.ZoneRegistry;
import com.yahoo.vespa.hosted.controller.application.ApplicationList;
import com.yahoo.vespa.hosted.controller.application.Deployment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;

/**
 * Expires instances in zones that have configured expiration using TimeToLive.
 * 
 * @author mortent
 * @author bratseth
 */
public class DeploymentExpirer extends Maintainer {

    private final Clock clock;

    public DeploymentExpirer(Controller controller, Duration interval, JobControl jobControl) {
        this(controller, interval, Clock.systemUTC(), jobControl);
    }

    public DeploymentExpirer(Controller controller, Duration interval, Clock clock, JobControl jobControl) {
        super(controller, interval, jobControl);
        this.clock = clock;
    }

    @Override
    protected void maintain() {
        for (ApplicationList.Entry entry : controller().applications().list().asList()) {
            for (Deployment deployment : entry.deployments().values()) {
                if (deployment.zone().environment().equals(Environment.prod)) continue;

                if (hasExpired(controller().zoneRegistry(), deployment, clock.instant()))
                    deactivate(entry.id(), deployment);
            }
        }
    }

    private void deactivate(ApplicationId id, Deployment deployment) {
        try {
            controller().applications().deactivate(id, deployment, true);
        }
        catch (Exception e) {
            log.log(Level.WARNING, "Could not expire " + deployment + " of " + id, e);
        }
    }

    public static boolean hasExpired(ZoneRegistry zoneRegistry, Deployment deployment, Instant now) {
        return zoneRegistry.getDeploymentTimeToLive(deployment.zone().environment(), deployment.zone().region())
                .map(duration -> getExpiration(deployment, duration))
                .map(now::isAfter)
                .orElse(false);
    }

    private static Instant getExpiration(Deployment instance, Duration ttl) {
        return instance.at().plus(ttl);
    }

}
