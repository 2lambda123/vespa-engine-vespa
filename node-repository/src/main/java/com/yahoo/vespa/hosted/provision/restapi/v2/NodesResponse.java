// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.restapi.v2;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.slime.Cursor;
import com.yahoo.slime.Slime;
import com.yahoo.vespa.config.SlimeUtils;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.node.Allocation;
import com.yahoo.vespa.hosted.provision.node.History;
import com.yahoo.vespa.hosted.provision.node.filter.NodeFilter;
import com.yahoo.vespa.hosted.provision.restapi.NodeStateSerializer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
* @author bratseth
*/
class NodesResponse extends HttpResponse {

    /** The responses this can create */
    public enum ResponseType { nodeList, stateList, nodesInStateList, singleNode }

    /** The request url minus parameters, with a trailing slash added if missing */
    private final String parentUrl;

    /** The parent url of nodes */
    private final String nodeParentUrl;

    private final NodeFilter filter;
    private final boolean recursive;
    private final NodeRepository nodeRepository;

    private final Slime slime;

    public NodesResponse(ResponseType responseType, HttpRequest request, NodeRepository nodeRepository) {
        super(200);
        this.parentUrl = toParentUrl(request);
        this.nodeParentUrl = toNodeParentUrl(request);
        filter = NodesApiHandler.toNodeFilter(request);
        this.recursive = request.getBooleanProperty("recursive");
        this.nodeRepository = nodeRepository;

        slime = new Slime();
        Cursor root = slime.setObject();
        switch (responseType) {
            case nodeList: nodesToSlime(root); break;
            case stateList : statesToSlime(root); break;
            case nodesInStateList: nodesToSlime(stateFromString(lastElement(parentUrl)), root); break;
            case singleNode : nodeToSlime(lastElement(parentUrl), root); break;
            default: throw new IllegalArgumentException();
        }
    }

    private String toParentUrl(HttpRequest request) {
        URI uri = request.getUri();
        String parentUrl = uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
        if ( ! parentUrl.endsWith("/"))
            parentUrl = parentUrl + "/";
        return parentUrl;
    }

    private String toNodeParentUrl(HttpRequest request) {
        URI uri = request.getUri();
        return uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort() + "/nodes/v2/node/";
    }

    @Override
    public void render(OutputStream stream) throws IOException {
        stream.write(toJson());
    }

    @Override
    public String getContentType() {
        return "application/json";
    }

    private byte[] toJson() throws IOException {
        return SlimeUtils.toJsonBytes(slime);
    }

    private void statesToSlime(Cursor root) {
        Cursor states = root.setObject("states");
        for (Node.State state : Node.State.values())
            toSlime(state, states.setObject(NodeStateSerializer.wireNameOf(state)));
    }

    private void toSlime(Node.State state, Cursor object) {
        object.setString("url", parentUrl + NodeStateSerializer.wireNameOf(state));
        if (recursive)
            nodesToSlime(state, object);
    }

    /** Outputs the nodes in the given state to a node array */
    private void nodesToSlime(Node.State state, Cursor parentObject) {
        Cursor nodeArray = parentObject.setArray("nodes");
        for (Node.Type type : Node.Type.values())
            toSlime(nodeRepository.getNodes(type, state), nodeArray);
    }

    /** Outputs all the nodes to a node array */
    private void nodesToSlime(Cursor parentObject) {
        Cursor nodeArray = parentObject.setArray("nodes");
        for (Node.State state : Node.State.values()) {
            for (Node.Type type : Node.Type.values())
                toSlime(nodeRepository.getNodes(type, state), nodeArray);
        }
    }

    private void toSlime(List<Node> nodes, Cursor array) {
        for (Node node : nodes) {
            if ( ! filter.matches(node)) continue;
            toSlime(node, recursive, array.addObject());
        }
    }

    private void nodeToSlime(String hostname, Cursor object) {
        Optional<Node> node = nodeRepository.getNode(hostname);
        if (! node.isPresent())
            throw new IllegalArgumentException("No node with hostname '" + hostname + "'");
        toSlime(node.get(), true, object);
    }

