// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi.screwdriver;

import com.yahoo.application.container.handler.Request;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.application.DeploymentJobs.JobType;
import com.yahoo.vespa.hosted.controller.deployment.DeploymentQueue;
import com.yahoo.vespa.hosted.controller.restapi.ContainerControllerTester;
import com.yahoo.vespa.hosted.controller.restapi.ControllerContainerTest;
import com.yahoo.vespa.hosted.controller.versions.VersionStatus;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * @author bratseth
 * @author jvenstad
 */
public class ScrewdriverApiTest extends ControllerContainerTest {

    private static final String responseFiles = "src/test/java/com/yahoo/vespa/hosted/controller/restapi/screwdriver/responses/";

    @Test
    public void testGetReleaseStatus() throws Exception {
        ContainerControllerTester tester = new ContainerControllerTester(container, responseFiles);
        tester.containerTester().assertResponse(authenticatedRequest("http://localhost:8080/screwdriver/v1/release/vespa"),
                                                "{\"error-code\":\"NOT_FOUND\",\"message\":\"Information about the current system version is not available at this time\"}",
                                                404);

        tester.controller().updateVersionStatus(VersionStatus.compute(tester.controller()));
        tester.containerTester().assertResponse(authenticatedRequest("http://localhost:8080/screwdriver/v1/release/vespa"),
                                                new File("release-response.json"), 200);
    }

    @Test
    public void testTriggerJobForApplication() {
        ContainerControllerTester tester = new ContainerControllerTester(container, responseFiles);
        DeploymentQueue deploymentQueue = tester.controller().applications().deploymentTrigger().deploymentQueue();
        tester.containerTester().updateSystemVersion();

        Application app = tester.createApplication();
        tester.controller().applications().lockOrThrow(app.id(), application ->
                tester.controller().applications().store(application.withProjectId(1)));

        // Unknown application
        assertResponse(new Request("http://localhost:8080/screwdriver/v1/trigger/tenant/foo/application/bar",
                                   new byte[0], Request.Method.POST),
                       400, "{\"error-code\":\"BAD_REQUEST\",\"message\":\"foo.bar not found\"}");

        // Invalid job
        assertResponse(new Request("http://localhost:8080/screwdriver/v1/trigger/tenant/" +
                                   app.id().tenant().value() + "/application/" + app.id().application().value(),
                                   "invalid".getBytes(StandardCharsets.UTF_8), Request.Method.POST),
                       400, "{\"error-code\":\"BAD_REQUEST\",\"message\":\"Unknown job name 'invalid'\"}");

        // component is triggered if no job is specified in request body
        assertResponse(new Request("http://localhost:8080/screwdriver/v1/trigger/tenant/" +
                                   app.id().tenant().value() + "/application/" + app.id().application().value(),
                                   new byte[0], Request.Method.POST),
                       200, "{\"message\":\"Triggered component for tenant1.application1\"}");

        assertFalse(deploymentQueue.jobs().isEmpty());
        assertEquals(JobType.component.jobName(), deploymentQueue.jobs().get(0).jobName());
        assertEquals(1L, deploymentQueue.jobs().get(0).projectId());
        deploymentQueue.takeJobsToRun();

        // Triggers specific job when given
        assertResponse(new Request("http://localhost:8080/screwdriver/v1/trigger/tenant/" +
                                   app.id().tenant().value() + "/application/" + app.id().application().value(),
                                   "staging-test".getBytes(StandardCharsets.UTF_8), Request.Method.POST),
                       200, "{\"message\":\"Triggered staging-test for tenant1.application1\"}");
        assertFalse(deploymentQueue.jobs().isEmpty());
        assertEquals(JobType.stagingTest.jobName(), deploymentQueue.jobs().get(0).jobName());
        assertEquals(1L, deploymentQueue.jobs().get(0).projectId());
    }

}
