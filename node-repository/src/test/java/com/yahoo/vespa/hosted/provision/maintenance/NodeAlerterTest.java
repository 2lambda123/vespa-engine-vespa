package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeType;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.junit.Assert.*;

/**
 * @author mgimle
 */
public class NodeAlerterTest {
    private NodeAlerterTester tester;
    private NodeAlerter alerter;

    @Before
    public void setup() {
        tester = new NodeAlerterTester();
        alerter = tester.makeNodeAlerter();
    }

    @Test
    public void testWithRealData() throws IOException {
        String path = "./src/test/resources/zookeeper_dump.json";

        tester.cleanRepository();
        tester.restoreNodeRepositoryFromJsonFile(Paths.get(path));
        NodeAlerter.HostFailurePath failurePath = alerter.worstCaseHostLossLeadingToFailure();
        if (failurePath != null) {
            assertTrue(tester.nodeRepository.getNodes(NodeType.host).containsAll(failurePath.hostsCausingFailure));
        } else fail();
    }

    @Test
    public void testWithGeneratedData() {
        tester.createNodes(1, 1,
                0, new NodeResources(1, 10, 100), 10,
                0, new NodeResources(1, 10, 100), 10);
        var failurePath = alerter.worstCaseHostLossLeadingToFailure();
        assertNull("Computing worst case host loss with no hosts should return null.", failurePath);

        // Odd edge case that should never be able to occur in prod
        tester.createNodes(1, 10,
                10, new NodeResources(10, 1000, 10000), 100,
                1, new NodeResources(10, 1000, 10000), 100);
        failurePath = alerter.worstCaseHostLossLeadingToFailure();
        assertNotNull(failurePath);
        assertTrue("Computing worst case host loss if all hosts have to be removed should result in an non-empty failureReason with empty nodes.",
                failurePath.failureReason.tenant.isEmpty() && failurePath.failureReason.host.isEmpty());
        assertEquals(tester.nodeRepository.getNodes(NodeType.host).size(), failurePath.hostsCausingFailure.size());

        tester.createNodes(1, 10,
                10, new NodeResources(1, 100, 1000), 100,
                10, new NodeResources(0, 100, 1000), 100);
        failurePath = alerter.worstCaseHostLossLeadingToFailure();
        assertNotNull(failurePath);
        if (failurePath.failureReason.tenant.isPresent()) {
            var failureReasons = failurePath.failureReason.failureReasons;
            assertEquals("All failures should be due to hosts lacking cpu cores.",
                    failureReasons.singularReasonFailures().insufficientVcpu(), failureReasons.size());
        } else fail();

        tester.createNodes(1, 10,
                10, new NodeResources(10, 1, 1000), 100,
                10, new NodeResources(10, 0, 1000), 100);
        failurePath = alerter.worstCaseHostLossLeadingToFailure();
        assertNotNull(failurePath);
        if (failurePath.failureReason.tenant.isPresent()) {
            var failureReasons = failurePath.failureReason.failureReasons;
            assertEquals("All failures should be due to hosts lacking memory.",
                    failureReasons.singularReasonFailures().insufficientMemoryGb(), failureReasons.size());
        } else fail();

        tester.createNodes(1, 10,
                10, new NodeResources(10, 100, 10), 100,
                10, new NodeResources(10, 100, 0), 100);
        failurePath = alerter.worstCaseHostLossLeadingToFailure();
        assertNotNull(failurePath);
        if (failurePath.failureReason.tenant.isPresent()) {
            var failureReasons = failurePath.failureReason.failureReasons;
            assertEquals("All failures should be due to hosts lacking disk space.",
                    failureReasons.singularReasonFailures().insufficientDiskGb(), failureReasons.size());
        } else fail();

        int emptyHostsWithSlowDisk = 10;
        tester.createNodes(1, 10, List.of(new NodeResources(1, 10, 100)),
                10, new NodeResources(0, 0, 0), 100,
                10, new NodeResources(10, 1000, 10000, NodeResources.DiskSpeed.slow), 100);
        failurePath = alerter.worstCaseHostLossLeadingToFailure();
        assertNotNull(failurePath);
        if (failurePath.failureReason.tenant.isPresent()) {
            var failureReasons = failurePath.failureReason.failureReasons;
            assertEquals("All empty hosts should be invalid due to having incompatible disk speed.",
                    failureReasons.singularReasonFailures().incompatibleDiskSpeed(), emptyHostsWithSlowDisk);
        } else fail();

        tester.createNodes(1, 10,
                10, new NodeResources(10, 1000, 10000), 1,
                10, new NodeResources(10, 1000, 10000), 1);
        failurePath = alerter.worstCaseHostLossLeadingToFailure();
        assertNotNull(failurePath);
        if (failurePath.failureReason.tenant.isPresent()) {
            var failureReasons = failurePath.failureReason.failureReasons;
            assertEquals("All failures should be due to hosts having a lack of available ip addresses.",
                    failureReasons.singularReasonFailures().insufficientAvailableIps(), failureReasons.size());
        } else fail();

        tester.createNodes(1, 1,
                10, new NodeResources(1, 100, 1000), 100,
                10, new NodeResources(10, 1000, 10000), 100);
        failurePath = alerter.worstCaseHostLossLeadingToFailure();
        assertNotNull(failurePath);
        if (failurePath.failureReason.tenant.isPresent()) {
            var failureReasons = failurePath.failureReason.failureReasons;
            assertEquals("With only one type of tenant, all failures should be due to violation of the parent host policy.",
                    failureReasons.singularReasonFailures().violatesParentHostPolicy(), failureReasons.size());
        } else fail();

        tester.createNodes(1, 2,
                10, new NodeResources(10, 100, 1000), 1,
                0, new NodeResources(0, 0, 0), 0);
        failurePath = alerter.worstCaseHostLossLeadingToFailure();
        assertNotNull(failurePath);
        if (failurePath.failureReason.tenant.isPresent()) {
            var failureReasons = failurePath.failureReason.failureReasons;
            assertNotEquals("Fewer distinct children than hosts should result in some parent host policy violations.",
                    failureReasons.size(), failureReasons.singularReasonFailures().violatesParentHostPolicy());
            assertNotEquals(0, failureReasons.singularReasonFailures().violatesParentHostPolicy());
        } else fail();

        tester.createNodes(3, 30,
                10, new NodeResources(0, 0, 10000), 1000,
                0, new NodeResources(0, 0, 0), 0);
        failurePath = alerter.worstCaseHostLossLeadingToFailure();
        assertNotNull(failurePath);
        if (failurePath.failureReason.tenant.isPresent()) {
            var failureReasons = failurePath.failureReason.failureReasons;
            assertEquals("When there are multiple lacking resources, all failures are multipleReasonFailures",
                    failureReasons.size(), failureReasons.multipleReasonFailures().size());
            assertEquals(0, failureReasons.singularReasonFailures().size());
        } else fail();
    }
}


