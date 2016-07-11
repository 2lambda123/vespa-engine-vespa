// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.deploy;

import com.yahoo.config.model.api.*;
import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.config.application.api.FileRegistry;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Rotation;
import com.yahoo.config.provision.Version;
import com.yahoo.config.provision.Zone;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Implementation of {@link ModelContext} for configserver.
 *
 * @author lulf
 */
public class ModelContextImpl implements ModelContext {

    private final ApplicationPackage applicationPackage;
    private final Optional<Model> previousModel;
    private final Optional<ApplicationPackage> permanentApplicationPackage;
    private final DeployLogger deployLogger;
    private final ConfigDefinitionRepo configDefinitionRepo;
    private final FileRegistry fileRegistry;
    private final Optional<HostProvisioner> hostProvisioner;
    private final ModelContext.Properties properties;
    private final Optional<File> appDir;
    Optional<Version> vespaVersion;

    public ModelContextImpl(ApplicationPackage applicationPackage,
                            Optional<Model> previousModel,
                            Optional<ApplicationPackage> permanentApplicationPackage,
                            DeployLogger deployLogger,
                            ConfigDefinitionRepo configDefinitionRepo,
                            FileRegistry fileRegistry,
                            Optional<HostProvisioner> hostProvisioner,
                            ModelContext.Properties properties,
                            Optional<File> appDir,
                            Optional<Version> vespaVersion) {
        this.applicationPackage = applicationPackage;
        this.previousModel = previousModel;
        this.permanentApplicationPackage = permanentApplicationPackage;
        this.deployLogger = deployLogger;
        this.configDefinitionRepo = configDefinitionRepo;
        this.fileRegistry = fileRegistry;
        this.hostProvisioner = hostProvisioner;
        this.properties = properties;
        this.appDir = appDir;
        this.vespaVersion = vespaVersion;
    }

    @Override
    public ApplicationPackage applicationPackage() {
        return applicationPackage;
    }

    @Override
    public Optional<Model> previousModel() {
        return previousModel;
    }

    @Override
    public Optional<ApplicationPackage> permanentApplicationPackage() {
        return permanentApplicationPackage;
    }

    @Override
    public Optional<HostProvisioner> hostProvisioner() {
        return hostProvisioner;
    }

    @Override
    public DeployLogger deployLogger() {
        return deployLogger;
    }

    @Override
    public ConfigDefinitionRepo configDefinitionRepo() {
        return configDefinitionRepo;
    }

    @Override
    public FileRegistry getFileRegistry() {
        return fileRegistry;
    }

    @Override
    public ModelContext.Properties properties() {
        return properties;
    }

    @Override
    public Optional<File> appDir() {
        return appDir;
    }

    @Override
    public Optional<Version> vespaVersion() { return vespaVersion; }

    /**
    * @author lulf
    */
    public static class Properties implements ModelContext.Properties {
        private final ApplicationId applicationId;
        private final boolean multitenant;
        private final List<ConfigServerSpec> configServerSpecs;
        private final boolean hostedVespa;
        private final Zone zone;
        private final Set<Rotation> rotations;

        public Properties(ApplicationId applicationId,
                          boolean multitenant,
                          List<ConfigServerSpec> configServerSpecs,
                          boolean hostedVespa,
                          Zone zone,
                          Set<Rotation> rotations) {
            this.applicationId = applicationId;
            this.multitenant = multitenant;
            this.configServerSpecs = configServerSpecs;
            this.hostedVespa = hostedVespa;
            this.zone = zone;
            this.rotations = rotations;
        }

        @Override
        public boolean multitenant() {
            return multitenant;
        }

        @Override
        public ApplicationId applicationId() {
            return applicationId;
        }

        @Override
        public List<ConfigServerSpec> configServerSpecs() {
            return configServerSpecs;
        }

        @Override
        public boolean hostedVespa() {
            return hostedVespa;
        }

        @Override
        public Zone zone() {
            return zone;
        }

        @Override
        public Set<Rotation> rotations() {
            return rotations;
        }
    }

}
