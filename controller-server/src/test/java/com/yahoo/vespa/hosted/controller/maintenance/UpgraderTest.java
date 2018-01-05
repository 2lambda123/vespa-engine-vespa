// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.component.Version;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.RegionName;
import com.yahoo.vespa.hosted.controller.api.integration.zone.ZoneId;
import com.yahoo.test.ManualClock;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.ControllerTester;
import com.yahoo.vespa.hosted.controller.application.ApplicationPackage;
import com.yahoo.vespa.hosted.controller.application.Change;
import com.yahoo.vespa.hosted.controller.application.Deployment;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs;
import com.yahoo.vespa.hosted.controller.deployment.ApplicationPackageBuilder;
import com.yahoo.vespa.hosted.controller.deployment.DeploymentTester;
import com.yahoo.vespa.hosted.controller.versions.VespaVersion;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author bratseth
 */
public class UpgraderTest {

    @Test
    public void testUpgrading() {
        // --- Setup
        DeploymentTester tester = new DeploymentTester();

        Version version = Version.fromString("5.0");
        tester.updateVersionStatus(version);

        tester.upgrader().maintain();
        assertEquals("No applications: Nothing to do", 0, tester.deploymentQueue().jobs().size());

        // Setup applications
        Application canary0 = tester.createAndDeploy("canary0", 1, "canary");
        Application canary1 = tester.createAndDeploy("canary1", 2, "canary");
        Application default0 = tester.createAndDeploy("default0", 3, "default");
        Application default1 = tester.createAndDeploy("default1", 4, "default");
        Application default2 = tester.createAndDeploy("default2", 5, "default");
        Application conservative0 = tester.createAndDeploy("conservative0", 6, "conservative");

        tester.upgrader().maintain();
        assertEquals("All already on the right version: Nothing to do", 0, tester.deploymentQueue().jobs().size());

        // --- 5.1 is released - everything goes smoothly
        version = Version.fromString("5.1");
        tester.updateVersionStatus(version);
        assertEquals(version, tester.controller().versionStatus().systemVersion().get().versionNumber());
        tester.upgrader().maintain();

        assertEquals("New system version: Should upgrade Canaries", 2, tester.deploymentQueue().jobs().size());
        tester.completeUpgrade(canary0, version, "canary");
        assertEquals(version, tester.configServer().lastPrepareVersion().get());

        tester.updateVersionStatus(version);
        tester.upgrader().maintain();
        assertEquals("One canary pending; nothing else", 1, tester.deploymentQueue().jobs().size());

        tester.completeUpgrade(canary1, version, "canary");

        tester.updateVersionStatus(version);
        assertEquals(VespaVersion.Confidence.normal, tester.controller().versionStatus().systemVersion().get().confidence());
        tester.upgrader().maintain();
        assertEquals("Canaries done: Should upgrade defaults", 3, tester.deploymentQueue().jobs().size());

        tester.completeUpgrade(default0, version, "default");
        tester.completeUpgrade(default1, version, "default");
        tester.completeUpgrade(default2, version, "default");

        tester.updateVersionStatus(version);
        assertEquals(VespaVersion.Confidence.high, tester.controller().versionStatus().systemVersion().get().confidence());
        tester.upgrader().maintain();
        assertEquals("Normals done: Should upgrade conservatives", 1, tester.deploymentQueue().jobs().size());
        tester.completeUpgrade(conservative0, version, "conservative");

        tester.updateVersionStatus(version);
        tester.upgrader().maintain();
        assertEquals("Nothing to do", 0, tester.deploymentQueue().jobs().size());

        // --- 5.2 is released - which fails a Canary
        version = Version.fromString("5.2");
        tester.updateVersionStatus(version);
        assertEquals(version, tester.controller().versionStatus().systemVersion().get().versionNumber());
        tester.upgrader().maintain();

        assertEquals("New system version: Should upgrade Canaries", 2, tester.deploymentQueue().jobs().size());
        tester.completeUpgradeWithError(canary0, version, "canary", DeploymentJobs.JobType.stagingTest);
        assertEquals("Other Canary was cancelled", 2, tester.deploymentQueue().jobs().size());
        // TODO: Cancelled would mean it was triggerd, removed from the build system, but never reported in.
        //       Thus, the expected number of jobs should be 1, above: the retrying canary0.
        //       Further, canary1 should be retried after the timeout period of 12 hours, but verifying this is
        //       not possible when jobs are consumed form the build system on notification, rather than on deploy.

        tester.updateVersionStatus(version);
        assertEquals(VespaVersion.Confidence.broken, tester.controller().versionStatus().systemVersion().get().confidence());
        tester.upgrader().maintain();
        assertEquals("Version broken, but Canaries should keep trying", 2, tester.deploymentQueue().jobs().size());

        // Exhaust canary retries.
        tester.notifyJobCompletion(DeploymentJobs.JobType.systemTest, canary1, false);
        tester.clock().advance(Duration.ofHours(1));
        tester.deployAndNotify(canary0, DeploymentTester.applicationPackage("canary"), false, DeploymentJobs.JobType.stagingTest);
        tester.notifyJobCompletion(DeploymentJobs.JobType.systemTest, canary1, false);
        //tester.deployAndNotify(canary1, DeploymentTester.applicationPackage("canary"), false, DeploymentJobs.JobType.stagingTest);

        // --- A new version is released - which repairs the Canary app and fails a default
        version = Version.fromString("5.3");
        tester.updateVersionStatus(version);
        assertEquals(version, tester.controller().versionStatus().systemVersion().get().versionNumber());
        tester.upgrader().maintain();

        assertEquals("New system version: Should upgrade Canaries", 2, tester.deploymentQueue().jobs().size());
        tester.completeUpgrade(canary0, version, "canary");
        assertEquals(version, tester.configServer().lastPrepareVersion().get());

        tester.updateVersionStatus(version);
        tester.upgrader().maintain();
        assertEquals("One canary pending; nothing else", 1, tester.deploymentQueue().jobs().size());

        tester.completeUpgrade(canary1, version, "canary");

        tester.updateVersionStatus(version);
        assertEquals(VespaVersion.Confidence.normal, tester.controller().versionStatus().systemVersion().get().confidence());
        tester.upgrader().maintain();

        assertEquals("Canaries done: Should upgrade defaults", 3, tester.deploymentQueue().jobs().size());

        tester.completeUpgradeWithError(default0, version, "default", DeploymentJobs.JobType.stagingTest);
        tester.completeUpgrade(default1, version, "default");
        tester.completeUpgrade(default2, version, "default");

        tester.updateVersionStatus(version);
        assertEquals("Not enough evidence to mark this as neither broken nor high",
                     VespaVersion.Confidence.normal, tester.controller().versionStatus().systemVersion().get().confidence());

        assertEquals("Upgrade with error should retry", 1, tester.deploymentQueue().jobs().size());

        // Finish previous run, with exhausted retry.
        tester.clock().advance(Duration.ofHours(1));
        tester.notifyJobCompletion(DeploymentJobs.JobType.stagingTest, default0, false);

        // --- Failing application is repaired by changing the application, causing confidence to move above 'high' threshold
        // Deploy application change
        tester.deployCompletely("default0");

        tester.updateVersionStatus(version);
        assertEquals(VespaVersion.Confidence.high, tester.controller().versionStatus().systemVersion().get().confidence());
        tester.upgrader().maintain();
        assertEquals("Normals done: Should upgrade conservatives", 1, tester.deploymentQueue().jobs().size());
        tester.completeUpgrade(conservative0, version, "conservative");

        tester.updateVersionStatus(version);
        tester.upgrader().maintain();
        assertEquals("Applications are on 5.3 - nothing to do", 0, tester.deploymentQueue().jobs().size());
        
        // --- Starting upgrading to a new version which breaks, causing upgrades to commence on the previous version
        Version version54 = Version.fromString("5.4");
        Application default3 = tester.createAndDeploy("default3", 5, "default"); // need 4 to break a version
        Application default4 = tester.createAndDeploy("default4", 5, "default");
        tester.updateVersionStatus(version54);
        tester.upgrader().maintain(); // cause canary upgrades to 5.4
        tester.completeUpgrade(canary0, version54, "canary");
        tester.completeUpgrade(canary1, version54, "canary");
        tester.updateVersionStatus(version54);
        assertEquals(VespaVersion.Confidence.normal, tester.controller().versionStatus().systemVersion().get().confidence());
        tester.upgrader().maintain();
        assertEquals("Upgrade of defaults are scheduled", 5, tester.deploymentQueue().jobs().size());
        assertEquals(version54, ((Change.VersionChange)tester.application(default0.id()).deploying().get()).version());
        assertEquals(version54, ((Change.VersionChange)tester.application(default1.id()).deploying().get()).version());
        assertEquals(version54, ((Change.VersionChange)tester.application(default2.id()).deploying().get()).version());
        assertEquals(version54, ((Change.VersionChange)tester.application(default3.id()).deploying().get()).version());
        assertEquals(version54, ((Change.VersionChange)tester.application(default4.id()).deploying().get()).version());
        tester.completeUpgrade(default0, version54, "default");
        // State: Default applications started upgrading to 5.4 (and one completed)
        Version version55 = Version.fromString("5.5");
        tester.updateVersionStatus(version55);
        tester.upgrader().maintain(); // cause canary upgrades to 5.5
        tester.completeUpgrade(canary0, version55, "canary");
        tester.completeUpgrade(canary1, version55, "canary");
        tester.updateVersionStatus(version55);
        assertEquals(VespaVersion.Confidence.normal, tester.controller().versionStatus().systemVersion().get().confidence());
        tester.upgrader().maintain();
        assertEquals("Upgrade of defaults are scheduled", 5, tester.deploymentQueue().jobs().size());
        assertEquals(version55, ((Change.VersionChange)tester.application(default0.id()).deploying().get()).version());
        assertEquals(version54, ((Change.VersionChange)tester.application(default1.id()).deploying().get()).version());
        assertEquals(version54, ((Change.VersionChange)tester.application(default2.id()).deploying().get()).version());
        assertEquals(version54, ((Change.VersionChange)tester.application(default3.id()).deploying().get()).version());
        assertEquals(version54, ((Change.VersionChange)tester.application(default4.id()).deploying().get()).version());
        tester.completeUpgrade(default1, version54, "default");
        tester.completeUpgrade(default2, version54, "default");
        tester.completeUpgradeWithError(default3, version54, "default", DeploymentJobs.JobType.stagingTest);
        tester.completeUpgradeWithError(default4, version54, "default", DeploymentJobs.JobType.productionUsWest1);
        // State: Default applications started upgrading to 5.5
        tester.upgrader().maintain();
        tester.completeUpgradeWithError(default0, version55, "default", DeploymentJobs.JobType.stagingTest);
        tester.completeUpgradeWithError(default1, version55, "default", DeploymentJobs.JobType.stagingTest);
        tester.completeUpgradeWithError(default2, version55, "default", DeploymentJobs.JobType.stagingTest);
        tester.completeUpgradeWithError(default3, version55, "default", DeploymentJobs.JobType.productionUsWest1);
        tester.updateVersionStatus(version55);
        assertEquals(VespaVersion.Confidence.broken, tester.controller().versionStatus().systemVersion().get().confidence());

        // Finish running job, without retry.
        tester.clock().advance(Duration.ofHours(1));
        tester.notifyJobCompletion(DeploymentJobs.JobType.productionUsWest1, default3, false);

        tester.upgrader().maintain();
        assertEquals("Upgrade of defaults are scheduled on 5.4 instead, since 5.5 broken: " +
                     "This is default3 since it failed upgrade on both 5.4 and 5.5",
                     1, tester.deploymentQueue().jobs().size());
        assertEquals("5.4", ((Change.VersionChange)tester.application(default3.id()).deploying().get()).version().toString());
    }

