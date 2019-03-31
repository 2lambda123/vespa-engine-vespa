// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.role;

import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.TenantName;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * This declares all tenant roles known to the controller. A role contains one or more {@link Policy}s which decide
 * what actions a member of a role can perform.
 *
 * Optionally, some role definition also inherit all policies from a "lower ranking" role. Read the list of roles
 * from {@code everyone} to {@code tenantAdmin}, in order, to see what policies these roles.
 *
 * @author mpolden
 * @author jonmv
 */
public class Role implements RoleInSystem, RoleInSystemWithTenant, RoleInSystemWithTenantAndApplication {

    /** Deus ex machina. */
    public static final RoleInSystem hostedOperator = new Role(Policy.operator);

    /** Build service which may submit new applications for continuous deployment. */
    public static final RoleInSystemWithTenantAndApplication buildService = new Role(Policy.submission,
                                                                                     Policy.applicationRead);

    /** Base role which every user is part of. */
    public static final RoleInSystem everyone = new Role(Policy.classifiedRead,
                                                         Policy.publicRead,
                                                         Policy.userCreate,
                                                         Policy.tenantCreate);

    /** Application reader which can see all information about an application, its tenant and deployments. */
    public static final RoleInSystemWithTenantAndApplication applicationReader = new Role(everyone,
                                                                                          Policy.tenantRead,
                                                                                          Policy.applicationRead,
                                                                                          Policy.deploymentRead);

    /** Application developer with access to deploy to development zones. */
    public static final RoleInSystemWithTenantAndApplication applicationDeveloper = new Role(applicationReader,
                                                                                             Policy.developmentDeployment);

    /** Application operator with access to normal, operational tasks of an application. */
    public static final RoleInSystemWithTenantAndApplication applicationOperator = new Role(applicationDeveloper,
                                                                                            Policy.applicationOperations);

    /** Application administrator with full access to an already existing application, including emergency operations. */
    public static final RoleInSystemWithTenantAndApplication applicationAdmin = new Role(applicationOperator,
                                                                                         Policy.applicationUpdate,
                                                                                         Policy.productionDeployment,
                                                                                         Policy.submission);

    /** Tenant admin with full access to all tenant resources, including the ability to create new applications. */
    public static final RoleInSystemWithTenant tenantAdmin = new Role(applicationAdmin,
                                                                      Policy.applicationCreate,
                                                                      Policy.applicationDelete,
                                                                      Policy.manager,
                                                                      Policy.tenantWrite);

    /** Build and continuous delivery service. */ // TODO replace with buildService, when everyone is on new pipeline.
    public static final RoleInSystemWithTenantAndApplication tenantPipeline = new Role(everyone,
                                                                                       Policy.submission,
                                                                                       Policy.deploymentPipeline,
                                                                                       Policy.productionDeployment);

    /** Tenant administrator with full access to all child resources. */
    public static final RoleInSystemWithTenant athenzTenantAdmin = new Role(everyone,
                                                                            Policy.tenantWrite,
                                                                            Policy.tenantRead,
                                                                            Policy.applicationCreate,
                                                                            Policy.applicationUpdate,
                                                                            Policy.applicationDelete,
                                                                            Policy.applicationOperations,
                                                                            Policy.developmentDeployment);

    private final Set<Policy> policies;

    private Role(Policy... policies) {
        this.policies = EnumSet.copyOf(Set.of(policies));
    }

    private Role(Object inherited, Policy... policies) {
        this.policies = EnumSet.copyOf(Set.of(policies));
        this.policies.addAll(((Role) inherited).policies);
    }

    /**
     * Returns whether this role is allowed to perform action in given role context. Action is allowed if at least one
     * policy evaluates to true.
     */
    public boolean allows(Action action, String path, Context context) {
        return policies.stream().anyMatch(policy -> policy.evaluate(action, path, context));
    }

    @Override
    public RoleMembership limitedTo(SystemName system) {
        return new RoleWithContext(this, Context.unlimitedIn(system));
    }

    @Override
    public RoleMembership limitedTo(TenantName tenant, SystemName system) {
        return new RoleWithContext(this, Context.limitedTo(tenant, system));
    }

    @Override
    public RoleMembership limitedTo(ApplicationName application, TenantName tenant, SystemName system) {
        return new RoleWithContext(this, Context.limitedTo(tenant, application, system));
    }


    public static class RoleWithContext extends RoleMembership { // TODO fix.

        private RoleWithContext(Role role, Context context) {
            super(Map.of(role, Set.of(context)));
        }

    }

}

