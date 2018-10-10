// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.deployment;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.TenantName;
import com.yahoo.test.ManualClock;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.ApplicationController;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.ControllerTester;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.api.integration.stubs.MockBuildService;
import com.yahoo.vespa.hosted.controller.api.integration.zone.ZoneId;
import com.yahoo.vespa.hosted.controller.application.ApplicationPackage;
import com.yahoo.vespa.hosted.controller.application.Change;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs;
import com.yahoo.vespa.hosted.controller.application.JobStatus;
import com.yahoo.vespa.hosted.controller.application.SystemApplication;
import com.yahoo.vespa.hosted.controller.integration.ApplicationStoreMock;
import com.yahoo.vespa.hosted.controller.integration.ArtifactRepositoryMock;
import com.yahoo.vespa.hosted.controller.integration.ConfigServerMock;
import com.yahoo.vespa.hosted.controller.maintenance.JobControl;
import com.yahoo.vespa.hosted.controller.maintenance.ReadyJobsTrigger;
import com.yahoo.vespa.hosted.controller.maintenance.Upgrader;
import com.yahoo.vespa.hosted.controller.versions.VersionStatus;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * This class provides convenience methods for testing deployments
 *
 * @author bratseth
 * @author mpolden
 */
public class DeploymentTester {

    // Set a long interval so that maintainers never do scheduled runs during tests
    private static final Duration maintenanceInterval = Duration.ofDays(1);

    private final ControllerTester tester;
    private final Upgrader upgrader;
    private final ReadyJobsTrigger readyJobTrigger;

    public DeploymentTester() {
        this(new ControllerTester());
    }

    public DeploymentTester(ControllerTester tester) {
        this.tester = tester;
        tester.curator().writeUpgradesPerMinute(100);

        JobControl jobControl = new JobControl(tester.curator());
        this.upgrader = new Upgrader(tester.controller(), maintenanceInterval, jobControl, tester.curator());
        this.readyJobTrigger = new ReadyJobsTrigger(tester.controller(), maintenanceInterval, jobControl);
    }

    public Upgrader upgrader() { return upgrader; }

    public ReadyJobsTrigger readyJobTrigger() { return readyJobTrigger; }

    public Controller controller() { return tester.controller(); }

    public ApplicationController applications() { return tester.controller().applications(); }

    public MockBuildService buildService() { return tester.buildService(); }

    public DeploymentTrigger deploymentTrigger() { return tester.controller().applications().deploymentTrigger(); }

    public ManualClock clock() { return tester.clock(); }

    public ControllerTester controllerTester() { return tester; }

    public ConfigServerMock configServer() { return tester.configServer(); }

    public ArtifactRepositoryMock artifactRepository() { return tester.artifactRepository(); }

    public ApplicationStoreMock applicationStore() { return tester.applicationStore(); }

    public Application application(String name) {
        return application(ApplicationId.from("tenant1", name, "default"));
    }

    public Application application(ApplicationId application) {
        return controller().applications().require(application);
    }

    /** Re-compute and write version status */
    public void computeVersionStatus() {
        controller().updateVersionStatus(VersionStatus.compute(controller()));
    }

    /** Upgrade controller to given version */
    public void upgradeController(Version version) {
        controller().curator().writeControllerVersion(controller().hostname(), version);
        computeVersionStatus();
    }

    /** Upgrade system applications in all zones to given version */
    public void upgradeSystemApplications(Version version) {
        for (ZoneId zone : tester.zoneRegistry().zones().all().ids()) {
            for (SystemApplication application : SystemApplication.all()) {
                tester.configServer().setVersion(application.id(), zone, version);
                tester.configServer().convergeServices(application.id(), zone);
            }
        }
        computeVersionStatus();
    }

    /** Upgrade entire system to given version */
    public void upgradeSystem(Version version) {
        upgradeController(version);
        upgradeSystemApplications(version);
        upgrader().maintain();
        readyJobTrigger().maintain();
    }

    public void triggerUntilQuiescence() {
        while (deploymentTrigger().triggerReadyJobs() > 0);
    }

