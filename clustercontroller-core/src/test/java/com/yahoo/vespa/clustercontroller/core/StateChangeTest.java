// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.clustercontroller.core;

import com.yahoo.jrt.*;
import com.yahoo.vdslib.distribution.ConfiguredNode;
import com.yahoo.vdslib.state.*;
import com.yahoo.vespa.clustercontroller.core.database.DatabaseHandler;
import com.yahoo.vespa.clustercontroller.core.testutils.StateWaiter;
import com.yahoo.vespa.clustercontroller.utils.util.NoMetricReporter;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.*;
import java.util.logging.Logger;

public class StateChangeTest extends FleetControllerTest {

    public static Logger log = Logger.getLogger(StateChangeTest.class.getName());
    private Supervisor supervisor;
    private FleetController ctrl;
    private DummyCommunicator communicator;
    private EventLog eventLog;

    @Before
    public void setUp() {
        supervisor = new Supervisor(new Transport());
    }

    private void initialize(FleetControllerOptions options) throws Exception {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < options.nodes.size(); ++i) {
            nodes.add(new Node(NodeType.STORAGE, i));
            nodes.add(new Node(NodeType.DISTRIBUTOR, i));
        }

        communicator = new DummyCommunicator(nodes, timer);
        MetricUpdater metricUpdater = new MetricUpdater(new NoMetricReporter(), options.fleetControllerIndex);
        eventLog = new EventLog(timer, metricUpdater);
        ContentCluster cluster = new ContentCluster(options.clusterName, options.nodes, options.storageDistribution,
                                                    options.minStorageNodesUp, options.minRatioOfStorageNodesUp);
        NodeStateGatherer stateGatherer = new NodeStateGatherer(timer, timer, eventLog);
        DatabaseHandler database = new DatabaseHandler(timer, options.zooKeeperServerAddress, options.fleetControllerIndex, timer);
        StateChangeHandler stateGenerator = new StateChangeHandler(timer, eventLog, metricUpdater);
        SystemStateBroadcaster stateBroadcaster = new SystemStateBroadcaster(timer, timer);
        MasterElectionHandler masterElectionHandler = new MasterElectionHandler(options.fleetControllerIndex, options.fleetControllerCount, timer, timer);
        ctrl = new FleetController(timer, eventLog, cluster, stateGatherer, communicator, null, null, communicator, database, stateGenerator, stateBroadcaster, masterElectionHandler, metricUpdater, options);

        ctrl.tick();

