// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.versions;

import com.google.common.collect.ImmutableSet;
import com.yahoo.component.Version;
import com.yahoo.component.Vtag;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.HostName;
import com.yahoo.config.provision.zone.ZoneApi;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.Instance;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.ControllerTester;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.Node;
import com.yahoo.vespa.hosted.controller.application.ApplicationPackage;
import com.yahoo.vespa.hosted.controller.application.SystemApplication;
import com.yahoo.vespa.hosted.controller.deployment.ApplicationPackageBuilder;
import com.yahoo.vespa.hosted.controller.deployment.DeploymentTester;
import com.yahoo.vespa.hosted.controller.persistence.CuratorDb;
import com.yahoo.vespa.hosted.controller.persistence.MockCuratorDb;
import com.yahoo.vespa.hosted.controller.versions.VespaVersion.Confidence;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionUsEast3;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionUsWest1;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.stagingTest;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.systemTest;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Test computing of version status
 * 
 * @author bratseth
 */
public class VersionStatusTest {
    
    @Test
    public void testEmptyVersionStatus() {
        VersionStatus status = VersionStatus.empty();
        assertFalse(status.systemVersion().isPresent());
        assertTrue(status.versions().isEmpty());
    }

    @Test
    public void testSystemVersionIsControllerVersionIfConfigServersAreNewer() {
        DeploymentTester tester = new DeploymentTester();
        Version largerThanCurrent = new Version(Vtag.currentVersion.getMajor() + 1);
        tester.upgradeSystemApplications(largerThanCurrent);
        VersionStatus versionStatus = VersionStatus.compute(tester.controller());
        assertEquals(Vtag.currentVersion, versionStatus.systemVersion().get().versionNumber());
    }

    @Test
    public void testSystemVersionIsVersionOfOldestConfigServer() {
        DeploymentTester tester = new DeploymentTester();
        Version version0 = Version.fromString("6.1");
        Version version1 = Version.fromString("6.5");
        // Upgrade some config servers
        for (ZoneApi zone : tester.controllerTester().zoneRegistry().zones().all().zones()) {
            for (Node node : tester.configServer().nodeRepository().list(zone.getId(), SystemApplication.configServer.id())) {
                Node upgradedNode = new Node(node.hostname(), node.state(), node.type(), node.owner(), version1, node.wantedVersion());
                tester.configServer().nodeRepository().putByHostname(zone.getId(), upgradedNode);
                break;
            }
        }
        VersionStatus versionStatus = VersionStatus.compute(tester.controller());
        assertEquals(version0, versionStatus.systemVersion().get().versionNumber());
    }

    @Test
    public void testControllerVersionIsVersionOfOldestController() {
        HostName controller1 = HostName.from("controller-1");
        HostName controller2 = HostName.from("controller-2");
        HostName controller3 = HostName.from("controller-3");
        MockCuratorDb db = new MockCuratorDb(Stream.of(controller1, controller2, controller3)
                                                   .map(hostName -> hostName.value() + ":2222")
                                                   .collect(Collectors.joining(",")));
        ControllerTester tester = new ControllerTester(db);

        writeControllerVersion(controller1, Version.fromString("6.2"), db);
        writeControllerVersion(controller2, Version.fromString("6.1"), db);
        writeControllerVersion(controller3, Version.fromString("6.2"), db);

        VersionStatus versionStatus = VersionStatus.compute(tester.controller());
        assertEquals("Controller version is oldest version", Version.fromString("6.1"),
                     versionStatus.controllerVersion().get().versionNumber());

        // Last controller upgrades
        writeControllerVersion(controller2, Version.fromString("6.2"), db);
        versionStatus = VersionStatus.compute(tester.controller());
        assertEquals(Version.fromString("6.2"), versionStatus.controllerVersion().get().versionNumber());
    }

