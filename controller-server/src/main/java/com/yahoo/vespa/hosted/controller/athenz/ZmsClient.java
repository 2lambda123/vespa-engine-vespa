// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.athenz;

import com.yahoo.vespa.hosted.controller.api.identifiers.ApplicationId;
import com.yahoo.vespa.hosted.controller.api.identifiers.AthensDomain;

import java.util.List;

/**
 * @author bjorncs
 */
public interface ZmsClient {

    void createTenant(AthensDomain tenantDomain);

    void deleteTenant(AthensDomain tenantDomain);

    void addApplication(AthensDomain tenantDomain, ApplicationId applicationName);

    void deleteApplication(AthensDomain tenantDomain, ApplicationId applicationName);

    boolean hasApplicationAccess(AthenzPrincipal principal, ApplicationAction action, AthensDomain tenantDomain, ApplicationId applicationName);

    boolean hasTenantAdminAccess(AthenzPrincipal principal, AthensDomain tenantDomain);

    // Used before vespa tenancy is established for the domain.
    boolean isDomainAdmin(AthenzPrincipal principal, AthensDomain domain);

    List<AthensDomain> getDomainList(String prefix);

    AthenzPublicKey getPublicKey(AthenzService service, String keyId);

    List<AthenzPublicKey> getPublicKeys(AthenzService service);

}
