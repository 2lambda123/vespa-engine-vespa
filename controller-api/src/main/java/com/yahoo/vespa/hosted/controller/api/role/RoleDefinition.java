package com.yahoo.vespa.hosted.controller.api.role;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * This declares all tenant roles known to the controller. A role contains one or more {@link Policy}s which decide
 * what actions a member of a role can perform, given a {@link Context} for the action.
 *
 * Optionally, some role definitions also inherit all policies from a "lower ranking" role.
 *
 * See {@link Role} for roles bound to a context, where policies can be evaluated.
 *
 * @author mpolden
 * @author jonmv
 */
public enum RoleDefinition {

    /** Deus ex machina. */
    hostedOperator(Policy.operator),

    /** Reader - the base role for all tenant users */
    reader(
            Policy.tenantRead,
            Policy.applicationRead,
            Policy.deploymentRead,
            Policy.publicRead
    ),

    /** User - the dev.ops. role for normal Vespa tenant users */
    developer(
            reader,
            Policy.applicationCreate,
            Policy.applicationUpdate,
            Policy.applicationDelete,
            Policy.applicationOperations,
            Policy.developmentDeployment,
            Policy.keyManagement
    ),

    /** Admin - the administrative function for user management etc. */
    administrator(
            reader,
            Policy.tenantUpdate,
            Policy.tenantManager,
            Policy.applicationManager
    ),

    /** Headless - the application specific role identified by deployment keys for production */
    headless(
            Policy.submission
    ),

    /** Base role which every user is part of. */
    everyone(Policy.classifiedRead,
             Policy.publicRead,
             Policy.userCreate,
             Policy.tenantCreate),

    /** Build and continuous delivery service. */ // TODO replace with buildService, when everyone is on new pipeline.
    tenantPipeline(everyone,
            Policy.submission,
            Policy.deploymentPipeline,
            Policy.productionDeployment),

    /** Tenant administrator with full access to all child resources. */
    athenzTenantAdmin(everyone,
                      Policy.tenantRead,
                      Policy.tenantUpdate,
                      Policy.tenantDelete,
                      Policy.applicationCreate,
                      Policy.applicationUpdate,
                      Policy.applicationDelete,
                      Policy.applicationOperations,
                      Policy.keyManagement,
                      Policy.developmentDeployment);

    private final Set<RoleDefinition> parents;
    private final Set<Policy> policies;

    RoleDefinition(Policy... policies) {
        this(Set.of(), policies);
    }

    RoleDefinition(RoleDefinition first, Policy... policies) {
        this(Set.of(first), policies);
    }

    RoleDefinition(RoleDefinition first, RoleDefinition second, Policy... policies) {
        this(Set.of(first, second), policies);
    }

    RoleDefinition(Set<RoleDefinition> parents, Policy... policies) {
        this.parents = new HashSet<>(parents);
        this.policies = EnumSet.copyOf(Set.of(policies));
        for (RoleDefinition parent : parents) {
            this.parents.addAll(parent.parents);
            this.policies.addAll(parent.policies);
        }
    }

    Set<Policy> policies() {
        return policies;
    }

    Set<RoleDefinition> inherited() {
        return parents;
    }

}
