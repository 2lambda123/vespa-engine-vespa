// Copyright 2020 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.security;

import com.google.inject.Inject;
import com.yahoo.config.provision.TenantName;
import com.yahoo.vespa.flags.BooleanFlag;
import com.yahoo.vespa.flags.FetchVector;
import com.yahoo.vespa.flags.FlagSource;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.api.integration.organization.BillingInfo;
import com.yahoo.vespa.hosted.controller.api.integration.user.Roles;
import com.yahoo.vespa.hosted.controller.api.integration.user.UserId;
import com.yahoo.vespa.hosted.controller.api.integration.user.UserManagement;
import com.yahoo.vespa.hosted.controller.api.role.ApplicationRole;
import com.yahoo.vespa.hosted.controller.api.role.Role;
import com.yahoo.vespa.hosted.controller.api.role.TenantRole;
import com.yahoo.vespa.hosted.controller.application.TenantAndApplicationId;
import com.yahoo.vespa.hosted.controller.tenant.CloudTenant;
import com.yahoo.vespa.hosted.controller.tenant.Tenant;

import javax.ws.rs.ForbiddenException;
import java.util.List;

import static com.yahoo.vespa.hosted.controller.api.role.RoleDefinition.*;

/**
 * @author jonmv
 * @author andreer
 */
public class CloudAccessControl implements AccessControl {

    private static final BillingInfo defaultBillingInfo = new BillingInfo("customer", "Vespa");

    private final UserManagement userManagement;
    private final BooleanFlag enablePublicSignup;

    @Inject
    public CloudAccessControl(UserManagement userManagement, FlagSource flagSource) {
        this.userManagement = userManagement;
        this.enablePublicSignup = Flags.ENABLE_PUBLIC_SIGNUP_FLOW.bindTo(flagSource);
    }

    @Override
    public CloudTenant createTenant(TenantSpec tenantSpec, Credentials credentials, List<Tenant> existing) {
        requireTenantCreationAllowed((Auth0Credentials) credentials);

        CloudTenantSpec spec = (CloudTenantSpec) tenantSpec;
        CloudTenant tenant = CloudTenant.create(spec.tenant(), defaultBillingInfo);

        for (Role role : Roles.tenantRoles(spec.tenant())) {
            userManagement.createRole(role);
        }

        var userId = List.of(new UserId(credentials.user().getName()));
        userManagement.addUsers(Role.administrator(spec.tenant()), userId);
        userManagement.addUsers(Role.developer(spec.tenant()), userId);
        userManagement.addUsers(Role.reader(spec.tenant()), userId);

        return tenant;
    }

    private void requireTenantCreationAllowed(Auth0Credentials auth0Credentials) {
        if (allowedByPrivilegedRole(auth0Credentials)) return;

        if (!allowedByFeatureFlag(auth0Credentials)) {
            throw new ForbiddenException("You are not currently permitted to create tenants. Please contact the Vespa team to request access.");
        }

        if(administeredTenants(auth0Credentials) >= 3) {
            throw new ForbiddenException("You are already administering 3 tenants. If you need more, please contact the Vespa team.");
        }
    }

    private boolean allowedByPrivilegedRole(Auth0Credentials auth0Credentials) {
        return auth0Credentials.getRolesFromCookie().stream()
                .map(Role::definition)
                .anyMatch(rd -> rd == hostedOperator || rd == hostedSupporter);
    }

    private boolean allowedByFeatureFlag(Auth0Credentials auth0Credentials) {
        return enablePublicSignup.with(FetchVector.Dimension.CONSOLE_USER_EMAIL, auth0Credentials.user().getName()).value();
    }

    private long administeredTenants(Auth0Credentials auth0Credentials) {
        // We have to verify the roles with auth0 to ensure the user is not using an "old" cookie to make too many tenants.
        return userManagement.listRoles(new UserId(auth0Credentials.user().getName())).stream()
                .map(Role::definition)
                .filter(rd -> rd == administrator)
                .count();
    }

    @Override
    public Tenant updateTenant(TenantSpec tenantSpec, Credentials credentials, List<Tenant> existing, List<Application> applications) {
        throw new UnsupportedOperationException("Update is not supported here, as it would entail changing the tenant name.");
    }

    @Override
    public void deleteTenant(TenantName tenant, Credentials credentials) {
        // TODO: allow only if 0 resources, 0 balance

        for (TenantRole role : Roles.tenantRoles(tenant))
            userManagement.deleteRole(role);
    }

    @Override
    public void createApplication(TenantAndApplicationId id, Credentials credentials) {
        for (Role role : Roles.applicationRoles(id.tenant(), id.application()))
            userManagement.createRole(role);
    }

    @Override
    public void deleteApplication(TenantAndApplicationId id, Credentials credentials) {
        for (ApplicationRole role : Roles.applicationRoles(id.tenant(), id.application()))
            userManagement.deleteRole(role);
    }

}
