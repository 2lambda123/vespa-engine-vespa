// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.restapi.v2.filter;

import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provisioning.ConfigServerSecurityConfig;
import com.yahoo.vespa.athenz.api.AthenzIdentity;
import com.yahoo.vespa.athenz.utils.AthenzIdentities;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/**
 * Authorizer for config server REST APIs. This contains the rules for all API paths where the authorization process
 * may require information from the node-repository to make a decision
 *
 * @author mpolden
 * @author bjorncs
 */
public class Authorizer implements BiPredicate<NodePrincipal, URI> {
    private final NodeRepository nodeRepository;
    private final String athenzProviderHostname;
    private final AthenzIdentity controllerHostIdentity;
    private final Set<AthenzIdentity> trustedIdentities;
    private final Set<AthenzIdentity> hostAdminIdentities;

    Authorizer(NodeRepository nodeRepository, ConfigServerSecurityConfig securityConfig) {
        AthenzIdentity configServerHostIdentity = AthenzIdentities.from(securityConfig.configServerHostIdentity());

        this.nodeRepository = nodeRepository;
        this.athenzProviderHostname = securityConfig.athenzProviderHostname();
        this.controllerHostIdentity = AthenzIdentities.from(securityConfig.controllerHostIdentity());
        this.trustedIdentities = Set.of(controllerHostIdentity, configServerHostIdentity);
        this.hostAdminIdentities = Set.of(
                controllerHostIdentity,
                configServerHostIdentity,
                AthenzIdentities.from(securityConfig.tenantHostIdentity()),
                AthenzIdentities.from(securityConfig.proxyHostIdentity()));
    }

    /** Returns whether principal is authorized to access given URI */
    @Override
    public boolean test(NodePrincipal principal, URI uri) {
        if (principal.getAthenzIdentityName().isPresent()) {
            // All host admins can retrieve flags data
            if (uri.getPath().equals("/flags/v1/data") || uri.getPath().equals("/flags/v1/data/")) {
                return hostAdminIdentities.contains(principal.getAthenzIdentityName().get());
            }

            // Only controller can access everything else in flags
            if (uri.getPath().startsWith("/flags/v1/")) {
                return principal.getAthenzIdentityName().get().equals(controllerHostIdentity);
            }

            // Trusted services can access everything
            if (trustedIdentities.contains(principal.getAthenzIdentityName().get())) {
                return true;
            }
        }

        if (principal.getHostname().isPresent()) {
            String hostname = principal.getHostname().get();
            if (isAthenzProviderApi(uri)) {
                return athenzProviderHostname.equals(hostname);
            }

            // Individual nodes can only access their own resources
            if (canAccessAll(hostnamesFrom(uri), principal, this::isSelfOrParent)) {
                return true;
            }

            // Nodes can access this resource if its type matches any of the valid node types
            if (canAccessAny(nodeTypesFor(uri), principal, this::isNodeType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAthenzProviderApi(URI uri) {
        return "/athenz/v1/provider/instance".equals(uri.getPath()) ||
                "/athenz/v1/provider/refresh".equals(uri.getPath());
    }

    /** Returns whether principal is the node itself or the parent of the node */
    private boolean isSelfOrParent(String hostname, NodePrincipal principal) {
        // Node can always access itself
        if (principal.getHostname().get().equals(hostname)) {
            return true;
        }

        // Parent node can access its children
        return getNode(hostname).flatMap(Node::parentHostname)
                                .map(parentHostname -> principal.getHostname().get().equals(parentHostname))
                                .orElse(false);
    }

    /** Returns whether principal is a node of the given node type */
    private boolean isNodeType(NodeType type, NodePrincipal principal) {
        return getNode(principal.getHostname().get()).map(node -> node.type() == type)
                                           .orElse(false);
    }

    /** Returns whether principal can access all given resources */
    private <T> boolean canAccessAll(List<T> resources, NodePrincipal principal, BiPredicate<T, NodePrincipal> predicate) {
        return !resources.isEmpty() && resources.stream().allMatch(resource -> predicate.test(resource, principal));
    }

    /** Returns whether principal can access any of the given resources */
    private <T> boolean canAccessAny(List<T> resources, NodePrincipal principal, BiPredicate<T, NodePrincipal> predicate) {
        return !resources.isEmpty() && resources.stream().anyMatch(resource -> predicate.test(resource, principal));
    }

    private Optional<Node> getNode(String hostname) {
        // Ignore potential path traversal. Node repository happily passes arguments unsanitized all the way down to
        // curator...
        if (hostname.chars().allMatch(c -> c == '.')) {
            return Optional.empty();
        }
        return nodeRepository.getNode(hostname);
    }

    /** Returns hostnames contained in query parameters of given URI */
    private static List<String> hostnamesFromQuery(URI uri) {
        return URLEncodedUtils.parse(uri, StandardCharsets.UTF_8.name())
                              .stream()
                              .filter(pair -> "hostname".equals(pair.getName()) ||
                                              "parentHost".equals(pair.getName()))
                              .map(NameValuePair::getValue)
                              .filter(hostname -> !hostname.isEmpty())
                              .collect(Collectors.toList());
    }

    /** Returns hostnames from a URI if any, e.g. /nodes/v2/node/node1.fqdn */
    private static List<String> hostnamesFrom(URI uri) {
        if (isChildOf("/nodes/v2/acl/", uri.getPath()) ||
            isChildOf("/nodes/v2/node/", uri.getPath()) ||
            isChildOf("/nodes/v2/state/", uri.getPath())) {
            return Collections.singletonList(lastChildOf(uri.getPath()));
        }
        if (isChildOf("/orchestrator/v1/hosts/", uri.getPath())) {
            return firstChildOf("/orchestrator/v1/hosts/", uri.getPath())
                    .map(Collections::singletonList)
                    .orElseGet(Collections::emptyList);
        }
        if (isChildOf("/orchestrator/v1/suspensions/hosts/", uri.getPath())) {
            List<String> hostnames = new ArrayList<>();
            hostnames.add(lastChildOf(uri.getPath()));
            hostnames.addAll(hostnamesFromQuery(uri));
            return hostnames;
        }
        if (isChildOf("/nodes/v2/command/", uri.getPath()) ||
            "/nodes/v2/node/".equals(uri.getPath())) {
            return hostnamesFromQuery(uri);
        }
        if (isChildOf("/athenz/v1/provider/identity-document", uri.getPath())) {
            return Collections.singletonList(lastChildOf(uri.getPath()));
        }
        return Collections.emptyList();
    }

    /** Returns node types which can access given URI */
    private static List<NodeType> nodeTypesFor(URI uri) {
        if (isChildOf("/routing/v1/", uri.getPath())) {
            return Arrays.asList(NodeType.proxy, NodeType.proxyhost);
        }
        return Collections.emptyList();
    }

    /** Returns whether child is a sub-path of parent */
    private static boolean isChildOf(String parent, String child) {
        return child.startsWith(parent) && child.length() > parent.length();
    }

    /** Returns the first component of path relative to root */
    private static Optional<String> firstChildOf(String root, String path) {
        if (!isChildOf(root, path)) {
            return Optional.empty();
        }
        path = path.substring(root.length());
        int firstSeparator = path.indexOf('/');
        if (firstSeparator == -1) {
            return Optional.of(path);
        }
        return Optional.of(path.substring(0, firstSeparator));
    }

    /** Returns the last component of the given path */
    private static String lastChildOf(String path) {
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int lastSeparator = path.lastIndexOf("/");
        if (lastSeparator == -1) {
            return path;
        }
        return path.substring(lastSeparator + 1);
    }

}
