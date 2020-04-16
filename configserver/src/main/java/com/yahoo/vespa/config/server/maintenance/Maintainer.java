// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.maintenance;

import com.google.common.util.concurrent.UncheckedTimeoutException;
import com.yahoo.component.AbstractComponent;
import com.yahoo.concurrent.DaemonThreadFactory;
import com.yahoo.path.Path;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.curator.Lock;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A maintainer is some job which runs at a fixed interval to perform some maintenance task in the config server.
 *
 * @author hmusum
 */
public abstract class Maintainer extends AbstractComponent implements Runnable {

    protected static final Logger log = Logger.getLogger(Maintainer.class.getName());
    private static final Path root = Path.fromString("/configserver/v1/");
    private static final Path lockRoot = root.append("locks");

    private final Duration maintenanceInterval;
    private final ScheduledExecutorService service;
    protected final ApplicationRepository applicationRepository;
    protected final Curator curator;

    Maintainer(ApplicationRepository applicationRepository, Curator curator, Duration interval) {
        this(applicationRepository, curator, interval, interval);
    }

    Maintainer(ApplicationRepository applicationRepository, Curator curator, Duration initialDelay, Duration interval) {
        this.applicationRepository = applicationRepository;
        this.curator = curator;
        this.maintenanceInterval = interval;
        service = new ScheduledThreadPoolExecutor(1, new DaemonThreadFactory(name()));
        service.scheduleAtFixedRate(this, initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }


    @Override
    @SuppressWarnings({"try", "unused"})
    public void run() {
        try (Lock lock = curator.lock(lockRoot.append(name()), Duration.ofSeconds(1))) {
            maintain();
        } catch (UncheckedTimeoutException e) {
            // another config server instance is running this job at the moment; ok
        } catch (Throwable t) {
            log.log(Level.WARNING, this + " failed. Will retry in " + maintenanceInterval.toMinutes() + " minutes", t);
        }
    }

    @Override
    public void deconstruct() {
        this.service.shutdown();
    }

    /**
     * Called once each time this maintenance job should run
     */
    protected abstract void maintain();

    public String name() { return this.getClass().getSimpleName(); }

    /**
     * Returns the name of this
     */
    @Override
    public final String toString() {
        return name();
    }

}
