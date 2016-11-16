package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.config.provision.NodeType;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.zookeeper.ZooKeeperServer;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Maintains the list of hosts that should be allowed to access ZooKeeper in this runtime.
 * These are the zokeeper servers and all tenant and proxy nodes. This is maintained in the background because
 * nodes could be added or removed on another server. 
 * 
 * We could limit access to the <i>active</i> subset of nodes, but that 
 * does not seem to have any particular operational or security benefits and might make it more problematic
 * for this job to be behind actual changes to the active set of nodes.
 * 
 * @author bratseth
 */
public class ZooKeeperAccessMaintainer extends Maintainer {

    private final Curator curator;
    
    public ZooKeeperAccessMaintainer(NodeRepository nodeRepository, Curator curator, Duration maintenanceInterval) {
        super(nodeRepository, maintenanceInterval);
        this.curator = curator;
    }
    
    @Override
    protected void maintain() {
        Set<String> hosts = new HashSet<>();

        for (Node node : nodeRepository().getNodes(NodeType.tenant))
            hosts.add(node.hostname());
        for (Node node : nodeRepository().getNodes(NodeType.proxy))
            hosts.add(node.hostname());
        for (Node node : nodeRepository().getNodes(NodeType.host))
            hosts.add(node.hostname());
        for (String hostPort : curator.connectionSpec().split(","))
            hosts.add(hostPort.split(":")[0]);

        ZooKeeperServer.setAllowedClientHostnames(hosts);
    }

    @Override
    public String toString() {
        return "ZooKeeper access maintainer";
    }

}