    public Version defaultPlatformVersion() {
        return configServer().initialVersion();
    }

    public Application createApplication(String applicationName, String tenantName, long projectId, long propertyId) {
        TenantName tenant = tester.createTenant(tenantName, UUID.randomUUID().toString(), propertyId);
        return tester.createApplication(tenant, applicationName, "default", projectId);
    }

    public void restartController() { tester.createNewController(); }

    /** Notify the controller about a job completing */
    public BuildJob jobCompletion(JobType job) {
        return new BuildJob(this::notifyJobCompletion, tester.artifactRepository()).type(job);
    }

    /** Simulate the full lifecycle of an application deployment as declared in given application package */
    public Application createAndDeploy(String applicationName, int projectId, ApplicationPackage applicationPackage) {
        TenantName tenant = tester.createTenant("tenant1", "domain1", 1L);
        return createAndDeploy(tenant, applicationName, projectId, applicationPackage);
    }

    /** Simulate the full lifecycle of an application deployment as declared in given application package */
    public Application createAndDeploy(TenantName tenant, String applicationName, int projectId, ApplicationPackage applicationPackage) {
        Application application = tester.createApplication(tenant, applicationName, "default", projectId);
        deployCompletely(application, applicationPackage);
        return applications().require(application.id());
    }

    /** Simulate the full lifecycle of an application deployment to prod.us-west-1 with the given upgrade policy */
    public Application createAndDeploy(String applicationName, int projectId, String upgradePolicy) {
        return createAndDeploy(applicationName, projectId, applicationPackage(upgradePolicy));
    }

    /** Simulate the full lifecycle of an application deployment to prod.us-west-1 with the given upgrade policy */
    public Application createAndDeploy(TenantName tenant, String applicationName, int projectId, String upgradePolicy) {
        return createAndDeploy(tenant, applicationName, projectId, applicationPackage(upgradePolicy));
    }

    /** Deploy application completely using the given application package */
    public void deployCompletely(Application application, ApplicationPackage applicationPackage) {
        deployCompletely(application, applicationPackage, BuildJob.defaultBuildNumber);
    }

    public void completeDeploymentWithError(Application application, ApplicationPackage applicationPackage, long buildNumber, JobType failOnJob) {
        jobCompletion(JobType.component).application(application)
                                        .buildNumber(buildNumber)
                                        .uploadArtifact(applicationPackage)
                                        .submit();
        completeDeployment(application, applicationPackage, Optional.ofNullable(failOnJob));
    }

    public void deployCompletely(Application application, ApplicationPackage applicationPackage, long buildNumber) {
        completeDeploymentWithError(application, applicationPackage, buildNumber, null);
    }

    private void completeDeployment(Application application, ApplicationPackage applicationPackage, Optional<JobType> failOnJob) {
        assertTrue(applications().require(application.id()).change().isPresent());
        DeploymentSteps steps = controller().applications().deploymentTrigger().steps(applicationPackage.deploymentSpec());
        List<JobType> jobs = steps.jobs();
        for (JobType job : jobs) {
            boolean failJob = failOnJob.map(j -> j.equals(job)).orElse(false);
            deployAndNotify(application, applicationPackage, ! failJob, job);
            if (failJob) {
                break;
            }
        }
        if (failOnJob.isPresent()) {
            assertTrue(applications().require(application.id()).change().isPresent());
            assertTrue(applications().require(application.id()).deploymentJobs().hasFailures());
        } else {
            assertFalse(applications().require(application.id()).change().isPresent());
        }
    }

    public void completeUpgrade(Application application, Version version, String upgradePolicy) {
        completeUpgrade(application, version, applicationPackage(upgradePolicy));
    }

    public void completeUpgrade(Application application, Version version, ApplicationPackage applicationPackage) {
        assertTrue(application + " has a change", applications().require(application.id()).change().isPresent());
        assertEquals(Change.of(version), applications().require(application.id()).change());
        completeDeployment(application, applicationPackage, Optional.empty());
    }

