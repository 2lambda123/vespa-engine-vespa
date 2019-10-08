// Copyright 2019 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.flags;

import com.yahoo.vespa.defaults.Defaults;
import com.yahoo.vespa.flags.custom.PreprovisionCapacity;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static com.yahoo.vespa.flags.FetchVector.Dimension.APPLICATION_ID;
import static com.yahoo.vespa.flags.FetchVector.Dimension.HOSTNAME;
import static com.yahoo.vespa.flags.FetchVector.Dimension.NODE_TYPE;

/**
 * Definitions of feature flags.
 *
 * <p>To use feature flags, define the flag in this class as an "unbound" flag, e.g. {@link UnboundBooleanFlag}
 * or {@link UnboundStringFlag}. At the location you want to get the value of the flag, you need the following:</p>
 *
 * <ol>
 *     <li>The unbound flag</li>
 *     <li>A {@link FlagSource}. The flag source is typically available as an injectable component. Binding
 *     an unbound flag to a flag source produces a (bound) flag, e.g. {@link BooleanFlag} and {@link StringFlag}.</li>
 *     <li>If you would like your flag value to be dependent on e.g. the application ID, then 1. you should
 *     declare this in the unbound flag definition in this file (referring to
 *     {@link FetchVector.Dimension#APPLICATION_ID}), and 2. specify the application ID when retrieving the value, e.g.
 *     {@link BooleanFlag#with(FetchVector.Dimension, String)}. See {@link FetchVector} for more info.</li>
 * </ol>
 *
 * <p>Once the code is in place, you can override the flag value. This depends on the flag source, but typically
 * there is a REST API for updating the flags in the config server, which is the root of all flag sources in the zone.</p>
 *
 * @author hakonhall
 */
public class Flags {
    private static volatile TreeMap<FlagId, FlagDefinition> flags = new TreeMap<>();

    public static final UnboundIntFlag DROP_CACHES = defineIntFlag("drop-caches", 3,
            "The int value to write into /proc/sys/vm/drop_caches for each tick. " +
            "1 is page cache, 2 is dentries inodes, 3 is both page cache and dentries inodes, etc.",
            "Takes effect on next tick.",
            HOSTNAME);

    public static final UnboundBooleanFlag ENABLE_CROWDSTRIKE = defineFeatureFlag(
            "enable-crowdstrike", true,
            "Whether to enable CrowdStrike.", "Takes effect on next host admin tick",
            HOSTNAME);

    public static final UnboundBooleanFlag ENABLE_NESSUS = defineFeatureFlag(
            "enable-nessus", true,
            "Whether to enable Nessus.", "Takes effect on next host admin tick",
            HOSTNAME);

    public static final UnboundListFlag<String> DISABLED_HOST_ADMIN_TASKS = defineListFlag(
            "disabled-host-admin-tasks", List.of(), String.class,
            "List of host-admin task names (as they appear in the log, e.g. root>main>UpgradeTask) that should be skipped",
            "Takes effect on next host admin tick",
            HOSTNAME, NODE_TYPE);

    public static final UnboundStringFlag DOCKER_VERSION = defineStringFlag(
            "docker-version", "1.13.1-91.git07f3374",
            "The version of the docker to use of the format VERSION-REL: The YUM package to be installed will be " +
            "2:docker-VERSION-REL.el7.centos.x86_64 in AWS (and without '.centos' otherwise). " +
            "If docker-version is not of this format, it must be parseable by YumPackageName::fromString.",
            "Takes effect on next tick.",
            HOSTNAME);

    public static final UnboundLongFlag THIN_POOL_GB = defineLongFlag(
            "thin-pool-gb", -1,
            "The size of the disk reserved for the thin pool with dynamic provisioning in AWS, in base-2 GB. " +
                    "If <0, the default is used (which may depend on the zone and node type).",
            "Takes effect immediately (but used only during provisioning).",
            NODE_TYPE);

