package com.yahoo.vespa.hosted.controller.security;

import com.google.inject.Inject;
import com.yahoo.config.provision.TenantName;
import com.yahoo.jdisc.http.HttpRequest;
import com.yahoo.slime.Inspector;
import com.yahoo.vespa.athenz.api.AthenzDomain;
import com.yahoo.vespa.athenz.api.AthenzPrincipal;
import com.yahoo.vespa.athenz.api.OktaAccessToken;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.TenantController;
import com.yahoo.vespa.hosted.controller.api.identifiers.Property;
import com.yahoo.vespa.hosted.controller.api.identifiers.PropertyId;
import com.yahoo.vespa.hosted.controller.tenant.AthenzTenant;

import java.security.Principal;
import java.util.Objects;
import java.util.Optional;

/**
 * Extracts access control data for Athenz or user tenants from HTTP requests.
 *
 * @author jonmv
 */
public class AthenzAccessControlRequests implements AccessControlRequests {

    private final TenantController tenants;

    @Inject
    public AthenzAccessControlRequests(Controller controller) {
        this.tenants = controller.tenants();
    }

    @Override
    public TenantSpec specification(TenantName tenant, Inspector requestObject) {
        return new AthenzTenantSpec(tenant,
                                    new AthenzDomain(required("athensDomain", requestObject)),
                                    new Property(required("property", requestObject)),
                                    optional("propertyId", requestObject).map(PropertyId::new));
    }

    @Override
    public Credentials credentials(TenantName tenant, Inspector requestObject, HttpRequest request) {
        return new AthenzCredentials(requireAthenzPrincipal(request),
                                     tenants.get(tenant).map(AthenzTenant.class::cast).map(AthenzTenant::domain)
                                            .orElseGet(() -> new AthenzDomain(required("athensDomain", requestObject))),
                                     requireOktaAccessToken(request));
    }

    private static OktaAccessToken requireOktaAccessToken(HttpRequest request) {
        return Optional.ofNullable(request.context().get("okta.access-token"))
                       .map(attribute -> new OktaAccessToken((String) attribute))
                       .orElseThrow(() -> new IllegalArgumentException("No Okta Access Token provided"));
    }

    private static String required(String fieldName, Inspector object) {
        return optional(fieldName, object) .orElseThrow(() -> new IllegalArgumentException("Missing required field '" + fieldName + "'."));
    }

    private static Optional<String> optional(String fieldName, Inspector object) {
        return object.field(fieldName).valid() ? Optional.of(object.field(fieldName).asString()) : Optional.empty();
    }

    private static AthenzPrincipal requireAthenzPrincipal(HttpRequest request) {
        Principal principal = request.getUserPrincipal();
        Objects.requireNonNull(principal, "Expected a user principal");
        if ( ! (principal instanceof AthenzPrincipal))
            throw new RuntimeException(String.format("Expected principal of type %s, got %s",
                                                     AthenzPrincipal.class.getSimpleName(), principal.getClass().getName()));
        return (AthenzPrincipal) principal;
    }

}
