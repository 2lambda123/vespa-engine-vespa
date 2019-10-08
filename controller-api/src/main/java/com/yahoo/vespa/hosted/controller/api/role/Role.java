// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.role;

import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.TenantName;

import java.util.Objects;

/**
 * A role is a combination of a {@link RoleDefinition} and a {@link Context}, which allows evaluation
 * of access control for a given action on a resource.
 *
 * @author jonmv
 */
public abstract class Role {

    private final RoleDefinition roleDefinition;
    final Context context;

    Role(RoleDefinition roleDefinition, Context context) {
        this.roleDefinition = Objects.requireNonNull(roleDefinition);
        this.context = Objects.requireNonNull(context);
    }

    /** Returns a {@link RoleDefinition#hostedOperator} for the current system. */
    public static UnboundRole hostedOperator() {
        return new UnboundRole(RoleDefinition.hostedOperator);
    }

    /** Returns a {@link RoleDefinition#everyone} for the current system. */
    public static UnboundRole everyone() {
        return new UnboundRole(RoleDefinition.everyone);
    }

    /** Returns a {@link RoleDefinition#athenzTenantAdmin} for the current system and given tenant. */
    public static TenantRole athenzTenantAdmin(TenantName tenant) {
        return new TenantRole(RoleDefinition.athenzTenantAdmin, tenant);
    }

    /** Returns a {@link RoleDefinition#tenantPipeline} for the current system, given tenant, and application */
    public static ApplicationRole tenantPipeline(TenantName tenant, ApplicationName application) {
        return new ApplicationRole(RoleDefinition.tenantPipeline, tenant, application);
    }

    /** Returns a {@link RoleDefinition#reader} for the current system and given tenant. */
    public static TenantRole reader(TenantName tenant) {
        return new TenantRole(RoleDefinition.reader, tenant);
    }

    /** Returns a {@link RoleDefinition#developer} for the current system and given tenant. */
    public static TenantRole developer(TenantName tenant) {
        return new TenantRole(RoleDefinition.developer, tenant);
    }

    /** Returns a {@link RoleDefinition#administrator} for the current system and given tenant. */
    public static TenantRole administrator(TenantName tenant) {
        return new TenantRole(RoleDefinition.administrator, tenant);
    }

    /** Returns a {@link RoleDefinition#headless} for the current system, given tenant, and application */
    public static ApplicationRole headless(TenantName tenant, ApplicationName application) {
        return new ApplicationRole(RoleDefinition.headless, tenant, application);
    }

    /** Returns a {@link RoleDefinition#reader} for the current system and given tenant. */
    public static TenantRole reader(TenantName tenant) {
        return new TenantRole(RoleDefinition.reader, tenant);
    }

    /** Returns a {@link RoleDefinition#developer} for the current system and given tenant. */
    public static TenantRole developer(TenantName tenant) {
        return new TenantRole(RoleDefinition.developer, tenant);
    }

    /** Returns a {@link RoleDefinition#administrator} for the current system and given tenant. */
    public static TenantRole administrator(TenantName tenant) {
        return new TenantRole(RoleDefinition.administrator, tenant);
    }

    /** Returns a {@link RoleDefinition#headless} for the current system, given tenant, and application */
    public static ApplicationRole headless(TenantName tenant, ApplicationName application) {
        return new ApplicationRole(RoleDefinition.headless, tenant, application);
    }

    /** Returns a {@link RoleDefinition#applicationAdmin} for the current system and given tenant and application. */
    public static ApplicationRole applicationAdmin(TenantName tenant, ApplicationName application) {
        return new ApplicationRole(RoleDefinition.applicationAdmin, tenant, application);
    }

    /** Returns a {@link RoleDefinition#applicationOperator} for the current system and given tenant and application. */
    public static ApplicationRole applicationOperator(TenantName tenant, ApplicationName application) {
        return new ApplicationRole(RoleDefinition.applicationOperator, tenant, application);
    }

    /** Returns a {@link RoleDefinition#applicationDeveloper} for the current system and given tenant and application. */
    public static ApplicationRole applicationDeveloper(TenantName tenant, ApplicationName application) {
        return new ApplicationRole(RoleDefinition.applicationDeveloper, tenant, application);
    }

    /** Returns a {@link RoleDefinition#applicationReader} for the current system and given tenant and application. */
    public static ApplicationRole applicationReader(TenantName tenant, ApplicationName application) {
        return new ApplicationRole(RoleDefinition.applicationReader, tenant, application);
    }

    /** Returns a {@link RoleDefinition#buildService} for the current system and given tenant and application. */
    public static ApplicationRole buildService(TenantName tenant, ApplicationName application) {
        return new ApplicationRole(RoleDefinition.buildService, tenant, application);
    }

    /** Returns the role definition of this bound role. */
    public RoleDefinition definition() { return roleDefinition; }

    /** Returns whether the other role is a parent of this, and has a context included in this role's context. */
    public boolean implies(Role other) {
        return    (context.tenant().isEmpty() || context.tenant().equals(other.context.tenant()))
               && (context.application().isEmpty() || context.application().equals(other.context.application()))
               && roleDefinition.inherited().contains(other.roleDefinition);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Role role = (Role) o;
        return roleDefinition == role.roleDefinition &&
               Objects.equals(context, role.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roleDefinition, context);
    }

}

