// Copyright 2019 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.flags;

import com.yahoo.vespa.defaults.Defaults;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static com.yahoo.vespa.flags.FetchVector.Dimension.APPLICATION_ID;
import static com.yahoo.vespa.flags.FetchVector.Dimension.HOSTNAME;

/**
 * @author hakonhall
 */
public class Flags {
    private static volatile TreeMap<FlagId, FlagDefinition> flags = new TreeMap<>();

    public static final UnboundBooleanFlag HEALTHMONITOR_MONITOR_INFRA = defineFeatureFlag(
            "healthmonitor-monitorinfra", true,
                    "Whether the health monitor in service monitor monitors the health of infrastructure applications.",
                    "Affects all applications activated after the value is changed.",
            HOSTNAME);

    public static final UnboundBooleanFlag DUPERMODEL_CONTAINS_INFRA = defineFeatureFlag(
            "dupermodel-contains-infra", true,
            "Whether the DuperModel in config server/controller includes active infrastructure applications " +
                    "(except from controller/config apps).",
            "Requires restart of config server/controller to take effect.",
            HOSTNAME);

    public static final UnboundBooleanFlag DUPERMODEL_USE_CONFIGSERVERCONFIG = defineFeatureFlag(
            "dupermodel-use-configserverconfig", true,
            "For historical reasons, the ApplicationInfo in the DuperModel for controllers and config servers " +
                    "is based on the ConfigserverConfig (this flag is true). We want to transition to use the " +
                    "infrastructure application activated by the InfrastructureProvisioner once that supports health.",
            "Requires restart of config server/controller to take effect.",
            HOSTNAME);

    public static final UnboundBooleanFlag USE_CONFIG_SERVER_CACHE = defineFeatureFlag(
            "use-config-server-cache", true,
            "Whether config server will use cache to answer config requests.",
            "Takes effect immediately when changed.",
            HOSTNAME, FetchVector.Dimension.APPLICATION_ID);

    public static final UnboundBooleanFlag CONFIG_SERVER_BOOTSTRAP_IN_SEPARATE_THREAD = defineFeatureFlag(
            "config-server-bootstrap-in-separate-thread", true,
            "Whether to run config server/controller bootstrap in a separate thread.",
            "Takes effect only at bootstrap of config server/controller",
            HOSTNAME);

    public static final UnboundBooleanFlag PROXYHOST_USES_REAL_ORCHESTRATOR = defineFeatureFlag(
            "proxyhost-uses-real-orchestrator", true,
            "Whether proxy hosts uses the real Orchestrator when suspending/resuming, or a synthetic.",
            "Takes effect immediately when changed.",
            HOSTNAME);

    public static final UnboundBooleanFlag CONFIGHOST_USES_REAL_ORCHESTRATOR = defineFeatureFlag(
            "confighost-uses-real-orchestrator", true,
            "Whether the config server hosts uses the real Orchestrator when suspending/resuming, or a synthetic.",
            "Takes effect immediately when changed.",
            HOSTNAME);

    public static final UnboundBooleanFlag ENABLE_CROWDSTRIKE = defineFeatureFlag(
            "enable-crowdstrike", true,
            "Whether to enable CrowdStrike.", "Takes effect on next host admin tick",
            HOSTNAME);

    public static final UnboundBooleanFlag ENABLE_NESSUS = defineFeatureFlag(
            "enable-nessus", true,
            "Whether to enable Nessus.", "Takes effect on next host admin tick",
            HOSTNAME);

    public static final UnboundBooleanFlag ENABLE_CPU_TEMPERATURE_TASK = defineFeatureFlag(
            "enable-cputemptask", true,
            "Whether to enable CPU temperature task", "Takes effect on next host admin tick",
            HOSTNAME);
    
    public static final UnboundBooleanFlag ENABLE_LOGSERVER = defineFeatureFlag(
            "enable-logserver", false,
            "Whether to enable logserver.", "Takes effect at redeployment",
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
    public static <T> UnboundJacksonFlag<T> defineJacksonFlag(String flagId, T defaultValue, Class<T> jacksonClass, String description,
                                                              String modificationEffect, FetchVector.Dimension... dimensions) {
        return define((id2, defaultValue2, vector2) -> new UnboundJacksonFlag<>(id2, defaultValue2, vector2, jacksonClass),
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
        return new ArrayList<>(flags.values());
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
