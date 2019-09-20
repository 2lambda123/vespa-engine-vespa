// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller;

import com.google.common.collect.Sets;
import com.yahoo.component.Version;
import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.application.api.ValidationId;
import com.yahoo.config.application.api.ValidationOverrides;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.CloudName;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.SystemName;
import com.yahoo.config.provision.TenantName;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.flags.InMemoryFlagSource;
import com.yahoo.vespa.hosted.controller.api.application.v4.model.DeployOptions;
import com.yahoo.vespa.hosted.controller.api.application.v4.model.EndpointStatus;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;
import com.yahoo.vespa.hosted.controller.api.integration.certificates.ApplicationCertificate;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.SourceRevision;
import com.yahoo.vespa.hosted.controller.api.integration.dns.Record;
import com.yahoo.vespa.hosted.controller.api.integration.routing.RoutingEndpoint;
import com.yahoo.vespa.hosted.controller.application.ApplicationPackage;
import com.yahoo.vespa.hosted.controller.application.AssignedRotation;
import com.yahoo.vespa.hosted.controller.application.Deployment;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs.JobError;
import com.yahoo.vespa.hosted.controller.application.DeploymentMetrics;
import com.yahoo.vespa.hosted.controller.application.JobStatus;
import com.yahoo.vespa.hosted.controller.deployment.ApplicationPackageBuilder;
import com.yahoo.vespa.hosted.controller.deployment.BuildJob;
import com.yahoo.vespa.hosted.controller.deployment.DeploymentTester;
import com.yahoo.vespa.hosted.controller.integration.ZoneApiMock;
import com.yahoo.vespa.hosted.controller.persistence.ApplicationSerializer;
import com.yahoo.vespa.hosted.controller.persistence.InstanceSerializer;
import com.yahoo.vespa.hosted.controller.persistence.MockCuratorDb;
import com.yahoo.vespa.hosted.controller.persistence.OldMockCuratorDb;
import com.yahoo.vespa.hosted.controller.rotation.RotationId;
import com.yahoo.vespa.hosted.controller.rotation.RotationLock;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.yahoo.config.provision.SystemName.main;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.component;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionUsEast3;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionUsWest1;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.stagingTest;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.systemTest;
import static java.time.temporal.ChronoUnit.MILLIS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author bratseth
 * @author mpolden
 */
public class ControllerTest {

    private final DeploymentTester tester = new DeploymentTester();