    @Test
    public void testUpgradingToVersionWhichBreaksSomeNonCanaries() {
        // --- Setup
        DeploymentTester tester = new DeploymentTester();
        tester.upgrader().maintain();
        assertEquals("No system version: Nothing to do", 0, tester.deploymentQueue().jobs().size());

        Version version = Version.fromString("5.0"); // (lower than the hardcoded version in the config server client)
        tester.updateVersionStatus(version);

        tester.upgrader().maintain();
        assertEquals("No applications: Nothing to do", 0, tester.deploymentQueue().jobs().size());

        // Setup applications
        Application canary0  = tester.createAndDeploy("canary0",  1, "canary");
        Application canary1  = tester.createAndDeploy("canary1",  2, "canary");
        Application default0 = tester.createAndDeploy("default0", 3, "default");
        Application default1 = tester.createAndDeploy("default1", 4, "default");
        Application default2 = tester.createAndDeploy("default2", 5, "default");
        Application default3 = tester.createAndDeploy("default3", 6, "default");
        Application default4 = tester.createAndDeploy("default4", 7, "default");
        Application default5 = tester.createAndDeploy("default5", 8, "default");
        Application default6 = tester.createAndDeploy("default6", 9, "default");
        Application default7 = tester.createAndDeploy("default7", 10, "default");
        Application default8 = tester.createAndDeploy("default8", 11, "default");
        Application default9 = tester.createAndDeploy("default9", 12, "default");

        tester.upgrader().maintain();
        assertEquals("All already on the right version: Nothing to do", 0, tester.deploymentQueue().jobs().size());

        // --- A new version is released
        version = Version.fromString("5.1");
        tester.updateVersionStatus(version);
        assertEquals(version, tester.controller().versionStatus().systemVersion().get().versionNumber());
        tester.upgrader().maintain();

        assertEquals("New system version: Should upgrade Canaries", 2, tester.deploymentQueue().jobs().size());
        tester.completeUpgrade(canary0, version, "canary");
        assertEquals(version, tester.configServer().lastPrepareVersion().get());

        tester.updateVersionStatus(version);
        tester.upgrader().maintain();
        assertEquals("One canary pending; nothing else", 1, tester.deploymentQueue().jobs().size());

        tester.completeUpgrade(canary1, version, "canary");

        tester.updateVersionStatus(version);
        assertEquals(VespaVersion.Confidence.normal, tester.controller().versionStatus().systemVersion().get().confidence());
        tester.upgrader().maintain();
        assertEquals("Canaries done: Should upgrade defaults", 10, tester.deploymentQueue().jobs().size());

        tester.completeUpgrade(default0, version, "default");
        tester.completeUpgradeWithError(default1, version, "default", DeploymentJobs.JobType.systemTest);
        tester.completeUpgradeWithError(default2, version, "default", DeploymentJobs.JobType.systemTest);
        tester.completeUpgradeWithError(default3, version, "default", DeploymentJobs.JobType.systemTest);
        tester.completeUpgradeWithError(default4, version, "default", DeploymentJobs.JobType.systemTest);

        // > 40% and at least 4 failed - version is broken
        tester.updateVersionStatus(version);
        tester.upgrader().maintain();
        assertEquals(VespaVersion.Confidence.broken, tester.controller().versionStatus().systemVersion().get().confidence());
        assertEquals("Upgrades are cancelled", 0, tester.deploymentQueue().jobs().size());
    }

