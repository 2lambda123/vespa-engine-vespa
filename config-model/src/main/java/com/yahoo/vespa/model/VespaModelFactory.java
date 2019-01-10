// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model;

import com.google.inject.Inject;
import com.yahoo.component.provider.ComponentRegistry;
import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.model.ConfigModelRegistry;
import com.yahoo.config.model.MapConfigModelRegistry;
import com.yahoo.config.model.NullConfigModelRegistry;
import com.yahoo.config.model.api.ConfigChangeAction;
import com.yahoo.config.model.api.ConfigModelPlugin;
import com.yahoo.config.model.api.HostProvisioner;
import ai.vespa.rankingexpression.importer.configmodelview.MlModelImporter;
import com.yahoo.config.model.api.Model;
import com.yahoo.config.model.api.ModelContext;
import com.yahoo.config.model.api.ModelCreateResult;
import com.yahoo.config.model.api.ModelFactory;
import com.yahoo.config.model.api.ValidationParameters;
import com.yahoo.config.model.application.provider.ApplicationPackageXmlFilesValidator;
import com.yahoo.config.model.builder.xml.ConfigModelBuilder;
import com.yahoo.config.model.deploy.DeployProperties;
import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.component.Version;
import com.yahoo.config.provision.Zone;
import com.yahoo.vespa.config.VespaVersion;
import com.yahoo.vespa.flags.FlagSource;
import com.yahoo.vespa.model.application.validation.Validation;

import org.xml.sax.SAXException;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Factory for creating {@link VespaModel} instances.
 *
 * @author Ulf Lilleengen
 */
public class VespaModelFactory implements ModelFactory {

    private static final Logger log = Logger.getLogger(VespaModelFactory.class.getName());
    private final ConfigModelRegistry configModelRegistry;
    private final Collection<MlModelImporter> modelImporters;
    private final Zone zone;
    private final Clock clock;
    private final Version version;
    private final FlagSource flagSource;

    /** Creates a factory for vespa models for this version of the source */
    @Inject
    public VespaModelFactory(ComponentRegistry<ConfigModelPlugin> pluginRegistry,
                             ComponentRegistry<MlModelImporter> modelImporters,
                             Zone zone, FlagSource flagSource) {
        this.version = new Version(VespaVersion.major, VespaVersion.minor, VespaVersion.micro);
        List<ConfigModelBuilder> modelBuilders = new ArrayList<>();
        for (ConfigModelPlugin plugin : pluginRegistry.allComponents()) {
            if (plugin instanceof ConfigModelBuilder) {
                modelBuilders.add((ConfigModelBuilder) plugin);
            }
        }
        this.configModelRegistry = new MapConfigModelRegistry(modelBuilders);
        this.modelImporters = modelImporters.allComponents();
        this.zone = zone;
        this.clock = Clock.systemUTC();
        this.flagSource = flagSource;
    }
    
    public VespaModelFactory(ConfigModelRegistry configModelRegistry) {
        this(configModelRegistry, Clock.systemUTC());
    }
    public VespaModelFactory(ConfigModelRegistry configModelRegistry, Clock clock) {
        this(new Version(VespaVersion.major, VespaVersion.minor, VespaVersion.micro), configModelRegistry, clock);
    }
    public VespaModelFactory(Version version, ConfigModelRegistry configModelRegistry, Clock clock) {
        this.version = version;
        if (configModelRegistry == null) {
            this.configModelRegistry = new NullConfigModelRegistry();
            log.info("Will not load config models from plugins, as no registry is available");
        } else {
            this.configModelRegistry = configModelRegistry;
        }
        this.modelImporters = Collections.emptyList();
        this.zone = Zone.defaultZone();
        this.clock = clock;
        this.flagSource = (id, vector) -> Optional.empty();
    }

    /** Returns the version this model is build for */
    @Override
    public Version version() { return version; }

    @Override
    public Model createModel(ModelContext modelContext) {
        return buildModel(createDeployState(modelContext, new ValidationParameters(ValidationParameters.IgnoreValidationErrors.TRUE)));
    }

