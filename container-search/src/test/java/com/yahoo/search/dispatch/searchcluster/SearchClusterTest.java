package com.yahoo.search.dispatch.searchcluster;

import com.yahoo.container.QrSearchersConfig;
import com.yahoo.container.handler.ClustersStatus;
import com.yahoo.container.handler.VipStatus;
import com.yahoo.net.HostName;
import com.yahoo.prelude.Pong;
import com.yahoo.search.cluster.ClusterMonitor;
import com.yahoo.search.dispatch.MockSearchCluster;
import com.yahoo.search.result.ErrorMessage;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author baldersheim
 */
public class SearchClusterTest {

    static class State {
        class MyExecutor implements Executor {
            private final List<Runnable> list = new ArrayList<>();
            @Override
            public void execute(@NotNull Runnable command) {
                list.add(command);
            }
            void run() {
                for (Runnable runnable : list) {
                    runnable.run();
                }
                list.clear();
            }
        }
        final String clusterId;
        final int nodesPerGroup;
        final VipStatus vipStatus;
        final SearchCluster searchCluster;
        final List<AtomicInteger> numDocsPerNode;
        List<AtomicInteger> pingCounts;
        State(String clusterId, int nodesPergroup, String ... nodeNames) {
            this(clusterId, nodesPergroup, Arrays.asList(nodeNames));
        }
        State(String clusterId, int nodesPergroup, List<String> nodeNames) {
            this.clusterId = clusterId;
            this.nodesPerGroup = nodesPergroup;
            vipStatus = new VipStatus(new QrSearchersConfig.Builder().searchcluster(new QrSearchersConfig.Searchcluster.Builder().name(clusterId)).build(), new ClustersStatus());
            numDocsPerNode = new ArrayList<>(nodeNames.size());
            pingCounts = new ArrayList<>(nodeNames.size());
            List<Node> nodes = new ArrayList<>(nodeNames.size());

            for (String name : nodeNames) {
                int key = nodes.size() % nodesPergroup;
                int group = nodes.size() / nodesPergroup;
                nodes.add(new Node(key, name, group));
                numDocsPerNode.add(new AtomicInteger(1));
                pingCounts.add(new AtomicInteger(0));
            }
            searchCluster = new SearchCluster(clusterId, MockSearchCluster.createDispatchConfig(nodes), nodes.size() / nodesPergroup, vipStatus);
        }
        void startMonitoring() {
            searchCluster.startClusterMonitoring(new Factory(nodesPerGroup, numDocsPerNode, pingCounts));
        }
        static private int getMaxValue(List<AtomicInteger> list) {
            int max = list.get(0).get();
            for (AtomicInteger v : list) {
                if (v.get() > max) {
                    max = v.get();
                }
            }
            return max;
        }
        private static int getMinValue(List<AtomicInteger> list) {
            int min = list.get(0).get();
            for (AtomicInteger v : list) {
                if (v.get() < min) {
                    min = v.get();
                }
            }
            return min;
        }
        private void waitAtLeast(int atLeast, List<AtomicInteger> list) {
            while (getMinValue(list) < atLeast) {
                ExecutorService executor = Executors.newCachedThreadPool();
                searchCluster.clusterMonitor().ping(executor);
                executor.shutdown();
                try {
                    executor.awaitTermination(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {}
            }
        }
        void waitOneFullPingRound() {
            waitAtLeast(getMaxValue(pingCounts) + 1, pingCounts);
        }
        static class Factory implements PingFactory {
            static class Pinger implements Callable<Pong> {
                private final AtomicInteger numDocs;
                private final AtomicInteger pingCount;
                Pinger(AtomicInteger numDocs, AtomicInteger pingCount) {
                    this.numDocs = numDocs;
                    this.pingCount = pingCount;
                }
                @Override
                public Pong call() throws Exception {
                    int docs = numDocs.get();
                    pingCount.incrementAndGet();
                    return (docs < 0)
                            ? new Pong(ErrorMessage.createBackendCommunicationError("Negative numDocs = " + docs))
                            : new Pong(docs);
                }
            }

            private final List<AtomicInteger> activeDocs;
            private final List<AtomicInteger> pingCounts;
            private final int numPerGroup;
            Factory(int numPerGroup, List<AtomicInteger> activeDocs, List<AtomicInteger> pingCounts) {
                this.numPerGroup = numPerGroup;
                this.activeDocs = activeDocs;
                this.pingCounts = pingCounts;
            }

            @Override
            public Callable<Pong> createPinger(Node node, ClusterMonitor<Node> monitor) {
                int index = node.group() * numPerGroup + node.key();
                return new Pinger(activeDocs.get(index), pingCounts.get(index));
            }
        }
    }

    @Test
    public void requireThatVipStatusIsDefaultDownButComesUpAfterPinging() {
        State test = new State("cluster.1", 2, "a", "b");
        assertTrue(test.searchCluster.localCorpusDispatchTarget().isEmpty());

        assertFalse(test.vipStatus.isInRotation());
        test.startMonitoring();
        test.waitOneFullPingRound();
        assertTrue(test.vipStatus.isInRotation());
    }

    @Test
    public void requireThatZeroDocsAreFine() {
        State test = new State("cluster.1", 2,"a", "b");
        test.startMonitoring();
        test.waitOneFullPingRound();

        assertTrue(test.vipStatus.isInRotation());
        assertTrue(test.searchCluster.localCorpusDispatchTarget().isEmpty());

        test.numDocsPerNode.get(0).set(-1);
        test.numDocsPerNode.get(1).set(-1);
        test.waitOneFullPingRound();
        assertFalse(test.vipStatus.isInRotation());
        test.numDocsPerNode.get(0).set(0);
        test.waitOneFullPingRound();
        assertTrue(test.vipStatus.isInRotation());
    }

    @Test
    public void requireThatVipStatusIsDefaultDownWithLocalDispatch() {
        State test = new State("cluster.1", 1, HostName.getLocalhost(), "b");
        assertTrue(test.searchCluster.localCorpusDispatchTarget().isPresent());

        assertFalse(test.vipStatus.isInRotation());
        test.startMonitoring();
        test.waitOneFullPingRound();
        assertTrue(test.vipStatus.isInRotation());
    }

    @Test
    public void requireThatVipStatusIsDefaultDownWithOnlySingleLocalDispatch() {
        State test = new State("cluster.1", 1, HostName.getLocalhost());
        assertTrue(test.searchCluster.localCorpusDispatchTarget().isPresent());

        assertFalse(test.vipStatus.isInRotation());
        test.startMonitoring();
        test.waitOneFullPingRound();
        assertTrue(test.vipStatus.isInRotation());
        test.numDocsPerNode.get(0).set(-1);
        test.waitOneFullPingRound();
        assertFalse(test.vipStatus.isInRotation());
    }

    @Test
    public void requireThatVipStatusDownWhenLocalIsDown() {
        State test = new State("cluster.1",1,HostName.getLocalhost(), "b");

        test.startMonitoring();
        test.waitOneFullPingRound();
        assertTrue(test.vipStatus.isInRotation());
        assertTrue(test.searchCluster.localCorpusDispatchTarget().isPresent());

        test.waitOneFullPingRound();
        assertTrue(test.vipStatus.isInRotation());
        test.numDocsPerNode.get(0).set(-1);
        test.waitOneFullPingRound();
        assertFalse(test.vipStatus.isInRotation());

        test.numDocsPerNode.get(0).set(1);
        test.waitOneFullPingRound();
        assertTrue(test.vipStatus.isInRotation());

        test.numDocsPerNode.get(1).set(-1);
        test.waitOneFullPingRound();
        assertTrue(test.vipStatus.isInRotation());

        test.numDocsPerNode.get(0).set(-1);
        test.numDocsPerNode.get(1).set(-1);
        test.waitOneFullPingRound();
        assertFalse(test.vipStatus.isInRotation());
        test.numDocsPerNode.get(1).set(1);
        test.waitOneFullPingRound();
        assertFalse(test.vipStatus.isInRotation());
        test.numDocsPerNode.get(0).set(1);
        test.waitOneFullPingRound();
        assertTrue(test.vipStatus.isInRotation());
    }

    private void verifyThatVipStatusDownRequireAllNodesDown(int numGroups, int nodesPerGroup) {
        List<String> nodeNames = generateNodeNames(numGroups, nodesPerGroup);
        State test = new State("cluster.1", nodesPerGroup, nodeNames);
        test.startMonitoring();
        test.waitOneFullPingRound();
        assertTrue(test.vipStatus.isInRotation());
        assertTrue(test.searchCluster.localCorpusDispatchTarget().isEmpty());

        test.waitOneFullPingRound();
        assertTrue(test.vipStatus.isInRotation());

        for (int i=0; i < test.numDocsPerNode.size()-1; i++) {
            test.numDocsPerNode.get(i).set(-1);
        }
        test.waitOneFullPingRound();
        assertTrue(test.vipStatus.isInRotation());
        test.numDocsPerNode.get(test.numDocsPerNode.size()-1).set(-1);
        test.waitOneFullPingRound();
        assertFalse(test.vipStatus.isInRotation());
    }
    @Test
    public void requireThatVipStatusDownRequireAllNodesDown() {
        verifyThatVipStatusDownRequireAllNodesDown(1,2);
        verifyThatVipStatusDownRequireAllNodesDown(3, 3);
    }

    static private List<String> generateNodeNames(int numGroups, int nodesPerGroup) {
        List<String> nodeNames = new ArrayList<>(numGroups*nodesPerGroup);
        for (int g = 0; g < numGroups; g++) {
            for (int n=0; n < nodesPerGroup; n++) {
                nodeNames.add(new StringBuilder("node.").append(g).append('.').append(n).toString());
            }
        }
        return nodeNames;
    }

    private void verifyThatVipStatusUpRequireOnlyOneOnlineNode(int numGroups, int nodesPerGroup) {
        List<String> nodeNames = generateNodeNames(numGroups, nodesPerGroup);
        State test = new State("cluster.1", nodesPerGroup, nodeNames);
        test.startMonitoring();
        test.waitOneFullPingRound();
        assertTrue(test.vipStatus.isInRotation());
        assertTrue(test.searchCluster.localCorpusDispatchTarget().isEmpty());

        for (int i=0; i < test.numDocsPerNode.size()-1; i++) {
            test.numDocsPerNode.get(i).set(-1);
        }
        test.waitOneFullPingRound();
        assertTrue(test.vipStatus.isInRotation());
        test.numDocsPerNode.get(test.numDocsPerNode.size()-1).set(-1);
        test.waitOneFullPingRound();
        assertFalse(test.vipStatus.isInRotation());

        test.numDocsPerNode.get(0).set(0);
        test.waitOneFullPingRound();
        assertTrue(test.vipStatus.isInRotation());
    }
    @Test
    public void requireThatVipStatusUpRequireOnlyOneOnlineNode() {
        verifyThatVipStatusUpRequireOnlyOneOnlineNode(1, 2);
        verifyThatVipStatusUpRequireOnlyOneOnlineNode(3, 3);
    }

}