    @Test
    public void testSystemVersionNeverShrinks() {
        DeploymentTester tester = new DeploymentTester();
        Version version0 = Version.fromString("6.2");
        tester.upgradeSystem(version0);
        assertEquals(version0, tester.controller().systemVersion());

        // Downgrade one config server in each zone
        Version ancientVersion = Version.fromString("5.1");
        for (ZoneApi zone : tester.controller().zoneRegistry().zones().all().zones()) {
            for (Node node : tester.configServer().nodeRepository().list(zone.getId(), SystemApplication.configServer.id())) {
                Node downgradedNode = new Node(node.hostname(), node.state(), node.type(), node.owner(), ancientVersion, node.wantedVersion());
                tester.configServer().nodeRepository().putByHostname(zone.getId(), downgradedNode);
                break;
            }
        }

        tester.computeVersionStatus();
        assertEquals(version0, tester.controller().systemVersion());
    }

    @Test
    public void testVersionStatusAfterApplicationUpdates() {
        DeploymentTester tester = new DeploymentTester();
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .upgradePolicy("default")
                .environment(Environment.prod)
                .region("us-west-1")
                .region("us-east-3")
                .build();

        Version version1 = new Version("6.2");
        Version version2 = new Version("6.3");
        tester.upgradeSystem(version1);

        // Setup applications
        Application app1 = tester.createAndDeploy("app1", 11, applicationPackage);
        Application app2 = tester.createAndDeploy("app2", 22, applicationPackage);
        Application app3 = tester.createAndDeploy("app3", 33, applicationPackage);

        // version2 is released
        tester.upgradeSystem(version2);
        tester.triggerUntilQuiescence();

        // - app1 is in production on version1, but then fails in system test on version2
        tester.completeUpgradeWithError(app1, version2, applicationPackage, systemTest);
        // - app2 is partially in production on version1 and partially on version2
        tester.completeUpgradeWithError(app2, version2, applicationPackage, productionUsEast3);
        // - app3 is in production on version1, but then fails in staging test on version2
        tester.completeUpgradeWithError(app3, version2, applicationPackage, stagingTest);

        tester.computeVersionStatus();
        List<VespaVersion> versions = tester.controller().versionStatus().versions();
        assertEquals("The two versions above exist", 2, versions.size());

        VespaVersion v1 = versions.get(0);
        assertEquals(version1, v1.versionNumber());
        assertEquals("No applications are failing on version1.", ImmutableSet.of(), v1.statistics().failing());
        assertEquals("All applications have at least one active production deployment on version 1.", ImmutableSet.of(app1.id().defaultInstance(), app2.id().defaultInstance(), app3.id().defaultInstance()), v1.statistics().production());
        assertEquals("No applications have active deployment jobs on version1.", ImmutableSet.of(), v1.statistics().deploying());

        VespaVersion v2 = versions.get(1);
        assertEquals(version2, v2.versionNumber());
        assertEquals("All applications have failed on version2 in at least one zone.", ImmutableSet.of(app1.id().defaultInstance(), app2.id().defaultInstance(), app3.id().defaultInstance()), v2.statistics().failing());
        assertEquals("Only app2 has successfully deployed to production on version2.", ImmutableSet.of(app2.id().defaultInstance()), v2.statistics().production());
        // Should test the below, but can't easily be done with current test framework. This test passes in DeploymentApiTest.
        // assertEquals("All applications are being retried on version2.", ImmutableSet.of(app1.id(), app2.id(), app3.id()), v2.statistics().deploying());
    }
    
