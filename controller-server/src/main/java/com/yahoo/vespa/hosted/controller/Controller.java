// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller;

import com.google.inject.Inject;
import com.yahoo.component.AbstractComponent;
import com.yahoo.component.Version;
import com.yahoo.component.Vtag;
import com.yahoo.config.provision.HostName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.vespa.athenz.api.AthenzDomain;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.hosted.controller.api.identifiers.Property;
import com.yahoo.vespa.hosted.controller.api.identifiers.PropertyId;
import com.yahoo.vespa.hosted.controller.api.integration.BuildService;
import com.yahoo.vespa.hosted.controller.api.integration.MetricsService;
import com.yahoo.vespa.hosted.controller.api.integration.RunDataStore;
import com.yahoo.vespa.hosted.controller.api.integration.athenz.AthenzClientFactory;
import com.yahoo.vespa.hosted.controller.api.integration.chef.Chef;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.ConfigServer;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationStore;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ArtifactRepository;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.TesterCloud;
import com.yahoo.vespa.hosted.controller.api.integration.dns.NameService;
import com.yahoo.vespa.hosted.controller.api.integration.entity.EntityService;
import com.yahoo.vespa.hosted.controller.api.integration.github.GitHub;
import com.yahoo.vespa.hosted.controller.api.integration.routing.RoutingGenerator;
import com.yahoo.vespa.hosted.controller.api.integration.zone.CloudName;
import com.yahoo.vespa.hosted.controller.api.integration.zone.ZoneRegistry;
import com.yahoo.vespa.hosted.controller.deployment.JobController;
import com.yahoo.vespa.hosted.controller.persistence.CuratorDb;
import com.yahoo.vespa.hosted.controller.versions.OsVersion;
import com.yahoo.vespa.hosted.controller.versions.OsVersionStatus;
import com.yahoo.vespa.hosted.controller.versions.VersionStatus;
import com.yahoo.vespa.hosted.controller.versions.VespaVersion;
import com.yahoo.vespa.hosted.rotation.config.RotationsConfig;
import com.yahoo.vespa.serviceview.bindings.ApplicationView;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * API to the controller. This contains the object model of everything the controller cares about, mainly tenants and
 * applications. The object model is persisted to curator.
 * 
 * All the individual model objects reachable from the Controller are immutable.
 * 
 * Access to the controller is multi-thread safe, provided the locking methods are
 * used when accessing, modifying and storing objects provided by the controller.
 * 
 * @author bratseth
 */
public class Controller extends AbstractComponent {

    private static final Logger log = Logger.getLogger(Controller.class.getName());

    private final Supplier<String> hostnameSupplier;
    private final CuratorDb curator;
    private final ApplicationController applicationController;
    private final TenantController tenantController;
    private final JobController jobController;
    private final Clock clock;
    private final GitHub gitHub;
    private final EntityService entityService;
    private final ZoneRegistry zoneRegistry;
    private final ConfigServer configServer;
    private final MetricsService metricsService;
    private final Chef chef;
    private final AthenzClientFactory athenzClientFactory;

    /**
     * Creates a controller 
     * 
     * @param curator the curator instance storing the persistent state of the controller.
     */
    @Inject
    public Controller(CuratorDb curator, RotationsConfig rotationsConfig,
                      GitHub gitHub, EntityService entityService,
                      ZoneRegistry zoneRegistry, ConfigServer configServer,
                      MetricsService metricsService, NameService nameService,
                      RoutingGenerator routingGenerator, Chef chef, AthenzClientFactory athenzClientFactory,
                      ArtifactRepository artifactRepository, ApplicationStore applicationStore, TesterCloud testerCloud,
                      BuildService buildService, RunDataStore runDataStore) {
        this(curator, rotationsConfig,
             gitHub, entityService, zoneRegistry,
             configServer, metricsService, nameService, routingGenerator, chef,
             Clock.systemUTC(), athenzClientFactory, artifactRepository, applicationStore, testerCloud,
             buildService, runDataStore, com.yahoo.net.HostName::getLocalhost);
    }

