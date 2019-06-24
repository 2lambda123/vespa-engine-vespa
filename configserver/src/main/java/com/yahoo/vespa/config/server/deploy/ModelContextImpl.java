// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.deploy;

import com.yahoo.component.Version;
import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.config.application.api.FileRegistry;
import com.yahoo.config.model.api.ConfigDefinitionRepo;
import com.yahoo.config.model.api.ConfigServerSpec;
import com.yahoo.config.model.api.ContainerEndpoint;
import com.yahoo.config.model.api.HostProvisioner;
import com.yahoo.config.model.api.Model;
import com.yahoo.config.model.api.ModelContext;
import com.yahoo.config.model.api.TlsSecrets;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.HostName;
import com.yahoo.config.provision.Rotation;
import com.yahoo.config.provision.Zone;
import com.yahoo.vespa.flags.FetchVector;
import com.yahoo.vespa.flags.FlagSource;
import com.yahoo.vespa.flags.Flags;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Implementation of {@link ModelContext} for configserver.
 *
 * @author Ulf Lilleengen
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

    /** The version of Vespa we are building a model for */
    private final Version modelVespaVersion;

    /**
     * The Version of Vespa this model should specify that nodes should use. Note that this
     * is separate from the version of this model, as upgrades are not immediate.
     * We may build a config model of Vespa version "a" which specifies that nodes should
     * use Vespa version "b". The "a" model will then be used by nodes who have not yet
     * upgraded to version "b".
     */
    private final Version wantedNodeVespaVersion;

    public ModelContextImpl(ApplicationPackage applicationPackage,
                            Optional<Model> previousModel,
                            Optional<ApplicationPackage> permanentApplicationPackage,
                            DeployLogger deployLogger,
                            ConfigDefinitionRepo configDefinitionRepo,
                            FileRegistry fileRegistry,
                            Optional<HostProvisioner> hostProvisioner,
                            ModelContext.Properties properties,
                            Optional<File> appDir,
                            Version modelVespaVersion,
                            Version wantedNodeVespaVersion) {
        this.applicationPackage = applicationPackage;
        this.previousModel = previousModel;
        this.permanentApplicationPackage = permanentApplicationPackage;
        this.deployLogger = deployLogger;
        this.configDefinitionRepo = configDefinitionRepo;
        this.fileRegistry = fileRegistry;
        this.hostProvisioner = hostProvisioner;
        this.properties = properties;
        this.appDir = appDir;
        this.modelVespaVersion = modelVespaVersion;
        this.wantedNodeVespaVersion = wantedNodeVespaVersion;
    }

    @Override
    public ApplicationPackage applicationPackage() { return applicationPackage; }

    @Override
    public Optional<Model> previousModel() { return previousModel; }

    @Override
    public Optional<ApplicationPackage> permanentApplicationPackage() { return permanentApplicationPackage; }

    /**
     * Returns the host provisioner to use, or empty to use the default provisioner,
     * creating hosts from the application package defined hosts
     */
    // TODO: Don't allow empty here but create the right provisioner when this is set up instead
    @Override
    public Optional<HostProvisioner> hostProvisioner() { return hostProvisioner; }

    @Override
    public DeployLogger deployLogger() { return deployLogger; }

    @Override
    public ConfigDefinitionRepo configDefinitionRepo() { return configDefinitionRepo; }

    @Override
    public FileRegistry getFileRegistry() { return fileRegistry; }

    @Override
    public ModelContext.Properties properties() { return properties; }

    @Override
    public Optional<File> appDir() { return appDir; }

    @Override
    public Version modelVespaVersion() { return modelVespaVersion; }

    @Override
    public Version wantedNodeVespaVersion() { return wantedNodeVespaVersion; }

    public static class Properties implements ModelContext.Properties {

        private final ApplicationId applicationId;
        private final boolean multitenant;
        private final List<ConfigServerSpec> configServerSpecs;
        private final HostName loadBalancerName;
        private final URI ztsUrl;
        private final String athenzDnsSuffix;
        private final boolean hostedVespa;
        private final Zone zone;
        private final Set<Rotation> rotations;
        private final Set<ContainerEndpoint> endpoints;
        private final boolean isBootstrap;
        private final boolean isFirstTimeDeployment;
        private final boolean useDedicatedNodeForLogserver;
        private final boolean useFdispatchByDefault;
        private final boolean useAdaptiveDispatch;
        private final boolean dispatchWithProtobuf;
        private final Optional<TlsSecrets> tlsSecrets;

        public Properties(ApplicationId applicationId,
                          boolean multitenantFromConfig,
                          List<ConfigServerSpec> configServerSpecs,
                          HostName loadBalancerName,
                          URI ztsUrl,
                          String athenzDnsSuffix,
                          boolean hostedVespa,
                          Zone zone,
                          Set<Rotation> rotations,
                          Set<ContainerEndpoint> endpoints,
                          boolean isBootstrap,
                          boolean isFirstTimeDeployment,
                          FlagSource flagSource,
                          Optional<TlsSecrets> tlsSecrets) {
            this.applicationId = applicationId;
            this.multitenant = multitenantFromConfig || hostedVespa || Boolean.getBoolean("multitenant");
            this.configServerSpecs = configServerSpecs;
            this.loadBalancerName = loadBalancerName;
            this.ztsUrl = ztsUrl;
            this.athenzDnsSuffix = athenzDnsSuffix;
            this.hostedVespa = hostedVespa;
            this.zone = zone;
            this.rotations = rotations;
            this.endpoints = endpoints;
            this.isBootstrap = isBootstrap;
            this.isFirstTimeDeployment = isFirstTimeDeployment;
            this.useDedicatedNodeForLogserver = Flags.USE_DEDICATED_NODE_FOR_LOGSERVER.bindTo(flagSource)
                    .with(FetchVector.Dimension.APPLICATION_ID, applicationId.serializedForm()).value();
            this.useFdispatchByDefault = Flags.USE_FDISPATCH_BY_DEFAULT.bindTo(flagSource)
                    .with(FetchVector.Dimension.APPLICATION_ID, applicationId.serializedForm()).value();
            this.dispatchWithProtobuf = Flags.DISPATCH_WITH_PROTOBUF.bindTo(flagSource)
                    .with(FetchVector.Dimension.APPLICATION_ID, applicationId.serializedForm()).value();
            this.useAdaptiveDispatch = Flags.USE_ADAPTIVE_DISPATCH.bindTo(flagSource)
                    .with(FetchVector.Dimension.APPLICATION_ID, applicationId.serializedForm()).value();
            this.tlsSecrets = tlsSecrets;
        }

        @Override
        public boolean multitenant() { return multitenant; }

        @Override
        public ApplicationId applicationId() { return applicationId; }

        @Override
        public List<ConfigServerSpec> configServerSpecs() { return configServerSpecs; }

        @Override
        public HostName loadBalancerName() { return loadBalancerName; }

        @Override
        public URI ztsUrl() {
            return ztsUrl;
        }

        @Override
        public String athenzDnsSuffix() {
            return athenzDnsSuffix;
        }

        @Override
        public boolean hostedVespa() { return hostedVespa; }

        @Override
        public Zone zone() { return zone; }

        @Override
        public Set<Rotation> rotations() { return rotations; }

        @Override
        public Set<ContainerEndpoint> endpoints() { return endpoints; }

        @Override
        public boolean isBootstrap() { return isBootstrap; }

        @Override
        public boolean isFirstTimeDeployment() { return isFirstTimeDeployment; }

        @Override
        public boolean useDedicatedNodeForLogserver() { return useDedicatedNodeForLogserver; }

        @Override
        public boolean useFdispatchByDefault() { return useFdispatchByDefault; }

        @Override
        public boolean dispatchWithProtobuf() { return dispatchWithProtobuf; }

        @Override
        public boolean useAdaptiveDispatch() { return useAdaptiveDispatch; }

        @Override
        public Optional<TlsSecrets> tlsSecrets() { return tlsSecrets; }
    }

}