        for (int i = 0; i < options.nodes.size(); ++i) {
            communicator.setNodeState(new Node(NodeType.STORAGE, i), State.UP, "");
            communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, i), State.UP, "");
        }

        ctrl.tick();
    }

    public void tearDown() throws Exception {
        if (supervisor != null) {
            supervisor.transport().shutdown().join();
            supervisor = null;
        }
        super.tearDown();
    }

    public void verifyNodeEvents(Node n, String correct) {
        String actual = "";
        for (NodeEvent e : eventLog.getNodeEvents(n)) {
            actual += e.toString() + "\n";
        }

        assertEquals(correct, actual);

    }

    private List<ConfiguredNode> createNodes(int count) {
        List<ConfiguredNode> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++)
            nodes.add(new ConfiguredNode(i, false));
        return nodes;
    }

    @Test
    public void testNormalStartup() throws Exception {
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.maxInitProgressTime = 50000;

        initialize(options);

        // Should now pick up previous node states
        ctrl.tick();


        for (int j = 0; j < 10; ++j) {
            communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, j), new NodeState(NodeType.DISTRIBUTOR, State.INITIALIZING).setInitProgress(0.0), "");
        }

        for (int i=0; i<100; i += 10) {
            timer.advanceTime(options.maxInitProgressTime / 20);
            ctrl.tick();
            for (int j = 0; j < 10; ++j) {
                communicator.setNodeState(new Node(NodeType.STORAGE, j), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(i / 100.0), "");
            }
        }

        // Now, fleet controller should have generated a new cluster state.
        ctrl.tick();

        // Regular init progress does not update the cluster state until the node is done initializing (or goes down,
        // whichever comes first).
        assertEquals("version:6 distributor:10 .0.s:i .0.i:0.0 .1.s:i .1.i:0.0 .2.s:i .2.i:0.0 .3.s:i .3.i:0.0 " +
                        ".4.s:i .4.i:0.0 .5.s:i .5.i:0.0 .6.s:i .6.i:0.0 .7.s:i .7.i:0.0 .8.s:i .8.i:0.0 " +
                        ".9.s:i .9.i:0.0 storage:10 .0.s:i .0.i:0.1 .1.s:i .1.i:0.1 .2.s:i .2.i:0.1 .3.s:i .3.i:0.1 " +
                        ".4.s:i .4.i:0.1 .5.s:i .5.i:0.1 .6.s:i .6.i:0.1 .7.s:i .7.i:0.1 .8.s:i .8.i:0.1 .9.s:i .9.i:0.1",
                ctrl.consolidatedClusterState().toString());

        timer.advanceTime(options.maxInitProgressTime / 20);
        ctrl.tick();

        for (int i = 0; i < 10; ++i) {
            communicator.setNodeState(new Node(NodeType.STORAGE, i), new NodeState(NodeType.STORAGE, State.UP), "");
        }

        timer.advanceTime(options.maxInitProgressTime / 20);
        ctrl.tick();

        for (int i = 0; i < 10; ++i) {
            communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, i), new NodeState(NodeType.STORAGE, State.UP), "");
        }

        timer.advanceTime(options.maxInitProgressTime / 20);
        ctrl.tick();

        assertEquals("version:8 distributor:10 storage:10", ctrl.getSystemState().toString());

        verifyNodeEvents(new Node(NodeType.DISTRIBUTOR, 0),
                "Event: distributor.0: Now reporting state U\n" +
                "Event: distributor.0: Altered node state in cluster state from 'D: Node not seen in slobrok.' to 'U'\n" +
                "Event: distributor.0: Now reporting state I, i 0.00\n" +
                "Event: distributor.0: Altered node state in cluster state from 'U' to 'I, i 0.00'\n" +
                "Event: distributor.0: Now reporting state U\n" +
                "Event: distributor.0: Altered node state in cluster state from 'I, i 0.00' to 'U'\n");

        verifyNodeEvents(new Node(NodeType.STORAGE, 0),
                "Event: storage.0: Now reporting state U\n" +
                "Event: storage.0: Altered node state in cluster state from 'D: Node not seen in slobrok.' to 'U'\n" +
                "Event: storage.0: Now reporting state I, i 0.00 (ls)\n" +
                "Event: storage.0: Altered node state in cluster state from 'U' to 'D'\n" +
                "Event: storage.0: Now reporting state I, i 0.100 (read)\n" +
                "Event: storage.0: Altered node state in cluster state from 'D' to 'I, i 0.100 (read)'\n" +
                "Event: storage.0: Now reporting state U\n" +
                "Event: storage.0: Altered node state in cluster state from 'I, i 0.100 (read)' to 'U'\n");
    }

    @Test
    public void testNodeGoingDownAndUp() throws Exception {
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.nodeStateRequestTimeoutMS = 60 * 60 * 1000;
        options.minTimeBetweenNewSystemStates = 0;
        options.maxInitProgressTime = 50000;

        initialize(options);

        ctrl.tick();

        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 0), State.DOWN, "Closed at other end");

        ctrl.tick();

        String desc = ctrl.getReportedNodeState(new Node(NodeType.DISTRIBUTOR, 0)).getDescription();
        assertTrue(desc, desc.indexOf("Closed at other end") != -1);

        assertEquals("version:4 distributor:10 .0.s:d storage:10", ctrl.getSystemState().toString());

        timer.advanceTime(1000);

        ctrl.tick();

        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 0), new NodeState(NodeType.DISTRIBUTOR, State.UP).setStartTimestamp(12345678), "");
        communicator.setNodeState(new Node(NodeType.STORAGE, 0), State.DOWN, "Closed at other end");

        ctrl.tick();

        assertEquals("version:5 distributor:10 .0.t:12345678 storage:10 .0.s:m", ctrl.getSystemState().toString());

        assert(!ctrl.getReportedNodeState(new Node(NodeType.DISTRIBUTOR, 0)).hasDescription());
        desc = ctrl.getReportedNodeState(new Node(NodeType.STORAGE, 0)).getDescription();
        assertTrue(desc, desc.indexOf("Closed at other end") != -1);

        timer.advanceTime(options.maxTransitionTime.get(NodeType.STORAGE) + 1);

        ctrl.tick();

        assertEquals("version:6 distributor:10 .0.t:12345678 storage:10 .0.s:d", ctrl.getSystemState().toString());

        desc = ctrl.getReportedNodeState(new Node(NodeType.STORAGE, 0)).getDescription();
        assertTrue(desc, desc.indexOf("Closed at other end") != -1);

        timer.advanceTime(1000);

        ctrl.tick();

        communicator.setNodeState(new Node(NodeType.STORAGE, 0), new NodeState(NodeType.STORAGE, State.UP).setStartTimestamp(12345679), "");

        ctrl.tick();

        assertEquals("version:7 distributor:10 storage:10 .0.t:12345679", ctrl.getSystemState().toString());

        assert(!ctrl.getReportedNodeState(new Node(NodeType.STORAGE, 0)).hasDescription());

        verifyNodeEvents(new Node(NodeType.DISTRIBUTOR, 0),
                "Event: distributor.0: Now reporting state U\n" +
                "Event: distributor.0: Altered node state in cluster state from 'D: Node not seen in slobrok.' to 'U'\n" +
                "Event: distributor.0: Failed to get node state: D: Closed at other end\n" +
                "Event: distributor.0: Stopped or possibly crashed after 0 ms, which is before stable state time period. Premature crash count is now 1.\n" +
                "Event: distributor.0: Altered node state in cluster state from 'U' to 'D: Closed at other end'\n" +
                "Event: distributor.0: Now reporting state U, t 12345678\n" +
                "Event: distributor.0: Altered node state in cluster state from 'D: Closed at other end' to 'U, t 12345678'\n" +
                "Event: distributor.0: Altered node state in cluster state from 'U, t 12345678' to 'U'\n");

        verifyNodeEvents(new Node(NodeType.STORAGE, 0),
                "Event: storage.0: Now reporting state U\n" +
                "Event: storage.0: Altered node state in cluster state from 'D: Node not seen in slobrok.' to 'U'\n" +
                "Event: storage.0: Failed to get node state: D: Closed at other end\n" +
                "Event: storage.0: Stopped or possibly crashed after 1000 ms, which is before stable state time period. Premature crash count is now 1.\n" +
                "Event: storage.0: Altered node state in cluster state from 'U' to 'M: Closed at other end'\n" +
                "Event: storage.0: 5001 milliseconds without contact. Marking node down.\n" +
                "Event: storage.0: Altered node state in cluster state from 'M: Closed at other end' to 'D: Closed at other end'\n" +
                "Event: storage.0: Now reporting state U, t 12345679\n" +
                "Event: storage.0: Altered node state in cluster state from 'D: Closed at other end' to 'U, t 12345679'\n");

        assertEquals(1, ctrl.getCluster().getNodeInfo(new Node(NodeType.DISTRIBUTOR, 0)).getPrematureCrashCount());
        assertEquals(1, ctrl.getCluster().getNodeInfo(new Node(NodeType.STORAGE, 0)).getPrematureCrashCount());
    }

    public void tick(int timeMs) throws Exception {
        timer.advanceTime(timeMs);
        ctrl.tick();
    }

    @Test
    public void testNodeGoingDownAndUpNotifying() throws Exception {
        // Same test as above, but node manages to notify why it is going down first.
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.nodeStateRequestTimeoutMS = 60 * 60 * 1000;
        options.maxSlobrokDisconnectGracePeriod = 100000;

        initialize(options);

        ctrl.tick();

        tick((int)options.stableStateTimePeriod + 1);

        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 0), State.DOWN, "controlled shutdown");

        ctrl.tick();

        String desc = ctrl.getReportedNodeState(new Node(NodeType.DISTRIBUTOR, 0)).getDescription();
        assertTrue(desc, desc.indexOf("Received signal 15 (SIGTERM - Termination signal)") != -1
                      || desc.indexOf("controlled shutdown") != -1);

        tick(1000);

        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 0), State.UP, "");
        communicator.setNodeState(new Node(NodeType.STORAGE, 0), State.DOWN, "controlled shutdown");

        tick(1000);

        assertEquals("version:5 distributor:10 storage:10 .0.s:m", ctrl.getSystemState().toString());

        assert(!ctrl.getReportedNodeState(new Node(NodeType.DISTRIBUTOR, 0)).hasDescription());
        desc = ctrl.getReportedNodeState(new Node(NodeType.STORAGE, 0)).getDescription();
        assertTrue(desc, desc.indexOf("Received signal 15 (SIGTERM - Termination signal)") != -1
                      || desc.indexOf("controlled shutdown") != -1);

        tick(options.maxTransitionTime.get(NodeType.STORAGE) + 1);

        assertEquals("version:6 distributor:10 storage:10 .0.s:d", ctrl.getSystemState().toString());
        desc = ctrl.getReportedNodeState(new Node(NodeType.STORAGE, 0)).getDescription();
        assertTrue(desc, desc.indexOf("Received signal 15 (SIGTERM - Termination signal)") != -1
                      || desc.indexOf("controlled shutdown") != -1);

        communicator.setNodeState(new Node(NodeType.STORAGE, 0), State.UP, "");

        tick(1000);

        assertEquals("version:7 distributor:10 storage:10", ctrl.getSystemState().toString());
        assert(!ctrl.getReportedNodeState(new Node(NodeType.STORAGE, 0)).hasDescription());

        assertEquals(0, ctrl.getCluster().getNodeInfo(new Node(NodeType.DISTRIBUTOR, 0)).getPrematureCrashCount());
        assertEquals(0, ctrl.getCluster().getNodeInfo(new Node(NodeType.STORAGE, 0)).getPrematureCrashCount());

        verifyNodeEvents(new Node(NodeType.DISTRIBUTOR, 0),
                "Event: distributor.0: Now reporting state U\n" +
                "Event: distributor.0: Altered node state in cluster state from 'D: Node not seen in slobrok.' to 'U'\n" +
                "Event: distributor.0: Failed to get node state: D: controlled shutdown\n" +
                "Event: distributor.0: Altered node state in cluster state from 'U' to 'D: controlled shutdown'\n" +
                "Event: distributor.0: Now reporting state U\n" +
                "Event: distributor.0: Altered node state in cluster state from 'D: controlled shutdown' to 'U'\n");

        verifyNodeEvents(new Node(NodeType.STORAGE, 0),
                "Event: storage.0: Now reporting state U\n" +
                "Event: storage.0: Altered node state in cluster state from 'D: Node not seen in slobrok.' to 'U'\n" +
                "Event: storage.0: Failed to get node state: D: controlled shutdown\n" +
                "Event: storage.0: Altered node state in cluster state from 'U' to 'M: controlled shutdown'\n" +
                "Event: storage.0: 5001 milliseconds without contact. Marking node down.\n" +
                "Event: storage.0: Altered node state in cluster state from 'M: controlled shutdown' to 'D: controlled shutdown'\n" +
                "Event: storage.0: Now reporting state U\n" +
                "Event: storage.0: Altered node state in cluster state from 'D: controlled shutdown' to 'U'\n");

    }

    @Test
    public void testNodeGoingDownAndUpFast() throws Exception {
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.maxSlobrokDisconnectGracePeriod = 60 * 1000;

        initialize(options);

        ctrl.tick();

        // Node dropped out of slobrok
        List<Node> nodes = new ArrayList<>();
        for (int i = 1; i < 10; ++i) {
            nodes.add(new Node(NodeType.STORAGE, i));
            nodes.add(new Node(NodeType.DISTRIBUTOR, i));
        }

        communicator.newNodes = nodes;

        ctrl.tick();
        ctrl.tick();

        assertEquals("version:3 distributor:10 storage:10", ctrl.getSystemState().toString());

        nodes = new ArrayList<>();
        for (int i = 0; i < 10; ++i) {
            nodes.add(new Node(NodeType.STORAGE, i));
            nodes.add(new Node(NodeType.DISTRIBUTOR, i));
        }

        communicator.newNodes = nodes;

        ctrl.tick();

        assertEquals("version:3 distributor:10 storage:10", ctrl.getSystemState().toString());

        verifyNodeEvents(new Node(NodeType.STORAGE, 0),
                "Event: storage.0: Now reporting state U\n" +
                "Event: storage.0: Altered node state in cluster state from 'D: Node not seen in slobrok.' to 'U'\n" +
                "Event: storage.0: Node is no longer in slobrok, but we still have a pending state request.\n");
    }

    @Test
    public void testMaintenanceWhileNormalStorageNodeRestart() throws Exception {
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.maxSlobrokDisconnectGracePeriod = 60 * 1000;

        initialize(options);

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), State.DOWN, "Connection error: Closed at other end");

        ctrl.tick();

        assertEquals("version:4 distributor:10 storage:10 .6.s:m", ctrl.getSystemState().toString());

        NodeState ns = ctrl.getReportedNodeState(new Node(NodeType.STORAGE, 6));
        assertTrue(ns.toString(), ns.getDescription().indexOf("Connection error: Closed at other end") != -1);

        tick(1000);

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(0.0), "");

        ctrl.tick();

        // Still maintenance since .i progress 0.0 is really down.
        assertEquals("version:4 distributor:10 storage:10 .6.s:m", ctrl.getSystemState().toString());

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(0.6), "");

        ctrl.tick();

        // Now it's OK
        assertEquals("version:5 distributor:10 storage:10 .6.s:i .6.i:0.6", ctrl.getSystemState().toString());

        tick(1000);

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.UP), "");

        ctrl.tick();

        assertEquals("version:6 distributor:10 storage:10", ctrl.getSystemState().toString());
        assert(!ctrl.getReportedNodeState(new Node(NodeType.STORAGE, 6)).hasDescription());

        verifyNodeEvents(new Node(NodeType.STORAGE, 6),
                "Event: storage.6: Now reporting state U\n" +
                "Event: storage.6: Altered node state in cluster state from 'D: Node not seen in slobrok.' to 'U'\n" +
                "Event: storage.6: Failed to get node state: D: Connection error: Closed at other end\n" +
                "Event: storage.6: Stopped or possibly crashed after 0 ms, which is before stable state time period. Premature crash count is now 1.\n" +
                "Event: storage.6: Altered node state in cluster state from 'U' to 'M: Connection error: Closed at other end'\n" +
                "Event: storage.6: Now reporting state I, i 0.00 (ls)\n" +
                "Event: storage.6: Now reporting state I, i 0.600 (read)\n" +
                "Event: storage.6: Altered node state in cluster state from 'M: Connection error: Closed at other end' to 'I, i 0.600 (read)'\n" +
                "Event: storage.6: Now reporting state U\n" +
                "Event: storage.6: Altered node state in cluster state from 'I, i 0.600 (read)' to 'U'\n");
    }

    @Test
    public void testMaintenanceWithoutInitIfRetired() throws Exception {
        List<ConfiguredNode> nodes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            boolean retired = (i == 6);
            nodes.add(new ConfiguredNode(i, retired));
        }

        FleetControllerOptions options = new FleetControllerOptions("mycluster", nodes);
        options.maxSlobrokDisconnectGracePeriod = 60 * 1000;

        initialize(options);

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), State.DOWN, "Connection error: Closed at other end");

        ctrl.tick();

        assertEquals("version:4 distributor:10 storage:10 .6.s:m", ctrl.getSystemState().toString());

        NodeState ns = ctrl.getReportedNodeState(new Node(NodeType.STORAGE, 6));
        assertTrue(ns.toString(), ns.getDescription().indexOf("Connection error: Closed at other end") != -1);

        tick(1000);

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(0.0), "");

        ctrl.tick();

        // Still maintenance since .i progress 0.0 is really down.
        assertEquals("version:4 distributor:10 storage:10 .6.s:m", ctrl.getSystemState().toString());

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(0.6), "");

        ctrl.tick();

        // Still maintenance since configured.
        assertEquals("version:4 distributor:10 storage:10 .6.s:m", ctrl.getSystemState().toString());

        tick(1000);

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.UP), "");

        ctrl.tick();

        assertEquals("version:5 distributor:10 storage:10 .6.s:r", ctrl.getSystemState().toString());
        assert(!ctrl.getReportedNodeState(new Node(NodeType.STORAGE, 6)).hasDescription());

        verifyNodeEvents(new Node(NodeType.STORAGE, 6),
                "Event: storage.6: Now reporting state U\n" +
                "Event: storage.6: Altered node state in cluster state from 'D: Node not seen in slobrok.' to 'R'\n" +
                "Event: storage.6: Failed to get node state: D: Connection error: Closed at other end\n" +
                "Event: storage.6: Stopped or possibly crashed after 0 ms, which is before stable state time period. Premature crash count is now 1.\n" +
                "Event: storage.6: Altered node state in cluster state from 'R' to 'M: Connection error: Closed at other end'\n" +
                "Event: storage.6: Now reporting state I, i 0.00 (ls)\n" +
                "Event: storage.6: Now reporting state I, i 0.600 (read)\n" +
                "Event: storage.6: Now reporting state U\n" +
                "Event: storage.6: Altered node state in cluster state from 'M: Connection error: Closed at other end' to 'R'\n");
    }

    @Test
    public void testMaintenanceToDownIfPastTransitionTimeAndRetired() throws Exception {
        List<ConfiguredNode> nodes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            boolean retired = (i == 6);
            nodes.add(new ConfiguredNode(i, retired));
        }

        FleetControllerOptions options = new FleetControllerOptions("mycluster", nodes);
        options.maxSlobrokDisconnectGracePeriod = 60 * 1000;

        initialize(options);

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), State.DOWN, "Connection error: Closed at other end");

        ctrl.tick();

        assertEquals("version:4 distributor:10 storage:10 .6.s:m", ctrl.getSystemState().toString());

        timer.advanceTime(100000);

        ctrl.tick();

        assertEquals("version:5 distributor:10 storage:10 .6.s:d", ctrl.getSystemState().toString());
    }

    // Test that a node that has been down for a long time (above steady state period), actually alters cluster state to
    // tell that it is initializing, rather than being ignored as a just restarted/unstable node should be.
    @Test
    public void testDownNodeInitializing() throws Exception {
        // Actually report initializing state if node has been down steadily for a while
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.maxTransitionTime.put(NodeType.STORAGE, 5000);
        options.maxInitProgressTime = 5000;
        options.stableStateTimePeriod = 20000;
        options.nodeStateRequestTimeoutMS = 1000000;
        options.maxSlobrokDisconnectGracePeriod = 1000000;

        initialize(options);

        timer.advanceTime(100000); // Node has been in steady state up
        ctrl.tick();

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), State.DOWN, "Connection error: Closed at other end");

        ctrl.tick();

        assertEquals("version:4 distributor:10 storage:10 .6.s:m", ctrl.getSystemState().toString());

        timer.advanceTime(100000); // Node has been in steady state down

        ctrl.tick();

        assertEquals("version:5 distributor:10 storage:10 .6.s:d", ctrl.getSystemState().toString());

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(0.001), "");

        ctrl.tick();

        assertEquals("version:5 distributor:10 storage:10 .6.s:d", ctrl.getSystemState().toString());

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(0.1), "");

        ctrl.tick();

        assertEquals("version:6 distributor:10 storage:10 .6.s:i .6.i:0.1", ctrl.getSystemState().toString());

        ctrl.tick();

        assertEquals("version:6 distributor:10 storage:10 .6.s:i .6.i:0.1", ctrl.getSystemState().toString());

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.UP), "");

        ctrl.tick();

        assertEquals("version:7 distributor:10 storage:10", ctrl.getSystemState().toString());

        verifyNodeEvents(new Node(NodeType.STORAGE, 6),
                "Event: storage.6: Now reporting state U\n" +
                "Event: storage.6: Altered node state in cluster state from 'D: Node not seen in slobrok.' to 'U'\n" +
                "Event: storage.6: Failed to get node state: D: Connection error: Closed at other end\n" +
                "Event: storage.6: Altered node state in cluster state from 'U' to 'M: Connection error: Closed at other end'\n" +
                "Event: storage.6: 100000 milliseconds without contact. Marking node down.\n" +
                "Event: storage.6: Altered node state in cluster state from 'M: Connection error: Closed at other end' to 'D: Connection error: Closed at other end'\n" +
                "Event: storage.6: Now reporting state I, i 0.00100 (ls)\n" +
                "Event: storage.6: Now reporting state I, i 0.100 (read)\n" +
                "Event: storage.6: Altered node state in cluster state from 'D: Connection error: Closed at other end' to 'I, i 0.100 (read)'\n" +
                "Event: storage.6: Now reporting state U\n" +
                "Event: storage.6: Altered node state in cluster state from 'I, i 0.100 (read)' to 'U'\n");
    }

    @Test
    public void testNodeInitializationStalled() throws Exception {
        // Node should eventually be marked down, and not become initializing next time, but stay down until up
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.maxTransitionTime.put(NodeType.STORAGE, 5000);
        options.maxInitProgressTime = 5000;
        options.stableStateTimePeriod = 1000000;
        options.maxSlobrokDisconnectGracePeriod = 10000000;

        initialize(options);

        timer.advanceTime(1000000); // Node has been in steady state up

        ctrl.tick();

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), State.DOWN, "Connection error: Closed at other end");

        ctrl.tick();

        assertEquals("version:4 distributor:10 storage:10 .6.s:m", ctrl.getSystemState().toString());

        timer.advanceTime(1000000); // Node has been in steady state down

        ctrl.tick();

        assertEquals("version:5 distributor:10 storage:10 .6.s:d", ctrl.getSystemState().toString());

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(0.1), "");

        ctrl.tick();

        assertEquals("version:6 distributor:10 storage:10 .6.s:i .6.i:0.1", ctrl.getSystemState().toString());

        timer.advanceTime(options.maxInitProgressTime + 1);

        ctrl.tick();

        // We should now get the node marked down.
        assertEquals("version:7 distributor:10 storage:10 .6.s:d", ctrl.getSystemState().toString());

        tick(1000);

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), State.DOWN, "Connection error: Closed at other end");

        ctrl.tick();

        tick(options.nodeStateRequestTimeoutMS + 1);

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(0.0), "");

        tick(1000);

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(0.1), "");

        tick(1000);

        // Still down since it seemingly crashed during last init.
        assertEquals("version:7 distributor:10 storage:10 .6.s:d", ctrl.getSystemState().toString());

        ctrl.tick();

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), State.UP, "");

        ctrl.tick();

        assertEquals("version:8 distributor:10 storage:10", ctrl.getSystemState().toString());

        verifyNodeEvents(new Node(NodeType.STORAGE, 6),
                "Event: storage.6: Now reporting state U\n" +
                "Event: storage.6: Altered node state in cluster state from 'D: Node not seen in slobrok.' to 'U'\n" +
                "Event: storage.6: Failed to get node state: D: Connection error: Closed at other end\n" +
                "Event: storage.6: Altered node state in cluster state from 'U' to 'M: Connection error: Closed at other end'\n" +
                "Event: storage.6: 1000000 milliseconds without contact. Marking node down.\n" +
                "Event: storage.6: Altered node state in cluster state from 'M: Connection error: Closed at other end' to 'D: Connection error: Closed at other end'\n" +
                "Event: storage.6: Now reporting state I, i 0.100 (read)\n" +
                "Event: storage.6: Altered node state in cluster state from 'D: Connection error: Closed at other end' to 'I, i 0.100 (read)'\n" +
                "Event: storage.6: 5001 milliseconds without initialize progress. Marking node down. Premature crash count is now 1.\n" +
                "Event: storage.6: Altered node state in cluster state from 'I, i 0.100 (read)' to 'D'\n" +
                "Event: storage.6: Failed to get node state: D: Connection error: Closed at other end\n" +
                "Event: storage.6: Now reporting state I, i 0.00 (ls)\n" +
                "Event: storage.6: Now reporting state I, i 0.100 (read)\n" +
                "Event: storage.6: Now reporting state U\n" +
                "Event: storage.6: Altered node state in cluster state from 'D' to 'U'\n");

    }

    @Test
    public void testBackwardsInitializationProgress() throws Exception {
        // Same as stalled. Mark down, keep down until up
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.maxTransitionTime.put(NodeType.STORAGE, 5000);
        options.maxInitProgressTime = 5000;
        options.stableStateTimePeriod = 1000000;
            // Set long so we dont time out RPC requests and mark nodes down due to advancing time to get in steady state
        options.nodeStateRequestTimeoutMS = (int) options.stableStateTimePeriod * 2;

        initialize(options);

        timer.advanceTime(1000000); // Node has been in steady state up

        ctrl.tick();

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), State.DOWN, "Connection error: Closed at other end");

        ctrl.tick();

        assertEquals("version:4 distributor:10 storage:10 .6.s:m", ctrl.getSystemState().toString());

        timer.advanceTime(1000000); // Node has been in steady state down

        ctrl.tick();

        assertEquals("version:5 distributor:10 storage:10 .6.s:d", ctrl.getSystemState().toString());

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(0.3), "");

        ctrl.tick();

        assertEquals("version:6 distributor:10 storage:10 .6.s:i .6.i:0.3", ctrl.getSystemState().toString());

        ctrl.tick();

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(0.2), "");

        ctrl.tick();

        assertEquals("version:7 distributor:10 storage:10 .6.s:d", ctrl.getSystemState().toString());
    }

    @Test
    public void testNodeGoingDownWhileInitializing() throws Exception {
        // Same as stalled. Mark down, keep down until up
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.maxTransitionTime.put(NodeType.STORAGE, 5000);
        options.maxInitProgressTime = 5000;
        options.stableStateTimePeriod = 1000000;
        options.nodeStateRequestTimeoutMS = 365 * 24 * 60 * 1000; // Set very high so the advanceTime don't start sending state replies right before we disconnect.

        initialize(options);

        timer.advanceTime(1000000); // Node has been in steady state up

        ctrl.tick();

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), State.DOWN, "Connection error: Closed at other end");

        ctrl.tick();

        assertEquals("version:4 distributor:10 storage:10 .6.s:m", ctrl.getSystemState().toString());

        timer.advanceTime(1000000); // Node has been in steady state down

        ctrl.tick();

        assertEquals("version:5 distributor:10 storage:10 .6.s:d", ctrl.getSystemState().toString());

        ctrl.tick();

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(0.3), "");

        ctrl.tick();

        assertEquals("version:6 distributor:10 storage:10 .6.s:i .6.i:0.3", ctrl.getSystemState().toString());

        ctrl.tick();

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), State.DOWN, "Connection error: Closed at other end");

        ctrl.tick();

        assertEquals("version:7 distributor:10 storage:10 .6.s:d", ctrl.getSystemState().toString());

        tick(1000);

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(0.3), "");

        ctrl.tick();

        assertEquals("version:7 distributor:10 storage:10 .6.s:d", ctrl.getSystemState().toString());

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), State.UP, "");

        ctrl.tick();

        assertEquals("version:8 distributor:10 storage:10", ctrl.getSystemState().toString());
    }

    @Test
    public void testContinuousCrashRightAfterInit() throws Exception {
        startingTest("StateChangeTest::testContinuousCrashRightAfterInit");
        // If node does this too many times, take it out of service
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.maxTransitionTime.put(NodeType.STORAGE, 5000);
        options.maxInitProgressTime = 5000;
        options.maxPrematureCrashes = 2;
        options.stableStateTimePeriod = 1000000;
        options.maxSlobrokDisconnectGracePeriod = 10000000;

        initialize(options);

        timer.advanceTime(1000000); // Node has been in steady state up

        ctrl.tick();

        communicator.setNodeState(new Node(NodeType.STORAGE, 6), State.DOWN, "Connection error: Closed at other end");

        ctrl.tick();

        assertEquals("version:4 distributor:10 storage:10 .6.s:m", ctrl.getSystemState().toString());

        timer.advanceTime(1000000); // Node has been in steady state down

        ctrl.tick();

        assertEquals("version:5 distributor:10 storage:10 .6.s:d", ctrl.getSystemState().toString());

        for (int j = 0; j <= options.maxPrematureCrashes; ++j) {
            ctrl.tick();

            tick(options.nodeStateRequestTimeoutMS + 1);

            communicator.setNodeState(new Node(NodeType.STORAGE, 6), State.DOWN, "Connection error: Closed at other end");

            ctrl.tick();

            tick(options.nodeStateRequestTimeoutMS + 1);

            communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(0.0), "");

            ctrl.tick();

            tick(options.nodeStateRequestTimeoutMS + 1);

            communicator.setNodeState(new Node(NodeType.STORAGE, 6), new NodeState(NodeType.STORAGE, State.INITIALIZING).setInitProgress(0.1), "");

            tick(1000);
        }

        assertEquals("version:7 distributor:10 storage:10 .6.s:d", ctrl.getSystemState().toString());
    }

    @Test
    public void testClusterStateMinNodes() throws Exception {
        startingTest("StateChangeTest::testClusterStateMinNodes");
        // If node does this too many times, take it out of service
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.maxTransitionTime.put(NodeType.STORAGE, 0);
        options.maxInitProgressTime = 0;
        options.minDistributorNodesUp = 6;
        options.minStorageNodesUp = 8;
        options.minRatioOfDistributorNodesUp = 0.0;
        options.minRatioOfStorageNodesUp = 0.0;

        initialize(options);

        timer.advanceTime(1000000); // Node has been in steady state up

        ctrl.tick();

        assertEquals("version:3 distributor:10 storage:10", ctrl.getSystemState().toString());

        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 0), State.DOWN, "Connection error: Closed at other end");
        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 1), State.DOWN, "Connection error: Closed at other end");
        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 2), State.DOWN, "Connection error: Closed at other end");
        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 3), State.DOWN, "Connection error: Closed at other end");

        communicator.setNodeState(new Node(NodeType.STORAGE, 0), State.DOWN, "Connection error: Closed at other end");
        communicator.setNodeState(new Node(NodeType.STORAGE, 1), State.DOWN, "Connection error: Closed at other end");

        ctrl.tick();

        assertEquals("version:4 distributor:10 .0.s:d .1.s:d .2.s:d .3.s:d storage:10 .0.s:d .1.s:d", ctrl.getSystemState().toString());

        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 4), State.DOWN, "Connection error: Closed at other end");

        ctrl.tick();

        assertEquals("version:5 cluster:d distributor:10 .0.s:d .1.s:d .2.s:d .3.s:d .4.s:d storage:10 .0.s:d .1.s:d", ctrl.getSystemState().toString());

        tick(1000);

        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 4), State.UP, "");

        ctrl.tick();

        assertEquals("version:6 distributor:10 .0.s:d .1.s:d .2.s:d .3.s:d storage:10 .0.s:d .1.s:d", ctrl.getSystemState().toString());

        tick(1000);

        communicator.setNodeState(new Node(NodeType.STORAGE, 2), State.DOWN, "");

        ctrl.tick();

        assertEquals("version:7 cluster:d distributor:10 .0.s:d .1.s:d .2.s:d .3.s:d storage:10 .0.s:d .1.s:d .2.s:d", ctrl.getSystemState().toString());
    }

    @Test
    public void testClusterStateMinFactor() throws Exception {
        startingTest("StateChangeTest::testClusterStateMinFactor");
        // If node does this too many times, take it out of service
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.maxTransitionTime.put(NodeType.STORAGE, 0);
        options.maxInitProgressTime = 0;
        options.minDistributorNodesUp = 0;
        options.minStorageNodesUp = 0;
        options.minRatioOfDistributorNodesUp = 0.6;
        options.minRatioOfStorageNodesUp = 0.8;

        initialize(options);

        timer.advanceTime(1000000); // Node has been in steady state up

        ctrl.tick();

        assertEquals("version:3 distributor:10 storage:10", ctrl.getSystemState().toString());

        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 0), State.DOWN, "Connection error: Closed at other end");
        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 1), State.DOWN, "Connection error: Closed at other end");
        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 2), State.DOWN, "Connection error: Closed at other end");
        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 3), State.DOWN, "Connection error: Closed at other end");

        communicator.setNodeState(new Node(NodeType.STORAGE, 0), State.DOWN, "Connection error: Closed at other end");
        communicator.setNodeState(new Node(NodeType.STORAGE, 1), State.DOWN, "Connection error: Closed at other end");

        ctrl.tick();

        assertEquals("version:4 distributor:10 .0.s:d .1.s:d .2.s:d .3.s:d storage:10 .0.s:d .1.s:d", ctrl.getSystemState().toString());

        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 4), State.DOWN, "Connection error: Closed at other end");

        ctrl.tick();

        assertEquals("version:5 cluster:d distributor:10 .0.s:d .1.s:d .2.s:d .3.s:d .4.s:d storage:10 .0.s:d .1.s:d", ctrl.getSystemState().toString());

        tick(1000);

        communicator.setNodeState(new Node(NodeType.DISTRIBUTOR, 4), State.UP, "");

        ctrl.tick();

        assertEquals("version:6 distributor:10 .0.s:d .1.s:d .2.s:d .3.s:d storage:10 .0.s:d .1.s:d", ctrl.getSystemState().toString());

        tick(1000);

        communicator.setNodeState(new Node(NodeType.STORAGE, 2), State.DOWN, "");

        ctrl.tick();

        assertEquals("version:7 cluster:d distributor:10 .0.s:d .1.s:d .2.s:d .3.s:d storage:10 .0.s:d .1.s:d .2.s:d", ctrl.getSystemState().toString());
    }

    /**
     * Class for testing states of all nodes. Will fail in constructor with
     * debug message on non-expected results.
     */
    abstract class StateMessageChecker {
        StateMessageChecker(final List<DummyVdsNode> nodes) {
            for (final DummyVdsNode node : nodes) {
                final List<ClusterState> states = node.getSystemStatesReceived();
                final StringBuilder debugString = new StringBuilder();
                debugString.append("Node ").append(node).append("\n");
                for (ClusterState state : states) {
                    debugString.append(state.toString()).append("\n");
                }
                assertEquals(debugString.toString(), expectedMessageCount(node), states.size());
            }
        }
        abstract int expectedMessageCount(final DummyVdsNode node);
    }

    @Test
    public void testNoSystemStateBeforeInitialTimePeriod() throws Exception {
        startingTest("StateChangeTest::testNoSystemStateBeforeInitialTimePeriod()");
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.minTimeBeforeFirstSystemStateBroadcast = 3 * 60 * 1000;
        setUpSystem(true, options);
        setUpVdsNodes(true, new DummyVdsNodeOptions(), true);
            // Leave one node down to avoid sending cluster state due to having seen all node states.
        for (int i=0; i<nodes.size(); ++i) {
            if (i != 3) {
                nodes.get(i).connect();
            }
        }
        setUpFleetController(true, options);

        StateWaiter waiter = new StateWaiter(timer);
        fleetController.addSystemStateListener(waiter);

            // Ensure all nodes have been seen by fleetcontroller and that it has had enough time to possibly have sent a cluster state
        waiter.waitForState("version:\\d+ distributor:10 (\\.\\d+\\.t:\\d+ )*storage:10 (\\.\\d+\\.t:\\d+ )*.1.s:d( \\.\\d+\\.t:\\d+)*", timeoutMS);
        waitForCompleteCycle();
        new StateMessageChecker(nodes) {
            @Override int expectedMessageCount(final DummyVdsNode node) { return 0; }
        };

            // Pass time and see that the nodes get state
        timer.advanceTime(3 * 60 * 1000);
        waiter.waitForState("version:\\d+ distributor:10 storage:10 .1.s:d", timeoutMS);

        int version = waiter.getCurrentSystemState().getVersion();
        fleetController.waitForNodesHavingSystemStateVersionEqualToOrAbove(version, 19, timeoutMS);

        new StateMessageChecker(nodes) {
            @Override int expectedMessageCount(final DummyVdsNode node) {
                return node.getNode().equals(new Node(NodeType.STORAGE, 1)) ? 0 : 2;
            }
        };
        assertEquals(version, waiter.getCurrentSystemState().getVersion());
    }

    @Test
    public void testSystemStateSentWhenNodesReplied() throws Exception {
        startingTest("StateChangeTest::testSystemStateSentWhenNodesReplied()");
        final FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.minTimeBeforeFirstSystemStateBroadcast = 300 * 60 * 1000;

        setUpSystem(true, options);

        setUpVdsNodes(true, new DummyVdsNodeOptions(), true);

        for (int i=0; i<nodes.size(); ++i) {
            nodes.get(i).connect();
        }
        // Marking one node as 'initializing' improves testing of state later on.
        nodes.get(3).setNodeState(State.INITIALIZING);

        setUpFleetController(true, options);

        final StateWaiter waiter = new StateWaiter(timer);

        fleetController.addSystemStateListener(waiter);
        waiter.waitForState("version:\\d+ distributor:10 storage:10 .1.s:i .1.i:1.0", timeoutMS);
        waitForCompleteCycle();

        final int version = waiter.getCurrentSystemState().getVersion();
        fleetController.waitForNodesHavingSystemStateVersionEqualToOrAbove(version, 20, timeoutMS);

        // The last two versions of the cluster state should be seen (all nodes up,
        // zero out timestate)
        new StateMessageChecker(nodes) {
            @Override int expectedMessageCount(final DummyVdsNode node) { return 2; }
        };
    }

    @Test
    public void testDontTagFailingSetSystemStateOk() throws Exception {
        startingTest("StateChangeTest::testDontTagFailingSetSystemStateOk()");
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        setUpFleetController(true, options);
        setUpVdsNodes(true, new DummyVdsNodeOptions());
        waitForStableSystem();

        StateWaiter waiter = new StateWaiter(timer);
        fleetController.addSystemStateListener(waiter);

        nodes.get(1).failSetSystemState(true);
        int versionBeforeChange = nodes.get(1).getSystemStatesReceived().get(0).getVersion();
        nodes.get(2).disconnect(); // cause a new state
        waiter.waitForState("version:\\d+ distributor:10 .1.s:d storage:10", timeoutMS);
        int versionAfterChange = waiter.getCurrentSystemState().getVersion();
        assertTrue(versionAfterChange > versionBeforeChange);
        fleetController.waitForNodesHavingSystemStateVersionEqualToOrAbove(versionAfterChange, 18, timeoutMS);

        // Assert that the failed node has not acknowledged the latest version.
        // (The version may still be larger than versionBeforeChange if the fleet controller sends a
        // "stable system" update without timestamps in the meantime
        assertTrue(fleetController.getCluster().getNodeInfo(nodes.get(1).getNode()).getSystemStateVersionAcknowledged() < versionAfterChange);

        // Ensure non-concurrent access to getNewestSystemStateVersionSent
        synchronized(timer) {
            int sentVersion = fleetController.getCluster().getNodeInfo(nodes.get(1).getNode()).getNewestSystemStateVersionSent();
            assertTrue(sentVersion == -1 || sentVersion == versionAfterChange);
        }
    }

    @Test
    public void testAlteringDistributionSplitCount() throws Exception {
        startingTest("StateChangeTest::testAlteringDistributionSplitCount");
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.distributionBits = 17;

        initialize(options);

        timer.advanceTime(1000000); // Node has been in steady state up

        ctrl.tick();

        setMinUsedBitsForAllNodes(15);

        ctrl.tick();

        assertEquals("version:4 bits:15 distributor:10 storage:10", ctrl.getSystemState().toString());

        tick(1000);

        communicator.setNodeState(new Node(NodeType.STORAGE, 0), new NodeState(NodeType.STORAGE, State.UP).setMinUsedBits(13), "");

        ctrl.tick();

        assertEquals("version:5 bits:13 distributor:10 storage:10", ctrl.getSystemState().toString());

        tick(1000);
        setMinUsedBitsForAllNodes(16);
        ctrl.tick();

        // Don't increase dist bits until we've reached at least the wanted
        // level, in order to avoid multiple full redistributions of data.
        assertEquals("version:5 bits:13 distributor:10 storage:10", ctrl.getSystemState().toString());

        tick(1000);
        setMinUsedBitsForAllNodes(19);
        ctrl.tick();

        assertEquals("version:6 bits:17 distributor:10 storage:10", ctrl.getSystemState().toString());
    }

    private void setMinUsedBitsForAllNodes(int bits) throws Exception {
        for (int i = 0; i < 10; ++i) {
            communicator.setNodeState(new Node(NodeType.STORAGE, i), new NodeState(NodeType.STORAGE, State.UP).setMinUsedBits(bits), "");
        }
    }

    @Test
    public void testSetAllTimestampsAfterDowntime() throws Exception {
        startingTest("StateChangeTest::testSetAllTimestampsAfterDowntime");
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        setUpFleetController(true, options);
        setUpVdsNodes(true, new DummyVdsNodeOptions());
        waitForStableSystem();

        StateWaiter waiter = new StateWaiter(timer);
        fleetController.addSystemStateListener(waiter);

        // Simulate netsplit. Take node down without node booting
        assertEquals(true, nodes.get(0).isDistributor());
        nodes.get(0).disconnectImmediately();
        waiter.waitForState("version:\\d+ distributor:10 .0.s:d storage:10", timeoutMS);

        // Add node back.
        nodes.get(0).connect();
        waitForStableSystem();

        // At this time, node taken down should have cluster states with all starting timestamps set. Others node should not.
        for (DummyVdsNode node : nodes) {
            node.waitForSystemStateVersion(waiter.getCurrentSystemState().getVersion(), timeoutMS);
            List<ClusterState> states = node.getSystemStatesReceived();
            ClusterState lastState = states.get(0);
            StringBuilder stateHistory = new StringBuilder();
            for (ClusterState state : states) {
                stateHistory.append(state.toString()).append("\n");
            }

            if (node.getNode().equals(new Node(NodeType.DISTRIBUTOR, 0))) {
                for (ConfiguredNode i : options.nodes) {
                    Node nodeId = new Node(NodeType.STORAGE, i.index());
                    long ts = lastState.getNodeState(nodeId).getStartTimestamp();
                    assertTrue(nodeId + "\n" + stateHistory + "\nWas " + ts + " should be " + fleetController.getCluster().getNodeInfo(nodeId).getStartTimestamp(), ts > 0);
                }
            } else {
                for (ConfiguredNode i : options.nodes) {
                    Node nodeId = new Node(NodeType.STORAGE, i.index());
                    assertTrue(nodeId.toString(), lastState.getNodeState(nodeId).getStartTimestamp() == 0);
                }
            }

            for (ConfiguredNode i : options.nodes) {
                Node nodeId = new Node(NodeType.DISTRIBUTOR, i.index());
                assertTrue(nodeId.toString(), lastState.getNodeState(nodeId).getStartTimestamp() == 0);
            }
        }
    }

    @Test
    public void consolidated_cluster_state_reflects_node_changes_when_cluster_is_down() throws Exception {
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.maxTransitionTime.put(NodeType.STORAGE, 0);
        options.minStorageNodesUp = 10;
        options.minDistributorNodesUp = 10;
        initialize(options);

        ctrl.tick();
        assertThat(ctrl.consolidatedClusterState().toString(), equalTo("version:3 distributor:10 storage:10"));

        communicator.setNodeState(new Node(NodeType.STORAGE, 2), State.DOWN, "foo");
        ctrl.tick();

        assertThat(ctrl.consolidatedClusterState().toString(),
                   equalTo("version:4 cluster:d distributor:10 storage:10 .2.s:d"));

        // After this point, any further node changes while the cluster is still down won't be published.
        // This is because cluster state similarity checks are short-circuited if both are Down, as no other parts
        // of the state matter. Despite this, REST API access and similar features need up-to-date information,
        // and therefore need to get a state which represents the _current_ state rather than the published state.
        // The consolidated state offers this by selectively generating the current state on-demand if the
        // cluster is down.
        communicator.setNodeState(new Node(NodeType.STORAGE, 5), State.DOWN, "bar");
        ctrl.tick();

        // NOTE: _same_ version, different node state content. Overall cluster down-state is still the same.
        assertThat(ctrl.consolidatedClusterState().toString(),
                   equalTo("version:4 cluster:d distributor:10 storage:10 .2.s:d .5.s:d"));
    }

    // Related to the above test, watchTimer invocations must receive the _current_ state and not the
    // published state. Failure to ensure this would cause events to be fired non-stop, as the effect
    // of previous timer invocations (with subsequent state generation) would not be visible.
    @Test
    public void timer_events_during_cluster_down_observe_most_recent_node_changes() throws Exception {
        FleetControllerOptions options = new FleetControllerOptions("mycluster", createNodes(10));
        options.maxTransitionTime.put(NodeType.STORAGE, 1000);
        options.minStorageNodesUp = 10;
        options.minDistributorNodesUp = 10;
        initialize(options);

        ctrl.tick();
        communicator.setNodeState(new Node(NodeType.STORAGE, 2), State.DOWN, "foo");
        timer.advanceTime(500);
        ctrl.tick();
        communicator.setNodeState(new Node(NodeType.STORAGE, 3), State.DOWN, "foo");
        ctrl.tick();
        assertThat(ctrl.consolidatedClusterState().toString(), equalTo("version:4 cluster:d distributor:10 storage:10 .2.s:m .3.s:m"));

        // Subsequent timer tick should _not_ trigger additional events. Providing published state
        // only would result in "Marking node down" events for node 2 emitted per tick.
        for (int i = 0; i < 3; ++i) {
            timer.advanceTime(5000);
            ctrl.tick();
        }

        verifyNodeEvents(new Node(NodeType.STORAGE, 2),
                "Event: storage.2: Now reporting state U\n" +
                "Event: storage.2: Altered node state in cluster state from 'D: Node not seen in slobrok.' to 'U'\n" +
                "Event: storage.2: Failed to get node state: D: foo\n" +
                "Event: storage.2: Stopped or possibly crashed after 500 ms, which is before stable state time period. Premature crash count is now 1.\n" +
                "Event: storage.2: Altered node state in cluster state from 'U' to 'M: foo'\n" +
                "Event: storage.2: 5000 milliseconds without contact. Marking node down.\n");
    }

}