    public static final UnboundDoubleFlag CONTAINER_CPU_CAP = defineDoubleFlag(
            "container-cpu-cap", 0,
            "Hard limit on how many CPUs a container may use. This value is multiplied by CPU allocated to node, so " +
            "to cap CPU at 200%, set this to 2, etc.",
            "Takes effect on next node agent tick. Change is orchestrated, but does NOT require container restart",
            HOSTNAME, APPLICATION_ID);

    public static final UnboundBooleanFlag INCLUDE_SIS_IN_TRUSTSTORE = defineFeatureFlag(
            "include-sis-in-truststore", false,
            "Whether to use the trust store backed by Athenz and (in public) Service Identity certificates in " +
            "host-admin and/or Docker containers",
            "Takes effect on restart of host-admin (for host-admin), and restart of Docker container.",
            // For host-admin, HOSTNAME and NODE_TYPE is available
            // For Docker containers, HOSTNAME and APPLICATION_ID is available
            // WARNING: Having different sets of dimensions is DISCOURAGED in general, but needed for here since
            // trust store for host-admin is determined before having access to application ID from node repo.
            HOSTNAME, NODE_TYPE, APPLICATION_ID);

    public static final UnboundStringFlag TLS_INSECURE_MIXED_MODE = defineStringFlag(
            "tls-insecure-mixed-mode", "tls_client_mixed_server",
            "TLS insecure mixed mode. Allowed values: ['plaintext_client_mixed_server', 'tls_client_mixed_server', 'tls_client_tls_server']",
            "Takes effect on restart of Docker container",
            NODE_TYPE, APPLICATION_ID, HOSTNAME);

    public static final UnboundStringFlag TLS_INSECURE_AUTHORIZATION_MODE = defineStringFlag(
            "tls-insecure-authorization-mode", "log_only",
            "TLS insecure authorization mode. Allowed values: ['disable', 'log_only', 'enforce']",
            "Takes effect on restart of Docker container",
            NODE_TYPE, APPLICATION_ID, HOSTNAME);

    public static final UnboundBooleanFlag USE_ADAPTIVE_DISPATCH = defineFeatureFlag(
            "use-adaptive-dispatch", false,
            "Should adaptive dispatch be used over round robin",
            "Takes effect at redeployment",
            APPLICATION_ID);

    public static final UnboundBooleanFlag ENABLE_DYNAMIC_PROVISIONING = defineFeatureFlag(
            "enable-dynamic-provisioning", false,
            "Provision a new docker host when we otherwise can't allocate a docker node",
            "Takes effect on next deployment",
            APPLICATION_ID);

    public static final UnboundListFlag<PreprovisionCapacity> PREPROVISION_CAPACITY = defineListFlag(
            "preprovision-capacity", List.of(), PreprovisionCapacity.class,
            "List of node resources and their count that should be present in zone to receive new deployments. When a " +
            "preprovisioned is taken, new will be provisioned within next iteration of maintainer.",
            "Takes effect on next iteration of HostProvisionMaintainer.");

    public static final UnboundBooleanFlag USE_ADVERTISED_RESOURCES = defineFeatureFlag(
            "use-advertised-resources", true,
            "When enabled, will use advertised host resources rather than actual host resources, ignore host resource " +
                    "reservation, and fail with exception unless requested resource match advertised host resources exactly.",
            "Takes effect on next iteration of HostProvisionMaintainer.",
            APPLICATION_ID);

    public static final UnboundStringFlag CONFIGSERVER_RPC_AUTHORIZER = defineStringFlag(
            "configserver-rpc-authorizer", "enforce",
            "Configserver RPC authorizer. Allowed values: ['disable', 'log-only', 'enforce']",
            "Takes effect on restart of configserver");

    public static final UnboundBooleanFlag PROVISION_APPLICATION_CERTIFICATE = defineFeatureFlag(
            "provision-application-certificate", false,
            "Provision certificate from CA and include reference in deployment",
            "Takes effect on deployment through controller",
            APPLICATION_ID);