    @Test
    public void testDeployment() {
        // Setup system
        ApplicationController applications = tester.controller().applications();
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("us-west-1")
                .region("us-east-3")
                .build();

        // staging job - succeeding
        Version version1 = tester.defaultPlatformVersion();
        Application app1 = tester.createApplication("app1", "tenant1", 1, 11L);
        Instance instance = tester.instance(app1.id());
        tester.jobCompletion(component).application(app1).uploadArtifact(applicationPackage).submit();
        assertEquals("Application version is known from completion of initial job",
                     ApplicationVersion.from(BuildJob.defaultSourceRevision, BuildJob.defaultBuildNumber),
                     tester.controller().applications().require(app1.id()).change().application().get());
        tester.deployAndNotify(instance.id(), Optional.of(applicationPackage), true, systemTest);
        tester.deployAndNotify(instance.id(), Optional.of(applicationPackage), true, stagingTest);
        assertEquals(4, applications.require(app1.id()).deploymentJobs().jobStatus().size());

        ApplicationVersion applicationVersion = tester.controller().applications().require(app1.id()).change().application().get();
        assertFalse("Application version has been set during deployment", applicationVersion.isUnknown());
        assertStatus(JobStatus.initial(stagingTest)
                              .withTriggering(version1, applicationVersion, Optional.empty(),"", tester.clock().instant().truncatedTo(MILLIS))
                              .withCompletion(42, Optional.empty(), tester.clock().instant().truncatedTo(MILLIS)), app1.id(), tester.controller());

        // Causes first deployment job to be triggered
        assertStatus(JobStatus.initial(productionUsWest1)
                              .withTriggering(version1, applicationVersion, Optional.empty(), "", tester.clock().instant().truncatedTo(MILLIS)), app1.id(), tester.controller());
        tester.clock().advance(Duration.ofSeconds(1));

        // production job (failing) after deployment
        tester.deploy(productionUsWest1, instance.id(), applicationPackage);
        tester.deployAndNotify(instance.id(), Optional.of(applicationPackage), false, productionUsWest1);
        assertEquals(4, applications.require(app1.id()).deploymentJobs().jobStatus().size());

        JobStatus expectedJobStatus = JobStatus.initial(productionUsWest1)
                                               .withTriggering(version1, applicationVersion, Optional.empty(), "", tester.clock().instant().truncatedTo(MILLIS)) // Triggered first without application version info
                                               .withCompletion(42, Optional.of(JobError.unknown), tester.clock().instant().truncatedTo(MILLIS))
                                               .withTriggering(version1,
                                                               applicationVersion,
                                                               Optional.of(tester.instance(app1.id()).deployments().get(productionUsWest1.zone(main))),
                                                               "",
                                                               tester.clock().instant().truncatedTo(MILLIS)); // Re-triggering (due to failure) has application version info

        assertStatus(expectedJobStatus, app1.id(), tester.controller());

        // Simulate restart
        tester.restartController();

        applications = tester.controller().applications();

        assertNotNull(tester.controller().tenants().get(TenantName.from("tenant1")));
        assertNotNull(applications.get(ApplicationId.from(TenantName.from("tenant1"),
                                                          ApplicationName.from("application1"),
                                                          InstanceName.from("default"))));
        assertEquals(4, applications.require(app1.id()).deploymentJobs().jobStatus().size());


        tester.clock().advance(Duration.ofHours(1));

        // system and staging test job - succeeding
        tester.jobCompletion(component).application(app1).nextBuildNumber().uploadArtifact(applicationPackage).submit();
        applicationVersion = tester.instance("app1").change().application().get();
        tester.deployAndNotify(instance.id(), Optional.of(applicationPackage), true, systemTest);
        assertStatus(JobStatus.initial(systemTest)
                              .withTriggering(version1, applicationVersion, Optional.of(tester.instance(app1.id()).deployments().get(productionUsWest1.zone(main))), "", tester.clock().instant().truncatedTo(MILLIS))
                              .withCompletion(42, Optional.empty(), tester.clock().instant().truncatedTo(MILLIS)),
                     app1.id(), tester.controller());
        tester.clock().advance(Duration.ofHours(1)); // Stop retrying
        tester.jobCompletion(productionUsWest1).application(app1).unsuccessful().submit();
        tester.deployAndNotify(instance.id(), Optional.of(applicationPackage), true, stagingTest);

        // production job succeeding now
        expectedJobStatus = expectedJobStatus
                .withTriggering(version1, applicationVersion, Optional.of(tester.instance(app1.id()).deployments().get(productionUsWest1.zone(main))), "", tester.clock().instant().truncatedTo(MILLIS))
                .withCompletion(42, Optional.empty(), tester.clock().instant().truncatedTo(MILLIS));
        tester.deployAndNotify(instance.id(), Optional.of(applicationPackage), true, productionUsWest1);
        assertStatus(expectedJobStatus, app1.id(), tester.controller());

        // causes triggering of next production job
        assertStatus(JobStatus.initial(productionUsEast3)
                              .withTriggering(version1, applicationVersion, Optional.empty(), "", tester.clock().instant().truncatedTo(MILLIS)),
                     app1.id(), tester.controller());
        tester.deployAndNotify(instance.id(), Optional.of(applicationPackage), true, productionUsEast3);

        assertEquals(5, applications.get(app1.id()).get().deploymentJobs().jobStatus().size());

        // Production zone for which there is no JobType is not allowed.
        applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("deep-space-9")
                .build();
        try {
            tester.controller().jobController().submit(app1.id(), BuildJob.defaultSourceRevision, "a@b",
                                                       2, applicationPackage, new byte[0]);
            fail("Expected exception due to illegal deployment spec.");
        }
        catch (IllegalArgumentException e) {
            assertEquals("Zone prod.deep-space-9 in deployment spec was not found in this system!", e.getMessage());
        }

        // prod zone removal is not allowed
        applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("us-east-3")
                .build();
        tester.jobCompletion(component).application(app1).nextBuildNumber().nextBuildNumber().uploadArtifact(applicationPackage).submit();
        try {
            tester.deploy(systemTest, instance.id(), applicationPackage);
            fail("Expected exception due to illegal production deployment removal");
        }
        catch (IllegalArgumentException e) {
            assertEquals("deployment-removal: application 'tenant1.app1' is deployed in us-west-1, but does not include this zone in deployment.xml. " +
                         ValidationOverrides.toAllowMessage(ValidationId.deploymentRemoval),
                         e.getMessage());
        }
        assertNotNull("Zone was not removed",
                      applications.require(app1.id()).deployments().get(productionUsWest1.zone(main)));
        JobStatus jobStatus = applications.require(app1.id()).deploymentJobs().jobStatus().get(productionUsWest1);
        assertNotNull("Deployment job was not removed", jobStatus);
        assertEquals(42, jobStatus.lastCompleted().get().id());
        assertEquals("New change available", jobStatus.lastCompleted().get().reason());

        // prod zone removal is allowed with override
        applicationPackage = new ApplicationPackageBuilder()
                .allow(ValidationId.deploymentRemoval)
                .upgradePolicy("default")
                .environment(Environment.prod)
                .region("us-east-3")
                .build();
        tester.jobCompletion(component).application(app1).nextBuildNumber(2).uploadArtifact(applicationPackage).submit();
        tester.deployAndNotify(instance.id(), Optional.of(applicationPackage), true, systemTest);
        assertNull("Zone was removed",
                   applications.require(app1.id()).deployments().get(productionUsWest1.zone(main)));
        assertNull("Deployment job was removed", applications.require(app1.id()).deploymentJobs().jobStatus().get(productionUsWest1));
    }

