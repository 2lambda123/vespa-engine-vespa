// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.orchestrator;

import com.google.common.util.concurrent.UncheckedTimeoutException;
import com.yahoo.time.TimeBudget;

import java.time.Clock;
import java.time.Duration;

/**
 * Context for the Orchestrator, e.g. timeout management.
 *
 * @author hakon
 */
public class OrchestratorContext {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private TimeBudget timeBudget;

    public OrchestratorContext(Clock clock) {
        this.timeBudget = TimeBudget.fromNow(clock, DEFAULT_TIMEOUT);
    }

    /** Get the original timeout in seconds. */
    public long getOriginalTimeoutInSeconds() {
        return timeBudget.originalTimeout().get().getSeconds();
    }

    /**
     * Get number of seconds until the deadline, or empty if there's no deadline, or throw
     * an {@link UncheckedTimeoutException} if timed out.
     */
    public float getSuboperationTimeoutInSeconds() {
        return (float) (timeBudget.timeLeftOrThrow().get().toMillis() / 1000.0);
    }
}