    @Test
    public void testVersionConfidence() {
        DeploymentTester tester = new DeploymentTester();
        Version version0 = new Version("6.2");
        tester.upgradeSystem(version0);

        // Setup applications - all running on version0
        Application canary0 = tester.createAndDeploy("canary0", 1, "canary");
        Application canary1 = tester.createAndDeploy("canary1", 2, "canary");
        Application canary2 = tester.createAndDeploy("canary2", 3, "canary");
        Application default0 = tester.createAndDeploy("default0", 4, "default");
        Application default1 = tester.createAndDeploy("default1", 5, "default");
        Application default2 = tester.createAndDeploy("default2", 6, "default");
        Application default3 = tester.createAndDeploy("default3", 7, "default");
        Application default4 = tester.createAndDeploy("default4", 8, "default");
        Application default5 = tester.createAndDeploy("default5", 9, "default");
        Application default6 = tester.createAndDeploy("default6", 10, "default");
        Application default7 = tester.createAndDeploy("default7", 11, "default");
        Application default8 = tester.createAndDeploy("default8", 12, "default");
        Application default9 = tester.createAndDeploy("default9", 13, "default");
        Application conservative0 = tester.createAndDeploy("conservative1", 14, "conservative");

        // Applications that do not affect confidence calculation:

        // Application without deployment
        Application ignored0 = tester.createApplication("ignored0", "tenant1", 1000, 1000L);

        assertEquals("All applications running on this version: High",
                     Confidence.high, confidence(tester.controller(), version0));

        // New version is released
        Version version1 = new Version("6.3");
        tester.upgradeSystem(version1);
        tester.triggerUntilQuiescence();

        // Canaries upgrade to new versions and fail
        tester.completeUpgrade(canary0, version1, "canary");
        tester.completeUpgradeWithError(canary1, version1, "canary", productionUsWest1);
        tester.computeVersionStatus();
        assertEquals("One canary failed: Broken",
                     Confidence.broken, confidence(tester.controller(), version1));

        // New version is released
        Version version2 = new Version("6.4");
        tester.upgradeSystem(version2);
        tester.triggerUntilQuiescence();
        assertEquals("Confidence defaults to low for version with no applications",
                     Confidence.low, confidence(tester.controller(), version2));

        // All canaries upgrade successfully
        tester.completeUpgrade(canary0, version2, "canary");
        tester.jobCompletion(productionUsWest1).application(canary1).unsuccessful().submit();
        tester.completeUpgrade(canary1, version2, "canary");

        assertEquals("Confidence for remains unchanged for version1: Broken",
                     Confidence.broken, confidence(tester.controller(), version1));
        assertEquals("Nothing has failed but not all canaries have upgraded: Low",
                     Confidence.low, confidence(tester.controller(), version2));

        // Remaining canary upgrades to version2 which raises confidence to normal and more apps upgrade
        tester.jobCompletion(systemTest).application(canary2).unsuccessful().submit();
        tester.jobCompletion(stagingTest).application(canary2).submit();
        tester.completeUpgrade(canary2, version2, "canary");
        tester.computeVersionStatus();
        tester.upgrader().maintain();
        tester.triggerUntilQuiescence();
        assertEquals("Canaries have upgraded: Normal",
                     Confidence.normal, confidence(tester.controller(), version2));
        tester.completeUpgrade(default0, version2, "default");
        tester.completeUpgrade(default1, version2, "default");
        tester.completeUpgrade(default2, version2, "default");
        tester.completeUpgrade(default3, version2, "default");
        tester.completeUpgrade(default4, version2, "default");
        tester.completeUpgrade(default5, version2, "default");
        tester.completeUpgrade(default6, version2, "default");
        tester.completeUpgrade(default7, version2, "default");
        tester.computeVersionStatus();

        // Remember confidence across restart
        tester.restartController();

        assertEquals("Confidence remains unchanged for version0: High",
                     Confidence.high, confidence(tester.controller(), version0));
        assertEquals("All canaries deployed + < 90% of defaults: Normal",
                     Confidence.normal, confidence(tester.controller(), version2));
        assertTrue("Status for version without applications is removed",
                   tester.controller().versionStatus().versions().stream()
                           .noneMatch(vespaVersion -> vespaVersion.versionNumber().equals(version1)));

        // Another default application upgrades, raising confidence to high
        tester.completeUpgrade(default8, version2, "default");
        tester.completeUpgrade(default9, version2, "default");
        tester.computeVersionStatus();

        assertEquals("Confidence remains unchanged for version0: High",
                     Confidence.high, confidence(tester.controller(), version0));
        assertEquals("90% of defaults deployed successfully: High",
                     VespaVersion.Confidence.high, confidence(tester.controller(), version2));

        // A new version is released, all canaries upgrade successfully, but enough "default" apps fail to mark version
        // as broken
        Version version3 = new Version("6.5");
        tester.upgradeSystem(version3);
        tester.triggerUntilQuiescence();
        tester.completeUpgrade(canary0, version3, "canary");
        tester.completeUpgrade(canary1, version3, "canary");
        tester.completeUpgrade(canary2, version3, "canary");
        tester.upgradeSystem(version3);
        tester.triggerUntilQuiescence();
        tester.completeUpgradeWithError(default0, version3, "default", stagingTest);
        tester.completeUpgradeWithError(default1, version3, "default", stagingTest);
        tester.completeUpgradeWithError(default2, version3, "default", stagingTest);
        tester.completeUpgradeWithError(default9, version3, "default", stagingTest);
        tester.computeVersionStatus();

        assertEquals("Confidence remains unchanged for version0: High",
                     Confidence.high, confidence(tester.controller(), version0));
        assertEquals("Confidence remains unchanged for version2: High",
                     Confidence.high, confidence(tester.controller(), version2));
        assertEquals("40% of defaults failed: Broken",
                     VespaVersion.Confidence.broken, confidence(tester.controller(), version3));

        // Test version order
        List<VespaVersion> versions = tester.controller().versionStatus().versions();
        assertEquals(3, versions.size());
        assertEquals("6.2", versions.get(0).versionNumber().toString());
        assertEquals("6.4", versions.get(1).versionNumber().toString());
        assertEquals("6.5", versions.get(2).versionNumber().toString());

        // Check release status is correct (static data in MockMavenRepository).
        assertTrue(versions.get(0).isReleased());
        assertFalse(versions.get(1).isReleased());
        assertFalse(versions.get(2).isReleased());
    }