    @Test
    public void testDeploymentApplicationVersion() {
        Application app = tester.createApplication("app1", "tenant1", 1, 11L);
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("us-west-1")
                .region("us-east-3")
                .build();
        SourceRevision source = new SourceRevision("repo", "master", "commit1");

        ApplicationVersion applicationVersion = ApplicationVersion.from(source, 101);
        runDeployment(tester, app.id(), applicationVersion, applicationPackage, source,101);
        assertEquals("Artifact is downloaded twice in staging and once for other zones", 5,
                     tester.controllerTester().serviceRegistry().artifactRepositoryMock().hits(app.id(), applicationVersion.id()));

        // Application is upgraded. This makes deployment orchestration pick the last successful application version in
        // zones which do not have permanent deployments, e.g. test and staging
        runUpgrade(tester, app.id(), applicationVersion);
    }

    @Test
    public void testGlobalRotations() {
        // Setup
        ControllerTester tester = this.tester.controllerTester();
        ZoneId zone = ZoneId.from(Environment.defaultEnvironment(), RegionName.defaultName());
        ApplicationId app = ApplicationId.from("tenant", "app1", "default");
        DeploymentId deployment = new DeploymentId(app, zone);
        tester.serviceRegistry().routingGeneratorMock().putEndpoints(deployment, List.of(
                new RoutingEndpoint("http://old-endpoint.vespa.yahooapis.com:4080", "host1", false, "upstream2"),
                new RoutingEndpoint("http://qrs-endpoint.vespa.yahooapis.com:4080", "host1", false, "upstream1"),
                new RoutingEndpoint("http://feeding-endpoint.vespa.yahooapis.com:4080", "host2", false, "upstream3"),
                new RoutingEndpoint("http://global-endpoint.vespa.yahooapis.com:4080", "host1", true, "upstream1"),
                new RoutingEndpoint("http://alias-endpoint.vespa.yahooapis.com:4080", "host1", true, "upstream1")
        ));

        Supplier<Map<RoutingEndpoint, EndpointStatus>> rotationStatus = () -> tester.controller().applications().globalRotationStatus(deployment);
        Function<String, Optional<EndpointStatus>> findStatusByUpstream = (upstreamName) -> {
            return rotationStatus.get()
                                 .entrySet().stream()
                                 .filter(kv -> kv.getKey().upstreamName().equals(upstreamName))
                                 .findFirst()
                                 .map(Map.Entry::getValue);
        };

        // Check initial rotation status
        assertEquals(1, rotationStatus.get().size());
        assertEquals(findStatusByUpstream.apply("upstream1").get().getStatus(), EndpointStatus.Status.in);

        // Set the global rotations out of service
        EndpointStatus status = new EndpointStatus(EndpointStatus.Status.out, "unit-test", "Test", tester.clock().instant().getEpochSecond());
        tester.controller().applications().setGlobalRotationStatus(deployment, status);
        assertEquals(1, rotationStatus.get().size());
        assertEquals(findStatusByUpstream.apply("upstream1").get().getStatus(), EndpointStatus.Status.out);
        assertEquals("unit-test", findStatusByUpstream.apply("upstream1").get().getReason());

        // Deployment without a global endpoint
        tester.serviceRegistry().routingGeneratorMock().putEndpoints(deployment, List.of(
                new RoutingEndpoint("http://old-endpoint.vespa.yahooapis.com:4080", "host1", false, "upstream2"),
                new RoutingEndpoint("http://qrs-endpoint.vespa.yahooapis.com:4080", "host1", false, "upstream1"),
                new RoutingEndpoint("http://feeding-endpoint.vespa.yahooapis.com:4080", "host2", false, "upstream3")
        ));
        assertFalse("No global endpoint exists", findStatusByUpstream.apply("upstream1").isPresent());
        try {
            tester.controller().applications().setGlobalRotationStatus(deployment, status);
            fail("Expected exception");
        } catch (IllegalArgumentException ignored) {}
    }

    @Test
    public void testDnsAliasRegistration() {
        Application application = tester.createApplication("app1", "tenant1", 1, 1L);

        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .endpoint("default", "foo")
                .region("us-west-1")
                .region("us-central-1") // Two deployments should result in each DNS alias being registered once
                .build();

        tester.deployCompletely(application, applicationPackage);
        Collection<Deployment> deployments = tester.instance(application.id()).deployments().values();
        assertFalse(deployments.isEmpty());
        for (Deployment deployment : deployments) {
            assertEquals("Rotation names are passed to config server in " + deployment.zone(),
                         Set.of("rotation-id-01",
                                "app1--tenant1.global.vespa.oath.cloud"),
                         tester.configServer().rotationNames().get(new DeploymentId(application.id(), deployment.zone())));
        }
        tester.flushDnsRequests();

        assertEquals(1, tester.controllerTester().nameService().records().size());

        var record = tester.controllerTester().findCname("app1--tenant1.global.vespa.oath.cloud");
        assertTrue(record.isPresent());
        assertEquals("app1--tenant1.global.vespa.oath.cloud", record.get().name().asString());
        assertEquals("rotation-fqdn-01.", record.get().data().asString());
    }

