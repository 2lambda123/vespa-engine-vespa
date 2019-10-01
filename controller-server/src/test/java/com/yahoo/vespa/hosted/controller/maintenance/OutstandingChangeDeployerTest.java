// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.component.Version;
import com.yahoo.config.provision.Environment;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.Instance;
import com.yahoo.vespa.hosted.controller.api.integration.BuildService;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.application.ApplicationPackage;
import com.yahoo.vespa.hosted.controller.application.Change;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.SourceRevision;
import com.yahoo.vespa.hosted.controller.deployment.ApplicationPackageBuilder;
import com.yahoo.vespa.hosted.controller.deployment.DeploymentTester;
import com.yahoo.vespa.hosted.controller.persistence.MockCuratorDb;
import org.junit.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author bratseth
 */
public class OutstandingChangeDeployerTest {

    @Test
    public void testChangeDeployer() {
        DeploymentTester tester = new DeploymentTester();
        OutstandingChangeDeployer deployer = new OutstandingChangeDeployer(tester.controller(), Duration.ofMinutes(10),
                                                                           new JobControl(new MockCuratorDb()));
        ApplicationPackage applicationPackage = new ApplicationPackageBuilder()
                .environment(Environment.prod)
                .region("us-west-1")
                .build();

        Application app1 = tester.createAndDeploy("app1", 11, applicationPackage);
        Application app2 = tester.createAndDeploy("app2", 22, applicationPackage);

        Version version = new Version(6, 2);
        tester.deploymentTrigger().triggerChange(app1.id(), Change.of(version));
        tester.deploymentTrigger().triggerReadyJobs();

        assertEquals(Change.of(version), tester.application(app1.id()).change());
        assertFalse(tester.application(app1.id()).outstandingChange().hasTargets());

        tester.jobCompletion(JobType.component)
              .application(app1)
              .sourceRevision(new SourceRevision("repository1","master", "cafed00d"))
              .nextBuildNumber()
              .uploadArtifact(applicationPackage)
              .submit();

        Instance instance = tester.defaultInstance("app1");
        assertTrue(tester.application(app1.id()).outstandingChange().hasTargets());
        assertEquals("1.0.43-cafed00d", tester.application(app1.id()).outstandingChange().application().get().id());
        assertEquals(2, tester.buildService().jobs().size());

        deployer.maintain();
        tester.deploymentTrigger().triggerReadyJobs();
        assertEquals("No effect as job is in progress", 2, tester.buildService().jobs().size());
        assertEquals("1.0.43-cafed00d", tester.application(app1.id()).outstandingChange().application().get().id());

        tester.deployAndNotify(instance.id(), Optional.of(applicationPackage), true, JobType.systemTest);
        tester.deployAndNotify(instance.id(), Optional.of(applicationPackage), true, JobType.stagingTest);
        tester.deployAndNotify(instance.id(), Optional.of(applicationPackage), true, JobType.productionUsWest1);
        tester.deployAndNotify(instance.id(), Optional.of(applicationPackage), true, JobType.systemTest);
        tester.deployAndNotify(instance.id(), Optional.of(applicationPackage), true, JobType.stagingTest);
        assertEquals("Upgrade done", 0, tester.buildService().jobs().size());

        deployer.maintain();
        tester.deploymentTrigger().triggerReadyJobs();
        instance = tester.defaultInstance("app1");
        assertEquals("1.0.43-cafed00d", tester.application(app1.id()).change().application().get().id());
        List<BuildService.BuildJob> jobs = tester.buildService().jobs();
        assertEquals(1, jobs.size());
        assertEquals(JobType.productionUsWest1.jobName(), jobs.get(0).jobName());
        assertEquals(app1.id().defaultInstance(), jobs.get(0).applicationId());
        assertFalse(tester.application(app1.id()).outstandingChange().hasTargets());
    }

}
