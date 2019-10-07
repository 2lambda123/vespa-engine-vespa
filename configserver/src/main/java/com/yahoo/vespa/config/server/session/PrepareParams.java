// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.session;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Rotation;
import com.yahoo.config.provision.TenantName;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.slime.Slime;
import com.yahoo.vespa.config.SlimeUtils;
import com.yahoo.vespa.config.server.TimeoutBudget;
import com.yahoo.vespa.config.server.http.SessionHandler;
import com.yahoo.config.model.api.ContainerEndpoint;
import com.yahoo.vespa.config.server.tenant.ContainerEndpointSerializer;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Parameters for prepare. Immutable.
 *
 * @author Ulf Lilleengen
 */
public final class PrepareParams {

    static final String APPLICATION_NAME_PARAM_NAME = "applicationName";
    static final String INSTANCE_PARAM_NAME = "instance";
    static final String IGNORE_VALIDATION_PARAM_NAME = "ignoreValidationErrors";
    static final String DRY_RUN_PARAM_NAME = "dryRun";
    static final String VERBOSE_PARAM_NAME = "verbose";
    static final String VESPA_VERSION_PARAM_NAME = "vespaVersion";
    static final String ROTATIONS_PARAM_NAME = "rotations";
    static final String CONTAINER_ENDPOINTS_PARAM_NAME = "containerEndpoints";
    static final String TLS_SECRETS_KEY_NAME_PARAM_NAME = "tlsSecretsKeyName";
    static final String TLS_SECRETS_KEY_VERSION_PARAM_NAME = "tlsSecretsKeyVersion";

    private final ApplicationId applicationId;
    private final TimeoutBudget timeoutBudget;
    private final boolean ignoreValidationErrors;
    private final boolean dryRun;
    private final boolean verbose;
    private final boolean isBootstrap;
    private final Optional<Version> vespaVersion;
    private final Set<Rotation> rotations;
    private final List<ContainerEndpoint> containerEndpoints;
    private final Optional<String> tlsSecretsKeyName;
    private final Optional<Integer> tlsSecretsKeyVersion;

    private PrepareParams(ApplicationId applicationId, TimeoutBudget timeoutBudget, boolean ignoreValidationErrors,
                          boolean dryRun, boolean verbose, boolean isBootstrap, Optional<Version> vespaVersion, Set<Rotation> rotations,
			  List<ContainerEndpoint> containerEndpoints, Optional<String> tlsSecretsKeyName, Optional<Integer> tlsSecretsKeyVersion) {
        this.timeoutBudget = timeoutBudget;
        this.applicationId = applicationId;
        this.ignoreValidationErrors = ignoreValidationErrors;
        this.dryRun = dryRun;
        this.verbose = verbose;
        this.isBootstrap = isBootstrap;
        this.vespaVersion = vespaVersion;
        this.rotations = rotations;
        this.containerEndpoints = containerEndpoints;
        if ((rotations != null && !rotations.isEmpty()) && !containerEndpoints.isEmpty()) {
            throw new IllegalArgumentException("Cannot set both rotations and containerEndpoints");
        }
        this.tlsSecretsKeyName = tlsSecretsKeyName;
        this.tlsSecretsKeyVersion = tlsSecretsKeyVersion;
    }

    public static class Builder {

        private boolean ignoreValidationErrors = false;
        private boolean dryRun = false;
        private boolean verbose = false;
        private boolean isBootstrap = false;
        private ApplicationId applicationId = ApplicationId.defaultId();
        private TimeoutBudget timeoutBudget = new TimeoutBudget(Clock.systemUTC(), Duration.ofSeconds(30));
        private Optional<Version> vespaVersion = Optional.empty();
        private Set<Rotation> rotations;
        private List<ContainerEndpoint> containerEndpoints = List.of();
        private Optional<String> tlsSecretsKeyName = Optional.empty();
        private Optional<Integer> tlsSecretsKeyVersion = Optional.empty();

        public Builder() { }

        public Builder applicationId(ApplicationId applicationId) {
            this.applicationId = applicationId;
            return this;
        }

        public Builder ignoreValidationErrors(boolean ignoreValidationErrors) {
            this.ignoreValidationErrors = ignoreValidationErrors;
            return this;
        }

        public Builder dryRun(boolean dryRun) {
            this.dryRun = dryRun;
            return this;
        }

        public Builder verbose(boolean verbose) {
            this.verbose = verbose;
            return this;
        }

        public Builder isBootstrap(boolean isBootstrap) {
            this.isBootstrap = isBootstrap;
            return this;
        }

        public Builder timeoutBudget(TimeoutBudget timeoutBudget) {
            this.timeoutBudget = timeoutBudget;
            return this;
        }

