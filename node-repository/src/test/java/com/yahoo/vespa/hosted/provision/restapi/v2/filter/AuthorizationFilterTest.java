// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.restapi.v2.filter;

import com.yahoo.application.container.handler.Request.Method;
import com.yahoo.vespa.curator.mock.MockCurator;
import com.yahoo.vespa.hosted.provision.restapi.v2.filter.FilterTester.Request;
import com.yahoo.vespa.hosted.provision.testutils.MockNodeFlavors;
import com.yahoo.vespa.hosted.provision.testutils.MockNodeRepository;
import org.junit.Test;

/**
 * @author mpolden
 */
public class AuthorizationFilterTest {

    private final FilterTester tester = new FilterTester(new AuthorizationFilter(
            new MockNodeRepository(new MockCurator(), new MockNodeFlavors()),
            NodeIdentifierTest.SECURITY_CONFIG));

    @Test
    public void filter() {
        // These are just rudimentary tests of the filter. See AuthorizerTest for more exhaustive tests
        tester.assertRequest(new Request(Method.GET, "/"), 500,
                             "{\"error-code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"GET / denied for " +
                             "remote-addr: Principal is missing. NodeIdentifierFilter has not been applied.\"}");

        tester.assertRequest(new Request(Method.GET, "/").commonName("foo"), 403,
                             "{\"error-code\":\"FORBIDDEN\",\"message\":\"GET / " +
                             "denied for remote-addr: Invalid credentials: NodePrincipal{identityName='foo', hostname='foo', type=LEGACY}\"}");

        tester.assertRequest(new Request(Method.GET, "/nodes/v2/node/foo").commonName("bar"),
                              403, "{\"error-code\":\"FORBIDDEN\",\"message\":\"GET /nodes/v2/node/foo " +
                                   "denied for remote-addr: Invalid credentials: NodePrincipal{identityName='bar', hostname='bar', type=LEGACY}\"}");

        tester.assertSuccess(new Request(Method.GET, "/nodes/v2/node/foo").commonName("foo"));
    }

}
