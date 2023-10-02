// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.application;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.CloudName;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.zone.AuthMethod;
import com.yahoo.config.provision.zone.RoutingMethod;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.text.Text;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;

/**
 * Represents an application or instance endpoint in hosted Vespa.
 * <p>
 * This encapsulates the logic for building URLs and DNS names for applications in all hosted Vespa systems.
 *
 * @author mpolden
 */
public class Endpoint {

    private static final String MAIN_OATH_DNS_SUFFIX = ".vespa.oath.cloud";
    private static final String CD_OATH_DNS_SUFFIX = ".cd.vespa.oath.cloud";
    private static final String PUBLIC_DNS_SUFFIX = ".vespa-app.cloud";
    private static final String PUBLIC_CD_DNS_SUFFIX = ".cd.vespa-app.cloud";

    private final EndpointId id;
    private final ClusterSpec.Id cluster;
    private final Optional<InstanceName> instance;
    private final URI url;
    private final List<Target> targets;
    private final Scope scope;
    private final boolean legacy;
    private final RoutingMethod routingMethod;
    private final AuthMethod authMethod;
    private final Optional<GeneratedEndpoint> generated;

    private Endpoint(TenantAndApplicationId application, Optional<InstanceName> instanceName, EndpointId id,
                     ClusterSpec.Id cluster, URI url, List<Target> targets, Scope scope, Port port, boolean legacy,
                     RoutingMethod routingMethod, boolean certificateName, AuthMethod authMethod, Optional<GeneratedEndpoint> generated) {
        Objects.requireNonNull(application, "application must be non-null");
        Objects.requireNonNull(instanceName, "instanceName must be non-null");
        Objects.requireNonNull(cluster, "cluster must be non-null");
        Objects.requireNonNull(url, "url must be non-null");
        Objects.requireNonNull(targets, "deployment must be non-null");
        Objects.requireNonNull(scope, "scope must be non-null");
        Objects.requireNonNull(port, "port must be non-null");
        Objects.requireNonNull(routingMethod, "routingMethod must be non-null");
        Objects.requireNonNull(authMethod, "authMethod must be non-null");
        Objects.requireNonNull(generated, "generated must be non-null");
        this.id = requireEndpointId(id, scope, certificateName);
        this.cluster = requireCluster(cluster, certificateName);
        this.instance = requireInstance(instanceName, scope);
        this.url = url;
        this.targets = List.copyOf(requireTargets(targets, application, instanceName, scope, certificateName));
        this.scope = requireScope(scope, routingMethod);
        this.legacy = legacy;
        this.routingMethod = routingMethod;
        this.authMethod = authMethod;
        this.generated = generated;
    }

    /**
     * Returns the name of this endpoint (the first component of the DNS name). This can be one of the following:
     *
     * - The wildcard character '*' (for wildcard endpoints, with any scope)
     * - The cluster ID ({@link Scope#zone} and {@link Scope#weighted}
     * - The endpoint ID ({@link Scope#global} and {@link Scope#application})
     */
    public String name() {
        return endpointOrClusterAsString(id, cluster);
    }

    /** Returns the cluster ID to which this routes traffic */
    public ClusterSpec.Id cluster() {
        return cluster;
    }

    /** The specific instance this endpoint points to, if any */
    public Optional<InstanceName> instance() {
        return instance;
    }

    /** Returns the URL used to access this */
    public URI url() {
        return url;
    }

    /** Returns the DNS name of this */
    public String dnsName() {
        // because getHost returns "null" for wildcard endpoints
        return url.getAuthority().replaceAll(":.*", "");
    }

    /** Returns the target(s) to which this routes traffic */
    public List<Target> targets() {
        return targets;
    }

    /** Returns the deployments(s) to which this routes traffic */
    public List<DeploymentId> deployments() {
        return targets.stream().map(Target::deployment).toList();
    }

    /** Returns the scope of this */
    public Scope scope() {
        return scope;
    }

    /** Returns whether this is considered a legacy DNS name intended to be removed at some point */
    public boolean legacy() {
        return legacy;
    }

    /** Returns the routing method used for this */
    public RoutingMethod routingMethod() {
        return routingMethod;
    }

    /** Returns whether this endpoint supports TLS connections */
    public boolean tls() {
        return true;
    }

    /** Returns whether this requires a rotation to be reachable */
    public boolean requiresRotation() {
        return routingMethod.isShared() && scope == Scope.global;
    }