    public static final UnboundBooleanFlag DIRECT_ROUTING_USE_HTTPS_4443 = defineFeatureFlag(
            "direct-routing-use-https-4443", false,
            "Decides whether NLB is pointed at container on port 4443 (https) or 4080 (http)",
            "Takes effect at redeployment",
            APPLICATION_ID
    );

    public static final UnboundBooleanFlag MULTIPLE_GLOBAL_ENDPOINTS = defineFeatureFlag(
            "multiple-global-endpoints", false,
            "Allow applications to use new endpoints syntax in deployment.xml",
            "Takes effect on deployment through controller",
            APPLICATION_ID);

    public static final UnboundDoubleFlag DEFAULT_TERM_WISE_LIMIT = defineDoubleFlag(
            "default-term-wise-limit", 1.0,
            "Node resource memory in Gb for admin cluster nodes",
            "Takes effect at redeployment",
            APPLICATION_ID);

    public static final UnboundBooleanFlag HOST_HARDENING = defineFeatureFlag(
            "host-hardening", false,
            "Whether to enable host hardening Linux baseline.",
            "Takes effect on next tick or on host-admin restart (may vary where used).",
            HOSTNAME);

    public static final UnboundBooleanFlag USE_INTERNAL_ZTS = defineFeatureFlag(
            "use-internal-zts", false,
            "Decides if certificate in public should be requested from 'zts' configserver or mapped in",
            "Takes effect on next tick or on host-admin restart.",
            APPLICATION_ID);

    public static final UnboundBooleanFlag HEALTH_CHECK_ON_4081 = defineFeatureFlag(
            "health-check-on-4081", false,
            "Change nginx to send health check requests on port 4081 instead of 4080.",
            "Takes effect on routing container redeployment",
            APPLICATION_ID);

