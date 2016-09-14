package com.yahoo.vespa.hosted.provision;

import com.yahoo.test.ManualClock;
import com.yahoo.vespa.config.nodes.NodeRepositoryConfig;
import com.yahoo.vespa.curator.mock.MockCurator;
import com.yahoo.vespa.hosted.provision.node.Flavor;
import com.yahoo.vespa.hosted.provision.node.NodeFlavors;
import com.yahoo.vespa.hosted.provision.testutils.FlavorConfigBuilder;

import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * @author bratseth
 */
public class NodeRepositoryTester {

    private final NodeFlavors nodeFlavors;
    private final NodeRepository nodeRepository;
    private final Clock clock;
    private final MockCurator curator;
    
    
    public NodeRepositoryTester() {
        nodeFlavors = new NodeFlavors(createConfig());
        clock = new ManualClock();
        curator = new MockCurator();
        curator.setConnectionSpec("server1:1234,server2:5678");
        nodeRepository = new NodeRepository(nodeFlavors, curator, clock);
    }
    
    public NodeRepository nodeRepository() { return nodeRepository; }
    public MockCurator curator() { return curator; }
    
    public List<Node> getNodes(Node.Type type, Node.State ... inState) {
        return nodeRepository.getNodes(type, inState);
    }
    
    public Node addNode(String id, String hostname, String flavor, Node.Type type) {
        Node node = nodeRepository.createNode(id, hostname, Optional.empty(), 
                                              nodeFlavors.getFlavorOrThrow(flavor), type);
        return nodeRepository.addNodes(Collections.singletonList(node)).get(0);
    }

    private NodeRepositoryConfig createConfig() {
        FlavorConfigBuilder b = new FlavorConfigBuilder();
        b.addFlavor("default", 2., 4., 100, Flavor.Type.BARE_METAL).cost(3);
        b.addFlavor("small", 1., 2., 50, Flavor.Type.BARE_METAL).cost(2);
        return b.build();
    }

}
