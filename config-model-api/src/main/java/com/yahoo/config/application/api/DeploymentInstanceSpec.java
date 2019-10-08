// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.application.api;

import com.yahoo.config.provision.AthenzDomain;
import com.yahoo.config.provision.AthenzService;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.RegionName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The deployment spec for an application instance
 *
 * @author bratseth
 */
public class DeploymentInstanceSpec extends DeploymentSpec.Step {

    /** The name of the instance this step deploys */
    private final InstanceName name;

    private final List<DeploymentSpec.Step> steps;
    private final DeploymentSpec.UpgradePolicy upgradePolicy;
    private final List<DeploymentSpec.ChangeBlocker> changeBlockers;
    private final Optional<String> globalServiceId;
    private final Optional<AthenzDomain> athenzDomain;
    private final Optional<AthenzService> athenzService;
    private final Notifications notifications;
    private final List<Endpoint> endpoints;

    public DeploymentInstanceSpec(InstanceName name,
                                  List<DeploymentSpec.Step> steps,
                                  DeploymentSpec.UpgradePolicy upgradePolicy,
                                  List<DeploymentSpec.ChangeBlocker> changeBlockers,
                                  Optional<String> globalServiceId,
                                  Optional<AthenzDomain> athenzDomain,
                                  Optional<AthenzService> athenzService,
                                  Notifications notifications,
                                  List<Endpoint> endpoints) {
        this.name = name;
        this.steps = steps;
        this.upgradePolicy = upgradePolicy;
        this.changeBlockers = changeBlockers;
        this.globalServiceId = globalServiceId;
        this.athenzDomain = athenzDomain;
        this.athenzService = athenzService;
        this.notifications = notifications;
        this.endpoints = List.copyOf(validateEndpoints(endpoints, this.steps));
        validateZones(this.steps);
        validateEndpoints(this.steps, globalServiceId, this.endpoints);
        validateAthenz();
    }

    public InstanceName name() { return name; }

    /** Throw an IllegalArgumentException if any production zone is declared multiple times */
    private void validateZones(List<DeploymentSpec.Step> steps) {
        Set<DeploymentSpec.DeclaredZone> zones = new HashSet<>();

        for (DeploymentSpec.Step step : steps)
            for (DeploymentSpec.DeclaredZone zone : step.zones())
                ensureUnique(zone, zones);
    }

    private void ensureUnique(DeploymentSpec.DeclaredZone zone, Set<DeploymentSpec.DeclaredZone> zones) {
        if ( ! zones.add(zone))
            throw new IllegalArgumentException(zone + " is listed twice in deployment.xml");
    }

    /** Validates the endpoints and makes sure default values are respected */
    private List<Endpoint> validateEndpoints(List<Endpoint> endpoints, List<DeploymentSpec.Step> steps) {
        Objects.requireNonNull(endpoints, "Missing endpoints parameter");

        var productionRegions = steps.stream()
                                     .filter(step -> step.deploysTo(Environment.prod))
                                     .flatMap(step -> step.zones().stream())
                                     .flatMap(zone -> zone.region().stream())
                                     .map(RegionName::value)
                                     .collect(Collectors.toSet());

        var rebuiltEndpointsList = new ArrayList<Endpoint>();

        for (var endpoint : endpoints) {
            if (endpoint.regions().isEmpty()) {
                var rebuiltEndpoint = endpoint.withRegions(productionRegions);
                rebuiltEndpointsList.add(rebuiltEndpoint);
            } else {
                rebuiltEndpointsList.add(endpoint);
            }
        }

        return List.copyOf(rebuiltEndpointsList);
    }

    /** Throw an IllegalArgumentException if an endpoint refers to a region that is not declared in 'prod' */
    private void validateEndpoints(List<DeploymentSpec.Step> steps, Optional<String> globalServiceId, List<Endpoint> endpoints) {
        if (globalServiceId.isPresent() && ! endpoints.isEmpty()) {
            throw new IllegalArgumentException("Providing both 'endpoints' and 'global-service-id'. Use only 'endpoints'.");
        }

        var stepZones = steps.stream()
                             .flatMap(s -> s.zones().stream())
                             .flatMap(z -> z.region().stream())
                             .collect(Collectors.toSet());

        for (var endpoint : endpoints){
            for (var endpointRegion : endpoint.regions()) {
                if (! stepZones.contains(endpointRegion)) {
                    throw new IllegalArgumentException("Region used in endpoint that is not declared in 'prod': " + endpointRegion);
                }
            }
        }
    }