    private void toSlime(Node node, boolean allFields, Cursor object) {
        object.setString("url", nodeParentUrl + node.hostname());
        if ( ! allFields) return;
        object.setString("id", node.id());
        object.setString("state", NodeStateSerializer.wireNameOf(node.state()));
        object.setString("type", node.type().name());
        object.setString("hostname", node.hostname());
        object.setString("type", toString(node.type()));
        if (node.parentHostname().isPresent()) {
            object.setString("parentHostname", node.parentHostname().get());
        }
        object.setString("openStackId", node.openStackId());
        object.setString("flavor", node.configuration().flavor().name());
        if (node.configuration().flavor().getMinDiskAvailableGb() > 0) {
            object.setDouble("minDiskAvailableGb", node.configuration().flavor().getMinDiskAvailableGb());
        }
        if (node.configuration().flavor().getMinMainMemoryAvailableGb() > 0) {
            object.setDouble("minMainMemoryAvailableGb", node.configuration().flavor().getMinMainMemoryAvailableGb());
        }
        if (node.configuration().flavor().getDescription() != null && ! node.configuration().flavor().getDescription().isEmpty()) {
            object.setString("description", node.configuration().flavor().getDescription());
        }
        if (node.configuration().flavor().getMinCpuCores() > 0) {
            object.setDouble("minCpuCores", node.configuration().flavor().getMinCpuCores());
        }
        object.setString("canonicalFlavor", node.configuration().flavor().canonicalName());
        if (node.configuration().flavor().cost() > 0) {
            object.setLong("cost", node.configuration().flavor().cost());
        }
        if (node.configuration().flavor().getEnvironment() != null && ! node.configuration().flavor().getEnvironment().isEmpty()) {
            object.setString("environment", node.configuration().flavor().getEnvironment());
        }
        Optional<Allocation> allocation = node.allocation();
        if (allocation.isPresent()) {
            toSlime(allocation.get().owner(), object.setObject("owner"));
            toSlime(allocation.get().membership(), object.setObject("membership"));
            object.setLong("restartGeneration", allocation.get().restartGeneration().wanted());
            object.setLong("currentRestartGeneration", allocation.get().restartGeneration().current());
            allocation.get().membership().cluster().dockerImage().ifPresent(
                    image -> object.setString("wantedDockerImage", image));
        }
        object.setLong("rebootGeneration", node.status().reboot().wanted());
        object.setLong("currentRebootGeneration", node.status().reboot().current());
        node.status().vespaVersion().ifPresent(version -> object.setString("vespaVersion", version.toString()));
        node.status().hostedVersion().ifPresent(version -> object.setString("hostedVersion", version.toString()));
        node.status().dockerImage().ifPresent(image -> object.setString("currentDockerImage", image));
        node.status().stateVersion().ifPresent(version -> object.setString("convergedStateVersion", version));
        object.setLong("failCount", node.status().failCount());
        object.setBool("hardwareFailure", node.status().hardwareFailure());
        toSlime(node.history(), object.setArray("history"));
    }

    private String toString(Node.Type type) {
        switch(type) {
            case tenant: return "tenant";
            case host: return "host";
            default:
                throw new RuntimeException("New type added to enum, not implemented in NodesResponse: " + type.name());
        }
    }

    private void toSlime(ApplicationId id, Cursor object) {
        object.setString("tenant", id.tenant().value());
        object.setString("application", id.application().value());
        object.setString("instance", id.instance().value());
    }

    private void toSlime(ClusterMembership membership, Cursor object) {
        object.setString("clustertype", membership.cluster().type().name());
        object.setString("clusterid", membership.cluster().id().value());
        object.setString("group", membership.cluster().group().get().value());
        object.setLong("index", membership.index());
        object.setBool("retired", membership.retired());
    }

    private void toSlime(History history, Cursor array) {
        for (History.Event event : history.events()) {
            Cursor object = array.addObject();
            object.setString("event", event.type().name());
            object.setLong("at", event.at().toEpochMilli());
        }
    }

    private String lastElement(String path) {
        if (path.endsWith("/"))
            path = path.substring(0, path.length()-1);
        int lastSlash = path.lastIndexOf("/");
        if (lastSlash < 0) return path;
        return path.substring(lastSlash+1, path.length());
    }

    private static Node.State stateFromString(String stateString) {
        return NodeStateSerializer.fromWireName(stateString)
                .orElseThrow(() -> new RuntimeException("Node state '" + stateString + "' is not known"));
    }

}
