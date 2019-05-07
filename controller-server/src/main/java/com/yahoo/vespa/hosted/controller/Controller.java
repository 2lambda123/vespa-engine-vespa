// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller;

import com.google.inject.Inject;
import com.yahoo.component.AbstractComponent;
import com.yahoo.component.Version;
import com.yahoo.component.Vtag;
import com.yahoo.config.provision.CloudName;
import com.yahoo.config.provision.HostName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.flags.FlagSource;
import com.yahoo.vespa.hosted.controller.api.integration.BuildService;
import com.yahoo.vespa.hosted.controller.api.integration.MetricsService;
import com.yahoo.vespa.hosted.controller.api.integration.RunDataStore;
import com.yahoo.vespa.hosted.controller.api.integration.chef.Chef;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.ConfigServer;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationStore;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ArtifactRepository;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.TesterCloud;
import com.yahoo.vespa.hosted.controller.api.integration.github.GitHub;
import com.yahoo.vespa.hosted.controller.api.integration.organization.Mailer;
import com.yahoo.vespa.hosted.controller.api.integration.routing.RoutingGenerator;
import com.yahoo.vespa.hosted.controller.api.integration.user.Roles;
import com.yahoo.vespa.hosted.controller.api.integration.zone.ZoneRegistry;
import com.yahoo.vespa.hosted.controller.api.role.ApplicationRole;
import com.yahoo.vespa.hosted.controller.api.role.Role;
import com.yahoo.vespa.hosted.controller.api.role.TenantRole;
import com.yahoo.vespa.hosted.controller.auditlog.AuditLogger;
import com.yahoo.vespa.hosted.controller.deployment.JobController;
import com.yahoo.vespa.hosted.controller.dns.NameServiceForwarder;
import com.yahoo.vespa.hosted.controller.persistence.CuratorDb;
import com.yahoo.vespa.hosted.controller.security.AccessControl;
import com.yahoo.vespa.hosted.controller.versions.OsVersion;
import com.yahoo.vespa.hosted.controller.versions.OsVersionStatus;
import com.yahoo.vespa.hosted.controller.versions.VersionStatus;
import com.yahoo.vespa.hosted.controller.versions.VespaVersion;
import com.yahoo.vespa.hosted.rotation.config.RotationsConfig;
import com.yahoo.vespa.serviceview.bindings.ApplicationView;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private final ZoneRegistry zoneRegistry;
    private final ConfigServer configServer;
    private final MetricsService metricsService;
    private final Chef chef;
    private final Mailer mailer;
    private final AuditLogger auditLogger;
    private final FlagSource flagSource;
    private final NameServiceForwarder nameServiceForwarder;

    /**
     * Creates a controller 
     * 
     * @param curator the curator instance storing the persistent state of the controller.
     */
    @Inject
    public Controller(CuratorDb curator, RotationsConfig rotationsConfig, GitHub gitHub,
                      ZoneRegistry zoneRegistry, ConfigServer configServer, MetricsService metricsService,
                      RoutingGenerator routingGenerator, Chef chef,
                      AccessControl accessControl,
                      ArtifactRepository artifactRepository, ApplicationStore applicationStore, TesterCloud testerCloud,
                      BuildService buildService, RunDataStore runDataStore, Mailer mailer, FlagSource flagSource) {
        this(curator, rotationsConfig, gitHub, zoneRegistry,
             configServer, metricsService, routingGenerator, chef,
             Clock.systemUTC(), accessControl, artifactRepository, applicationStore, testerCloud,
             buildService, runDataStore, com.yahoo.net.HostName::getLocalhost, mailer, flagSource);
    }

    public Controller(CuratorDb curator, RotationsConfig rotationsConfig, GitHub gitHub,
                      ZoneRegistry zoneRegistry, ConfigServer configServer,
                      MetricsService metricsService,
                      RoutingGenerator routingGenerator, Chef chef, Clock clock,
                      AccessControl accessControl,
                      ArtifactRepository artifactRepository, ApplicationStore applicationStore, TesterCloud testerCloud,
                      BuildService buildService, RunDataStore runDataStore, Supplier<String> hostnameSupplier,
                      Mailer mailer, FlagSource flagSource) {

        this.hostnameSupplier = Objects.requireNonNull(hostnameSupplier, "HostnameSupplier cannot be null");
        this.curator = Objects.requireNonNull(curator, "Curator cannot be null");
        this.gitHub = Objects.requireNonNull(gitHub, "GitHub cannot be null");
        this.zoneRegistry = Objects.requireNonNull(zoneRegistry, "ZoneRegistry cannot be null");
        this.configServer = Objects.requireNonNull(configServer, "ConfigServer cannot be null");
        this.metricsService = Objects.requireNonNull(metricsService, "MetricsService cannot be null");
        this.chef = Objects.requireNonNull(chef, "Chef cannot be null");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null");
        this.mailer = Objects.requireNonNull(mailer, "Mailer cannot be null");
        this.flagSource = Objects.requireNonNull(flagSource, "FlagSource cannot be null");
        this.nameServiceForwarder = new NameServiceForwarder(curator);

        jobController = new JobController(this, runDataStore, Objects.requireNonNull(testerCloud));
        applicationController = new ApplicationController(this, curator, accessControl,
                                                          Objects.requireNonNull(rotationsConfig, "RotationsConfig cannot be null"),
                                                          configServer,
                                                          Objects.requireNonNull(artifactRepository, "ArtifactRepository cannot be null"),
                                                          Objects.requireNonNull(applicationStore, "ApplicationStore cannot be null"),
                                                          Objects.requireNonNull(routingGenerator, "RoutingGenerator cannot be null"),
                                                          Objects.requireNonNull(buildService, "BuildService cannot be null"),
                                                          clock
        );
        tenantController = new TenantController(this, curator, accessControl);
        auditLogger = new AuditLogger(curator, clock);

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

    public Mailer mailer() {
        return mailer;
    }

    /** Provides access to the feature flags of this */
    public FlagSource flagSource() {
        return flagSource;
    }

    public Clock clock() { return clock; }

    public ZoneRegistry zoneRegistry() { return zoneRegistry; }

    public NameServiceForwarder nameServiceForwarder() {
        return nameServiceForwarder;
    }

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
    public void upgradeOsIn(CloudName cloud, Version version, boolean force) {
        if (version.isEmpty()) {
            throw new IllegalArgumentException("Invalid version '" + version.toFullString() + "'");
        }
        if (!clouds().contains(cloud)) {
            throw new IllegalArgumentException("Cloud '" + cloud.value() + "' does not exist in this system");
        }
        try (Lock lock = curator.lockOsVersions()) {
            Set<OsVersion> versions = new TreeSet<>(curator.readOsVersions());
            if (!force && versions.stream().anyMatch(osVersion -> osVersion.cloud().equals(cloud) &&
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
            OsVersionStatus currentStatus = curator.readOsVersionStatus();
            for (CloudName cloud : clouds()) {
                Set<Version> newVersions = newStatus.versionsIn(cloud);
                if (currentStatus.versionsIn(cloud).size() > 1 && newVersions.size() == 1) {
                    log.info("All nodes in " + cloud + " cloud upgraded to OS version " +
                             newVersions.iterator().next());
                }
            }
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

    public AuditLogger auditLogger() {
        return auditLogger;
    }

    /** Returns all other roles the given tenant role implies. */
    public Set<Role> impliedRoles(TenantRole role) {
        return Stream.concat(Roles.tenantRoles(role.tenant()).stream(),
                             applications().asList(role.tenant()).stream()
                                           .flatMap(application -> Roles.applicationRoles(application.id().tenant(), application.id().application()).stream()))
                .filter(role::implies)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Returns all other roles the given application role implies. */
    public Set<Role> impliedRoles(ApplicationRole role) {
        return Roles.applicationRoles(role.tenant(), role.application()).stream()
                .filter(role::implies)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<CloudName> clouds() {
        return zoneRegistry.zones().all().ids().stream()
                           .map(ZoneId::cloud)
                           .collect(Collectors.toUnmodifiableSet());
    }

    private static String printableVersion(Optional<VespaVersion> vespaVersion) {
        return vespaVersion.map(v -> v.versionNumber().toFullString()).orElse("unknown");
    }

}