    /**
     * Throw an IllegalArgumentException if Athenz configuration violates:
     * domain not configured -> no zone can configure service
     * domain configured -> all zones must configure service
     */
    private void validateAthenz() {
        // If athenz domain is not set, athenz service cannot be set on any level
        if (athenzDomain.isEmpty()) {
            for (DeploymentSpec.DeclaredZone zone : zones()) {
                if(zone.athenzService().isPresent()) {
                    throw new IllegalArgumentException("Athenz service configured for zone: " + zone + ", but Athenz domain is not configured");
                }
            }
            // if athenz domain is not set, athenz service must be set implicitly or directly on all zones.
        } else if (athenzService.isEmpty()) {
            for (DeploymentSpec.DeclaredZone zone : zones()) {
                if (zone.athenzService().isEmpty()) {
                    throw new IllegalArgumentException("Athenz domain is configured, but Athenz service not configured for zone: " + zone);
                }
            }
        }
    }

    @Override
    public Duration delay() {
        return Duration.ofSeconds(steps.stream().mapToLong(step -> (step.delay().getSeconds())).sum());
    }

    /** Returns the deployment steps inside this in the order they will be performed */
    @Override
    public List<DeploymentSpec.Step> steps() { return steps; }

    /** Returns the upgrade policy of this, which is defaultPolicy if none is specified */
    public DeploymentSpec.UpgradePolicy upgradePolicy() { return upgradePolicy; }

    /** Returns time windows where upgrades are disallowed for these instances */
    public List<DeploymentSpec.ChangeBlocker> changeBlocker() { return changeBlockers; }

    /** Returns the ID of the service to expose through global routing, if present */
    public Optional<String> globalServiceId() { return globalServiceId; }

    /** Returns whether the instances in this step can upgrade at the given instant */
    public boolean canUpgradeAt(Instant instant) {
        return changeBlockers.stream().filter(block -> block.blocksVersions())
                                      .noneMatch(block -> block.window().includes(instant));
    }

    /** Returns whether an application revision change for these instances can occur at the given instant */
    public boolean canChangeRevisionAt(Instant instant) {
        return changeBlockers.stream().filter(block -> block.blocksRevisions())
                             .noneMatch(block -> block.window().includes(instant));
    }

    /** Returns all the deployment steps which are zones in the order they are declared */
    public List<DeploymentSpec.DeclaredZone> zones() {
        return steps.stream()
                    .flatMap(step -> step.zones().stream())
                    .collect(Collectors.toList());
    }

    /** Returns whether this deployment spec specifies the given zone, either implicitly or explicitly */
    @Override
    public boolean deploysTo(Environment environment, Optional<RegionName> region) {
        for (DeploymentSpec.Step step : steps)
            if (step.deploysTo(environment, region)) return true;
        return false;
    }

    /** Returns the athenz domain if configured */
    public Optional<AthenzDomain> athenzDomain() { return athenzDomain; }

    /** Returns the athenz service for environment/region if configured */
    public Optional<AthenzService> athenzService(Environment environment, RegionName region) {
        AthenzService athenzService = zones().stream()
                                             .filter(zone -> zone.deploysTo(environment, Optional.of(region)))
                                             .findFirst()
                                             .flatMap(DeploymentSpec.DeclaredZone::athenzService)
                                             .orElse(this.athenzService.orElse(null));
        return Optional.ofNullable(athenzService);
    }

    /** Returns the notification configuration of these instances */
    public Notifications notifications() { return notifications; }

    /** Returns the rotations configuration of these instances */
    public List<Endpoint> endpoints() { return endpoints; }

    /** Returns whether this instances deployment specifies the given zone, either implicitly or explicitly */
    public boolean includes(Environment environment, Optional<RegionName> region) {
        for (DeploymentSpec.Step step : steps)
            if (step.deploysTo(environment, region)) return true;
        return false;
    }

    DeploymentInstanceSpec withSteps(List<DeploymentSpec.Step> steps) {
        return new DeploymentInstanceSpec(name,
                                          steps,
                                          upgradePolicy,
                                          changeBlockers,
                                          globalServiceId,
                                          athenzDomain,
                                          athenzService,
                                          notifications,
                                          endpoints);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeploymentInstanceSpec other = (DeploymentInstanceSpec) o;
        return globalServiceId.equals(other.globalServiceId) &&
               upgradePolicy == other.upgradePolicy &&
               changeBlockers.equals(other.changeBlockers) &&
               steps.equals(other.steps) &&
               athenzDomain.equals(other.athenzDomain) &&
               athenzService.equals(other.athenzService) &&
               notifications.equals(other.notifications) &&
               endpoints.equals(other.endpoints);
    }

    @Override
    public int hashCode() {
        return Objects.hash(globalServiceId, upgradePolicy, changeBlockers, steps, athenzDomain, athenzService, notifications, endpoints);
    }

    @Override
    public String toString() {
        return "instance '" + name + "'";
    }

}