    @Test
    public void testConfidenceOverride() {
        DeploymentTester tester = new DeploymentTester();
        Version version0 = new Version("6.2");
        tester.upgradeSystem(version0);

        // Create and deploy application on current version
        Application app = tester.createAndDeploy("app", 1, "canary");
        tester.computeVersionStatus();
        assertEquals(Confidence.high, confidence(tester.controller(), version0));

        // Override confidence
        tester.upgrader().overrideConfidence(version0, Confidence.broken);
        tester.computeVersionStatus();
        assertEquals(Confidence.broken, confidence(tester.controller(), version0));

        // New version is released and application upgrades
        Version version1 = new Version("6.3");
        tester.upgradeSystem(version1);
        tester.completeUpgrade(app, version1, "canary");
        tester.computeVersionStatus();
        assertEquals(Confidence.high, confidence(tester.controller(), version1));

        // Stale override was removed
        assertFalse("Stale override removed", tester.controller().curator().readConfidenceOverrides()
                                                    .keySet().contains(version0));
    }

    @Test
    public void testCommitDetailsPreservation() {
        DeploymentTester tester = new DeploymentTester();
        // Commit details are set for initial version
        var version0 = new Version("6.2");
        var commitSha0 = "badc0ffee";
        var commitDate0 = Instant.EPOCH;
        tester.upgradeSystem(version0);
        assertEquals(version0, tester.controller().versionStatus().systemVersion().get().versionNumber());
        assertEquals(commitSha0, tester.controller().versionStatus().systemVersion().get().releaseCommit());
        assertEquals(commitDate0, tester.controller().versionStatus().systemVersion().get().committedAt());

        // Deploy app on version0 to keep computing statistics for that version
        tester.createAndDeploy("app", 1, "canary");

        // Commit details are updated for new version
        var version1 = new Version("6.3");
        var commitSha1 = "deadbeef";
        var commitDate1 = Instant.ofEpochMilli(123);
        tester.upgradeController(version1, commitSha1, commitDate1);
        tester.upgradeSystemApplications(version1);
        assertEquals(version1, tester.controller().versionStatus().systemVersion().get().versionNumber());
        assertEquals(commitSha1, tester.controller().versionStatus().systemVersion().get().releaseCommit());
        assertEquals(commitDate1, tester.controller().versionStatus().systemVersion().get().committedAt());

        // Commit details for previous version are preserved
        assertEquals(commitSha0, tester.controller().versionStatus().version(version0).releaseCommit());
        assertEquals(commitDate0, tester.controller().versionStatus().version(version0).committedAt());
    }

