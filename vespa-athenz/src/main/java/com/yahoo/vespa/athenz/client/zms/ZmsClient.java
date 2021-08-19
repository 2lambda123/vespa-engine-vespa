// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.athenz.client.zms;

import com.yahoo.vespa.athenz.api.AthenzDomain;
import com.yahoo.vespa.athenz.api.AthenzGroup;
import com.yahoo.vespa.athenz.api.AthenzIdentity;
import com.yahoo.vespa.athenz.api.AthenzResourceName;
import com.yahoo.vespa.athenz.api.AthenzRole;
import com.yahoo.vespa.athenz.api.AthenzService;
import com.yahoo.vespa.athenz.api.AthenzUser;
import com.yahoo.vespa.athenz.api.OktaAccessToken;
import com.yahoo.vespa.athenz.api.OktaIdentityToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * @author bjorncs
 */
public interface ZmsClient extends AutoCloseable {

    void createTenancy(AthenzDomain tenantDomain, AthenzIdentity providerService,
                       OktaIdentityToken identityToken, OktaAccessToken accessToken);

    void deleteTenancy(AthenzDomain tenantDomain, AthenzIdentity providerService,
                       OktaIdentityToken identityToken, OktaAccessToken accessToken);

    void createProviderResourceGroup(AthenzDomain tenantDomain, AthenzIdentity providerService, String resourceGroup,
                                     Set<RoleAction> roleActions, OktaIdentityToken identityToken, OktaAccessToken accessToken);

    void deleteProviderResourceGroup(AthenzDomain tenantDomain, AthenzIdentity providerService, String resourceGroup,
                                     OktaIdentityToken identityToken, OktaAccessToken accessToken);

    void addRoleMember(AthenzRole role, AthenzIdentity member, Optional<String> reason);

    void deleteRoleMember(AthenzRole role, AthenzIdentity member);

    boolean getMembership(AthenzRole role, AthenzIdentity identity);

    boolean getGroupMembership(AthenzGroup group, AthenzIdentity identity);

    List<AthenzDomain> getDomainList(String prefix);

    boolean hasAccess(AthenzResourceName resource, String action, AthenzIdentity identity);

    void addPolicyRule(AthenzDomain athenzDomain, String athenzPolicy, String action, AthenzResourceName resourceName, AthenzRole athenzRole);

    boolean deletePolicyRule(AthenzDomain athenzDomain, String athenzPolicy, String action, AthenzResourceName resourceName, AthenzRole athenzRole);

    Map<AthenzUser, String> listPendingRoleApprovals(AthenzRole athenzRole);

    void approvePendingRoleMembership(AthenzRole athenzRole, AthenzUser athenzUser, Instant expiry);

    List<AthenzIdentity> listMembers(AthenzRole athenzRole);

    List<AthenzService> listServices(AthenzDomain athenzDomain);

    void createOrUpdateService(AthenzService athenzService);

    void deleteService(AthenzService athenzService);

    void close();
}