    @Test
    public void testDnsAliasRegistrationLegacy() {
        Application application = tester.createApplication("app1", "tenant1", 1, 1L);

        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .globalServiceId("foo")
                .region("us-west-1")
                .region("us-central-1") // Two deployments should result in each DNS alias being registered once
                .build();

        tester.deployCompletely(application, applicationPackage);
        Collection<Deployment> deployments = tester.instance(application.id()).deployments().values();
        assertFalse(deployments.isEmpty());
        for (Deployment deployment : deployments) {
            assertEquals("Rotation names are passed to config server in " + deployment.zone(),
                    Set.of("rotation-id-01",
                            "app1--tenant1.global.vespa.oath.cloud",
                            "app1.tenant1.global.vespa.yahooapis.com",
                            "app1--tenant1.global.vespa.yahooapis.com"),
                    tester.configServer().rotationNames().get(new DeploymentId(application.id(), deployment.zone())));
        }
        tester.flushDnsRequests();
        assertEquals(3, tester.controllerTester().nameService().records().size());

        Optional<Record> record = tester.controllerTester().findCname("app1--tenant1.global.vespa.yahooapis.com");
        assertTrue(record.isPresent());
        assertEquals("app1--tenant1.global.vespa.yahooapis.com", record.get().name().asString());
        assertEquals("rotation-fqdn-01.", record.get().data().asString());

        record = tester.controllerTester().findCname("app1--tenant1.global.vespa.oath.cloud");
        assertTrue(record.isPresent());
        assertEquals("app1--tenant1.global.vespa.oath.cloud", record.get().name().asString());
        assertEquals("rotation-fqdn-01.", record.get().data().asString());

        record = tester.controllerTester().findCname("app1.tenant1.global.vespa.yahooapis.com");
        assertTrue(record.isPresent());
        assertEquals("app1.tenant1.global.vespa.yahooapis.com", record.get().name().asString());
        assertEquals("rotation-fqdn-01.", record.get().data().asString());
    }

    @Test
    public void testDnsAliasRegistrationWithEndpoints() {
        Application application = tester.createApplication("app1", "tenant1", 1, 1L);

        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .endpoint("foobar", "qrs", "us-west-1", "us-central-1")
                .endpoint("default", "qrs", "us-west-1", "us-central-1")
                .endpoint("all", "qrs")
                .endpoint("west", "qrs", "us-west-1")
                .region("us-west-1")
                .region("us-central-1")
                .build();

        tester.deployCompletely(application, applicationPackage);
        Collection<Deployment> deployments = tester.instance(application.id()).deployments().values();
        assertFalse(deployments.isEmpty());

        var notWest = Set.of(
                "rotation-id-01", "foobar--app1--tenant1.global.vespa.oath.cloud",
                "rotation-id-02", "app1--tenant1.global.vespa.oath.cloud",
                "rotation-id-04", "all--app1--tenant1.global.vespa.oath.cloud"
        );
        var west = Sets.union(notWest, Set.of("rotation-id-03", "west--app1--tenant1.global.vespa.oath.cloud"));

        for (Deployment deployment : deployments) {
            assertEquals("Rotation names are passed to config server in " + deployment.zone(),
                    ZoneId.from("prod.us-west-1").equals(deployment.zone()) ? west : notWest,
                    tester.configServer().rotationNames().get(new DeploymentId(application.id(), deployment.zone())));
        }
        tester.flushDnsRequests();

        assertEquals(4, tester.controllerTester().nameService().records().size());

        var record1 = tester.controllerTester().findCname("app1--tenant1.global.vespa.oath.cloud");
        assertTrue(record1.isPresent());
        assertEquals("app1--tenant1.global.vespa.oath.cloud", record1.get().name().asString());
        assertEquals("rotation-fqdn-04.", record1.get().data().asString());

        var record2 = tester.controllerTester().findCname("foobar--app1--tenant1.global.vespa.oath.cloud");
        assertTrue(record2.isPresent());
        assertEquals("foobar--app1--tenant1.global.vespa.oath.cloud", record2.get().name().asString());
        assertEquals("rotation-fqdn-01.", record2.get().data().asString());

        var record3 = tester.controllerTester().findCname("all--app1--tenant1.global.vespa.oath.cloud");
        assertTrue(record3.isPresent());
        assertEquals("all--app1--tenant1.global.vespa.oath.cloud", record3.get().name().asString());
        assertEquals("rotation-fqdn-02.", record3.get().data().asString());

        var record4 = tester.controllerTester().findCname("west--app1--tenant1.global.vespa.oath.cloud");
        assertTrue(record4.isPresent());
        assertEquals("west--app1--tenant1.global.vespa.oath.cloud", record4.get().name().asString());
        assertEquals("rotation-fqdn-03.", record4.get().data().asString());
    }

