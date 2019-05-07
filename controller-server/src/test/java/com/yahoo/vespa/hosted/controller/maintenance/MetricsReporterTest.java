// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yahoo.component.Version;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.SystemName;
import com.yahoo.test.ManualClock;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.ControllerTester;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;
import com.yahoo.vespa.hosted.controller.api.integration.chef.ChefMock;
import com.yahoo.vespa.hosted.controller.api.integration.chef.rest.PartialNodeResult;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.ApplicationVersion;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.hosted.controller.application.ApplicationPackage;
import com.yahoo.vespa.hosted.controller.deployment.ApplicationPackageBuilder;
import com.yahoo.vespa.hosted.controller.deployment.DeploymentTester;
import com.yahoo.vespa.hosted.controller.deployment.InternalDeploymentTester;
import com.yahoo.vespa.hosted.controller.integration.MetricsMock;
import com.yahoo.vespa.hosted.controller.integration.MetricsMock.MapContext;
import com.yahoo.vespa.hosted.controller.persistence.MockCuratorDb;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.component;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.productionUsWest1;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.stagingTest;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType.systemTest;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * @author mortent
 */
public class MetricsReporterTest {

    private static final Path testData = Paths.get("src/test/resources/");

    private MetricsMock metrics;

    @Before
    public void before() {
        metrics = new MetricsMock();
    }

    @Test
    public void test_chef_metrics() {
        Clock clock = new ManualClock(Instant.ofEpochSecond(1475497913));
        ControllerTester tester = new ControllerTester();
        MetricsReporter metricsReporter = createReporter(clock, tester.controller(), metrics, SystemName.cd);
        metricsReporter.maintain();
        assertEquals(2, metrics.getMetrics().size());

        Map<MapContext, Map<String, Number>> hostMetrics = getMetricsByHost("fake-node.test");
        assertEquals(1, hostMetrics.size());
        Map.Entry<MapContext, Map<String, Number>> metricEntry = hostMetrics.entrySet().iterator().next();
        MapContext metricContext = metricEntry.getKey();
        assertDimension(metricContext, "tenantName", "ciintegrationtests");
        assertDimension(metricContext, "app", "restart.default");
        assertDimension(metricContext, "zone", "prod.cd-us-east-1");
        assertEquals(727, metricEntry.getValue().get(MetricsReporter.CONVERGENCE_METRIC).longValue());
    }

    @Test
    public void test_deployment_fail_ratio() {
        DeploymentTester tester = new DeploymentTester();
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("us-west-1")
                .build();
        MetricsReporter metricsReporter = createReporter(tester.controller(), metrics, SystemName.main);

        metricsReporter.maintain();
        assertEquals(0.0, metrics.getMetric(MetricsReporter.DEPLOYMENT_FAIL_METRIC));

        // Deploy all apps successfully
        Application app1 = tester.createApplication("app1", "tenant1", 1, 11L);
        Application app2 = tester.createApplication("app2", "tenant1", 2, 22L);
        Application app3 = tester.createApplication("app3", "tenant1", 3, 33L);
        Application app4 = tester.createApplication("app4", "tenant1", 4, 44L);
        tester.deployCompletely(app1, applicationPackage);
        tester.deployCompletely(app2, applicationPackage);
        tester.deployCompletely(app3, applicationPackage);
        tester.deployCompletely(app4, applicationPackage);

        metricsReporter.maintain();
        assertEquals(0.0, metrics.getMetric(MetricsReporter.DEPLOYMENT_FAIL_METRIC));

        // 1 app fails system-test
        tester.jobCompletion(component).application(app4).nextBuildNumber().uploadArtifact(applicationPackage).submit();
        tester.deployAndNotify(app4, applicationPackage, false, systemTest);

        metricsReporter.maintain();
        assertEquals(25.0, metrics.getMetric(MetricsReporter.DEPLOYMENT_FAIL_METRIC));
    }

    @Test
    public void test_chef_metrics_omit_zone_when_unknown() {
        ControllerTester tester = new ControllerTester();
        String hostname = "fake-node2.test";
        MapContext metricContext = getMetricContextByHost(tester.controller(), hostname);
        assertNull(metricContext.getDimensions().get("zone"));
    }

