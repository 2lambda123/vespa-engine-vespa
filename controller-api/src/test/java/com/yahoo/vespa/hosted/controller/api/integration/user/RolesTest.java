package com.yahoo.vespa.hosted.controller.api.integration.user;

import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.TenantName;
import com.yahoo.vespa.hosted.controller.api.role.ApplicationRole;
import com.yahoo.vespa.hosted.controller.api.role.Role;
import com.yahoo.vespa.hosted.controller.api.role.TenantRole;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * @author jonmv
 */
public class RolesTest {

    @Test
    public void testSerialization() {
        TenantName tenant = TenantName.from("my-tenant");
        for (TenantRole role : Roles.tenantRoles(tenant))
            assertEquals(role, Roles.toRole(Roles.valueOf(role)));

        ApplicationName application = ApplicationName.from("my-application");
        for (ApplicationRole role : Roles.applicationRoles(tenant, application))
            assertEquals(role, Roles.toRole(Roles.valueOf(role)));

        assertEquals(Role.hostedOperator(),
                     Roles.toRole("hostedOperator"));
        assertEquals(Role.tenantOperator(tenant),
                     Roles.toRole("my-tenant.tenantOperator"));
        assertEquals(Role.applicationReader(tenant, application),
                     Roles.toRole("my-tenant.my-application.applicationReader"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void illegalTenantName() {
        Roles.valueOf(Role.tenantAdmin(TenantName.from("my.tenant")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void illegalApplicationName() {
        Roles.valueOf(Role.applicationOperator(TenantName.from("my-tenant"), ApplicationName.from("my.app")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void illegalRole() {
        Roles.valueOf(Role.tenantPipeline(TenantName.from("my-tenant"), ApplicationName.from("my-app")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void illegalRoleValue() {
        Roles.toRole("my-tenant.awesomePerson");
    }

    @Test(expected = IllegalArgumentException.class)
    public void illegalCombination() {
        Roles.toRole("my-tenant.my-application.tenantOwner");
    }

    @Test(expected = IllegalArgumentException.class)
    public void illegalValue() {
        Roles.toRole("everyone");
    }

}
