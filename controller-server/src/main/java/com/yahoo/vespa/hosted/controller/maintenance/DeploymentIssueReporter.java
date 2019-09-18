// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.SystemName;
import com.yahoo.vespa.hosted.controller.Instance;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.integration.organization.DeploymentIssues;
import com.yahoo.vespa.hosted.controller.api.integration.organization.IssueId;
import com.yahoo.vespa.hosted.controller.api.integration.organization.User;
import com.yahoo.vespa.hosted.controller.application.InstanceList;
import com.yahoo.vespa.hosted.controller.tenant.Tenant;
import com.yahoo.yolean.Exceptions;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;

import static com.yahoo.vespa.hosted.controller.versions.VespaVersion.Confidence.broken;

/**
 * Maintenance job which files issues for tenants when they have jobs which fails continuously
 * and escalates issues which are not handled in a timely manner.
 *
 * @author jonmv
 */
public class DeploymentIssueReporter extends Maintainer {

    static final Duration maxFailureAge = Duration.ofDays(2);
    static final Duration maxInactivity = Duration.ofDays(4);
    static final Duration upgradeGracePeriod = Duration.ofHours(4);

    private final DeploymentIssues deploymentIssues;

    DeploymentIssueReporter(Controller controller, DeploymentIssues deploymentIssues, Duration maintenanceInterval, JobControl jobControl) {
        super(controller, maintenanceInterval, jobControl);
        this.deploymentIssues = deploymentIssues;
    }

    @Override
    protected void maintain() {
        maintainDeploymentIssues(applications());
        maintainPlatformIssue(applications());
        escalateInactiveDeploymentIssues(applications());
    }

    /** Returns the applications to maintain issue status for. */
    private List<Instance> applications() {
        return InstanceList.from(controller().applications().asList())
                           .withProjectId()
                           .asList();
    }

    /**
     * File issues for applications which have failed deployment for longer than maxFailureAge
     * and store the issue id for the filed issues. Also, clear the issueIds of applications
     * where deployment has not failed for this amount of time.
     */
    private void maintainDeploymentIssues(List<Instance> instances) {
        Set<ApplicationId> failingApplications = InstanceList.from(instances)
                                                             .failingApplicationChangeSince(controller().clock().instant().minus(maxFailureAge))
                                                             .asList().stream()
                                                             .map(Instance::id)
                                                             .collect(Collectors.toSet());

        for (Instance instance : instances)
            if (failingApplications.contains(instance.id()))
                fileDeploymentIssueFor(instance.id());
            else
                store(instance.id(), null);
    }

    /**
     * When the confidence for the system version is BROKEN, file an issue listing the
     * applications that have been failing the upgrade to the system version for
     * longer than the set grace period, or update this list if the issue already exists.
     */
    private void maintainPlatformIssue(List<Instance> instances) {
        if (controller().system() == SystemName.cd)
            return;
        
        Version systemVersion = controller().systemVersion();

        if ((controller().versionStatus().version(systemVersion).confidence() != broken))
            return;

        if (InstanceList.from(instances)
                        .failingUpgradeToVersionSince(systemVersion, controller().clock().instant().minus(upgradeGracePeriod))
                        .isEmpty())
            return;

        List<ApplicationId> failingApplications = InstanceList.from(instances)
                                                              .failingUpgradeToVersionSince(systemVersion, controller().clock().instant())
                                                              .idList();

        deploymentIssues.fileUnlessOpen(failingApplications, systemVersion);
    }

    private Tenant ownerOf(ApplicationId applicationId) {
        return controller().tenants().get(applicationId.tenant())
                .orElseThrow(() -> new IllegalStateException("No tenant found for application " + applicationId));
    }

    private User userFor(Tenant tenant) {
        return User.from(tenant.name().value().replaceFirst(Tenant.userPrefix, ""));
    }

    /** File an issue for applicationId, if it doesn't already have an open issue associated with it. */
    private void fileDeploymentIssueFor(ApplicationId applicationId) {
        try {
            Tenant tenant = ownerOf(applicationId);
            tenant.contact().ifPresent(contact -> {
                User assignee = tenant.type() == Tenant.Type.user ? userFor(tenant) : null;
                Optional<IssueId> ourIssueId = controller().applications().require(applicationId).deploymentJobs().issueId();
                IssueId issueId = deploymentIssues.fileUnlessOpen(ourIssueId, applicationId, assignee, contact);
                store(applicationId, issueId);
            });
        }
        catch (RuntimeException e) { // Catch errors due to wrong data in the controller, or issues client timeout.
            log.log(Level.INFO, "Exception caught when attempting to file an issue for '" + applicationId + "': " + Exceptions.toMessageString(e));
        }
    }

    /** Escalate issues for which there has been no activity for a certain amount of time. */
    private void escalateInactiveDeploymentIssues(Collection<Instance> instances) {
        instances.forEach(application -> application.deploymentJobs().issueId().ifPresent(issueId -> {
            try {
                Tenant tenant = ownerOf(application.id());
                deploymentIssues.escalateIfInactive(issueId,
                                                    maxInactivity,
                                                    tenant.type() == Tenant.Type.athenz ? tenant.contact() : Optional.empty());
            }
            catch (RuntimeException e) {
                log.log(Level.INFO, "Exception caught when attempting to escalate issue with id '" + issueId + "': " + Exceptions.toMessageString(e));
            }
        }));
    }

    private void store(ApplicationId id, IssueId issueId) {
        controller().applications().lockIfPresent(id, application ->
                controller().applications().store(application.withDeploymentIssueId(issueId)));
    }

}
