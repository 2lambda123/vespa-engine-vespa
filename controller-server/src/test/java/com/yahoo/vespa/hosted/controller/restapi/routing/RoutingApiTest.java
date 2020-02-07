// Copyright 2020 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi.routing;

import com.yahoo.application.container.handler.Request;
import com.yahoo.config.provision.zone.RoutingMethod;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.hosted.controller.ControllerTester;
import com.yahoo.vespa.hosted.controller.deployment.ApplicationPackageBuilder;
import com.yahoo.vespa.hosted.controller.deployment.DeploymentTester;
import com.yahoo.vespa.hosted.controller.integration.ZoneApiMock;
import com.yahoo.vespa.hosted.controller.restapi.ContainerTester;
import com.yahoo.vespa.hosted.controller.restapi.ControllerContainerTest;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertNotEquals;

/**
 * @author mpolden
 */
public class RoutingApiTest extends ControllerContainerTest {

    private static final String responseFiles = "src/test/java/com/yahoo/vespa/hosted/controller/restapi/routing/responses/";

    private ContainerTester tester;
    private DeploymentTester deploymentTester;

    @Before
    public void before() {
        tester = new ContainerTester(container, responseFiles);
        deploymentTester = new DeploymentTester(new ControllerTester(tester));
    }

    @Test
    public void exclusive_routing() {
        var context = deploymentTester.newDeploymentContext();
        // Zones support direct routing
        var westZone = ZoneId.from("prod", "us-west-1");
        var eastZone = ZoneId.from("prod", "us-east-3");
        deploymentTester.controllerTester().zoneRegistry().exclusiveRoutingIn(ZoneApiMock.from(westZone),
                                                                              ZoneApiMock.from(eastZone));
        // Deploy application
        var applicationPackage = new ApplicationPackageBuilder()
                .region(westZone.region())
                .region(eastZone.region())
                .endpoint("default", "default", eastZone.region().value(), westZone.region().value())
                .build();
        context.submit(applicationPackage).deploy();
        context.addRoutingPolicy(westZone, true);
        context.addRoutingPolicy(eastZone, true);

        // GET initial deployment status
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/tenant/tenant/application/application/instance/default/environment/prod/region/us-west-1",
                                              "", Request.Method.GET),
                              new File("policy/deployment-status-initial.json"));

        // POST sets deployment out
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/inactive/tenant/tenant/application/application/instance/default/environment/prod/region/us-west-1",
                                              "", Request.Method.POST),
                              "{\"message\":\"Set global routing status for tenant.application in prod.us-west-1 to OUT\"}");
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/tenant/tenant/application/application/instance/default/environment/prod/region/us-west-1",
                                              "", Request.Method.GET),
                              new File("policy/deployment-status-out.json"));

        // DELETE sets deployment in
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/inactive/tenant/tenant/application/application/instance/default/environment/prod/region/us-west-1",
                                              "", Request.Method.DELETE),
                              "{\"message\":\"Set global routing status for tenant.application in prod.us-west-1 to IN\"}");
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/tenant/tenant/application/application/instance/default/environment/prod/region/us-west-1",
                                              "", Request.Method.GET),
                              new File("policy/deployment-status-in.json"));

        // GET initial zone status
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/environment/prod/region/us-west-1",
                                              "", Request.Method.GET),
                              new File("policy/zone-status-initial.json"));

        // POST sets zone out
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/inactive/environment/prod/region/us-west-1",
                                              "", Request.Method.POST),
                              "{\"message\":\"Set global routing status for deployments in prod.us-west-1 to OUT\"}");
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/environment/prod/region/us-west-1",
                                              "", Request.Method.GET),
                              new File("policy/zone-status-out.json"));

        // DELETE sets zone in
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/inactive/environment/prod/region/us-west-1",
                                              "", Request.Method.DELETE),
                              "{\"message\":\"Set global routing status for deployments in prod.us-west-1 to IN\"}");
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/environment/prod/region/us-west-1",
                                              "", Request.Method.GET),
                              new File("policy/zone-status-in.json"));
    }

    @Test
    public void shared_routing() {
        // Deploy application
        var context = deploymentTester.newDeploymentContext();
        var westZone = ZoneId.from("prod", "us-west-1");
        var eastZone = ZoneId.from("prod", "us-east-3");
        var applicationPackage = new ApplicationPackageBuilder()
                .region(westZone.region())
                .region(eastZone.region())
                .endpoint("default", "default", eastZone.region().value(), westZone.region().value())
                .build();
        context.submit(applicationPackage).deploy();

        assertNotEquals("Rotation is assigned", List.of(), context.instance().rotations());

        // GET initial deployment status
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/tenant/tenant/application/application/instance/default/environment/prod/region/us-west-1",
                                              "", Request.Method.GET),
                              new File("rotation/deployment-status-initial.json"));

        // POST sets deployment out
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/inactive/tenant/tenant/application/application/instance/default/environment/prod/region/us-west-1",
                                              "", Request.Method.POST),
                              "{\"message\":\"Set global routing status for tenant.application in prod.us-west-1 to OUT\"}");
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/tenant/tenant/application/application/instance/default/environment/prod/region/us-west-1",
                                              "", Request.Method.GET),
                              new File("rotation/deployment-status-out.json"));

        // DELETE sets deployment in
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/inactive/tenant/tenant/application/application/instance/default/environment/prod/region/us-west-1",
                                              "", Request.Method.DELETE),
                              "{\"message\":\"Set global routing status for tenant.application in prod.us-west-1 to IN\"}");
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/tenant/tenant/application/application/instance/default/environment/prod/region/us-west-1",
                                              "", Request.Method.GET),
                              new File("rotation/deployment-status-in.json"));

        // GET initial zone status
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/environment/prod/region/us-west-1",
                                              "", Request.Method.GET),
                              new File("rotation/zone-status-initial.json"));

        // POST sets zone out
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/inactive/environment/prod/region/us-west-1",
                                              "", Request.Method.POST),
                              "{\"message\":\"Set global routing status for deployments in prod.us-west-1 to OUT\"}");
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/environment/prod/region/us-west-1",
                                              "", Request.Method.GET),
                              new File("rotation/zone-status-out.json"));

        // DELETE sets zone in
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/inactive/environment/prod/region/us-west-1",
                                              "", Request.Method.DELETE),
                              "{\"message\":\"Set global routing status for deployments in prod.us-west-1 to IN\"}");
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/environment/prod/region/us-west-1",
                                              "", Request.Method.GET),
                              new File("rotation/zone-status-in.json"));
    }

    // TODO(mpolden): Remove this once a zone supports either of routing policy and rotation
    @Test
    public void mixed_routing() {
        var westZone = ZoneId.from("prod", "us-west-1");
        var eastZone = ZoneId.from("prod", "us-east-3");
        var centralZone = ZoneId.from("prod", "us-central-1");

        // One zone supports multiple routing methods
        deploymentTester.controllerTester().zoneRegistry().setRoutingMethod(List.of(ZoneApiMock.from(westZone),
                                                                                    ZoneApiMock.from(centralZone)),
                                                                            RoutingMethod.shared,
                                                                            RoutingMethod.exclusive);

        // Deploy application
        var context = deploymentTester.newDeploymentContext();
        var applicationPackage = new ApplicationPackageBuilder()
                .region(westZone.region())
                .region(centralZone.region())
                .region(eastZone.region())
                .endpoint("default", "default", westZone.region().value(), centralZone.region().value())
                .build();
        context.submit(applicationPackage).deploy();

        // Assign policy in 2/3 zones
        context.addRoutingPolicy(westZone, true);
        context.addRoutingPolicy(centralZone, true);

        // GET status with both policy and rotation assigned
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/tenant/tenant/application/application/instance/default/environment/prod/region/us-west-1",
                                              "", Request.Method.GET),
                              new File("multi-status-initial.json"));

        // POST sets deployment out
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/inactive/tenant/tenant/application/application/instance/default/environment/prod/region/us-west-1",
                                              "", Request.Method.POST),
                              "{\"message\":\"Set global routing status for tenant.application in prod.us-west-1 to OUT\"}");
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/tenant/tenant/application/application/instance/default/environment/prod/region/us-west-1",
                                              "", Request.Method.GET),
                              new File("multi-status-out.json"));

        // DELETE sets deployment in
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/inactive/tenant/tenant/application/application/instance/default/environment/prod/region/us-west-1",
                                              "", Request.Method.DELETE),
                              "{\"message\":\"Set global routing status for tenant.application in prod.us-west-1 to IN\"}");
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/tenant/tenant/application/application/instance/default/environment/prod/region/us-west-1",
                                              "", Request.Method.GET),
                              new File("multi-status-in.json"));
    }

    @Test
    public void invalid_requests() {
        // GET non-existent application
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/tenant/t1/application/a1/instance/default/environment/prod/region/us-west-1",
                                                   "", Request.Method.GET),
                              "{\"error-code\":\"BAD_REQUEST\",\"message\":\"t1.a1 not found\"}",
                              400);

        // GET non-existent zone
        tester.assertResponse(operatorRequest("http://localhost:8080/routing/v1/status/environment/prod/region/us-north-1",
                                                   "", Request.Method.GET),
                              "{\"error-code\":\"BAD_REQUEST\",\"message\":\"No such zone: prod.us-north-1\"}",
                              400);
    }

}
