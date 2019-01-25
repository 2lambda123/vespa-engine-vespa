// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.service.health;

import com.yahoo.vespa.applicationmodel.ServiceStatus;
import com.yahoo.yolean.Exceptions;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The result of a health lookup.
 *
 * @author hakon
 */
public class HealthInfo {
    public static final String UP_STATUS_CODE = "up";

    private final Optional<Exception> exception;
    private final OptionalInt httpStatusCode;
    private final Optional<String> healthStatusCode;
    private final Instant time;

    static HealthInfo fromException(Exception exception) {
        return new HealthInfo(Optional.of(exception), OptionalInt.empty(), Optional.empty());
    }

    static HealthInfo fromBadHttpStatusCode(int httpStatusCode) {
        return new HealthInfo(Optional.empty(), OptionalInt.of(httpStatusCode), Optional.empty());
    }

    static HealthInfo fromHealthStatusCode(String healthStatusCode) {
        return new HealthInfo(Optional.empty(), OptionalInt.empty(), Optional.of(healthStatusCode));
    }

    static HealthInfo empty() {
        return new HealthInfo(Optional.empty(), OptionalInt.empty(), Optional.empty());
    }

    private HealthInfo(Optional<Exception> exception,
                       OptionalInt httpStatusCode,
                       Optional<String> healthStatusCode) {
        this.exception = exception;
        this.httpStatusCode = httpStatusCode;
        this.healthStatusCode = healthStatusCode;
        this.time = Instant.now();
    }

    public boolean isHealthy() {
        return healthStatusCode.map(UP_STATUS_CODE::equals).orElse(false);
    }

    public ServiceStatus toServiceStatus() {
        // Bootstrapping ServiceStatus: To avoid thundering herd problem at startup,
        // the clients will not fetch the health immediately. What should the ServiceStatus
        // be before the first health has been fetched?
        //
        // NOT_CHECKED: Logically the right thing, but if an Orchestrator gets a suspend request
        // in this window, and another service within the cluster is down, it ends up allowing
        // suspension when it shouldn't have done so.
        //
        // DOWN: Only safe initial value, possibly except if the first initial delay is long,
        // as that could indicate it has been down for too long.
        return isHealthy() ? ServiceStatus.UP : ServiceStatus.DOWN;
    }

    public Instant time() {
        return time;
    }

    @Override
    public String toString() {
        if (isHealthy()) {
            return UP_STATUS_CODE;
        } else if (healthStatusCode.isPresent()) {
            return "Bad health status code '" + healthStatusCode.get() + "'";
        } else if (exception.isPresent()) {
            return "Exception: " + Exceptions.toMessageString(exception.get());
        } else if (httpStatusCode.isPresent()) {
            return "Bad HTTP response status code " + httpStatusCode.getAsInt();
        } else {
            return "No health info available";
        }
    }
}