    @Override
    public ModelCreateResult createAndValidateModel(ModelContext modelContext, ValidationParameters validationParameters) {
        validateXml(modelContext, validationParameters.ignoreValidationErrors());
        DeployState deployState = createDeployState(modelContext, validationParameters);
        VespaModel model = buildModel(deployState);
        List<ConfigChangeAction> changeActions = validateModel(model, deployState, validationParameters);
        return new ModelCreateResult(model, changeActions);
    }
    
    private void validateXml(ModelContext modelContext, boolean ignoreValidationErrors) {
        if (modelContext.appDir().isPresent()) {
            ApplicationPackageXmlFilesValidator validator =
                    ApplicationPackageXmlFilesValidator.create(modelContext.appDir().get(),
                                                               modelContext.modelVespaVersion());
            try {
                validator.checkApplication();
                validator.checkIncludedDirs(modelContext.applicationPackage());
            } catch (IllegalArgumentException e) {
                rethrowUnlessIgnoreErrors(e, ignoreValidationErrors);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            validateXML(modelContext.applicationPackage(), ignoreValidationErrors);
        }
    }

    private VespaModel buildModel(DeployState deployState) {
        try {
            return new VespaModel(configModelRegistry, deployState);
        } catch (IOException | SAXException e) {
            throw new RuntimeException(e);
        }
    }

    private DeployState createDeployState(ModelContext modelContext, ValidationParameters validationParameters) {
        DeployState.Builder builder = new DeployState.Builder()
            .applicationPackage(modelContext.applicationPackage())
            .deployLogger(modelContext.deployLogger())
            .configDefinitionRepo(modelContext.configDefinitionRepo())
            .fileRegistry(modelContext.getFileRegistry())
            .permanentApplicationPackage(modelContext.permanentApplicationPackage())
            .properties(createDeployProperties(modelContext.properties()))
            .modelHostProvisioner(createHostProvisioner(modelContext))
            .rotations(modelContext.properties().rotations())
            .modelImporters(modelImporters)
            .zone(zone)
            .now(clock.instant())
            .flagSource(flagSource)
            .wantedNodeVespaVersion(modelContext.wantedNodeVespaVersion());
        modelContext.previousModel().ifPresent(builder::previousModel);
        return builder.build(validationParameters);
    }

    private DeployProperties createDeployProperties(ModelContext.Properties properties) {
        return new DeployProperties.Builder()
                .applicationId(properties.applicationId())
                .configServerSpecs(properties.configServerSpecs())
                .loadBalancerName(properties.loadBalancerName())
                .ztsUrl(properties.ztsUrl())
                .athenzDnsSuffix(properties.athenzDnsSuffix())
                .multitenant(properties.multitenant())
                .hostedVespa(properties.hostedVespa())
                .vespaVersion(version())
                .isBootstrap(properties.isBootstrap())
                .isFirstTimeDeployment(properties.isFirstTimeDeployment())
                .useDedicatedNodeForLogserver(properties.useDedicatedNodeForLogserver())
                .build();
    }

    private static HostProvisioner createHostProvisioner(ModelContext modelContext) {
        return modelContext.hostProvisioner().orElse(
                DeployState.getDefaultModelHostProvisioner(modelContext.applicationPackage()));
    }

    private void validateXML(ApplicationPackage applicationPackage, boolean ignoreValidationErrors) {
        try {
            applicationPackage.validateXML();
        } catch (IllegalArgumentException e) {
            rethrowUnlessIgnoreErrors(e, ignoreValidationErrors);
        } catch (Exception e) {
             throw new RuntimeException(e);
        }
    }

    private List<ConfigChangeAction> validateModel(VespaModel model, DeployState deployState, ValidationParameters validationParameters) {
        try {
            return Validation.validate(model, validationParameters, deployState);
        } catch (IllegalArgumentException e) {
            rethrowUnlessIgnoreErrors(e, validationParameters.ignoreValidationErrors());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new ArrayList<>();
    }

    private static void rethrowUnlessIgnoreErrors(IllegalArgumentException e, boolean ignoreValidationErrors) {
        if (!ignoreValidationErrors) {
            throw e;
        }
    }

}
