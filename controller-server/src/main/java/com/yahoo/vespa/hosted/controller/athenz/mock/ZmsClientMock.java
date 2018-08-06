// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.athenz.mock;

import com.yahoo.vespa.hosted.controller.api.identifiers.ApplicationId;
import com.yahoo.vespa.athenz.api.AthenzDomain;
import com.yahoo.vespa.hosted.controller.api.integration.athenz.ApplicationAction;
import com.yahoo.vespa.athenz.api.AthenzIdentity;
import com.yahoo.vespa.hosted.controller.api.integration.athenz.ZmsClient;
import com.yahoo.vespa.hosted.controller.api.integration.athenz.ZmsException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author bjorncs
 */
public class ZmsClientMock implements ZmsClient {

    private static final Logger log = Logger.getLogger(ZmsClientMock.class.getName());

    private final AthenzDbMock athenz;

    public ZmsClientMock(AthenzDbMock athenz) {
        this.athenz = athenz;
    }

    @Override
    public void createTenant(AthenzDomain tenantDomain) {
        log("createTenant(tenantDomain='%s')", tenantDomain);
        getDomainOrThrow(tenantDomain, false).isVespaTenant = true;
    }

    @Override
    public void deleteTenant(AthenzDomain tenantDomain) {
        log("deleteTenant(tenantDomain='%s')", tenantDomain);
        AthenzDbMock.Domain domain = getDomainOrThrow(tenantDomain, false);
        domain.isVespaTenant = false;
        domain.applications.clear();
        domain.tenantAdmins.clear();
    }

    @Override
    public void addApplication(AthenzDomain tenantDomain, ApplicationId applicationName) {
        log("addApplication(tenantDomain='%s', applicationName='%s')", tenantDomain, applicationName);
        AthenzDbMock.Domain domain = getDomainOrThrow(tenantDomain, true);
        if (!domain.applications.containsKey(applicationName)) {
            domain.applications.put(applicationName, new AthenzDbMock.Application());
        }
    }

    @Override
    public void deleteApplication(AthenzDomain tenantDomain, ApplicationId applicationName) {
        log("addApplication(tenantDomain='%s', applicationName='%s')", tenantDomain, applicationName);
        getDomainOrThrow(tenantDomain, true).applications.remove(applicationName);
    }

    @Override
    public boolean hasApplicationAccess(AthenzIdentity identity, ApplicationAction action, AthenzDomain tenantDomain, ApplicationId applicationName) {
        log("hasApplicationAccess(principal='%s', action='%s', tenantDomain='%s', applicationName='%s')",
            identity, action, tenantDomain, applicationName);
        AthenzDbMock.Domain domain = getDomainOrThrow(tenantDomain, true);
        AthenzDbMock.Application application = domain.applications.get(applicationName);
        if (application == null) {
            throw zmsException(400, "Application '%s' not found", applicationName);
        }
        return isHostedOperator(identity) || domain.admins.contains(identity) || application.acl.get(action).contains(identity);
    }

    @Override
    public boolean hasTenantAdminAccess(AthenzIdentity identity, AthenzDomain tenantDomain) {
        log("hasTenantAdminAccess(principal='%s', tenantDomain='%s')", identity, tenantDomain);
        return isHostedOperator(identity) || isDomainAdmin(identity, tenantDomain) ||
                getDomainOrThrow(tenantDomain, true).tenantAdmins.contains(identity);
    }

    @Override
    public boolean hasHostedOperatorAccess(AthenzIdentity identity) {
        log("hasHostedOperatorAccess(identity='%s')", identity);
        return isHostedOperator(identity);
    }

    @Override
    public boolean isDomainAdmin(AthenzIdentity identity, AthenzDomain domain) {
        log("isDomainAdmin(principal='%s', domain='%s')", identity, domain);
        return getDomainOrThrow(domain, false).admins.contains(identity);
    }

    @Override
    public List<AthenzDomain> getDomainList(String prefix) {
        log("getDomainList()");
        return new ArrayList<>(athenz.domains.keySet());
    }

    private AthenzDbMock.Domain getDomainOrThrow(AthenzDomain domainName, boolean verifyVespaTenant) {
        AthenzDbMock.Domain domain = Optional.ofNullable(athenz.domains.get(domainName))
                .orElseThrow(() -> zmsException(400, "Domain '%s' not found", domainName));
        if (verifyVespaTenant && !domain.isVespaTenant) {
            throw zmsException(400, "Domain not a Vespa tenant: '%s'", domainName);
        }
        return domain;
    }

    private boolean isHostedOperator(AthenzIdentity identity) {
        return athenz.hostedOperators.contains(identity);
    }

    private static ZmsException zmsException(int code, String message, Object... args) {
        return new ZmsException(code, String.format(message, args));
    }

    private static void log(String format, Object... args) {
        log.log(Level.INFO, String.format(format, args));
    }

}
