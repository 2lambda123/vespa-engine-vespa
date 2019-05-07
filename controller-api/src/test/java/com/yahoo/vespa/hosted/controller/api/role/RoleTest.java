// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.role;

import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.TenantName;
import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author mpolden
 */
public class RoleTest {

    private static final Enforcer mainEnforcer = new Enforcer(SystemName.main);
    private static final Enforcer vaasEnforcer = new Enforcer(SystemName.vaas);

    @Test
    public void operator_membership() {
        Role role = Role.hostedOperator();

        // Operator actions
        assertFalse(mainEnforcer.allows(role, Action.create, URI.create("/not/explicitly/defined")));
        assertTrue(mainEnforcer.allows(role, Action.create, URI.create("/controller/v1/foo")));
        assertTrue(mainEnforcer.allows(role, Action.update, URI.create("/os/v1/bar")));
        assertTrue(mainEnforcer.allows(role, Action.update, URI.create("/application/v4/tenant/t1/application/a1")));
        assertTrue(mainEnforcer.allows(role, Action.update, URI.create("/application/v4/tenant/t2/application/a2")));
    }

    @Test
    public void tenant_membership() {
        Role role = Role.athenzTenantAdmin(TenantName.from("t1"));
        assertFalse(mainEnforcer.allows(role, Action.create, URI.create("/not/explicitly/defined")));
        assertFalse("Deny access to operator API", mainEnforcer.allows(role, Action.create, URI.create("/controller/v1/foo")));
        assertFalse("Deny access to other tenant and app", mainEnforcer.allows(role, Action.update, URI.create("/application/v4/tenant/t2/application/a2")));
        assertTrue(mainEnforcer.allows(role, Action.update, URI.create("/application/v4/tenant/t1/application/a1")));

        Role publicSystem = Role.athenzTenantAdmin(TenantName.from("t1"));
        assertFalse(vaasEnforcer.allows(publicSystem, Action.read, URI.create("/controller/v1/foo")));
        assertTrue(vaasEnforcer.allows(publicSystem, Action.read, URI.create("/badge/v1/badge")));
        assertTrue(vaasEnforcer.allows(publicSystem, Action.update, URI.create("/application/v4/tenant/t1/application/a1")));
    }

    @Test
    public void build_service_membership() {
        Role role = Role.tenantPipeline(TenantName.from("t1"), ApplicationName.from("a1"));
        assertFalse(vaasEnforcer.allows(role, Action.create, URI.create("/not/explicitly/defined")));
        assertFalse(vaasEnforcer.allows(role, Action.update, URI.create("/application/v4/tenant/t1/application/a1")));
        assertTrue(vaasEnforcer.allows(role, Action.create, URI.create("/application/v4/tenant/t1/application/a1/jobreport")));
        assertFalse("No global read access", vaasEnforcer.allows(role, Action.read, URI.create("/controller/v1/foo")));
    }

    @Test
    public void implications() {
        TenantName tenant1 = TenantName.from("t1");
        ApplicationName application1 = ApplicationName.from("a1");
        TenantName tenant2 = TenantName.from("t2");
        ApplicationName application2 = ApplicationName.from("a2");

        Role tenantOwner1 = Role.tenantOwner(tenant1);
        Role tenantAdmin1 = Role.tenantAdmin(tenant1);
        Role tenantAdmin2 = Role.tenantAdmin(tenant2);
        Role tenantOperator1 = Role.tenantOperator(tenant1);
        Role applicationAdmin11 = Role.applicationAdmin(tenant1, application1);
        Role applicationOperator11 = Role.applicationOperator(tenant1, application1);
        Role applicationDeveloper11 = Role.applicationDeveloper(tenant1, application1);
        Role applicationReader11 = Role.applicationReader(tenant1, application1);
        Role applicationReader12 = Role.applicationReader(tenant1, application2);
        Role applicationReader22 = Role.applicationReader(tenant2, application2);

        assertFalse(tenantOwner1.implies(tenantOwner1));
        assertTrue(tenantOwner1.implies(tenantAdmin1));
        assertFalse(tenantOwner1.implies(tenantAdmin2));
        assertTrue(tenantOwner1.implies(tenantOperator1));
        assertTrue(tenantOwner1.implies(applicationAdmin11));
        assertTrue(tenantOwner1.implies(applicationReader11));
        assertTrue(tenantOwner1.implies(applicationReader12));
        assertFalse(tenantOwner1.implies(applicationReader22));

        assertFalse(tenantAdmin1.implies(tenantOwner1));
        assertFalse(tenantAdmin1.implies(tenantAdmin2));
        assertTrue(tenantAdmin1.implies(applicationDeveloper11));

        assertFalse(tenantOperator1.implies(applicationReader11));

        assertFalse(applicationAdmin11.implies(tenantAdmin1));
        assertFalse(applicationAdmin11.implies(tenantOperator1));
        assertTrue(applicationAdmin11.implies(applicationOperator11));
        assertTrue(applicationAdmin11.implies(applicationDeveloper11));
        assertTrue(applicationAdmin11.implies(applicationReader11));
        assertFalse(applicationAdmin11.implies(applicationReader12));
        assertFalse(applicationAdmin11.implies(applicationReader22));

        assertFalse(applicationOperator11.implies(applicationDeveloper11));
        assertTrue(applicationOperator11.implies(applicationReader11));

        assertFalse(applicationDeveloper11.implies(applicationOperator11));
        assertTrue(applicationDeveloper11.implies(applicationReader11));
    }

}
