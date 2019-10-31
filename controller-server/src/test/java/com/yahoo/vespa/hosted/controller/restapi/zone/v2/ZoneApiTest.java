// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi.zone.v2;

import com.yahoo.application.container.handler.Request.Method;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.zone.ZoneApi;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.hosted.controller.integration.ConfigServerProxyMock;
import com.yahoo.vespa.hosted.controller.integration.ZoneApiMock;
import com.yahoo.vespa.hosted.controller.integration.ZoneRegistryMock;
import com.yahoo.vespa.hosted.controller.proxy.ProxyRequest;
import com.yahoo.vespa.hosted.controller.restapi.ContainerControllerTester;
import com.yahoo.vespa.hosted.controller.restapi.ControllerContainerTest;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author mpolden
 */
public class ZoneApiTest extends ControllerContainerTest {

    private static final String responseFiles = "src/test/java/com/yahoo/vespa/hosted/controller/restapi/zone/v2/responses/";
    private static final List<ZoneApi> zones = List.of(
            ZoneApiMock.fromId("prod.us-north-1"),
            ZoneApiMock.fromId("dev.aws-us-north-2"),
            ZoneApiMock.fromId("test.us-north-3"),
            ZoneApiMock.fromId("staging.us-north-4"));

    private ContainerControllerTester tester;
    private ConfigServerProxyMock proxy;

    @Before
    public void before() {
        ZoneRegistryMock zoneRegistry = (ZoneRegistryMock) container.components()
                                                                    .getComponent(ZoneRegistryMock.class.getName());
        zoneRegistry.setDefaultRegionForEnvironment(Environment.dev, RegionName.from("us-north-2"))
                    .setZones(zones);
        this.tester = new ContainerControllerTester(container, responseFiles);
        this.proxy = (ConfigServerProxyMock) container.components().getComponent(ConfigServerProxyMock.class.getName());
    }

    @Test
    public void test_requests() {
        // GET /zone/v2
        tester.containerTester().assertResponse(authenticatedRequest("http://localhost:8080/zone/v2"),
                                                new File("root.json"));

        // GET /zone/v2/prod/us-north-1
        tester.containerTester().assertResponse(authenticatedRequest("http://localhost:8080/zone/v2/prod/us-north-1"),
                                                "ok");

        assertLastRequest(ZoneId.from("prod", "us-north-1"), 2, "GET");

        // GET /zone/v2/nodes/v2/node/?recursive=true
        tester.containerTester().assertResponse(authenticatedRequest("http://localhost:8080/zone/v2/prod/us-north-1/nodes/v2/node/?recursive=true"),
                                                "ok");
        assertLastRequest(ZoneId.from("prod", "us-north-1"), 2, "GET");

        // POST /zone/v2/dev/us-north-2/nodes/v2/command/restart?hostname=node1
        tester.containerTester().assertResponse(operatorRequest("http://localhost:8080/zone/v2/dev/aws-us-north-2/nodes/v2/command/restart?hostname=node1",
                                                            "", Method.POST),
                                                "ok");

        // PUT /zone/v2/prod/us-north-1/nodes/v2/state/dirty/node1
        tester.containerTester().assertResponse(operatorRequest("http://localhost:8080/zone/v2/prod/us-north-1/nodes/v2/state/dirty/node1",
                                                            "", Method.PUT), "ok");
        assertLastRequest(ZoneId.from("prod", "us-north-1"), 2, "PUT");

        // DELETE /zone/v2/prod/us-north-1/nodes/v2/node/node1
        tester.containerTester().assertResponse(operatorRequest("http://localhost:8080/zone/v2/prod/us-north-1/nodes/v2/node/node1",
                                                            "", Method.DELETE), "ok");
        assertLastRequest(ZoneId.from("prod", "us-north-1"), 2, "DELETE");

        // PATCH /zone/v2/prod/us-north-1/nodes/v2/node/node1
        tester.containerTester().assertResponse(operatorRequest("http://localhost:8080/zone/v2/dev/aws-us-north-2/nodes/v2/node/node1",
                                                            "{\"currentRestartGeneration\": 1}",
                                                            Method.PATCH), "ok");
        assertLastRequest(ZoneId.from("dev", "aws-us-north-2"), 1, "PATCH");
        assertEquals("{\"currentRestartGeneration\": 1}", proxy.lastRequestBody().get());

        assertFalse("Actions are logged to audit log", tester.controller().auditLogger().readLog().entries().isEmpty());
    }

    @Test
    public void test_invalid_requests() {
        // POST /zone/v2/prod/us-north-34/nodes/v2
        tester.containerTester().assertResponse(operatorRequest("http://localhost:8080/zone/v2/prod/us-north-42/nodes/v2",
                                                            "", Method.POST),
                                                new File("unknown-zone.json"), 400);
        assertFalse(proxy.lastReceived().isPresent());
    }

    private void assertLastRequest(ZoneId zoneId, int targets, String method) {
        ProxyRequest last = proxy.lastReceived().orElseThrow();
        assertEquals(targets, last.getTargets().size());
        assertTrue(last.getTargets().get(0).toString().contains(zoneId.value()));
        assertEquals(com.yahoo.jdisc.http.HttpRequest.Method.valueOf(method), last.getMethod());
    }

}
