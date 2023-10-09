// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.routing.context;

import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.zone.RoutingMethod;
import com.yahoo.vespa.hosted.controller.LockedApplication;
import com.yahoo.vespa.hosted.controller.RoutingController;
import com.yahoo.vespa.hosted.controller.api.application.v4.model.EndpointStatus;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;
import com.yahoo.vespa.hosted.controller.api.integration.certificates.EndpointCertificate;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.ConfigServer;
import com.yahoo.vespa.hosted.controller.application.Endpoint;
import com.yahoo.vespa.hosted.controller.application.EndpointList;
import com.yahoo.vespa.hosted.controller.application.pkg.BasicServicesXml;
import com.yahoo.vespa.hosted.controller.routing.PreparedEndpoints;
import com.yahoo.vespa.hosted.controller.routing.RoutingPolicy;
import com.yahoo.vespa.hosted.controller.routing.RoutingPolicyId;
import com.yahoo.vespa.hosted.controller.routing.RoutingStatus;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A deployment routing context. This extends {@link RoutingContext} to support configuration of routing for a deployment.
 *
 * @author mpolden
 */
public abstract class DeploymentRoutingContext implements RoutingContext {

    final DeploymentId deployment;
    final RoutingController routing;
    final RoutingMethod method;

    public DeploymentRoutingContext(DeploymentId deployment, RoutingMethod method, RoutingController routing) {
        this.deployment = Objects.requireNonNull(deployment);
        this.routing = Objects.requireNonNull(routing);
        this.method = Objects.requireNonNull(method);
    }

    /**
     * Prepare routing configuration for the deployment in this context
     *
     * @return the container endpoints relevant for this deployment, as declared in deployment spec
     */
    public final PreparedEndpoints prepare(BasicServicesXml services, Optional<EndpointCertificate> certificate, LockedApplication application) {
        return routing.prepare(deployment, services, certificate, application);
    }

    /** Finalize routing configuration for the deployment in this context, using given deployment spec */
    public final void activate(DeploymentSpec deploymentSpec, EndpointList generatedEndpoints) {
        routing.policies().refresh(deployment, deploymentSpec, generatedEndpoints);
    }

    /** Deactivate routing configuration for the deployment in this context, using given deployment spec */
    public final void deactivate(DeploymentSpec deploymentSpec) {
        routing.policies().refresh(deployment, deploymentSpec, EndpointList.EMPTY);
        routing.policies().removeDnsChallenges(deployment);
    }

    /** Routing method of this context */
    public final RoutingMethod routingMethod() {
        return method;
    }

    /** Read the routing policy for given cluster in this deployment */
    public final Optional<RoutingPolicy> routingPolicy(ClusterSpec.Id cluster) {
        RoutingPolicyId id = new RoutingPolicyId(deployment.applicationId(), cluster, deployment.zoneId());
        return routing.policies().read(deployment).of(id);
    }

    /** Extension of a {@link DeploymentRoutingContext} for deployments using {@link RoutingMethod#sharedLayer4} routing */
    public static class SharedDeploymentRoutingContext extends DeploymentRoutingContext {

        private final Clock clock;
        private final ConfigServer configServer;

        public SharedDeploymentRoutingContext(DeploymentId deployment, RoutingController controller, ConfigServer configServer, Clock clock) {
            super(deployment, RoutingMethod.sharedLayer4, controller);
            this.clock = Objects.requireNonNull(clock);
            this.configServer = Objects.requireNonNull(configServer);
        }

        @Override
        public void setRoutingStatus(RoutingStatus.Value value, RoutingStatus.Agent agent) {
            EndpointStatus newStatus = new EndpointStatus(value == RoutingStatus.Value.in
                                                                  ? EndpointStatus.Status.in
                                                                  : EndpointStatus.Status.out,
                                                          agent.name(),
                                                          clock.instant());
            try {
                configServer.setGlobalRotationStatus(deployment, upstreamNames(), newStatus);
            } catch (Exception e) {
                throw new RuntimeException("Failed to change rotation status of " + deployment, e);
            }
        }

        @Override
        public RoutingStatus routingStatus() {
            // In a given deployment, all upstreams (clusters) share the same status, so we can query using any
            // upstream name
            String upstreamName = upstreamNames().get(0);
            EndpointStatus status = configServer.getGlobalRotationStatus(deployment, upstreamName);
            RoutingStatus.Agent agent;
            try {
                agent = RoutingStatus.Agent.valueOf(status.agent().toLowerCase());
            } catch (IllegalArgumentException e) {
                agent = RoutingStatus.Agent.unknown;
            }
            return new RoutingStatus(status.status() == EndpointStatus.Status.in
                                             ? RoutingStatus.Value.in
                                             : RoutingStatus.Value.out,
                                     agent,
                                     status.changedAt());
        }

        private List<String> upstreamNames() {
            List<String> upstreamNames = routing.readEndpointsOf(deployment)
                                                .scope(Endpoint.Scope.zone)
                                                .shared()
                                                .asList().stream()
                                                .map(endpoint -> endpoint.upstreamName(deployment))
                                                .distinct()
                                                .toList();
            if (upstreamNames.isEmpty()) {
                throw new IllegalArgumentException("No upstream names found for " + deployment);
            }
            return upstreamNames;
        }

    }

    /**
     * Implementation of a {@link DeploymentRoutingContext} for deployments using {@link RoutingMethod#exclusive}
     * routing.
     */
    public static class ExclusiveDeploymentRoutingContext extends DeploymentRoutingContext {

        public ExclusiveDeploymentRoutingContext(DeploymentId deployment, RoutingController controller) {
            super(deployment, RoutingMethod.exclusive, controller);
        }

        @Override
        public void setRoutingStatus(RoutingStatus.Value value, RoutingStatus.Agent agent) {
            routing.policies().setRoutingStatus(deployment, value, agent);
        }

        @Override
        public RoutingStatus routingStatus() {
            // Status for a deployment applies to all clusters within the deployment, so we use the status from the
            // first matching policy here
            return routing.policies().read(deployment)
                          .first()
                          .map(RoutingPolicy::routingStatus)
                          .orElse(RoutingStatus.DEFAULT);
        }

    }

}