    @Test
    public void testDeploymentAlreadyInProgressForUpgrade() {
        DeploymentTester tester = new DeploymentTester();
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .upgradePolicy("canary")
                .environment(Environment.prod)
                .region("us-east-3")
                .build();
        Version version = Version.fromString("5.0");
        tester.updateVersionStatus(version);

        Application app = tester.createApplication("app1", "tenant1", 1, 11L);
        tester.notifyJobCompletion(DeploymentJobs.JobType.component, app, true);
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.systemTest);
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.stagingTest);
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.productionUsEast3);

        tester.upgrader().maintain();
        assertEquals("Application is on expected version: Nothing to do", 0,
                     tester.deploymentQueue().jobs().size());

        // New version is released
        version = Version.fromString("5.1");
        tester.updateVersionStatus(version);
        assertEquals(version, tester.controller().versionStatus().systemVersion().get().versionNumber());
        tester.upgrader().maintain();

        // system-test completes successfully
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.systemTest);

        // staging-test fails multiple times, exhausts retries and failure is recorded
        tester.deployAndNotify(app, applicationPackage, false, DeploymentJobs.JobType.stagingTest);
        tester.deploymentQueue().takeJobsToRun();
        tester.clock().advance(Duration.ofMinutes(10));
        tester.notifyJobCompletion(DeploymentJobs.JobType.stagingTest, app, false);
        assertTrue("Retries exhausted", tester.deploymentQueue().jobs().isEmpty());
        assertTrue("Failure is recorded", tester.application(app.id()).deploymentJobs().hasFailures());
        assertTrue("Application has pending change", tester.application(app.id()).deploying().isPresent());

        // New version is released
        version = Version.fromString("5.2");
        tester.updateVersionStatus(version);
        assertEquals(version, tester.controller().versionStatus().systemVersion().get().versionNumber());

        // Upgrade is scheduled. system-tests starts, but does not complete
        tester.upgrader().maintain();
        assertTrue("Application still has failures", tester.application(app.id()).deploymentJobs().hasFailures());
        assertEquals(1, tester.deploymentQueue().jobs().size());
        tester.deploymentQueue().takeJobsToRun();

        // Upgrader runs again, nothing happens as there's already a job in progress for this change
        tester.upgrader().maintain();
        assertTrue("No more jobs triggered at this time", tester.deploymentQueue().jobs().isEmpty());
    }

    @Test
    public void testUpgradeCancelledWithDeploymentInProgress() {
        DeploymentTester tester = new DeploymentTester();
        Version version = Version.fromString("5.0");
        tester.updateVersionStatus(version);

        // Setup applications
        Application canary0 = tester.createAndDeploy("canary0", 1, "canary");
        Application canary1 = tester.createAndDeploy("canary1", 2, "canary");
        Application default0 = tester.createAndDeploy("default0", 3, "default");
        Application default1 = tester.createAndDeploy("default1", 4, "default");
        Application default2 = tester.createAndDeploy("default2", 5, "default");
        Application default3 = tester.createAndDeploy("default3", 6, "default");
        Application default4 = tester.createAndDeploy("default4", 7, "default");

        // New version is released
        version = Version.fromString("5.1");
        tester.updateVersionStatus(version);
        assertEquals(version, tester.controller().versionStatus().systemVersion().get().versionNumber());
        tester.upgrader().maintain();

        // Canaries upgrade and raise confidence
        tester.completeUpgrade(canary0, version, "canary");
        tester.completeUpgrade(canary1, version, "canary");
        tester.updateVersionStatus(version);
        assertEquals(VespaVersion.Confidence.normal, tester.controller().versionStatus().systemVersion().get().confidence());

        // Applications with default policy start upgrading
        tester.upgrader().maintain();
        assertEquals("Upgrade scheduled for remaining apps", 5, tester.deploymentQueue().jobs().size());

        // 4/5 applications fail and lowers confidence
        tester.completeUpgradeWithError(default0, version, "default", DeploymentJobs.JobType.systemTest);
        tester.completeUpgradeWithError(default1, version, "default", DeploymentJobs.JobType.systemTest);
        tester.completeUpgradeWithError(default2, version, "default", DeploymentJobs.JobType.systemTest);
        tester.completeUpgradeWithError(default3, version, "default", DeploymentJobs.JobType.systemTest);
        tester.updateVersionStatus(version);
        assertEquals(VespaVersion.Confidence.broken, tester.controller().versionStatus().systemVersion().get().confidence());
        tester.upgrader().maintain();

        // 5th app passes system-test, but does not trigger next job as upgrade is cancelled
        assertFalse("No change present", tester.applications().require(default4.id()).deploying().isPresent());
        tester.notifyJobCompletion(DeploymentJobs.JobType.systemTest, default4, true);
        assertTrue("All jobs consumed", tester.deploymentQueue().jobs().isEmpty());
    }

    @Test
    public void testConfidenceIgnoresFailingApplicationChanges() {
        DeploymentTester tester = new DeploymentTester();
        Version version = Version.fromString("5.0");
        tester.updateVersionStatus(version);

        // Setup applications
        Application canary0 = tester.createAndDeploy("canary0", 1, "canary");
        Application canary1 = tester.createAndDeploy("canary1", 2, "canary");
        Application default0 = tester.createAndDeploy("default0", 3, "default");
        Application default1 = tester.createAndDeploy("default1", 4, "default");
        Application default2 = tester.createAndDeploy("default2", 5, "default");
        Application default3 = tester.createAndDeploy("default3", 6, "default");
        Application default4 = tester.createAndDeploy("default4", 7, "default");

        // New version is released
        version = Version.fromString("5.1");
        tester.updateVersionStatus(version);
        assertEquals(version, tester.controller().versionStatus().systemVersion().get().versionNumber());
        tester.upgrader().maintain();

        // Canaries upgrade and raise confidence
        tester.completeUpgrade(canary0, version, "canary");
        tester.completeUpgrade(canary1, version, "canary");
        tester.updateVersionStatus(version);
        assertEquals(VespaVersion.Confidence.normal, tester.controller().versionStatus().systemVersion().get().confidence());

        // All applications upgrade successfully
        tester.upgrader().maintain();
        tester.completeUpgrade(default0, version, "default");
        tester.completeUpgrade(default1, version, "default");
        tester.completeUpgrade(default2, version, "default");
        tester.completeUpgrade(default3, version, "default");
        tester.completeUpgrade(default4, version, "default");
        tester.updateVersionStatus(version);
        assertEquals(VespaVersion.Confidence.high, tester.controller().versionStatus().systemVersion().get().confidence());

        // Multiple application changes are triggered and fail, but does not affect version confidence as upgrade has
        // completed successfully
        tester.notifyJobCompletion(DeploymentJobs.JobType.component, default0, false);
        tester.notifyJobCompletion(DeploymentJobs.JobType.component, default1, false);
        tester.notifyJobCompletion(DeploymentJobs.JobType.component, default2, true);
        tester.notifyJobCompletion(DeploymentJobs.JobType.component, default3, true);
        tester.notifyJobCompletion(DeploymentJobs.JobType.systemTest, default2, false);
        tester.notifyJobCompletion(DeploymentJobs.JobType.systemTest, default3, false);
        tester.updateVersionStatus(version);
        assertEquals(VespaVersion.Confidence.normal, tester.controller().versionStatus().systemVersion().get().confidence());
    }

    @Test
    public void testBlockVersionChange() {
        ManualClock clock = new ManualClock(Instant.parse("2017-09-26T18:00:00.00Z")); // Tuesday, 18:00
        DeploymentTester tester = new DeploymentTester(new ControllerTester(clock));
        Version version = Version.fromString("5.0");
        tester.updateVersionStatus(version);

        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .upgradePolicy("canary")
                // Block upgrades on Tuesday in hours 18 and 19
                .blockChange(false, true, "tue", "18-19", "UTC")
                .region("us-west-1")
                .build();

        Application app = tester.createAndDeploy("app1", 1, applicationPackage);

        // New version is released
        version = Version.fromString("5.1");
        tester.updateVersionStatus(version);

        // Application is not upgraded at this time
        tester.upgrader().maintain();
        assertTrue("No jobs scheduled", tester.deploymentQueue().jobs().isEmpty());

        // One hour passes, time is 19:00, still no upgrade
        tester.clock().advance(Duration.ofHours(1));
        tester.upgrader().maintain();
        assertTrue("No jobs scheduled", tester.deploymentQueue().jobs().isEmpty());

        // Two hours pass in total, time is 20:00 and application upgrades
        tester.clock().advance(Duration.ofHours(1));
        tester.upgrader().maintain();
        assertFalse("Job is scheduled", tester.deploymentQueue().jobs().isEmpty());
        tester.completeUpgrade(app, version, "canary");
        assertTrue("All jobs consumed", tester.deploymentQueue().jobs().isEmpty());
    }

    @Test
    public void testBlockVersionChangeHalfwayThough() {
        ManualClock clock = new ManualClock(Instant.parse("2017-09-26T17:00:00.00Z")); // Tuesday, 17:00
        DeploymentTester tester = new DeploymentTester(new ControllerTester(clock));
        ReadyJobsTrigger readyJobsTrigger = new ReadyJobsTrigger(tester.controller(),
                                                                 Duration.ofHours(1),
                                                                 new JobControl(tester.controllerTester().curator()));

        Version version = Version.fromString("5.0");
        tester.updateVersionStatus(version);

        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .upgradePolicy("canary")
                // Block upgrades on Tuesday in hours 18 and 19
                .blockChange(false, true, "tue", "18-19", "UTC")
                .region("us-west-1")
                .region("us-central-1")
                .region("us-east-3")
                .build();

        Application app = tester.createAndDeploy("app1", 1, applicationPackage);

        // New version is released
        version = Version.fromString("5.1");
        tester.updateVersionStatus(version);

        // Application upgrade starts
        tester.upgrader().maintain();
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.systemTest);
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.stagingTest);
        clock.advance(Duration.ofHours(1)); // Entering block window after prod job is triggered
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.productionUsWest1);
        assertTrue(tester.deploymentQueue().jobs().isEmpty()); // Next job not triggered due to being in the block window

        // One hour passes, time is 19:00, still no upgrade
        tester.clock().advance(Duration.ofHours(1));
        readyJobsTrigger.maintain();
        assertTrue("No jobs scheduled", tester.deploymentQueue().jobs().isEmpty());

        // Another hour pass, time is 20:00 and application upgrades
        tester.clock().advance(Duration.ofHours(1));
        readyJobsTrigger.maintain();
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.productionUsCentral1);
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.productionUsEast3);
        assertTrue("All jobs consumed", tester.deploymentQueue().jobs().isEmpty());
    }

    /**
     * Tests the scenario where a release is deployed to 2 of 3 production zones, then blocked,
     * followed by timeout of the upgrade and a new release.
     * In this case, the blocked production zone should not progress with upgrading to the previous version,
     * and should not upgrade to the new version until the other production zones have it
     * (expected behavior; both requirements are debatable).
     */
    @Test
    public void testBlockVersionChangeHalfwayThoughThenNewVersion() {
        ManualClock clock = new ManualClock(Instant.parse("2017-09-29T16:00:00.00Z")); // Friday, 16:00
        DeploymentTester tester = new DeploymentTester(new ControllerTester(clock));
        ReadyJobsTrigger readyJobsTrigger = new ReadyJobsTrigger(tester.controller(),
                                                                 Duration.ofHours(1),
                                                                 new JobControl(tester.controllerTester().curator()));

        Version version = Version.fromString("5.0");
        tester.updateVersionStatus(version);

        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .upgradePolicy("canary")
                // Block upgrades on weekends and ouside working hours
                .blockChange(false, true, "mon-fri", "00-09,17-23", "UTC")
                .blockChange(false, true, "sat-sun", "00-23", "UTC")
                .region("us-west-1")
                .region("us-central-1")
                .region("us-east-3")
                .build();

        Application app = tester.createAndDeploy("app1", 1, applicationPackage);

        // New version is released
        version = Version.fromString("5.1");
        tester.updateVersionStatus(version);

        // Application upgrade starts
        tester.upgrader().maintain();
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.systemTest);
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.stagingTest);
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.productionUsWest1);
        clock.advance(Duration.ofHours(1)); // Entering block window after prod job is triggered
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.productionUsCentral1);
        assertTrue(tester.deploymentQueue().jobs().isEmpty()); // Next job not triggered due to being in the block window

        // A day passes and we get a new version
        tester.clock().advance(Duration.ofDays(1));
        version = Version.fromString("5.2");
        tester.updateVersionStatus(version);
        tester.upgrader().maintain();
        readyJobsTrigger.maintain();
        assertTrue("Nothing is scheduled", tester.deploymentQueue().jobs().isEmpty());

        // Monday morning: We are not blocked
        tester.clock().advance(Duration.ofDays(1)); // Sunday, 17:00
        tester.clock().advance(Duration.ofHours(17)); // Monday, 10:00
        tester.upgrader().maintain();
        readyJobsTrigger.maintain();
        // We proceed with the new version in the expected order, not starting with the previously blocked version:
        // Test jobs are run with the new version, but not production as we are in the block window
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.systemTest);
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.stagingTest);
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.productionUsWest1);
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.productionUsCentral1);
        tester.deployAndNotify(app, applicationPackage, true, DeploymentJobs.JobType.productionUsEast3);
        assertTrue("All jobs consumed", tester.deploymentQueue().jobs().isEmpty());
        
        // App is completely upgraded to the latest version
        for (Deployment deployment : tester.applications().require(app.id()).deployments().values())
            assertEquals(version, deployment.version());
    }

    @Test
    public void testReschedulesUpgradeAfterTimeout() {
        DeploymentTester tester = new DeploymentTester();
        Version version = Version.fromString("5.0");
        tester.updateVersionStatus(version);

        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("us-west-1")
                .build();

        // Setup applications
        Application canary0 = tester.createAndDeploy("canary0", 1, "canary");
        Application canary1 = tester.createAndDeploy("canary1", 2, "canary");
        Application default0 = tester.createAndDeploy("default0", 3, "default");
        Application default1 = tester.createAndDeploy("default1", 4, "default");
        Application default2 = tester.createAndDeploy("default2", 5, "default");
        Application default3 = tester.createAndDeploy("default3", 6, "default");
        Application default4 = tester.createAndDeploy("default4", 7, "default");
        
        assertEquals(version, default0.oldestDeployedVersion().get());

        // New version is released
        version = Version.fromString("5.1");
        tester.updateVersionStatus(version);
        assertEquals(version, tester.controller().versionStatus().systemVersion().get().versionNumber());
        tester.upgrader().maintain();

        // Canaries upgrade and raise confidence
        tester.completeUpgrade(canary0, version, "canary");
        tester.completeUpgrade(canary1, version, "canary");
        tester.updateVersionStatus(version);
        assertEquals(VespaVersion.Confidence.normal, tester.controller().versionStatus().systemVersion().get().confidence());

        // Applications with default policy start upgrading
        tester.clock().advance(Duration.ofMinutes(1));
        tester.upgrader().maintain();
        assertEquals("Upgrade scheduled for remaining apps", 5, tester.deploymentQueue().jobs().size());

        // 4/5 applications fail, confidence is lowered and upgrade is cancelled
        tester.completeUpgradeWithError(default0, version, "default", DeploymentJobs.JobType.systemTest);
        tester.completeUpgradeWithError(default1, version, "default", DeploymentJobs.JobType.systemTest);
        tester.completeUpgradeWithError(default2, version, "default", DeploymentJobs.JobType.systemTest);
        tester.completeUpgradeWithError(default3, version, "default", DeploymentJobs.JobType.systemTest);
        tester.updateVersionStatus(version);
        assertEquals(VespaVersion.Confidence.broken, tester.controller().versionStatus().systemVersion().get().confidence());

        tester.upgrader().maintain();

        // Exhaust retries and finish runs
        tester.clock().advance(Duration.ofHours(1));
        tester.notifyJobCompletion(DeploymentJobs.JobType.systemTest, default0, false);
        tester.notifyJobCompletion(DeploymentJobs.JobType.systemTest, default1, false);
        tester.notifyJobCompletion(DeploymentJobs.JobType.systemTest, default2, false);
        tester.notifyJobCompletion(DeploymentJobs.JobType.systemTest, default3, false);

        // 5th app never reports back and has a dead job, but no ongoing change
        Application deadLocked = tester.applications().require(default4.id());
        assertTrue("Jobs in progress", deadLocked.deploymentJobs().isRunning(tester.controller().applications().deploymentTrigger().jobTimeoutLimit()));
        assertFalse("No change present", deadLocked.deploying().isPresent());

        // 4/5 applications are repaired and confidence is restored
        tester.deployCompletely(default0, applicationPackage);
        tester.deployCompletely(default1, applicationPackage);
        tester.deployCompletely(default2, applicationPackage);
        tester.deployCompletely(default3, applicationPackage);

        tester.updateVersionStatus(version);
        assertEquals(VespaVersion.Confidence.normal, tester.controller().versionStatus().systemVersion().get().confidence());

        tester.upgrader().maintain();
        assertEquals("Upgrade scheduled for previously failing apps", 4, tester.deploymentQueue().jobs().size());
        tester.completeUpgrade(default0, version, "default");
        tester.completeUpgrade(default1, version, "default");
        tester.completeUpgrade(default2, version, "default");
        tester.completeUpgrade(default3, version, "default");

        assertEquals(version, tester.application(default0.id()).oldestDeployedVersion().get());
        assertEquals(version, tester.application(default1.id()).oldestDeployedVersion().get());
        assertEquals(version, tester.application(default2.id()).oldestDeployedVersion().get());
        assertEquals(version, tester.application(default3.id()).oldestDeployedVersion().get());
    }

    @Test
    public void testThrottlesUpgrades() {
        DeploymentTester tester = new DeploymentTester();
        Version version = Version.fromString("5.0");
        tester.updateVersionStatus(version);

        // Setup our own upgrader as we need to control the interval
        Upgrader upgrader = new Upgrader(tester.controller(), Duration.ofMinutes(10),
                                         new JobControl(tester.controllerTester().curator()),
                                         tester.controllerTester().curator());
        upgrader.setUpgradesPerMinute(0.2);

        // Setup applications
        Application canary0 = tester.createAndDeploy("canary0", 1, "canary");
        Application canary1 = tester.createAndDeploy("canary1", 2, "canary");
        Application default0 = tester.createAndDeploy("default0", 3, "default");
        Application default1 = tester.createAndDeploy("default1", 4, "default");
        Application default2 = tester.createAndDeploy("default2", 5, "default");
        Application default3 = tester.createAndDeploy("default3", 6, "default");

        // Dev deployment which should be ignored
        Application dev0 = tester.createApplication("dev0", "tenant1", 7, 1L);
        tester.controllerTester().deploy(dev0, ZoneId.from(Environment.dev, RegionName.from("dev-region")));

        // New version is released and canaries upgrade
        version = Version.fromString("5.1");
        tester.updateVersionStatus(version);
        assertEquals(version, tester.controller().versionStatus().systemVersion().get().versionNumber());
        upgrader.maintain();

        assertEquals(2, tester.deploymentQueue().jobs().size());
        tester.completeUpgrade(canary0, version, "canary");
        tester.completeUpgrade(canary1, version, "canary");
        tester.updateVersionStatus(version);

        // Next run upgrades a subset
        upgrader.maintain();
        assertEquals(2, tester.deploymentQueue().jobs().size());
        tester.completeUpgrade(default0, version, "default");
        tester.completeUpgrade(default2, version, "default");

        // Remaining applications upgraded
        upgrader.maintain();
        assertEquals(2, tester.deploymentQueue().jobs().size());
        tester.completeUpgrade(default1, version, "default");
        tester.completeUpgrade(default3, version, "default");
        upgrader.maintain();
        assertTrue("All jobs consumed", tester.deploymentQueue().jobs().isEmpty());
    }

}