    @Test
    public void testDnsAliasRegistrationWithChangingZones() {
        Application application = tester.createApplication("app1", "tenant1", 1, 1L);

        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .endpoint("default", "qrs", "us-west-1", "us-central-1")
                .region("us-west-1")
                .region("us-central-1")
                .build();

        tester.deployCompletely(application, applicationPackage);

        assertEquals(
                Set.of("rotation-id-01", "app1--tenant1.global.vespa.oath.cloud"),
                tester.configServer().rotationNames().get(new DeploymentId(application.id(), ZoneId.from("prod", "us-west-1")))
        );

        assertEquals(
                Set.of("rotation-id-01", "app1--tenant1.global.vespa.oath.cloud"),
                tester.configServer().rotationNames().get(new DeploymentId(application.id(), ZoneId.from("prod", "us-central-1")))
        );


        ApplicationPackage applicationPackage2 = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .endpoint("default", "qrs", "us-west-1")
                .region("us-west-1")
                .region("us-central-1")
                .build();

        tester.deployCompletely(application, applicationPackage2, BuildJob.defaultBuildNumber + 1);

        assertEquals(
                Set.of("rotation-id-01", "app1--tenant1.global.vespa.oath.cloud"),
                tester.configServer().rotationNames().get(new DeploymentId(application.id(), ZoneId.from("prod", "us-west-1")))
        );

        assertEquals(
                Set.of(),
                tester.configServer().rotationNames().get(new DeploymentId(application.id(), ZoneId.from("prod", "us-central-1")))
        );

        assertEquals(Set.of(RegionName.from("us-west-1")), tester.instance(application.id()).rotations().get(0).regions());
    }

    @Test
    public void testUnassignRotations() {
        Application application = tester.createApplication("app1", "tenant1", 1, 1L);

        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .endpoint("default", "qrs", "us-west-1", "us-central-1")
                .region("us-west-1")
                .region("us-central-1") // Two deployments should result in each DNS alias being registered once
                .build();

        tester.deployCompletely(application, applicationPackage);

        ApplicationPackage applicationPackage2 = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("us-west-1")
                .region("us-central-1") // Two deployments should result in each DNS alias being registered once
                .build();

        tester.deployCompletely(application, applicationPackage2, BuildJob.defaultBuildNumber + 1);


        assertEquals(
                List.of(AssignedRotation.fromStrings("qrs", "default", "rotation-id-01", Set.of())),
                tester.instance(application.id()).rotations()
        );

        assertEquals(
                Set.of(),
                tester.configServer().rotationNames().get(new DeploymentId(application.id(), ZoneId.from("prod", "us-west-1")))
        );
    }

    @Test
    public void testUpdatesExistingDnsAlias() {
        // Application 1 is deployed and deleted
        {
            Application app1 = tester.createApplication("app1", "tenant1", 1, 1L);
            ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                    .environment(Environment.prod)
                    .endpoint("default", "foo")
                    .region("us-west-1")
                    .region("us-central-1") // Two deployments should result in each DNS alias being registered once
                    .build();

            tester.deployCompletely(app1, applicationPackage);
            assertEquals(1, tester.controllerTester().nameService().records().size());

            Optional<Record> record = tester.controllerTester().findCname("app1--tenant1.global.vespa.oath.cloud");
            assertTrue(record.isPresent());
            assertEquals("app1--tenant1.global.vespa.oath.cloud", record.get().name().asString());
            assertEquals("rotation-fqdn-01.", record.get().data().asString());

            // Application is deleted and rotation is unassigned
            applicationPackage = new ApplicationPackageBuilder()
                    .environment(Environment.prod)
                    .allow(ValidationId.deploymentRemoval)
                    .build();
            tester.jobCompletion(component).application(app1).nextBuildNumber().uploadArtifact(applicationPackage).submit();
            tester.deployAndNotify(tester.instance(app1.id()).id(), Optional.of(applicationPackage), true, systemTest);
            tester.applications().deactivate(app1.id(), ZoneId.from(Environment.test, RegionName.from("us-east-1")));
            tester.applications().deactivate(app1.id(), ZoneId.from(Environment.staging, RegionName.from("us-east-3")));
            tester.applications().deleteApplication(app1.id().tenant(), app1.id().application(), tester.controllerTester().credentialsFor(app1.id()));
            try (RotationLock lock = tester.applications().rotationRepository().lock()) {
                assertTrue("Rotation is unassigned",
                           tester.applications().rotationRepository().availableRotations(lock)
                                 .containsKey(new RotationId("rotation-id-01")));
            }
            tester.flushDnsRequests();

            // Records are removed
            record = tester.controllerTester().findCname("app1--tenant1.global.vespa.yahooapis.com");
            assertTrue(record.isEmpty());

            record = tester.controllerTester().findCname("app1--tenant1.global.vespa.oath.cloud");
            assertTrue(record.isEmpty());

            record = tester.controllerTester().findCname("app1.tenant1.global.vespa.yahooapis.com");
            assertTrue(record.isEmpty());
        }

        // Application 2 is deployed and assigned same rotation as application 1 had before deletion
        {
            Application app2 = tester.createApplication("app2", "tenant2", 2, 1L);
            ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                    .environment(Environment.prod)
                    .endpoint("default", "foo")
                    .region("us-west-1")
                    .region("us-central-1")
                    .build();
            tester.deployCompletely(app2, applicationPackage);
            assertEquals(1, tester.controllerTester().nameService().records().size());

            var record = tester.controllerTester().findCname("app2--tenant2.global.vespa.oath.cloud");
            assertTrue(record.isPresent());
            assertEquals("app2--tenant2.global.vespa.oath.cloud", record.get().name().asString());
            assertEquals("rotation-fqdn-01.", record.get().data().asString());
        }

        // Application 1 is recreated, deployed and assigned a new rotation
        {
            tester.buildService().clear();
            Application app1 = tester.createApplication("app1", "tenant1", 1, 1L);
            ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                    .environment(Environment.prod)
                    .endpoint("default", "foo")
                    .region("us-west-1")
                    .region("us-central-1")
                    .build();
            tester.deployCompletely(app1, applicationPackage);
            assertEquals("rotation-id-02", tester.instance(app1.id()).rotations().get(0).rotationId().asString());

            // DNS records are created for the newly assigned rotation
            assertEquals(2, tester.controllerTester().nameService().records().size());

            var record1 = tester.controllerTester().findCname("app1--tenant1.global.vespa.oath.cloud");
            assertTrue(record1.isPresent());
            assertEquals("rotation-fqdn-02.", record1.get().data().asString());

            var record2 = tester.controllerTester().findCname("app2--tenant2.global.vespa.oath.cloud");
            assertTrue(record2.isPresent());
            assertEquals("rotation-fqdn-01.", record2.get().data().asString());
        }

    }