        public Builder vespaVersion(String vespaVersion) {
            Optional<Version> version = Optional.empty();
            if (vespaVersion != null && !vespaVersion.isEmpty()) {
                version = Optional.of(Version.fromString(vespaVersion));
            }
            this.vespaVersion = version;
            return this;
        }

        public Builder vespaVersion(Version vespaVersion) {
            this.vespaVersion = Optional.ofNullable(vespaVersion);
            return this;
        }

        public Builder rotations(String rotationsString) {
            this.rotations = new LinkedHashSet<>();
            if (rotationsString != null && !rotationsString.isEmpty()) {
                String[] rotations = rotationsString.split(",");
                for (String s : rotations) {
                    this.rotations.add(new Rotation(s));
                }
            }
            return this;
        }

        public Builder containerEndpoints(String serialized) {
            if (serialized == null) return this;
            Slime slime = SlimeUtils.jsonToSlime(serialized);
            containerEndpoints = ContainerEndpointSerializer.endpointListFromSlime(slime);
	    return this;
	}

        public Builder tlsSecretsKeyName(String tlsSecretsKeyName) {
            this.tlsSecretsKeyName = Optional.ofNullable(tlsSecretsKeyName)
                                           .filter(s -> ! s.isEmpty());
            return this;
        }

        public Builder tlsSecretsKeyVersion(String tlsSecretsKeyVersion) {
            Optional<Integer> version = Optional.ofNullable(tlsSecretsKeyVersion).map(Integer::parseInt);
            version.ifPresent(v -> {
                if (v < 0) {
                    throw new IllegalArgumentException("TLS secret key version cannot be a negative number!");
                }
            });
            this.tlsSecretsKeyVersion = version;
            return this;
        }

        public PrepareParams build() {
            return new PrepareParams(applicationId, timeoutBudget, ignoreValidationErrors, dryRun, 
                                     verbose, isBootstrap, vespaVersion, rotations, containerEndpoints, tlsSecretsKeyName, tlsSecretsKeyVersion);
        }

    }

    public static PrepareParams fromHttpRequest(HttpRequest request, TenantName tenant, Duration barrierTimeout) {
        return new Builder().ignoreValidationErrors(request.getBooleanProperty(IGNORE_VALIDATION_PARAM_NAME))
                            .dryRun(request.getBooleanProperty(DRY_RUN_PARAM_NAME))
                            .verbose(request.getBooleanProperty(VERBOSE_PARAM_NAME))
                            .timeoutBudget(SessionHandler.getTimeoutBudget(request, barrierTimeout))
                            .applicationId(createApplicationId(request, tenant))
                            .vespaVersion(request.getProperty(VESPA_VERSION_PARAM_NAME))
                            .rotations(request.getProperty(ROTATIONS_PARAM_NAME))
                            .containerEndpoints(request.getProperty(CONTAINER_ENDPOINTS_PARAM_NAME))
                            .tlsSecretsKeyName(request.getProperty(TLS_SECRETS_KEY_NAME_PARAM_NAME))
                            .tlsSecretsKeyVersion(request.getProperty(TLS_SECRETS_KEY_VERSION_PARAM_NAME))
                            .build();
    }

    private static ApplicationId createApplicationId(HttpRequest request, TenantName tenant) {
        return new ApplicationId.Builder()
               .tenant(tenant)
               .applicationName(getPropertyWithDefault(request, APPLICATION_NAME_PARAM_NAME, "default"))
               .instanceName(getPropertyWithDefault(request, INSTANCE_PARAM_NAME, "default"))
               .build();
    }

    private static String getPropertyWithDefault(HttpRequest request, String propertyName, String defaultProperty) {
        return getProperty(request, propertyName).orElse(defaultProperty);
    }

    private static Optional<String> getProperty(HttpRequest request, String propertyName) {
        return Optional.ofNullable(request.getProperty(propertyName));
    }
    
    public String getApplicationName() {
        return applicationId.application().value();
    }

    public ApplicationId getApplicationId() {
        return applicationId;
    }

    /** Returns the Vespa version the nodes running the prepared system should have, or empty to use the system version */
    public Optional<Version> vespaVersion() { return vespaVersion; }

    /** Returns the global rotations that should be made available for this deployment */
    // TODO: Remove this once all applications have to switched to containerEndpoints
    public Set<Rotation> rotations() { return rotations; }

    /** Returns the container endpoints that should be made available for this deployment. One per cluster */
    public List<ContainerEndpoint> containerEndpoints() {
        return containerEndpoints;
    }

    public boolean ignoreValidationErrors() {
        return ignoreValidationErrors;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public boolean isBootstrap() { return isBootstrap; }

    public TimeoutBudget getTimeoutBudget() {
        return timeoutBudget;
    }

    public Optional<String> tlsSecretsKeyName() {
        return tlsSecretsKeyName;
    }

    public Optional<Integer> tlsSecretsKeyVersion() {
        return tlsSecretsKeyVersion;
    }
}