    public Controller(CuratorDb curator, RotationsConfig rotationsConfig,
                      GitHub gitHub, EntityService entityService,
                      ZoneRegistry zoneRegistry, ConfigServer configServer,
                      MetricsService metricsService, NameService nameService,
                      RoutingGenerator routingGenerator, Chef chef, Clock clock,
                      AthenzClientFactory athenzClientFactory, ArtifactRepository artifactRepository,
                      ApplicationStore applicationStore, TesterCloud testerCloud,
                      BuildService buildService, RunDataStore runDataStore, Supplier<String> hostnameSupplier) {

        this.hostnameSupplier = Objects.requireNonNull(hostnameSupplier, "HostnameSupplier cannot be null");
        this.curator = Objects.requireNonNull(curator, "Curator cannot be null");
        this.gitHub = Objects.requireNonNull(gitHub, "GitHub cannot be null");
        this.entityService = Objects.requireNonNull(entityService, "EntityService cannot be null");
        this.zoneRegistry = Objects.requireNonNull(zoneRegistry, "ZoneRegistry cannot be null");
        this.configServer = Objects.requireNonNull(configServer, "ConfigServer cannot be null");
        this.metricsService = Objects.requireNonNull(metricsService, "MetricsService cannot be null");
        this.chef = Objects.requireNonNull(chef, "Chef cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
        this.athenzClientFactory = Objects.requireNonNull(athenzClientFactory, "AthenzClientFactory cannot be null");

        jobController = new JobController(this, runDataStore, Objects.requireNonNull(testerCloud));
        applicationController = new ApplicationController(this, curator, athenzClientFactory,
                                                          Objects.requireNonNull(rotationsConfig, "RotationsConfig cannot be null"),
                                                          Objects.requireNonNull(nameService, "NameService cannot be null"),
                                                          configServer,
                                                          Objects.requireNonNull(artifactRepository, "ArtifactRepository cannot be null"),
                                                          Objects.requireNonNull(applicationStore, "ApplicationStore cannot be null"),
                                                          Objects.requireNonNull(routingGenerator, "RoutingGenerator cannot be null"),
                                                          Objects.requireNonNull(buildService, "BuildService cannot be null"),
                                                          clock);
        tenantController = new TenantController(this, curator, athenzClientFactory);

        // Record the version of this controller
        curator().writeControllerVersion(this.hostname(), Vtag.currentVersion);

        jobController.updateStorage();
    }
    
    /** Returns the instance controlling tenants */
    public TenantController tenants() { return tenantController; }

    /** Returns the instance controlling applications */
    public ApplicationController applications() { return applicationController; }

    /** Returns the instance controlling deployment jobs. */
    public JobController jobController() { return jobController; }

    public List<AthenzDomain> getDomainList(String prefix) {
        return athenzClientFactory.createZmsClientWithServicePrincipal().getDomainList(prefix);
    }

    /**
     * Fetch list of all active OpsDB properties.
     *
     * @return Hashed map with the property ID as key and property name as value
     */
    public Map<PropertyId, Property> fetchPropertyList() {
        return entityService.listProperties();
    }

    public Clock clock() { return clock; }

    public ZoneRegistry zoneRegistry() { return zoneRegistry; }

    public ApplicationView getApplicationView(String tenantName, String applicationName, String instanceName,
                                              String environment, String region) {
        return configServer.getApplicationView(tenantName, applicationName, instanceName, environment, region);
    }

    // TODO: Model the response properly
    public Map<?,?> getServiceApiResponse(String tenantName, String applicationName, String instanceName,
                                          String environment, String region, String serviceName, String restPath) {
        return configServer.getServiceApiResponse(tenantName, applicationName, instanceName, environment, region,
                                                  serviceName, restPath);
    }

    /** Replace the current version status by a new one */
    public void updateVersionStatus(VersionStatus newStatus) {
        VersionStatus currentStatus = versionStatus();
        if (newStatus.systemVersion().isPresent() &&
            ! newStatus.systemVersion().equals(currentStatus.systemVersion())) {
            log.info("Changing system version from " + printableVersion(currentStatus.systemVersion()) +
                     " to " + printableVersion(newStatus.systemVersion()));
        }
        curator.writeVersionStatus(newStatus);
        // Removes confidence overrides for versions that no longer exist in the system
        removeConfidenceOverride(version -> newStatus.versions().stream()
                                                     .noneMatch(vespaVersion -> vespaVersion.versionNumber()
                                                                                            .equals(version)));
    }
    
    /** Returns the latest known version status. Calling this is free but the status may be slightly out of date. */
    public VersionStatus versionStatus() { return curator.readVersionStatus(); }

    /** Remove confidence override for versions matching given filter */
    public void removeConfidenceOverride(Predicate<Version> filter) {
        try (Lock lock = curator.lockConfidenceOverrides()) {
            Map<Version, VespaVersion.Confidence> overrides = new LinkedHashMap<>(curator.readConfidenceOverrides());
            overrides.keySet().removeIf(filter);
            curator.writeConfidenceOverrides(overrides);
        }
    }
    
    /** Returns the current system version: The controller should drive towards running all applications on this version */
    public Version systemVersion() {
        return versionStatus().systemVersion()
                              .map(VespaVersion::versionNumber)
                              .orElse(Vtag.currentVersion);
    }

    /** Returns the target OS version for infrastructure in this system. The controller will drive infrastructure OS
     * upgrades to this version */
    public Optional<OsVersion> osVersion(CloudName cloud) {
        return osVersions().stream().filter(osVersion -> osVersion.cloud().equals(cloud)).findFirst();
    }

    /** Returns all target OS versions in this system */
    public Set<OsVersion> osVersions() {
        return curator.readOsVersions();
    }

    /** Set the target OS version for infrastructure on cloud in this system */
    public void upgradeOsIn(CloudName cloud, Version version) {
        if (version.isEmpty()) {
            throw new IllegalArgumentException("Invalid version '" + version.toFullString() + "'");
        }
        if (zoneRegistry.zones().all().ids().stream().noneMatch(zone -> cloud.equals(zone.cloud()))) {
            throw new IllegalArgumentException("Cloud '" + cloud.value() + "' does not exist in this system");
        }
        try (Lock lock = curator.lockOsVersions()) {
            Set<OsVersion> versions = new TreeSet<>(curator.readOsVersions());
            if (versions.stream().anyMatch(osVersion -> osVersion.cloud().equals(cloud) &&
                                                        osVersion.version().isAfter(version))) {
                throw new IllegalArgumentException("Cannot downgrade cloud '" + cloud.value() + "' to version " +
                                                   version.toFullString());
            }
            versions.removeIf(osVersion -> osVersion.cloud().equals(cloud)); // Only allow a single target per cloud
            versions.add(new OsVersion(version, cloud));
            curator.writeOsVersions(versions);
        }
    }

    /** Returns the current OS version status */
    public OsVersionStatus osVersionStatus() {
        return curator.readOsVersionStatus();
    }

    /** Replace the current OS version status with a new one */
    public void updateOsVersionStatus(OsVersionStatus newStatus) {
        try (Lock lock = curator.lockOsVersionStatus()) {
            curator.writeOsVersionStatus(newStatus);
        }
    }

    /** Returns the hostname of this controller */
    public HostName hostname() {
        return HostName.from(hostnameSupplier.get());
    }

    public GitHub gitHub() {
        return gitHub;
    }

    public MetricsService metricsService() {
        return metricsService;
    }

    public ConfigServer configServer() {
        return configServer;
    }

    public SystemName system() {
        return zoneRegistry.system();
    }

    public Chef chefClient() {
        return chef;
    }

    public CuratorDb curator() {
        return curator;
    }

    private static String printableVersion(Optional<VespaVersion> vespaVersion) {
        return vespaVersion.map(v -> v.versionNumber().toFullString()).orElse("unknown");
    }

}