    /** Returns whether this endpoint is generated by the system */
    public Optional<GeneratedEndpoint> generated() {
        return generated;
    }

    /** Returns the upstream name of given deployment. This *must* match what the routing layer generates */
    public String upstreamName(DeploymentId deployment) {
        if (!routingMethod.isShared()) throw new IllegalArgumentException("Routing method " + routingMethod + " does not have upstream name");
        return upstreamName(cluster.value(), deployment.applicationId(), deployment.zoneId());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Endpoint endpoint = (Endpoint) o;
        return url.equals(endpoint.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url);
    }

    @Override
    public String toString() {
        return Text.format("endpoint %s [scope=%s, legacy=%s, routingMethod=%s, authMethod=%s, name=%s]", url, scope, legacy, routingMethod, authMethod, name());
    }

    private static String endpointOrClusterAsString(EndpointId id, ClusterSpec.Id cluster) {
        return id == null ? cluster.value() : id.id();
    }

    private static URI createUrl(String name, TenantAndApplicationId application, Optional<InstanceName> instance,
                                 List<Target> targets, Scope scope, SystemName system, Port port,
                                 Optional<GeneratedEndpoint> generated) {

        String separator = ".";
        String portPart = port.isDefault() ? "" : ":" + port.port;
        final String subdomain;
        if (generated.isPresent()) {
            subdomain = generatedPart(generated.get(), separator);
        } else {
            subdomain = sanitize(namePart(name, separator)) +
                        systemPart(system, separator) +
                        sanitize(instancePart(instance, separator)) +
                        sanitize(application.application().value()) +
                        separator +
                        sanitize(application.tenant().value());
        }
        return URI.create("https://" +
                          subdomain +
                          "." +
                          scopePart(scope, targets, system, generated) +
                          dnsSuffix(system) +
                          portPart +
                          "/");
    }

    private static String generatedPart(GeneratedEndpoint generated, String separator) {
        return generated.clusterPart() + separator + generated.applicationPart();
    }

    private static String sanitize(String part) { // TODO: Reject reserved words
        return part.replace('_', '-');
    }

    private static String namePart(String name, String separator) {
        if ("default".equals(name)) return "";
        return name + separator;
    }

    private static String scopePart(Scope scope, List<Target> targets, SystemName system, Optional<GeneratedEndpoint> generated) {
        String scopeSymbol = scopeSymbol(scope, system, generated);
        if (scope == Scope.global) return scopeSymbol;
        if (scope == Scope.application) return scopeSymbol;
        if (scope == Scope.zone && generated.isPresent()) return scopeSymbol;

        ZoneId zone = targets.stream().map(target -> target.deployment.zoneId()).min(comparing(ZoneId::value)).get();
        String region = zone.region().value();
        String environment = zone.environment().isProduction() ? "" : "." + zone.environment().value();
        if (system.isPublic()) {
            return region + environment + "." + scopeSymbol;
        }
        return region + (scopeSymbol.isEmpty() ? "" : "-" + scopeSymbol) + environment;
    }

    private static String scopeSymbol(Scope scope, SystemName system, Optional<GeneratedEndpoint> generated) {
        if (system.isPublic() || generated.isPresent()) {
            return switch (scope) {
                case zone -> "z";
                case weighted -> "w";
                case global -> "g";
                case application -> "a";
            };
        }
        return switch (scope) {
            case zone -> "";
            case weighted -> "w";
            case global -> "global";
            case application -> "a";
        };
    }

    private static String instancePart(Optional<InstanceName> instance, String separator) {
        if (instance.isEmpty()) return "";
        if (instance.get().isDefault()) return ""; // Skip "default"
        return instance.get().value() + separator;
    }

    private static String systemPart(SystemName system, String separator) {
        if (!system.isCd()) return "";
        if (system.isPublic()) return "";
        return system.value() + separator;
    }

    /** Returns the DNS suffix used for endpoints in given system */
    public static String dnsSuffix(SystemName system) {
        return switch (system) {
            case cd -> CD_OATH_DNS_SUFFIX;
            case main -> MAIN_OATH_DNS_SUFFIX;
            case Public -> PUBLIC_DNS_SUFFIX;
            case PublicCd -> PUBLIC_CD_DNS_SUFFIX;
            default -> throw new IllegalArgumentException("No DNS suffix declared for system " + system);
        };
    }