    @Test
    public void testIntegrationTestDeployment() {
        Version six = Version.fromString("6.1");
        tester.upgradeSystem(six);
        tester.controllerTester().zoneRegistry().setSystemName(SystemName.cd);
        tester.controllerTester().zoneRegistry().setZones(ZoneApiMock.fromId("prod.cd-us-central-1"));
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .majorVersion(6)
                .region("cd-us-central-1")
                .build();

        // Create application
        Application app = tester.createApplication("app1", "tenant1", 1, 2L);

        // Direct deploy is allowed when deployDirectly is true
        ZoneId zone = ZoneId.from("prod", "cd-us-central-1");
        // Same options as used in our integration tests
        DeployOptions options = new DeployOptions(true, Optional.empty(), false,
                                                  false);
        tester.controller().applications().deploy(app.id(), zone, Optional.of(applicationPackage), options);

        assertTrue("Application deployed and activated",
                   tester.controllerTester().configServer().application(app.id(), zone).get().activated());

        assertTrue("No job status added",
                   tester.applications().require(app.id()).deploymentJobs().jobStatus().isEmpty());

        Version seven = Version.fromString("7.2");
        tester.upgradeSystem(seven);
        tester.controller().applications().deploy(app.id(), zone, Optional.of(applicationPackage), options);
        assertEquals(six, tester.instance(app.id()).deployments().get(zone).version());
    }

    @Test
    public void testDevDeployment() {
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.dev)
                .majorVersion(6)
                .region("us-east-1")
                .build();

        // Create application
        Application app = tester.createApplication("app1", "tenant1", 1, 2L);
        ZoneId zone = ZoneId.from("dev", "us-east-1");

