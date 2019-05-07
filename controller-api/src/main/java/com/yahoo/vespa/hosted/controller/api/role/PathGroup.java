// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.role;

import com.yahoo.restapi.Path;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * This declares and groups all known REST API paths in the controller.
 *
 * When creating a new API, its paths must be added here and a policy must be declared in {@link Policy}.
 *
 * @author mpolden
 * @author jonmv
 */
enum PathGroup {

    /** Paths used for system management by operators. */
    operator("/controller/v1/{*}",
             "/flags/v1/{*}",
             "/nodes/v2/{*}",
             "/orchestrator/v1/{*}",
             "/os/v1/{*}",
             "/provision/v2/{*}",
             "/zone/v2/{*}"),

    /** Paths used for creating user tenants. */
    user("/application/v4/user"),

    /** Paths used for creating tenants with proper access control. */
    tenant(Matcher.tenant,
           Optional.of("/api"),
           "/application/v4/tenant/{tenant}"),

    /** Paths used for user management on the tenant level. */
    tenantUsers(Matcher.tenant,
                Optional.of("/api"),
                "/user/v1/tenant/{tenant}"),

    /** Paths used by tenant administrators. */
    tenantInfo(Matcher.tenant,
               Optional.of("/api"),
               "/application/v4/tenant/{tenant}/application/"),

    /** Path for the base application resource. */
    application(Matcher.tenant,
                Matcher.application,
                Optional.of("/api"),
                "/application/v4/tenant/{tenant}/application/{application}"),

    /** Paths used for user management on the application level. */
    applicationUsers(Matcher.tenant,
                     Matcher.application,
                     Optional.of("/api"),
                     "/user/v1/tenant/{tenant}/application/{application}"),

    /** Paths used by application administrators. */
    applicationInfo(Matcher.tenant,
                    Matcher.application,
                    Optional.of("/api"),
                    "/application/v4/tenant/{tenant}/application/{application}/deploying/{*}",
                    "/application/v4/tenant/{tenant}/application/{application}/instance/{*}",
                    "/application/v4/tenant/{tenant}/application/{application}/environment/{environment}/region/{region}/instance/{instance}/nodes",
                    "/application/v4/tenant/{tenant}/application/{application}/environment/{environment}/region/{region}/instance/{instance}/logs",
                    "/application/v4/tenant/{tenant}/application/{application}/environment/{environment}/region/{region}/instance/{instance}/suspended",
                    "/application/v4/tenant/{tenant}/application/{application}/environment/{environment}/region/{region}/instance/{instance}/service/{*}",
                    "/application/v4/tenant/{tenant}/application/{application}/environment/{environment}/region/{region}/instance/{instance}/global-rotation/{*}"),

    /** Path used to restart development nodes. */
    developmentRestart(Matcher.tenant,
                       Matcher.application,
                       Optional.of("/api"),
                       "/application/v4/tenant/{tenant}/application/{application}/environment/dev/region/{region}/instance/{ignored}/restart",
                       "/application/v4/tenant/{tenant}/application/{application}/environment/perf/region/{region}/instance/{ignored}/restart"),

    /** Path used to restart production nodes. */
    productionRestart(Matcher.tenant,
                      Matcher.application,
                      Optional.of("/api"),
                      "/application/v4/tenant/{tenant}/application/{application}/environment/prod/region/{region}/instance/{ignored}/restart",
                      "/application/v4/tenant/{tenant}/application/{application}/environment/test/region/{region}/instance/{ignored}/restart",
                      "/application/v4/tenant/{tenant}/application/{application}/environment/staging/region/{region}/instance/{ignored}/restart"),

    /** Paths used for development deployments. */
    developmentDeployment(Matcher.tenant,
                          Matcher.application,
                          Optional.of("/api"),
                          "/application/v4/tenant/{tenant}/application/{application}/environment/dev/region/{region}/instance/{instance}",
                          "/application/v4/tenant/{tenant}/application/{application}/environment/dev/region/{region}/instance/{instance}/deploy",
                          "/application/v4/tenant/{tenant}/application/{application}/environment/perf/region/{region}/instance/{instance}",
                          "/application/v4/tenant/{tenant}/application/{application}/environment/perf/region/{region}/instance/{instance}/deploy"),

