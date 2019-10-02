// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.persistence;// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

import com.google.common.collect.ImmutableBiMap;
import com.yahoo.config.provision.TenantName;
import com.yahoo.vespa.athenz.api.AthenzDomain;
import com.yahoo.vespa.hosted.controller.api.identifiers.Property;
import com.yahoo.vespa.hosted.controller.api.identifiers.PropertyId;
import com.yahoo.vespa.hosted.controller.api.integration.organization.BillingInfo;
import com.yahoo.vespa.hosted.controller.api.integration.organization.Contact;
import com.yahoo.vespa.hosted.controller.api.role.SimplePrincipal;
import com.yahoo.vespa.hosted.controller.tenant.AthenzTenant;
import com.yahoo.vespa.hosted.controller.tenant.CloudTenant;
import com.yahoo.vespa.hosted.controller.tenant.UserTenant;
import org.junit.Test;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author mpolden
 */
public class TenantSerializerTest {

    private static final TenantSerializer serializer = new TenantSerializer();

    @Test
    public void athenz_tenant() {
        AthenzTenant tenant = AthenzTenant.create(TenantName.from("athenz-tenant"),
                                                  new AthenzDomain("domain1"),
                                                  new Property("property1"),
                                                  Optional.of(new PropertyId("1")));
        AthenzTenant serialized = (AthenzTenant) serializer.tenantFrom(serializer.toSlime(tenant));
        assertEquals(tenant.name(), serialized.name());
        assertEquals(tenant.domain(), serialized.domain());
        assertEquals(tenant.property(), serialized.property());
        assertTrue(serialized.propertyId().isPresent());
        assertEquals(tenant.propertyId(), serialized.propertyId());
    }

    @Test
    public void athenz_tenant_without_property_id() {
        AthenzTenant tenant = AthenzTenant.create(TenantName.from("athenz-tenant"),
                                                             new AthenzDomain("domain1"),
                                                             new Property("property1"),
                                                             Optional.empty());
        AthenzTenant serialized = (AthenzTenant) serializer.tenantFrom(serializer.toSlime(tenant));
        assertFalse(serialized.propertyId().isPresent());
        assertEquals(tenant.propertyId(), serialized.propertyId());
    }

    @Test
    public void athenz_tenant_with_contact() {
        AthenzTenant tenant = new AthenzTenant(TenantName.from("athenz-tenant"),
                                               new AthenzDomain("domain1"),
                                               new Property("property1"),
                                               Optional.of(new PropertyId("1")),
                                               Optional.of(contact()));
        AthenzTenant serialized = (AthenzTenant) serializer.tenantFrom(serializer.toSlime(tenant));
        assertEquals(tenant.contact(), serialized.contact());
    }

    @Test
    public void user_tenant() {
        UserTenant tenant = UserTenant.create("by-foo", Optional.of(contact()));
        UserTenant serialized = (UserTenant) serializer.tenantFrom(serializer.toSlime(tenant));
        assertEquals(tenant.name(), serialized.name());
        assertEquals(contact(), serialized.contact().get());
    }

    @Test
    public void cloud_tenant() {
        CloudTenant tenant = new CloudTenant(TenantName.from("elderly-lady"),
                                             new BillingInfo("old cat lady", "vespa"),
                                             ImmutableBiMap.of("-----BEGIN PUBLIC KEY-----\nƪ(`▿▿▿▿´ƪ)\n\n-----END PUBLIC KEY-----", new SimplePrincipal("joe"),
                                                               "-----BEGIN PUBLIC KEY-----\n∠( ᐛ 」∠)＿\n-----END PUBLIC KEY-----", new SimplePrincipal("jane")));
        CloudTenant serialized = (CloudTenant) serializer.tenantFrom(serializer.toSlime(tenant));
        assertEquals(tenant.name(), serialized.name());
        assertEquals(tenant.billingInfo(), serialized.billingInfo());
        assertEquals(tenant.pemDeveloperKeys(), serialized.pemDeveloperKeys());
    }

    private Contact contact() {
        return new Contact(
                URI.create("http://contact1.test"),
                URI.create("http://property1.test"),
                URI.create("http://issue-tracker-1.test"),
                List.of(
                        Collections.singletonList("person1"),
                        Collections.singletonList("person2")
                ),
                "queue",
                Optional.empty()
        );
    }

}
