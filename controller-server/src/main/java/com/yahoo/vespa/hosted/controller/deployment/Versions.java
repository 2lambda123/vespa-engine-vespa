// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.deployment;

import com.yahoo.component.Version;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.Instance;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.application.Change;
import com.yahoo.vespa.hosted.controller.application.Deployment;
import com.yahoo.vespa.hosted.controller.application.JobStatus;

import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Source and target versions for an application.
 *
 * @author jvenstad
 * @author mpolden
 */
public class Versions {

    private final Version targetPlatform;
    private final ApplicationVersion targetApplication;
    private final Optional<Version> sourcePlatform;
    private final Optional<ApplicationVersion> sourceApplication;

    public Versions(Version targetPlatform, ApplicationVersion targetApplication, Optional<Version> sourcePlatform,
                    Optional<ApplicationVersion> sourceApplication) {
        if (sourcePlatform.isPresent() ^ sourceApplication.isPresent())
            throw new IllegalArgumentException("Sources must both be present or absent.");

        this.targetPlatform = requireNonNull(targetPlatform);
        this.targetApplication = requireNonNull(targetApplication);
        this.sourcePlatform = requireNonNull(sourcePlatform);
        this.sourceApplication = requireNonNull(sourceApplication);
    }

    /** Target platform version for this */
    public Version targetPlatform() {
        return targetPlatform;
    }

    /** Target application version for this */
    public ApplicationVersion targetApplication() {
        return targetApplication;
    }

    /** Source platform version for this */
    public Optional<Version> sourcePlatform() {
        return sourcePlatform;
    }

    /** Source application version for this */
    public Optional<ApplicationVersion> sourceApplication() {
        return sourceApplication;
    }

    /** Returns whether source versions are present and match those of the given job run */
    public boolean sourcesMatchIfPresent(JobStatus.JobRun jobRun) {
        return (!sourcePlatform.filter(version -> !version.equals(targetPlatform)).isPresent() ||
                sourcePlatform.equals(jobRun.sourcePlatform())) &&
               (!sourceApplication.filter(version -> !version.equals(targetApplication)).isPresent() ||
                sourceApplication.equals(jobRun.sourceApplication()));
    }

    /** Returns whether source versions are present and match those of the given job other versions. */
    public boolean sourcesMatchIfPresent(Versions versions) {
        return ( ! sourcePlatform.filter(version -> ! version.equals(targetPlatform)).isPresent() ||
                sourcePlatform.equals(versions.sourcePlatform())) &&
               ( ! sourceApplication.filter(version -> ! version.equals(targetApplication)).isPresent() ||
                sourceApplication.equals(versions.sourceApplication()));
    }

    public boolean targetsMatch(Versions versions) {
        return targetPlatform.equals(versions.targetPlatform()) &&
               targetApplication.equals(versions.targetApplication());
    }

    public boolean targetsMatch(JobStatus.JobRun jobRun) {
        return targetPlatform.equals(jobRun.platform()) &&
               targetApplication.equals(jobRun.application());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if ( ! (o instanceof Versions)) return false;
        Versions versions = (Versions) o;
        return Objects.equals(targetPlatform, versions.targetPlatform) &&
               Objects.equals(targetApplication, versions.targetApplication) &&
               Objects.equals(sourcePlatform, versions.sourcePlatform) &&
               Objects.equals(sourceApplication, versions.sourceApplication);
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetPlatform, targetApplication, sourcePlatform, sourceApplication);
    }

    @Override
    public String toString() {
        return String.format("platform %s%s, application %s%s",
                             sourcePlatform.filter(source -> !source.equals(targetPlatform))
                                           .map(source -> source + " -> ").orElse(""),
                             targetPlatform,
                             sourceApplication.filter(source -> !source.equals(targetApplication))
                                              .map(source -> source.id() + " -> ").orElse(""),
                             targetApplication.id());
    }

    /** Create versions using change contained in application */
    public static Versions from(Application application, Version defaultPlatformVersion) {
        return from(application.change(), application, Optional.empty(), defaultPlatformVersion);
    }

    /** Create versions using given change and application */
    public static Versions from(Change change, Application application, Optional<Deployment> deployment,
                                Version defaultPlatformVersion) {
        return new Versions(targetPlatform(application, change, deployment, defaultPlatformVersion),
                            targetApplication(application, change, deployment),
                            deployment.map(Deployment::version),
                            deployment.map(Deployment::applicationVersion));
    }

    private static Version targetPlatform(Application application, Change change, Optional<Deployment> deployment,
                                          Version defaultVersion) {
        if (change.isPinned() && change.platform().isPresent())
            return change.platform().get();

        return max(change.platform(), deployment.map(Deployment::version))
                .orElseGet(() -> application.oldestDeployedPlatform().orElse(defaultVersion));
    }

    private static ApplicationVersion targetApplication(Application application, Change change,
                                                        Optional<Deployment> deployment) {
        return max(change.application(), deployment.map(Deployment::applicationVersion))
                .orElseGet(() -> defaultApplicationVersion(application));
    }

    private static ApplicationVersion defaultApplicationVersion(Application application) {
        return application.oldestDeployedApplication()
                          .or(() -> application.instances().values().stream()
                                               .flatMap(instance -> instance.deploymentJobs().statusOf(JobType.component)
                                                                            .flatMap(JobStatus::lastSuccess)
                                                                            .map(JobStatus.JobRun::application)
                                                                            .stream())
                                               .findAny())
                          .orElse(ApplicationVersion.unknown);
    }

    private static <T extends Comparable<T>> Optional<T> max(Optional<T> o1, Optional<T> o2) {
        return ! o1.isPresent() ? o2 : ! o2.isPresent() ? o1 : o1.get().compareTo(o2.get()) >= 0 ? o1 : o2;
    }

}
