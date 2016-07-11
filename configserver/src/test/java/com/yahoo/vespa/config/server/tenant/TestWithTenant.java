// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.tenant;

import com.yahoo.vespa.config.server.TestComponentRegistry;
import com.yahoo.vespa.config.server.TestWithCurator;
import com.yahoo.vespa.config.server.monitoring.Metrics;
import com.yahoo.vespa.config.server.tenant.Tenant;
import com.yahoo.vespa.config.server.tenant.Tenants;
import org.junit.Before;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Utility for a test using a single default tenant.
 *
 * @author lulf
 * @since 5.35
 */
public class TestWithTenant extends TestWithCurator {

    protected Tenants tenants;
    protected Tenant tenant;

    @Before
    public void setupTenant() throws Exception {
        tenants = new Tenants(new TestComponentRegistry(curator), Metrics.createTestMetrics());
        tenant = tenants.defaultTenant();
    }

}