    @Test
    public void test_deployment_average_duration() {
        DeploymentTester tester = new DeploymentTester();
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("us-west-1")
                .build();

        MetricsReporter reporter = createReporter(tester.controller(), metrics, SystemName.main);

        Application app = tester.createApplication("app1", "tenant1", 1, 11L);
        tester.deployCompletely(app, applicationPackage);
        reporter.maintain();
        assertEquals(Duration.ZERO, getAverageDeploymentDuration(app)); // An exceptionally fast deployment :-)

        // App spends 3 hours deploying
        tester.jobCompletion(component).application(app).nextBuildNumber().uploadArtifact(applicationPackage).submit();
        tester.clock().advance(Duration.ofHours(1));
        tester.deployAndNotify(app, applicationPackage, true, systemTest);

        tester.clock().advance(Duration.ofMinutes(30));
        tester.deployAndNotify(app, applicationPackage, true, stagingTest);

        tester.clock().advance(Duration.ofMinutes(90));
        tester.deployAndNotify(app, applicationPackage, true, productionUsWest1);
        reporter.maintain();

        // Average time is 1 hour (system-test) + 90 minutes (staging-test runs in parallel with system-test) + 90 minutes (production) / 3 jobs
        assertEquals(Duration.ofMinutes(80), getAverageDeploymentDuration(app));

        // Another deployment starts and stalls for 12 hours
        tester.jobCompletion(component).application(app).nextBuildNumber(2).uploadArtifact(applicationPackage).submit();
        tester.clock().advance(Duration.ofHours(12));
        reporter.maintain();

        assertEquals(Duration.ofHours(12) // hanging system-test
                             .plus(Duration.ofHours(12)) // hanging staging-test
                             .plus(Duration.ofMinutes(90)) // previous production job
                             .dividedBy(3), // Total number of orchestrated jobs
                     getAverageDeploymentDuration(app));
    }

    @Test
    public void test_deployments_failing_upgrade() {
        DeploymentTester tester = new DeploymentTester();
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("us-west-1")
                .build();

        MetricsReporter reporter = createReporter(tester.controller(), metrics, SystemName.main);
        Application app = tester.createApplication("app1", "tenant1", 1, 11L);

        // Initial deployment without failures
        tester.deployCompletely(app, applicationPackage);
        reporter.maintain();
        assertEquals(0, getDeploymentsFailingUpgrade(app));

        // Failing application change is not counted
        tester.jobCompletion(component).application(app).nextBuildNumber().uploadArtifact(applicationPackage).submit();
        tester.deployAndNotify(app, applicationPackage, false, systemTest);
        reporter.maintain();
        assertEquals(0, getDeploymentsFailingUpgrade(app));

        // Application change completes
        tester.deployAndNotify(app, applicationPackage, true, systemTest);
        tester.deployAndNotify(app, applicationPackage, true, stagingTest);
        tester.deployAndNotify(app, applicationPackage, true, productionUsWest1);
        assertFalse("Change deployed", tester.controller().applications().require(app.id()).change().hasTargets());

        // New versions is released and upgrade fails in test environments
        Version version = Version.fromString("7.1");
        tester.upgradeSystem(version);
        tester.upgrader().maintain();
        tester.deployAndNotify(app, applicationPackage, false, systemTest);
        tester.deployAndNotify(app, applicationPackage, false, stagingTest);
        reporter.maintain();
        assertEquals(2, getDeploymentsFailingUpgrade(app));

        // Test and staging pass and upgrade fails in production
        tester.deployAndNotify(app, applicationPackage, true, systemTest);
        tester.deployAndNotify(app, applicationPackage, true, stagingTest);
        tester.deployAndNotify(app, applicationPackage, false, productionUsWest1);
        reporter.maintain();
        assertEquals(1, getDeploymentsFailingUpgrade(app));

        // Upgrade eventually succeeds
        tester.deployAndNotify(app, applicationPackage, true, productionUsWest1);
        assertFalse("Upgrade deployed", tester.controller().applications().require(app.id()).change().hasTargets());
        reporter.maintain();
        assertEquals(0, getDeploymentsFailingUpgrade(app));
    }

    @Test
    public void test_deployment_warnings_metric() {
        DeploymentTester tester = new DeploymentTester();
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("us-west-1")
                .region("us-east-3")
                .build();
        MetricsReporter reporter = createReporter(tester.controller(), metrics, SystemName.main);
        Application application = tester.createApplication("app1", "tenant1", 1, 11L);
        tester.configServer().generateWarnings(new DeploymentId(application.id(), ZoneId.from("prod", "us-west-1")), 3);
        tester.configServer().generateWarnings(new DeploymentId(application.id(), ZoneId.from("prod", "us-east-3")), 4);
        tester.deployCompletely(application, applicationPackage);
        reporter.maintain();
        assertEquals(4, getDeploymentWarnings(application));
    }

