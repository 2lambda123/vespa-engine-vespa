package com.yahoo.vespa.hosted.controller.api.integration.user;

import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.TenantName;
import com.yahoo.vespa.hosted.controller.api.role.ApplicationRole;
import com.yahoo.vespa.hosted.controller.api.role.Role;
import com.yahoo.vespa.hosted.controller.api.role.RoleDefinition;
import com.yahoo.vespa.hosted.controller.api.role.TenantRole;

import java.util.List;

/**
 * Validation, utility and serialization methods for roles used in user management.
 *
 * @author jonmv
 */
public class Roles {

    private Roles() { }

    /** Returns the list of {@link TenantRole}s a {@link UserId} may be a member of. */
    public static List<TenantRole> tenantRoles(TenantName tenant) {
        return List.of(
                Role.reader(tenant),
                Role.developer(tenant),
                Role.administrator(tenant)
        );
    }

    /** Returns the list of {@link ApplicationRole}s a {@link UserId} may be a member of. */
    public static List<ApplicationRole> applicationRoles(TenantName tenant, ApplicationName application) {
        return List.of(Role.headless(tenant, application));
    }

    /** Returns the {@link Role} the given value represents. */
    public static Role toRole(String value) {
        String[] parts = value.split("\\.");
        if (parts.length == 1 && parts[0].equals("hostedOperator")) return Role.hostedOperator();
        if (parts.length == 2) return toRole(TenantName.from(parts[0]), parts[1]);
        if (parts.length == 3) return toRole(TenantName.from(parts[0]), ApplicationName.from(parts[1]), parts[2]);
        throw new IllegalArgumentException("Malformed or illegal role value '" + value + "'.");
    }

    /** Returns the {@link Role} the given tenant, application and role names correspond to. */
    public static Role toRole(TenantName tenant, String roleName) {
        switch (roleName) {
            case "reader": return Role.reader(tenant);
            case "user": return Role.developer(tenant);
            case "administrator": return Role.administrator(tenant);
            default: throw new IllegalArgumentException("Malformed or illegal role name '" + roleName + "'.");
        }
    }

    /** Returns the {@link Role} the given tenant and role names correspond to. */
    public static Role toRole(TenantName tenant, ApplicationName application, String roleName) {
        switch (roleName) {
            case "headless": return Role.headless(tenant, application);
            default: throw new IllegalArgumentException("Malformed or illegal role name '" + roleName + "'.");
        }
    }

    /** Returns a serialised representation the given role. */
    public static String valueOf(Role role) {
        if (role instanceof TenantRole) return valueOf((TenantRole) role);
        if (role instanceof ApplicationRole) return valueOf((ApplicationRole) role);
        throw new IllegalArgumentException("Unexpected role type '" + role.getClass().getName() + "'.");
    }

    private static String valueOf(TenantRole role) {
        return valueOf(role.tenant()) + "." + valueOf(role.definition());
    }

    private static String valueOf(ApplicationRole role) {
        return valueOf(role.tenant()) + "." + valueOf(role.application()) + "." + valueOf(role.definition());
    }

    private static String valueOf(TenantName tenant) {
        if (tenant.value().contains("."))
            throw new IllegalArgumentException("Tenant names may not contain '.'.");

        return tenant.value();
    }

    private static String valueOf(ApplicationName application) {
        if (application.value().contains("."))
            throw new IllegalArgumentException("Application names may not contain '.'.");

        return application.value();
    }

    public static String valueOf(RoleDefinition role) {
        switch (role) {
            case administrator:  return "administrator";
            case developer:      return "user";
            case reader:         return "reader";
            case headless:       return "headless";
            default: throw new IllegalArgumentException("No value defined for role '" + role + "'.");
        }
    }

}
