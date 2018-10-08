// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.athenz.mock;

import com.yahoo.vespa.athenz.api.AthenzDomain;
import com.yahoo.vespa.athenz.api.AthenzIdentity;
import com.yahoo.vespa.athenz.api.AthenzRole;
import com.yahoo.vespa.athenz.api.AthenzService;
import com.yahoo.vespa.athenz.api.ZToken;
import com.yahoo.vespa.athenz.client.zts.Identity;
import com.yahoo.vespa.athenz.client.zts.InstanceIdentity;
import com.yahoo.vespa.athenz.client.zts.ZtsClient;
import com.yahoo.vespa.athenz.tls.Pkcs10Csr;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.stream.Collectors.toList;

/**
 * @author bjorncs
 */
public class ZtsClientMock implements ZtsClient {
    private static final Logger log = Logger.getLogger(ZtsClientMock.class.getName());

    private final AthenzDbMock athenz;

    public ZtsClientMock(AthenzDbMock athenz) {
        this.athenz = athenz;
    }

    @Override
    public List<AthenzDomain> getTenantDomains(AthenzIdentity providerIdentity, AthenzIdentity userIdentity, String roleName) {
        log.log(Level.INFO, String.format("getTenantDomains(providerIdentity='%s', userIdentity='%s', roleName='%s')",
                                          providerIdentity.getFullName(), userIdentity.getFullName(), roleName));
        return athenz.domains.values().stream()
                .filter(domain -> domain.tenantAdmins.contains(userIdentity) || domain.admins.contains(userIdentity))
                .map(domain -> domain.name)
                .collect(toList());
    }

    @Override
    public InstanceIdentity registerInstance(AthenzService providerIdentity, AthenzService instanceIdentity, String instanceId, String attestationData, boolean requestServiceToken, Pkcs10Csr csr) {
        throw new UnsupportedOperationException();
    }

    @Override
    public InstanceIdentity refreshInstance(AthenzService providerIdentity, AthenzService instanceIdentity, String instanceId, boolean requestServiceToken, Pkcs10Csr csr) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Identity getServiceIdentity(AthenzService identity, String keyId, Pkcs10Csr csr) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Identity getServiceIdentity(AthenzService identity, String keyId, KeyPair keyPair, String dnsSuffix) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ZToken getRoleToken(AthenzDomain domain) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ZToken getRoleToken(AthenzRole athenzRole) {
        throw new UnsupportedOperationException();
    }

    @Override
    public X509Certificate getRoleCertificate(AthenzRole role, Pkcs10Csr csr, Duration expiry) {
        throw new UnsupportedOperationException();
    }

    @Override
    public X509Certificate getRoleCertificate(AthenzRole role, Pkcs10Csr csr) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {

    }
}
