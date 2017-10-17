// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.athenz.mock;

import com.yahoo.vespa.hosted.controller.api.identifiers.AthensDomain;
import com.yahoo.vespa.hosted.controller.athenz.AthenzPrincipal;
import com.yahoo.vespa.hosted.controller.athenz.ZtsClient;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.stream.Collectors.toList;

/**
 * @author bjorncs
 */
public class ZtsClientMock implements ZtsClient {
    private static final Logger log = Logger.getLogger(ZtsClientMock.class.getName());

    private final AthensDbMock athens;

    public ZtsClientMock(AthensDbMock athens) {
        this.athens = athens;
    }

    @Override
    public List<AthensDomain> getTenantDomainsForUser(AthenzPrincipal principal) {
        log.log(Level.INFO, "getTenantDomainsForUser(principal='%s')", principal);
        return athens.domains.values().stream()
                .filter(domain -> domain.tenantAdmins.contains(principal) || domain.admins.contains(principal))
                .map(domain -> domain.name)
                .collect(toList());
    }
}
