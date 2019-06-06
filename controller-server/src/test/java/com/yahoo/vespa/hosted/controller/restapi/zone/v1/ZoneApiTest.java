// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi.zone.v1;

import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.zone.ZoneApi;
import com.yahoo.vespa.hosted.controller.api.role.Role;
import com.yahoo.vespa.hosted.controller.integration.ZoneApiMock;
import com.yahoo.vespa.hosted.controller.integration.ZoneRegistryMock;
import com.yahoo.vespa.hosted.controller.restapi.ContainerControllerTester;
import com.yahoo.vespa.hosted.controller.restapi.ControllerContainerCloudTest;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * @author mpolden
 */
public class ZoneApiTest extends ControllerContainerCloudTest {

    private static final String responseFiles = "src/test/java/com/yahoo/vespa/hosted/controller/restapi/zone/v1/responses/";
    private static final List<ZoneApi> zones = List.of(
            ZoneApiMock.fromId("prod.us-north-1"),
            ZoneApiMock.fromId("dev.us-north-2"),
            ZoneApiMock.fromId("test.us-north-3"),
            ZoneApiMock.fromId("staging.us-north-4"));

    private static final Set<Role> everyone = Set.of(Role.everyone());

    private ContainerControllerTester tester;

    @Before
    public void before() {
        ZoneRegistryMock zoneRegistry = (ZoneRegistryMock) container.components()
                                                                    .getComponent(ZoneRegistryMock.class.getName());
        zoneRegistry.setDefaultRegionForEnvironment(Environment.dev, RegionName.from("us-north-2"))
                    .setZones(zones);
        this.tester = new ContainerControllerTester(container, responseFiles);
    }

    @Test
    public void test_requests() {
        // GET /zone/v1
        tester.containerTester().assertResponse(request("/zone/v1")
                                                        .roles(everyone),
                                                new File("root.json"));

        // GET /zone/v1/environment/prod
        tester.containerTester().assertResponse(request("/zone/v1/environment/prod")
                                                        .roles(everyone),
                                                new File("prod.json"));

        // GET /zone/v1/environment/dev/default
        tester.containerTester().assertResponse(request("/api/zone/v1/environment/dev/default")
                                                        .roles(everyone),
                                                new File("default-for-region.json"));
    }

    @Test
    public void test_invalid_requests() {
        // GET /zone/v1/environment/prod/default: No default region
        tester.containerTester().assertResponse(request("/zone/v1/environment/prod/default")
                                                        .roles(everyone),
                                                new File("no-default-region.json"),
                                                400);
    }

}
