// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.autoscale;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterResources;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.provisioning.ProvisioningTester;
import com.yahoo.vespa.hosted.provision.testutils.OrchestratorMock;
import com.yahoo.vespa.applicationmodel.HostName;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NodeMetricsFetcherTest {

    @Test
    public void testMetricsFetch() {
        NodeResources resources = new NodeResources(1, 10, 100, 1);
        ProvisioningTester tester = new ProvisioningTester.Builder().build();
        OrchestratorMock orchestrator = new OrchestratorMock();
        MockHttpClient httpClient = new MockHttpClient();
        NodeMetricsFetcher fetcher = new NodeMetricsFetcher(tester.nodeRepository(), orchestrator, httpClient);

        tester.makeReadyNodes(4, resources); // Creates (in order) host-1.yahoo.com, host-2.yahoo.com, host-3.yahoo.com, host-4.yahoo.com
        tester.activateTenantHosts();

        ApplicationId application1 = ProvisioningTester.makeApplicationId();
        ApplicationId application2 = ProvisioningTester.makeApplicationId();
        tester.deploy(application1, Capacity.from(new ClusterResources(2, 1, resources))); // host-1.yahoo.com, host-2.yahoo.com
        tester.deploy(application2, Capacity.from(new ClusterResources(2, 1, resources))); // host-4.yahoo.com, host-3.yahoo.com

        orchestrator.suspend(new HostName("host-4.yahoo.com"));

        {
            httpClient.cannedResponse = cannedResponseForApplication1;
            List<NodeMetrics.MetricValue> values = new ArrayList<>(fetcher.fetchMetrics(application1));
            assertEquals("http://host-1.yahoo.com:4080/metrics/v2/values?consumer=autoscaling",
                         httpClient.requestsReceived.get(0));
            assertEquals(2, values.size());
            assertEquals("node metrics for host-1.yahoo.com at 1970-01-01T00:20:34Z: cpuUtil: 16.2, totalMemUtil: 23.1, diskUtil: 82.0, applicationGeneration: 0.0",
                         values.get(0).toString());
            assertEquals("node metrics for host-2.yahoo.com at 1970-01-01T00:20:00Z: cpuUtil: 20.0, totalMemUtil: 0.0, diskUtil: 40.0, applicationGeneration: 0.0",
                         values.get(1).toString());
        }

        {
            httpClient.cannedResponse = cannedResponseForApplication2;
            List<NodeMetrics.MetricValue> values = new ArrayList<>(fetcher.fetchMetrics(application2));
            assertEquals("http://host-3.yahoo.com:4080/metrics/v2/values?consumer=autoscaling",
                         httpClient.requestsReceived.get(1));
            assertEquals(1, values.size());
            assertEquals("node metrics for host-3.yahoo.com at 1970-01-01T00:21:40Z: cpuUtil: 10.0, totalMemUtil: 15.0, diskUtil: 20.0, applicationGeneration: 3.0",
                         values.get(0).toString());
        }

        {
            tester.nodeRepository().write(tester.nodeRepository().getNodes(application1, Node.State.active).get(0).retire(tester.clock().instant()),
                                          tester.nodeRepository().lock(application1));
            assertTrue("No metrics fetching while unstable", fetcher.fetchMetrics(application1).isEmpty());
        }
    }

    private static class MockHttpClient implements NodeMetricsFetcher.HttpClient {

        List<String> requestsReceived = new ArrayList<>();

        String cannedResponse = null;
        @Override
        public String get(String url) {
            requestsReceived.add(url);
            return cannedResponse;
        }

        @Override
        public void close() { }

    }

    final String cannedResponseForApplication1 =
            "{\n" +
            "  \"nodes\": [\n" +
            "    {\n" +
            "      \"hostname\": \"host-1.yahoo.com\",\n" +
            "      \"role\": \"role0\",\n" +
            "      \"node\": {\n" +
            "        \"timestamp\": 1234,\n" +
            "        \"metrics\": [\n" +
            "          {\n" +
            "            \"values\": {\n" +
            "              \"cpu.util\": 16.2,\n" +
            "              \"mem_total.util\": 23.1,\n" +
            "              \"disk.util\": 82\n" +
            "            },\n" +
            "            \"dimensions\": {\n" +
            "              \"state\": \"active\"\n" +
            "            }\n" +
            "          }\n" +
            "        ]\n" +
            "      }\n" +
            "    },\n" +
            "    {\n" +
            "      \"hostname\": \"host-2.yahoo.com\",\n" +
            "      \"role\": \"role1\",\n" +
            "      \"node\": {\n" +
            "        \"timestamp\": 1200,\n" +
            "        \"metrics\": [\n" +
            "          {\n" +
            "            \"values\": {\n" +
            "              \"cpu.util\": 20,\n" +
            "              \"disk.util\": 40\n" +
            "            },\n" +
            "            \"dimensions\": {\n" +
            "              \"state\": \"active\"\n" +
            "            }\n" +
            "          }\n" +
            "        ]\n" +
            "      }\n" +
            "    }\n" +
            "  ]\n" +
            "}\n";

    final String cannedResponseForApplication2 =
            "{\n" +
            "  \"nodes\": [\n" +
            "    {\n" +
            "      \"hostname\": \"host-3.yahoo.com\",\n" +
            "      \"role\": \"role0\",\n" +
            "      \"node\": {\n" +
            "        \"timestamp\": 1300,\n" +
            "        \"metrics\": [\n" +
            "          {\n" +
            "            \"values\": {\n" +
            "              \"cpu.util\": 10,\n" +
            "              \"mem_total.util\": 15,\n" +
            "              \"disk.util\": 20,\n" +
            "              \"application_generation\": 3\n" +
            "            },\n" +
            "            \"dimensions\": {\n" +
            "              \"state\": \"active\"\n" +
            "            }\n" +
            "          }\n" +
            "        ]\n" +
            "      }\n" +
            "    }\n" +
            "  ]\n" +
            "}\n";

}