    public void completeUpgradeWithError(Application application, Version version, String upgradePolicy, JobType failOnJob) {
        completeUpgradeWithError(application, version, applicationPackage(upgradePolicy), Optional.of(failOnJob));
    }

    public void completeUpgradeWithError(Application application, Version version, ApplicationPackage applicationPackage, JobType failOnJob) {
        completeUpgradeWithError(application, version, applicationPackage, Optional.of(failOnJob));
    }

    private void completeUpgradeWithError(Application application, Version version, ApplicationPackage applicationPackage, Optional<JobType> failOnJob) {
        assertTrue(applications().require(application.id()).change().isPresent());
        assertEquals(Change.of(version), applications().require(application.id()).change());
        completeDeployment(application, applicationPackage, failOnJob);
    }

    public void deploy(JobType job, Application application, ApplicationPackage applicationPackage) {
        deploy(job, application, Optional.of(applicationPackage), false);
    }

    public void deploy(JobType job, Application application, ApplicationPackage applicationPackage,
                       boolean deployCurrentVersion) {
        deploy(job, application, Optional.of(applicationPackage), deployCurrentVersion);
    }

    public void deploy(JobType job, Application application, Optional<ApplicationPackage> applicationPackage,
                       boolean deployCurrentVersion) {
        tester.deploy(application, job.zone(controller().system()), applicationPackage, deployCurrentVersion);
    }

    public void deployAndNotify(Application application, String upgradePolicy, boolean success, JobType job) {
        deployAndNotify(application, applicationPackage(upgradePolicy), success, job);
    }

    public void deployAndNotify(Application application, ApplicationPackage applicationPackage, boolean success, JobType job) {
        deployAndNotify(application, Optional.of(applicationPackage), success, job);
    }

    public void deployAndNotify(Application application, boolean success, JobType job) {
        deployAndNotify(application, Optional.empty(), success, job);
    }

    public void deployAndNotify(Application application, Optional<ApplicationPackage> applicationPackage, boolean success, JobType job) {
        if (success) {
            // Staging deploys twice, once with current version and once with new version
            if (job == JobType.stagingTest) {
                deploy(job, application, applicationPackage, true);
            }
            deploy(job, application, applicationPackage, false);
        }
        // Deactivate test deployments after deploy. This replicates the behaviour of the tenant pipeline
        if (job.isTest()) {
            controller().applications().deactivate(application.id(), job.zone(controller().system()));
        }
        jobCompletion(job).application(application).success(success).submit();
    }

    public Optional<JobStatus.JobRun> firstFailing(Application application, JobType job) {
        return tester.controller().applications().require(application.id())
                     .deploymentJobs().jobStatus().get(job).firstFailing();
    }

    private void notifyJobCompletion(DeploymentJobs.JobReport report) {
        if (report.jobType() != JobType.component && ! buildService().remove(report.buildJob()))
            throw new IllegalArgumentException(report.jobType() + " is not running for " + report.applicationId());
        assertFalse("Unexpected entry '" + report.jobType() + "@" + report.projectId() + " in: " + buildService().jobs(),
                    buildService().remove(report.buildJob()));

        applications().deploymentTrigger().notifyOfCompletion(report);
        applications().deploymentTrigger().triggerReadyJobs();
    }

    public static ApplicationPackage applicationPackage(String upgradePolicy) {
        return new ApplicationPackageBuilder()
                .upgradePolicy(upgradePolicy)
                .environment(Environment.prod)
                .region("us-west-1")
                .region("us-east-3")
                .build();
    }

    public void assertRunning(JobType job, ApplicationId application) {
        assertTrue(String.format("Job %s for %s is running", job, application), isRunning(job, application));
    }

    public void assertNotRunning(JobType job, ApplicationId application) {
        assertFalse(String.format("Job %s for %s is not running", job, application), isRunning(job, application));
    }

    private boolean isRunning(JobType job, ApplicationId application) {
        return buildService().jobs().contains(ControllerTester.buildJob(application(application), job));
    }

}