    @Test
    public void testConfidenceChangeRespectsTimeWindow() {
        DeploymentTester tester = new DeploymentTester();
        // Canaries and normal application deploys on initial version
        assertEquals(5, tester.hourOfDayAfter(Duration.ZERO));
        Version version0 = Version.fromString("7.1");
        tester.upgradeSystem(version0);
        Application canary0 = tester.createAndDeploy("canary0", 1, "canary");
        Application canary1 = tester.createAndDeploy("canary1", 1, "canary");
        Application default0 = tester.createAndDeploy("default0", 1, "default");
        tester.computeVersionStatus();
        assertSame(Confidence.high, tester.controller().versionStatus().version(version0).confidence());

        // System and canary0 is upgraded within allowed time window
        Version version1 = Version.fromString("7.2");
        tester.upgradeSystem(version1);
        tester.completeUpgrade(canary0, version1, "canary");
        tester.computeVersionStatus();
        assertSame(Confidence.low, tester.controller().versionStatus().version(version1).confidence());

        // canary1 breaks just outside allowed upgrade window
        assertEquals(12, tester.hourOfDayAfter(Duration.ofHours(7)));
        tester.completeUpgradeWithError(canary1, version1, "canary", systemTest);
        tester.computeVersionStatus();
        assertSame(Confidence.broken, tester.controller().versionStatus().version(version1).confidence());

        // Second canary is fixed later in the day. All canaries are now fixed, but confidence is not raised as we're
        // outside the allowed time window
        assertEquals(20, tester.hourOfDayAfter(Duration.ofHours(8)));
        tester.completeUpgrade(canary1, version1, "canary");
        tester.computeVersionStatus();
        assertSame(Confidence.broken, tester.controller().versionStatus().version(version1).confidence());

        // Early morning arrives, confidence is raised and normal application upgrades
        assertEquals(5, tester.hourOfDayAfter(Duration.ofHours(9)));
        tester.computeVersionStatus();
        assertSame(Confidence.normal, tester.controller().versionStatus().version(version1).confidence());
        tester.upgrader().maintain();
        tester.triggerUntilQuiescence();
        tester.completeUpgrade(default0, version1, "default");

        // Another version is released. System and canaries upgrades late, confidence stays low
        Version version2 = Version.fromString("7.3");
        tester.upgradeSystem(version2);
        assertEquals(14, tester.hourOfDayAfter(Duration.ofHours(9)));
        tester.completeUpgrade(canary0, version2, "canary");
        tester.completeUpgrade(canary1, version2, "canary");
        tester.computeVersionStatus();
        assertSame(Confidence.low, tester.controller().versionStatus().version(version2).confidence());

        // Confidence override takes precedence over time window constraints
        tester.upgrader().overrideConfidence(version2, Confidence.normal);
        tester.computeVersionStatus();
        assertSame(Confidence.normal, tester.controller().versionStatus().version(version2).confidence());
        tester.upgrader().overrideConfidence(version2, Confidence.low);
        tester.computeVersionStatus();
        assertSame(Confidence.low, tester.controller().versionStatus().version(version2).confidence());
        tester.upgrader().removeConfidenceOverride(version2);

        // Next morning arrives, confidence is raised and normal application upgrades
        assertEquals(7, tester.hourOfDayAfter(Duration.ofHours(17)));
        tester.computeVersionStatus();
        assertSame(Confidence.normal, tester.controller().versionStatus().version(version2).confidence());
        tester.upgrader().maintain();
        tester.triggerUntilQuiescence();
        tester.completeUpgrade(default0, version2, "default");
    }

    private static void writeControllerVersion(HostName hostname, Version version, CuratorDb db) {
        db.writeControllerVersion(hostname, new ControllerVersion(version, "badc0ffee", Instant.EPOCH));
    }

    private Confidence confidence(Controller controller, Version version) {
        return controller.versionStatus().versions().stream()
                .filter(v -> v.statistics().version().equals(version))
                .findFirst()
                .map(VespaVersion::confidence)
                .orElseThrow(() -> new IllegalArgumentException("Expected to find version: " + version));
    }

}