    public static final UnboundIntFlag NGINX_UPSTREAM_KEEPALIVE_MULTIPLIER = defineIntFlag(
            "nginx-upstream-keepalive-multiplier", 2,
            "Multiplied with the number of servers to calculate the keepalive value for a upstream definition.",
            "Takes effect within a minute",
            APPLICATION_ID);

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static UnboundBooleanFlag defineFeatureFlag(String flagId, boolean defaultValue, String description,
                                                       String modificationEffect, FetchVector.Dimension... dimensions) {
        return define(UnboundBooleanFlag::new, flagId, defaultValue, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static UnboundStringFlag defineStringFlag(String flagId, String defaultValue, String description,
                                                     String modificationEffect, FetchVector.Dimension... dimensions) {
        return define(UnboundStringFlag::new, flagId, defaultValue, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static UnboundIntFlag defineIntFlag(String flagId, int defaultValue, String description,
                                               String modificationEffect, FetchVector.Dimension... dimensions) {
        return define(UnboundIntFlag::new, flagId, defaultValue, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static UnboundLongFlag defineLongFlag(String flagId, long defaultValue, String description,
                                                 String modificationEffect, FetchVector.Dimension... dimensions) {
        return define(UnboundLongFlag::new, flagId, defaultValue, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static UnboundDoubleFlag defineDoubleFlag(String flagId, double defaultValue, String description,
                                                     String modificationEffect, FetchVector.Dimension... dimensions) {
        return define(UnboundDoubleFlag::new, flagId, defaultValue, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static <T> UnboundJacksonFlag<T> defineJacksonFlag(String flagId, T defaultValue, Class<T> jacksonClass, String description,
                                                              String modificationEffect, FetchVector.Dimension... dimensions) {
        return define((id2, defaultValue2, vector2) -> new UnboundJacksonFlag<>(id2, defaultValue2, vector2, jacksonClass),
                flagId, defaultValue, description, modificationEffect, dimensions);
    }

    /** WARNING: public for testing: All flags should be defined in {@link Flags}. */
    public static <T> UnboundListFlag<T> defineListFlag(String flagId, List<T> defaultValue, Class<T> elementClass,
                                                        String description, String modificationEffect, FetchVector.Dimension... dimensions) {
        return define((fid, dval, fvec) -> new UnboundListFlag<>(fid, dval, elementClass, fvec),
                flagId, defaultValue, description, modificationEffect, dimensions);
    }

    @FunctionalInterface
    private interface TypedUnboundFlagFactory<T, U extends UnboundFlag<?, ?, ?>> {
        U create(FlagId id, T defaultVale, FetchVector defaultFetchVector);
    }

    /**
     * Defines a Flag.
     *
     * @param factory            Factory for creating unbound flag of type U
     * @param flagId             The globally unique FlagId.
     * @param defaultValue       The default value if none is present after resolution.
     * @param description        Description of how the flag is used.
     * @param modificationEffect What is required for the flag to take effect? A restart of process? immediately? etc.
     * @param dimensions         What dimensions will be set in the {@link FetchVector} when fetching
     *                           the flag value in
     *                           {@link FlagSource#fetch(FlagId, FetchVector) FlagSource::fetch}.
     *                           For instance, if APPLICATION is one of the dimensions here, you should make sure
     *                           APPLICATION is set to the ApplicationId in the fetch vector when fetching the RawFlag
     *                           from the FlagSource.
     * @param <T>                The boxed type of the flag value, e.g. Boolean for flags guarding features.
     * @param <U>                The type of the unbound flag, e.g. UnboundBooleanFlag.
     * @return An unbound flag with {@link FetchVector.Dimension#HOSTNAME HOSTNAME} environment. The ZONE environment
     *         is typically implicit.
     */
    private static <T, U extends UnboundFlag<?, ?, ?>> U define(TypedUnboundFlagFactory<T, U> factory,
                                                                String flagId,
                                                                T defaultValue,
                                                                String description,
                                                                String modificationEffect,
                                                                FetchVector.Dimension[] dimensions) {
        FlagId id = new FlagId(flagId);
        FetchVector vector = new FetchVector().with(HOSTNAME, Defaults.getDefaults().vespaHostname());
        U unboundFlag = factory.create(id, defaultValue, vector);
        FlagDefinition definition = new FlagDefinition(unboundFlag, description, modificationEffect, dimensions);
        flags.put(id, definition);
        return unboundFlag;
    }

    public static List<FlagDefinition> getAllFlags() {
        return List.copyOf(flags.values());
    }

    public static Optional<FlagDefinition> getFlag(FlagId flagId) {
        return Optional.ofNullable(flags.get(flagId));
    }

    /**
     * Allows the statically defined flags to be controlled in a test.
     *
     * <p>Returns a Replacer instance to be used with e.g. a try-with-resources block. Within the block,
     * the flags starts out as cleared. Flags can be defined, etc. When leaving the block, the flags from
     * before the block is reinserted.
     *
     * <p>NOT thread-safe. Tests using this cannot run in parallel.
     */
    public static Replacer clearFlagsForTesting() {
        return new Replacer();
    }

    public static class Replacer implements AutoCloseable {
        private static volatile boolean flagsCleared = false;

        private final TreeMap<FlagId, FlagDefinition> savedFlags;

        private Replacer() {
            verifyAndSetFlagsCleared(true);
            this.savedFlags = Flags.flags;
            Flags.flags = new TreeMap<>();
        }

        @Override
        public void close() {
            verifyAndSetFlagsCleared(false);
            Flags.flags = savedFlags;
        }

        /**
         * Used to implement a simple verification that Replacer is not used by multiple threads.
         * For instance two different tests running in parallel cannot both use Replacer.
         */
        private static void verifyAndSetFlagsCleared(boolean newValue) {
            if (flagsCleared == newValue) {
                throw new IllegalStateException("clearFlagsForTesting called while already cleared - running tests in parallell!?");
            }
            flagsCleared = newValue;
        }
    }
}