        // Deploy
        tester.controller().applications().deploy(app.id(), zone, Optional.of(applicationPackage), DeployOptions.none());
        assertTrue("Application deployed and activated",
                   tester.controllerTester().configServer().application(app.id(), zone).get().activated());
        assertTrue("No job status added",
                   tester.applications().require(app.id()).deploymentJobs().jobStatus().isEmpty());
        assertEquals("DeploymentSpec is not persisted", DeploymentSpec.empty, tester.applications().require(app.id()).deploymentSpec());
    }

    @Test
    public void testSuspension() {
        Application app = tester.createApplication("app1", "tenant1", 1, 11L);
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                                                        .environment(Environment.prod)
                                                        .region("us-west-1")
                                                        .region("us-east-3")
                                                        .build();
        SourceRevision source = new SourceRevision("repo", "master", "commit1");

        ApplicationVersion applicationVersion = ApplicationVersion.from(source, 101);
        runDeployment(tester, app.id(), applicationVersion, applicationPackage, source,101);

        DeploymentId deployment1 = new DeploymentId(app.id(), ZoneId.from(Environment.prod, RegionName.from("us-west-1")));
        DeploymentId deployment2 = new DeploymentId(app.id(), ZoneId.from(Environment.prod, RegionName.from("us-east-3")));
        assertFalse(tester.configServer().isSuspended(deployment1));
        assertFalse(tester.configServer().isSuspended(deployment2));
        tester.configServer().setSuspended(deployment1, true);
        assertTrue(tester.configServer().isSuspended(deployment1));
        assertFalse(tester.configServer().isSuspended(deployment2));
    }

    // Application may already have been deleted, or deployment failed without response, test that deleting a
    // second time will not fail
    @Test
    public void testDeletingApplicationThatHasAlreadyBeenDeleted() {
        Application app = tester.createApplication("app2", "tenant1", 1, 12L);
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("us-east-3")
                .region("us-west-1")
                .build();

        ZoneId zone = ZoneId.from("prod", "us-west-1");
        tester.controller().applications().deploy(app.id(), zone, Optional.of(applicationPackage), DeployOptions.none());
        tester.controller().applications().deactivate(app.id(), ZoneId.from(Environment.prod, RegionName.from("us-west-1")));
        tester.controller().applications().deactivate(app.id(), ZoneId.from(Environment.prod, RegionName.from("us-west-1")));
    }

    @Test
    public void testDeployApplicationPackageWithApplicationDir() {
        Application instance = tester.createApplication("app1", "tenant1", 1, 1L);
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("us-west-1")
                .build(true);
        tester.deployCompletely(instance, applicationPackage);
    }

    @Test
    public void testDeployApplicationWithWarnings() {
        Application instance = tester.createApplication("app1", "tenant1", 1, 1L);
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("us-west-1")
                .build();
        ZoneId zone = ZoneId.from("prod", "us-west-1");
        int warnings = 3;
        tester.configServer().generateWarnings(new DeploymentId(instance.id(), zone), warnings);
        tester.deployCompletely(instance, applicationPackage);
        assertEquals(warnings, tester.applications().require(instance.id()).deployments().get(zone)
                                     .metrics().warnings().get(DeploymentMetrics.Warning.all).intValue());
    }

    @Test
    public void testDeploySelectivelyProvisionsCertificate() {
        ((InMemoryFlagSource) tester.controller().flagSource()).withBooleanFlag(Flags.PROVISION_APPLICATION_CERTIFICATE.id(), true);
        Function<Instance, Optional<ApplicationCertificate>> certificate = (application) -> tester.controller().curator().readApplicationCertificate(application.id());

        // Create app1
        var app1 = tester.createApplication("app1", "tenant1", 1, 2L);
        var applicationPackage = new ApplicationPackageBuilder().environment(Environment.prod)
                                                                               .region("us-west-1")
                                                                               .build();
        // Deploy app1 in production
        tester.deployCompletely(app1, applicationPackage);
        Instance instance1 = tester.instance(app1.id());
        var cert = certificate.apply(instance1);
        assertTrue("Provisions certificate in " + Environment.prod, cert.isPresent());
        assertEquals(List.of(
                "vznqtz7a5ygwjkbhhj7ymxvlrekgt4l6g.vespa.oath.cloud",
                "app1.tenant1.global.vespa.oath.cloud",
                "*.app1.tenant1.global.vespa.oath.cloud",
                "app1.tenant1.us-east-3.vespa.oath.cloud",
                "*.app1.tenant1.us-east-3.vespa.oath.cloud",
                "app1.tenant1.us-west-1.vespa.oath.cloud",
                "*.app1.tenant1.us-west-1.vespa.oath.cloud",
                "app1.tenant1.us-central-1.vespa.oath.cloud",
                "*.app1.tenant1.us-central-1.vespa.oath.cloud",
                "app1.tenant1.eu-west-1.vespa.oath.cloud",
                "*.app1.tenant1.eu-west-1.vespa.oath.cloud"
        ), tester.controllerTester().serviceRegistry().applicationCertificateMock().dnsNamesOf(app1.id()));

        // Next deployment reuses certificate
        tester.deployCompletely(app1, applicationPackage, BuildJob.defaultBuildNumber + 1);
        assertEquals(cert, certificate.apply(instance1));

        // Create app2
        var app2 = tester.createApplication("app2", "tenant2", 3, 4L);
        Instance instance2 = tester.instance(app2.id());
        ZoneId zone = ZoneId.from("dev", "us-east-1");

        // Deploy app2 in dev
        tester.controller().applications().deploy(app2.id(), zone, Optional.of(applicationPackage), DeployOptions.none());
        assertTrue("Application deployed and activated",
                   tester.controllerTester().configServer().application(app2.id(), zone).get().activated());
        assertFalse("Does not provision certificate in " + Environment.dev, certificate.apply(instance2).isPresent());
    }

    @Test
    public void testDeployWithCrossCloudEndpoints() {
        tester.controllerTester().zoneRegistry().setZones(
                ZoneApiMock.fromId("prod.us-west-1"),
                ZoneApiMock.newBuilder().with(CloudName.from("aws")).withId("prod.aws-us-east-1").build()
        );
        var application = tester.createApplication("app1", "tenant1", 1L, 1L);
        var applicationPackage = new ApplicationPackageBuilder()
                .region("aws-us-east-1")
                .region("us-west-1")
                .endpoint("default", "default") // Contains to all regions by default
                .build();

        try {
            tester.deployCompletely(application, applicationPackage);
            fail("Expected exception");
        } catch (IllegalArgumentException e) {
            assertEquals("Endpoint 'default' cannot contain regions in different clouds: [aws-us-east-1, us-west-1]", e.getMessage());
        }

        var applicationPackage2 = new ApplicationPackageBuilder()
                .region("aws-us-east-1")
                .region("us-west-1")
                .endpoint("aws", "default", "aws-us-east-1")
                .endpoint("foo", "default", "aws-us-east-1", "us-west-1")
                .build();
        try {
            tester.deployCompletely(application, applicationPackage2);
            fail("Expected exception");
        } catch (IllegalArgumentException e) {
            assertEquals("Endpoint 'foo' cannot contain regions in different clouds: [aws-us-east-1, us-west-1]", e.getMessage());
        }
    }


    @Test
    public void testInstanceDataMigration() {
        MockCuratorDb newDb = new MockCuratorDb();
        OldMockCuratorDb oldDb = new OldMockCuratorDb(newDb.curator());

        Instance instance1 = new Instance(ApplicationId.from("tenant1", "application1", "instance1"), Instant.ofEpochMilli(1));
        Instance instance2 = new Instance(ApplicationId.from("tenant2", "application2", "instance2"), Instant.ofEpochMilli(2));

        oldDb.writeInstance(instance1);
        newDb.writeInstance(instance2);

        assertEquals(instance1, oldDb.readInstance(instance1.id()).orElseThrow());
        assertEquals(instance1, newDb.readInstance(instance1.id()).orElseThrow());

        assertEquals(instance2, oldDb.readInstance(instance2.id()).orElseThrow());
        assertEquals(instance2, newDb.readInstance(instance2.id()).orElseThrow());

        assertEquals(List.of(instance1, instance2), oldDb.readInstances());
        assertEquals(List.of(instance1, instance2), newDb.readInstances());

        instance1 = new Instance(instance1.id(), Instant.ofEpochMilli(3));
        oldDb.writeInstance(instance1);
        assertEquals(instance1, oldDb.readInstance(instance1.id()).orElseThrow());
        assertEquals(instance1, newDb.readInstance(instance1.id()).orElseThrow());

        instance2 = new Instance(instance2.id(), Instant.ofEpochMilli(4));
        newDb.writeInstance(instance2);
        assertEquals(instance2, oldDb.readInstance(instance2.id()).orElseThrow());
        assertEquals(instance2, newDb.readInstance(instance2.id()).orElseThrow());

        Application application = newDb.readApplication(instance1.id()).orElseThrow();
        assertEquals(new ApplicationSerializer().toSlime(application).toString(),
                     new InstanceSerializer().toSlime(instance1).toString());

        oldDb.removeInstance(instance1.id());
        newDb.removeInstance(instance2.id());
        assertEquals(List.of(), oldDb.readInstances());
        assertEquals(List.of(), newDb.readInstances());
    }

    private void runUpgrade(DeploymentTester tester, ApplicationId application, ApplicationVersion version) {
        Version next = Version.fromString("6.2");
        tester.upgradeSystem(next);
        runDeployment(tester, tester.applications().require(application), version, Optional.of(next), Optional.empty());
    }

    private void runDeployment(DeploymentTester tester, ApplicationId id, ApplicationVersion version,
                               ApplicationPackage applicationPackage, SourceRevision sourceRevision, long buildNumber) {
        Instance instance = tester.applications().require(id);
        tester.jobCompletion(component)
              .application(tester.application(id))
              .buildNumber(buildNumber)
              .sourceRevision(sourceRevision)
              .uploadArtifact(applicationPackage)
              .submit();

        ApplicationVersion change = ApplicationVersion.from(sourceRevision, buildNumber);
        assertEquals(change.id(), tester.controller().applications()
                                        .require(id)
                                        .change().application().get().id());
        runDeployment(tester, instance, version, Optional.empty(), Optional.of(applicationPackage));
    }

    private void assertStatus(JobStatus expectedStatus, ApplicationId id, Controller controller) {
        Instance app = controller.applications().get(id).get();
        JobStatus existingStatus = app.deploymentJobs().jobStatus().get(expectedStatus.type());
        assertNotNull("Status of type " + expectedStatus.type() + " is present", existingStatus);
        assertEquals(expectedStatus, existingStatus);
    }

    private void runDeployment(DeploymentTester tester, Instance app, ApplicationVersion version,
                               Optional<Version> upgrade, Optional<ApplicationPackage> applicationPackage) {
        Version vespaVersion = upgrade.orElseGet(tester::defaultPlatformVersion);

        // Deploy in test
        tester.deployAndNotify(app.id(), applicationPackage, true, systemTest);
        tester.deployAndNotify(app.id(), applicationPackage, true, stagingTest);
        JobStatus expected = JobStatus.initial(stagingTest)
                                      .withTriggering(vespaVersion, version, Optional.ofNullable(tester.instance(app.id()).deployments().get(productionUsWest1.zone(main))), "",
                                                      tester.clock().instant().truncatedTo(MILLIS))
                                      .withCompletion(42, Optional.empty(), tester.clock().instant().truncatedTo(MILLIS));
        assertStatus(expected, app.id(), tester.controller());

        // Deploy in production
        expected = JobStatus.initial(productionUsWest1)
                            .withTriggering(vespaVersion, version, Optional.ofNullable(tester.instance(app.id()).deployments().get(productionUsWest1.zone(main))), "",
                                            tester.clock().instant().truncatedTo(MILLIS))
                            .withCompletion(42, Optional.empty(), tester.clock().instant().truncatedTo(MILLIS));
        tester.deployAndNotify(app.id(), applicationPackage, true, productionUsWest1);
        assertStatus(expected, app.id(), tester.controller());

        expected = JobStatus.initial(productionUsEast3)
                            .withTriggering(vespaVersion, version, Optional.ofNullable(tester.instance(app.id()).deployments().get(productionUsEast3.zone(main))), "",
                                            tester.clock().instant().truncatedTo(MILLIS))
                            .withCompletion(42, Optional.empty(), tester.clock().instant().truncatedTo(MILLIS));
        tester.deployAndNotify(app.id(), applicationPackage, true, productionUsEast3);
        assertStatus(expected, app.id(), tester.controller());

        // Verify deployed version
        app = tester.controller().applications().require(app.id());
        for (Deployment deployment : app.productionDeployments().values()) {
            assertEquals(version, deployment.applicationVersion());
            upgrade.ifPresent(v -> assertEquals(v, deployment.version()));
        }
    }

}