    /** Paths used for production deployments. */
    productionDeployment(Matcher.tenant,
                         Matcher.application,
                         Optional.of("/api"),
                         "/application/v4/tenant/{tenant}/application/{application}/environment/prod/region/{region}/instance/{instance}",
                         "/application/v4/tenant/{tenant}/application/{application}/environment/prod/region/{region}/instance/{instance}/deploy",
                         "/application/v4/tenant/{tenant}/application/{application}/environment/test/region/{region}/instance/{instance}",
                         "/application/v4/tenant/{tenant}/application/{application}/environment/test/region/{region}/instance/{instance}/deploy",
                         "/application/v4/tenant/{tenant}/application/{application}/environment/staging/region/{region}/instance/{instance}",
                         "/application/v4/tenant/{tenant}/application/{application}/environment/staging/region/{region}/instance/{instance}/deploy"),

    /** Paths used for continuous deployment to production. */
    submission(Matcher.tenant,
               Matcher.application,
               Optional.of("/api"),
               "/application/v4/tenant/{tenant}/application/{application}/submit"),

    /** Paths used for other tasks by build services. */ // TODO: This will vanish.
    buildService(Matcher.tenant,
                 Matcher.application,
                 Optional.of("/api"),
                 "/application/v4/tenant/{tenant}/application/{application}/jobreport",
                 "/application/v4/tenant/{tenant}/application/{application}/promote",
                 "/application/v4/tenant/{tenant}/application/{application}/environment/{environment}/region/{region}/instance/{instance}/promote"),

    /** Paths which contain (not very strictly) classified information about customers. */
    classifiedTenantInfo(Optional.of("/api"),
                         "/application/v4/",
                         "/application/v4/tenant/"),

    /** Paths which contain (not very strictly) classified information about, e.g., customers. */
    classifiedInfo("/athenz/v1/{*}",
                   "/cost/v1/{*}",
                   "/deployment/v1/{*}",
                   "/",
                   "/d/{*}",
                   "/statuspage/v1/{*}"),

    /** Paths providing public information. */
    publicInfo("/badge/v1/{*}",
               "/zone/v1/{*}");

    final List<String> pathSpecs;
    final String prefix;
    final List<Matcher> matchers;

    PathGroup(String... pathSpecs) {
        this(List.of(), Optional.empty(), List.of(pathSpecs));
    }

    PathGroup(Optional<String> prefix, String... pathSpecs) {
        this(List.of(), prefix, List.of(pathSpecs));
    }

    PathGroup(Matcher first, String... pathSpecs) {
        this(List.of(first), Optional.empty(), List.of(pathSpecs));
    }

    PathGroup(Matcher first, Optional<String> prefix, String... pathSpecs) {
        this(List.of(first), prefix, List.of(pathSpecs));
    }

    PathGroup(Matcher first, Matcher second, String... pathSpecs) {
        this(List.of(first, second), Optional.empty(), List.of(pathSpecs));
    }

    PathGroup(Matcher first, Matcher second, Optional<String> prefix, String... pathSpecs) {
        this(List.of(first, second), prefix, List.of(pathSpecs));
    }

    /** Creates a new path group, if the given context matchers are each present exactly once in each of the given specs. */
    PathGroup(List<Matcher> matchers, Optional<String> prefix, List<String> pathSpecs) {
        this.matchers = matchers;
        this.prefix = prefix.orElse("");
        this.pathSpecs = pathSpecs;
    }

    /** Returns path if it matches any spec in this group, with match groups set by the match. */
    private Optional<Path> get(URI uri) {
        Path matcher = new Path(uri, prefix);
        for (String spec : pathSpecs) // Iterate to be sure the Path's state is that of the match.
            if (matcher.matches(spec)) return Optional.of(matcher);
        return Optional.empty();
    }

    /** All known path groups */
    static Set<PathGroup> all() {
        return EnumSet.allOf(PathGroup.class);
    }

    /** Returns whether this group matches path in given context */
    boolean matches(URI uri, Context context) {
        return get(uri).map(p -> {
            boolean match = true;
            String tenant = p.get(Matcher.tenant.name);
            if (tenant != null && context.tenant().isPresent()) {
                match = context.tenant().get().value().equals(tenant);
            }
            String application = p.get(Matcher.application.name);
            if (application != null && context.application().isPresent()) {
                match &= context.application().get().value().equals(application);
            }
            return match;
        }).orElse(false);
    }


    /** Fragments used to match parts of a path to create a context. */
    enum Matcher {

        tenant("{tenant}"),
        application("{application}");

        final String pattern;
        final String name;

        Matcher(String pattern) {
            this.pattern = pattern;
            this.name = pattern.substring(1, pattern.length() - 1);
        }

    }

}