    /** Returns the DNS suffix used for internal names (i.e. names not exposed to tenants) in given system */
    public static String internalDnsSuffix(SystemName system) {
        String suffix = dnsSuffix(system);
        if (system.isPublic()) {
            // Certificate provider requires special approval for three-level DNS names, e.g. foo.vespa-app.cloud.
            // To avoid this in public we always add an extra level.
            return ".internal" + suffix;
        }
        return suffix;
    }

    private static String upstreamName(String name, ApplicationId application, ZoneId zone) {
        return Stream.of(namePart(name, ""),
                         instancePart(Optional.of(application.instance()), ""),
                         application.application().value(),
                         application.tenant().value(),
                         zone.region().value(),
                         zone.environment().value())
                     .filter(Predicate.not(String::isEmpty))
                     .map(Endpoint::sanitizeUpstream)
                     .collect(Collectors.joining("."));
    }

    /** Remove any invalid characters from a upstream part */
    private static String sanitizeUpstream(String part) {
        return truncate(part.toLowerCase()
                            .replace('_', '-')
                            .replaceAll("[^a-z0-9-]*", ""));
    }

    /** Truncate the given part at the front so its length does not exceed 63 characters */
    private static String truncate(String part) {
        return part.substring(Math.max(0, part.length() - 63));
    }

    private static ClusterSpec.Id requireCluster(ClusterSpec.Id cluster, boolean certificateName) {
        if (!certificateName && cluster.value().equals("*")) throw new IllegalArgumentException("Wildcard found in cluster ID which is not a certificate name");
        return cluster;
    }

    private static EndpointId requireEndpointId(EndpointId endpointId, Scope scope, boolean certificateName) {
        if (scope.multiDeployment() && endpointId == null) throw new IllegalArgumentException("Endpoint ID must be set for multi-deployment endpoints");
        if (scope == Scope.zone && endpointId != null) throw new IllegalArgumentException("Endpoint ID cannot be set for " + scope + " endpoints");
        if (!certificateName && endpointId != null && endpointId.id().equals("*")) throw new IllegalArgumentException("Wildcard found in endpoint ID which is not a certificate name");
        return endpointId;
    }

    private static Optional<InstanceName> requireInstance(Optional<InstanceName> instanceName, Scope scope) {
        if (scope == Scope.application) {
            if (instanceName.isPresent()) throw new IllegalArgumentException("Instance cannot be set for scope " + scope);
        } else {
            if (instanceName.isEmpty()) throw new IllegalArgumentException("Instance must be set for scope " + scope);
        }
        return instanceName;
    }

    private static Scope requireScope(Scope scope, RoutingMethod routingMethod) {
        if (scope == Scope.application && !routingMethod.isDirect()) throw new IllegalArgumentException("Routing method " + routingMethod + " does not support " + scope + "-scoped endpoints");
        return scope;
    }

    private static List<Target> requireTargets(List<Target> targets, TenantAndApplicationId application, Optional<InstanceName> instanceName, Scope scope, boolean certificateName) {
        if (!certificateName && targets.isEmpty()) throw new IllegalArgumentException("At least one target must be given for " + scope + " endpoints");
        if (scope == Scope.zone && targets.size() != 1) throw new IllegalArgumentException("Exactly one target must be given for " + scope + " endpoints");
        for (var target : targets) {
            if (scope == Scope.application) {
                TenantAndApplicationId owner = TenantAndApplicationId.from(target.deployment().applicationId());
                if (!owner.equals(application)) {
                    throw new IllegalArgumentException("Endpoint has target owned by " + owner +
                                                       ", which does not match application of this endpoint: " +
                                                       application);
                }
            } else {
                ApplicationId owner = target.deployment.applicationId();
                ApplicationId instance = application.instance(instanceName.get());
                if (!owner.equals(instance)) {
                    throw new IllegalArgumentException("Endpoint has target owned by " + owner +
                                                       ", which does not match instance of this endpoint: " + instance);
                }
            }
        }
        return targets;
    }

    /** Returns the authentication method of this endpoint */
    public AuthMethod authMethod() {
        return authMethod;
    }

    /** An endpoint's scope */
    public enum Scope {

        /**
         * Endpoint points to a multiple instances of an application, in the same region.
         *
         * Traffic is routed across instances according to weights specified in deployment.xml
         */
        application,

        /** Endpoint points to one or more zones. Traffic is routed to the zone closest to the client */
        global,

        /**
         * Endpoint points to one more zones in the same geographical region. Traffic is routed evenly across zones.
         *
         * This is for internal use only. Endpoints with this scope are not exposed directly to tenants.
         */
        weighted,

        /** Endpoint points to a single zone */
        zone;