    @Test
    public void test_build_time_reporting() {
        InternalDeploymentTester tester = new InternalDeploymentTester();
        ApplicationVersion version = tester.deployNewSubmission();
        assertEquals(1000, version.buildTime().get().toEpochMilli());

        MetricsReporter reporter = createReporter(tester.tester().controller(), metrics, SystemName.main);
        reporter.maintain();
        assertEquals(tester.clock().instant().getEpochSecond() - 1,
                     getMetric(MetricsReporter.DEPLOYMENT_BUILD_AGE_SECONDS, tester.app()));
    }

    @Test
    public void test_name_service_queue_size_metric() {
        DeploymentTester tester = new DeploymentTester(new ControllerTester(), false);
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .globalServiceId("default")
                .region("us-west-1")
                .region("us-east-3")
                .build();
        MetricsReporter reporter = createReporter(tester.controller(), metrics, SystemName.main);
        Application application = tester.createApplication("app1", "tenant1", 1, 11L);
        reporter.maintain();
        assertEquals("Queue is empty initially", 0, metrics.getMetric(MetricsReporter.NAME_SERVICE_REQUESTS_QUEUED).intValue());

        tester.deployCompletely(application, applicationPackage);
        reporter.maintain();
        assertEquals("Deployment queues name services requests", 6, metrics.getMetric(MetricsReporter.NAME_SERVICE_REQUESTS_QUEUED).intValue());

        tester.updateDns();
        reporter.maintain();
        assertEquals("Queue consumed", 0, metrics.getMetric(MetricsReporter.NAME_SERVICE_REQUESTS_QUEUED).intValue());
    }

    private Duration getAverageDeploymentDuration(Application application) {
        return Duration.ofSeconds(getMetric(MetricsReporter.DEPLOYMENT_AVERAGE_DURATION, application).longValue());
    }

    private int getDeploymentsFailingUpgrade(Application application) {
        return getMetric(MetricsReporter.DEPLOYMENT_FAILING_UPGRADES, application).intValue();
    }

    private int getDeploymentWarnings(Application application) {
        return getMetric(MetricsReporter.DEPLOYMENT_WARNINGS, application).intValue();
    }

    private Number getMetric(String name, Application application) {
        return metrics.getMetric((dimensions) -> application.id().tenant().value().equals(dimensions.get("tenant")) &&
                                                 appDimension(application).equals(dimensions.get("app")),
                                 name)
                      .orElseThrow(() -> new RuntimeException("Expected metric to exist for " + application.id()));
    }

    private MetricsReporter createReporter(Controller controller, MetricsMock metricsMock, SystemName system) {
        return createReporter(controller.clock(), controller, metricsMock, system);
    }

    private MetricsReporter createReporter(Clock clock, Controller controller, MetricsMock metricsMock,
                                           SystemName system) {
        ChefMock chef = new ChefMock();
        PartialNodeResult result;
        try {
            result = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(testData.resolve("chef_output.json").toFile(), PartialNodeResult.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        chef.addPartialResult(result.rows);
        return new MetricsReporter(controller, metricsMock, chef, clock, new JobControl(new MockCuratorDb()), system);
    }

    private Map<MapContext, Map<String, Number>> getMetricsByHost(String hostname) {
        return metrics.getMetrics((dimensions) -> hostname.equals(dimensions.get("host")));
    }
    
    private MapContext getMetricContextByHost(Controller controller, String hostname) {
        MetricsReporter metricsReporter = createReporter(controller, metrics, SystemName.main);
        metricsReporter.maintain();

        assertFalse(metrics.getMetrics().isEmpty());

        Map<MapContext, Map<String, Number>> metrics = getMetricsByHost(hostname);
        assertEquals(1, metrics.size());
        Map.Entry<MapContext, Map<String, Number>> metricEntry = metrics.entrySet().iterator().next();
        return metricEntry.getKey();
    }

    private static void assertDimension(MapContext metricContext, String dimensionName, String expectedValue) {
        assertEquals(expectedValue, metricContext.getDimensions().get(dimensionName));
    }

    private static String appDimension(Application application) {
        return application.id().application().value() + "." + application.id().instance().value();
    }

}

