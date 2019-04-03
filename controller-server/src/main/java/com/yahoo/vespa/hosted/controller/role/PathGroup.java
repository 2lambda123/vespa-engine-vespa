// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.role;

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
 */
public enum PathGroup {

    /** Paths used for system management by operators. */
    operator("/controller/v1/{*}",
             "/flags/v1/{*}",
             "/nodes/v2/{*}",
             "/orchestrator/v1/{*}",
             "/os/v1/{*}",
             "/provision/v2/{*}",
             "/zone/v2/{*}"),

    /** Paths used for user management. */
    userManagement("/user/v1/{*}"), // TODO probably add tenant and application levels.

    /** Paths used for creating user tenants. */
    user("/application/v4/user"),

    /** Paths used for creating tenants with proper access control. */
    tenant(Matcher.tenant,
           "/application/v4/tenant/{tenant}"),

    /** Paths used by tenant administrators. */
    tenantInfo(Matcher.tenant,
               "/application/v4/tenant/{tenant}/application/"),

    /** Path for the base application resource. */
    application(Matcher.tenant,
                Matcher.application,
                "/application/v4/tenant/{tenant}/application/{application}"),

    /** Paths used by application administrators. */
    applicationInfo(Matcher.tenant,
                    Matcher.application,
                    "/application/v4/tenant/{tenant}/application/{application}/deploying/{*}",
                    "/application/v4/tenant/{tenant}/application/{application}/instance/{*}",
                    "/application/v4/tenant/{tenant}/application/{application}/environment/prod/region/{region}/instance/{instance}/logs",
                    "/application/v4/tenant/{tenant}/application/{application}/environment/prod/region/{region}/instance/{instance}/suspended",
                    "/application/v4/tenant/{tenant}/application/{application}/environment/prod/region/{region}/instance/{instance}/service/{*}",
                    "/application/v4/tenant/{tenant}/application/{application}/environment/prod/region/{region}/instance/{instance}/global-rotation/{*}"),

    /** Path used to restart application nodes. */ // TODO move to the above when everyone is on new pipeline.
    applicationRestart(Matcher.tenant,
                       Matcher.application,
                       "/application/v4/tenant/{tenant}/application/{application}/environment/{environment}/region/{region}/instance/{ignored}/restart"),
    /** Paths used for development deployments. */
    developmentDeployment(Matcher.tenant,
                          Matcher.application,
                          "/application/v4/tenant/{tenant}/application/{application}/environment/dev/region/{region}/instance/{instance}",
                          "/application/v4/tenant/{tenant}/application/{application}/environment/dev/region/{region}/instance/{instance}/deploy",
                          "/application/v4/tenant/{tenant}/application/{application}/environment/perf/region/{region}/instance/{instance}",
                          "/application/v4/tenant/{tenant}/application/{application}/environment/perf/region/{region}/instance/{instance}/deploy"),

    /** Paths used for production deployments. */
    productionDeployment(Matcher.tenant,
                         Matcher.application,
                         "/application/v4/tenant/{tenant}/application/{application}/environment/prod/region/{region}/instance/{instance}",
                         "/application/v4/tenant/{tenant}/application/{application}/environment/prod/region/{region}/instance/{instance}/deploy",
                         "/application/v4/tenant/{tenant}/application/{application}/environment/test/region/{region}/instance/{instance}",
                         "/application/v4/tenant/{tenant}/application/{application}/environment/test/region/{region}/instance/{instance}/deploy",
                         "/application/v4/tenant/{tenant}/application/{application}/environment/staging/region/{region}/instance/{instance}",
                         "/application/v4/tenant/{tenant}/application/{application}/environment/staging/region/{region}/instance/{instance}/deploy"),

    /** Paths used for continuous deployment to production. */
    submission(Matcher.tenant,
               Matcher.application,
               "/application/v4/tenant/{tenant}/application/{application}/submit"),

    /** Paths used for other tasks by build services. */ // TODO: This will vanish.
    buildService(Matcher.tenant,
                 Matcher.application,
                 "/application/v4/tenant/{tenant}/application/{application}/jobreport",
                 "/application/v4/tenant/{tenant}/application/{application}/promote",
                 "/application/v4/tenant/{tenant}/application/{application}/environment/{environment}/region/{region}/instance/{instance}/promote"),

    /** Paths which contain (not very strictly) classified information about, e.g., customers. */
    classifiedInfo("/athenz/v1/{*}",
                   "/cost/v1/{*}",
                   "/deployment/v1/{*}",
                   "/application/v4/",
                   "/application/v4/tenant/",
                   "/",
                   "/d/{*}",
                   "/statuspage/v1/{*}"
    ),

    /** Paths providing public information. */
    publicInfo("/badge/v1/{*}",
               "/zone/v1/{*}");

    final List<String> pathSpecs;
    final List<Matcher> matchers;

    PathGroup(String... pathSpecs) {
        this(List.of(), List.of(pathSpecs));
    }

    PathGroup(Matcher first, String... pathSpecs) {
        this(List.of(first), List.of(pathSpecs));
    }

    PathGroup(Matcher first, Matcher second, String... pathSpecs) {
        this(List.of(first, second), List.of(pathSpecs));
    }

    /** Creates a new path group, if the given context matchers are each present exactly once in each of the given specs. */
    PathGroup(List<Matcher> matchers, List<String> pathSpecs) {
        this.matchers = matchers;
        this.pathSpecs = pathSpecs;
    }

    /** Returns path if it matches any spec in this group, with match groups set by the match. */
    @SuppressWarnings("deprecation")
    private Optional<Path> get(URI uri) {
        Path matcher = new Path(uri); // TODO Get URI down here.
        for (String spec : pathSpecs) // Iterate to be sure the Path's state is that of the match.
            if (matcher.matches(spec)) return Optional.of(matcher);
        return Optional.empty();
    }

    /** All known path groups */
    public static Set<PathGroup> all() {
        return EnumSet.allOf(PathGroup.class);
    }

    /** Returns whether this group matches path in given context */
    public boolean matches(URI uri, Context context) {
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