        /** Returns whether this scope may span multiple deployments */
        public boolean multiDeployment() {
            return this == application || this == global;
        }

    }

    /** Represents an endpoint's HTTP port */
    public record Port(int port) {

        private static final Port TLS_DEFAULT = new Port(443);

        public Port {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535, got " + port);
            }
        }

        private boolean isDefault() {
            return port == TLS_DEFAULT.port;
        }

        /** Returns the default HTTPS port */
        public static Port tls() {
            return TLS_DEFAULT;
        }

        /** Returns default port for the given routing method */
        public static Port fromRoutingMethod(RoutingMethod method) {
            if (method.isDirect()) return Port.tls();
            return new Port(4443);
        }

    }

    /** Build an endpoint for given instance */
    public static EndpointBuilder of(ApplicationId instance) {
        return new EndpointBuilder(TenantAndApplicationId.from(instance), Optional.of(instance.instance()));
    }

    /** Build an endpoint for given application */
    public static EndpointBuilder of(TenantAndApplicationId application) {
        return new EndpointBuilder(application, Optional.empty());
    }

    /** A target of an endpoint */
    public static class Target {

        private final DeploymentId deployment;
        private final int weight;

        private Target(DeploymentId deployment, int weight) {
            this.deployment = Objects.requireNonNull(deployment);
            this.weight = weight;
            if (weight < 0 || weight > 100) {
                throw new IllegalArgumentException("Endpoint target weight must be in range [0, 100], got " + weight);
            }
        }

        private Target(DeploymentId deployment) {
            this(deployment, 1);
        }

        /** Returns the deployment of this */
        public DeploymentId deployment() {
            return deployment;
        }

        /** Returns the assigned weight of this */
        public int weight() {
            return weight;
        }

        /** Returns whether this routes to given deployment */
        public boolean routesTo(DeploymentId deployment) {
            return this.deployment.equals(deployment);
        }

    }

    public static class EndpointBuilder {

        private final TenantAndApplicationId application;
        private final Optional<InstanceName> instance;

        private Scope scope;
        private List<Target> targets;
        private ClusterSpec.Id cluster;
        private EndpointId endpointId;
        private Port port;
        private RoutingMethod routingMethod = RoutingMethod.sharedLayer4;
        private boolean legacy = false;
        private boolean certificateName = false;
        private AuthMethod authMethod = AuthMethod.mtls;
        private Optional<GeneratedEndpoint> generated = Optional.empty();

        private EndpointBuilder(TenantAndApplicationId application, Optional<InstanceName> instance) {
            this.application = Objects.requireNonNull(application);
            this.instance = Objects.requireNonNull(instance);
        }

        /** Sets the zone target for this */
        public EndpointBuilder target(ClusterSpec.Id cluster, DeploymentId deployment) {
            this.cluster = cluster;
            this.scope = requireUnset(Scope.zone);
            this.targets = List.of(new Target(deployment));
            return this;
        }

        /** Sets the global target with given ID, deployments and cluster (as defined in deployments.xml) */
        public EndpointBuilder target(EndpointId endpointId, ClusterSpec.Id cluster, List<DeploymentId> deployments) {
            this.endpointId = endpointId;
            this.cluster = cluster;
            this.targets = deployments.stream().map(Target::new).toList();
            this.scope = requireUnset(Scope.global);
            return this;
        }

        /** Sets the global target with given ID and pointing to the default cluster */
        public EndpointBuilder target(EndpointId endpointId) {
            return target(endpointId, ClusterSpec.Id.from("default"), List.of());
        }

        /** Sets the application target with given ID and pointing to the default cluster */
        public EndpointBuilder targetApplication(EndpointId endpointId, DeploymentId deployment) {
            return targetApplication(endpointId, ClusterSpec.Id.from("default"), Map.of(deployment, 1));
        }

        /** Sets the global wildcard target for this */
        public EndpointBuilder wildcard() {
            return target(EndpointId.of("*"), ClusterSpec.Id.from("*"), List.of());
        }

        /** Sets the application wildcard target for this */
        public EndpointBuilder wildcardApplication(DeploymentId deployment) {
            return targetApplication(EndpointId.of("*"), ClusterSpec.Id.from("*"), Map.of(deployment, 1));
        }

        /** Sets the zone wildcard target for this */
        public EndpointBuilder wildcard(DeploymentId deployment) {
            return target(ClusterSpec.Id.from("*"), deployment);
        }

        /** Sets the application target with given ID, cluster, deployments and their weights */
        public EndpointBuilder targetApplication(EndpointId endpointId, ClusterSpec.Id cluster, Map<DeploymentId, Integer> deployments) {
            this.endpointId = endpointId;
            this.cluster = cluster;
            this.targets = deployments.entrySet().stream()
                                      .map(kv -> new Target(kv.getKey(), kv.getValue()))
                                      .toList();
            this.scope = Scope.application;
            return this;
        }

        /** Sets the region target for this, deduced from given zone */
        public EndpointBuilder targetRegion(ClusterSpec.Id cluster, String cloudNativeRegion, CloudName cloudName) {
            this.cluster = cluster;
            this.scope = requireUnset(Scope.weighted);
            RegionName region = RegionName.from(cloudName.value() + "-" + cloudNativeRegion);
            this.targets = List.of(new Target(new DeploymentId(application.instance(instance.get()), ZoneId.from(Environment.prod, region))));
            this.authMethod = AuthMethod.none;
            return this;
        }

        /** Sets the valid authentication method supported by this */
        public EndpointBuilder authMethod(AuthMethod authMethod) {
            this.authMethod = authMethod;
            return this;
        }

        /** Sets the port of this */
        public EndpointBuilder on(Port port) {
            this.port = port;
            return this;
        }

        /** Set whether this is a legacy endpoint */
        public EndpointBuilder legacy(boolean legacy) {
            this.legacy = legacy;
            return this;
        }

        /** Sets the routing method for this */
        public EndpointBuilder routingMethod(RoutingMethod method) {
            this.routingMethod = method;
            return this;
        }

        /** Sets whether we're building a name for inclusion in a certificate */
        public EndpointBuilder certificateName() {
            this.certificateName = true;
            return this;
        }

        /** Sets the generated ID to use when building this */
        public EndpointBuilder generatedFrom(GeneratedEndpoint generated) {
            this.generated = Optional.of(generated);
            return this;
        }

        /** Sets the system that owns this */
        public Endpoint in(SystemName system) {
            String name = endpointOrClusterAsString(endpointId, Objects.requireNonNull(cluster, "cluster must be non-null"));
            URI url = createUrl(name,
                                Objects.requireNonNull(application, "application must be non-null"),
                                Objects.requireNonNull(instance, "instance must be non-null"),
                                Objects.requireNonNull(targets, "targets must be non-null"),
                                Objects.requireNonNull(scope, "scope must be non-null"),
                                Objects.requireNonNull(system, "system must be non-null"),
                                Objects.requireNonNull(port, "port must be non-null"),
                                Objects.requireNonNull(generated)
            );
            if (system.isPublic() && routingMethod != RoutingMethod.exclusive) {
                throw illegal(url, "Public system only supports routing method " + RoutingMethod.exclusive + ", got " + routingMethod);
            }
            if (routingMethod.isDirect() && !port.isDefault()) {
                throw illegal(url, "Routing method " + routingMethod + " can only use default port, got " + port);
            }
            if (authMethod == AuthMethod.token && generated.isEmpty()) {
                throw illegal(url, authMethod + " is only supported for generated endpoints");
            }
            if (scope != Scope.weighted && generated.isPresent() && generated.get().authMethod() != authMethod) {
                throw illegal(url, "Authentication method of " + scope + " endpoint does not match authentication method of generated endpoint: " + generated.get().authMethod());
            }
            if ((scope == Scope.weighted) != (authMethod == AuthMethod.none)) {
                throw illegal(url, "Attempted to set unsupported authentication method " + authMethod + " on " + scope + " endpoint");
            }
            if (scope.multiDeployment() && generated.isPresent() && (generated.get().endpoint().isEmpty() || !generated.get().endpoint().get().equals(endpointId))) {
                throw illegal(url, "Generated endpoint must contain a matching endpoint ID, but got " + generated.get().endpoint());
            }
            return new Endpoint(application,
                                instance,
                                endpointId,
                                cluster,
                                url,
                                targets,
                                scope,
                                port,
                                legacy,
                                routingMethod,
                                certificateName,
                                authMethod,
                                generated);
        }

        private static IllegalArgumentException illegal(URI url, String reason) {
            return new IllegalArgumentException("Invalid endpoint: " + url + ": " + reason);
        }

        private Scope requireUnset(Scope scope) {
            if (this.scope != null) {
                throw new IllegalArgumentException("Cannot change endpoint scope. Already set to " + scope);
            }
            return scope;
        }

    }

}
