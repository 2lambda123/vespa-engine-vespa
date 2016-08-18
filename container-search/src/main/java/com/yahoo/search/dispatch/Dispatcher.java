// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.dispatch;

import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.yahoo.collections.ListMap;
import com.yahoo.component.AbstractComponent;
import com.yahoo.compress.CompressionType;
import com.yahoo.compress.Compressor;
import com.yahoo.data.access.slime.SlimeAdapter;
import com.yahoo.prelude.fastsearch.FS4ResourcePool;
import com.yahoo.prelude.fastsearch.FastHit;
import com.yahoo.prelude.fastsearch.TimeoutException;
import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.result.ErrorMessage;
import com.yahoo.search.result.Hit;
import com.yahoo.slime.BinaryFormat;
import com.yahoo.slime.Cursor;
import com.yahoo.slime.Slime;
import com.yahoo.data.access.Inspector;
import com.yahoo.vespa.config.search.DispatchConfig;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A dispatcher communicates with search nodes to (in the future) perform queries and (now) fill hits.
 * This class is multithread safe.
 *
 * @author bratseth
 */
@Beta
public class Dispatcher extends AbstractComponent {

    private final static Logger log = Logger.getLogger(Dispatcher.class.getName());
    private final Client client;

    /** A model of the search cluster this dispatches to */
    private final SearchCluster searchCluster;
    
    /** Connections to the search nodes this talks to, indexed by node id ("partid") */
    private final ImmutableMap<Integer, Client.NodeConnection> nodeConnections;

    private final Compressor compressor = new Compressor();

    @Inject
    public Dispatcher(DispatchConfig dispatchConfig, FS4ResourcePool fs4ResourcePool) {
        this.client = new RpcClient();
        this.searchCluster = new SearchCluster(dispatchConfig, fs4ResourcePool);

        // Create node rpc connections, indexed by the legacy "partid", which allows us to bridge
        // between fs4 calls (for search) and rpc calls (for summary fetch)
        ImmutableMap.Builder<Integer, Client.NodeConnection> nodeConnectionsBuilder = new ImmutableMap.Builder<>();
        for (DispatchConfig.Node node : dispatchConfig.node()) {
            nodeConnectionsBuilder.put(node.key(), client.createConnection(node.host(), node.port()));
        }
        nodeConnections = nodeConnectionsBuilder.build();
    }

    /** For testing */
    public Dispatcher(Map<Integer, Client.NodeConnection> nodeConnections, Client client) {
        this.searchCluster = null;
        this.nodeConnections = ImmutableMap.copyOf(nodeConnections);
        this.client = client;
    }
    
    /** Returns the search cluster this dispatches to */
    public SearchCluster searchCluster() { return searchCluster; }

    /** Fills the given summary class by sending RPC requests to the right search nodes */
    public void fill(Result result, String summaryClass, CompressionType compression) {
        try {
            ListMap<Integer, FastHit> hitsByNode = hitsByNode(result);

            GetDocsumsResponseReceiver responseReceiver = new GetDocsumsResponseReceiver(hitsByNode.size(), compressor, result);
            for (Map.Entry<Integer, List<FastHit>> nodeHits : hitsByNode.entrySet()) {
                sendGetDocsumsRequest(nodeHits.getKey(), nodeHits.getValue(), summaryClass, compression, result, responseReceiver);
            }
            responseReceiver.processResponses(result.getQuery());
        }
        catch (TimeoutException e) {
            result.hits().addError(ErrorMessage.createTimeout("Summary data is incomplete: " + e.getMessage()));
        }
    }

    /** Return a map of hits by their search node (partition) id */
    private ListMap<Integer, FastHit> hitsByNode(Result result) {
        ListMap<Integer, FastHit> hitsByPartition = new ListMap<>();
        for (Iterator<Hit> i = result.hits().deepIterator() ; i.hasNext(); ) {
            Hit h = i.next();
            if ( ! (h instanceof FastHit)) continue;
            FastHit hit = (FastHit)h;

            hitsByPartition.put(hit.getDistributionKey(), hit);
        }
        return hitsByPartition;
    }

    /** Send a getDocsums request to a node. Responses will be added to the given receiver. */
    private void sendGetDocsumsRequest(int nodeId, List<FastHit> hits, String summaryClass,
                                       CompressionType compression,
                                       Result result, GetDocsumsResponseReceiver responseReceiver) {
        Client.NodeConnection node = nodeConnections.get(nodeId);
        if (node == null) {
            result.hits().addError(ErrorMessage.createEmptyDocsums("Could not fill hits from unknown node " + nodeId));
            log.warning("Got hits with partid " + nodeId + ", which is not included in the current dispatch config");
            return;
        }

        byte[] serializedSlime = BinaryFormat.encode(toSlime(summaryClass, hits));
        double timeoutSeconds = ((double)result.getQuery().getTimeLeft()-3.0)/1000.0;
        Compressor.Compression compressionResult = compressor.compress(compression, serializedSlime);
        client.getDocsums(hits, node, compressionResult.type(),
                          serializedSlime.length, compressionResult.data(), responseReceiver, timeoutSeconds);
    }

    public Slime toSlime(String summaryClass, List<FastHit> hits) {
        Slime slime = new Slime();
        Cursor root = slime.setObject();
        if (summaryClass != null) {
            root.setString("class", summaryClass);
        }
        Cursor gids = root.setArray("gids");
        for (FastHit hit : hits) {
            gids.addData(hit.getGlobalId().getRawId());
        }
        return slime;
    }

    @Override
    public void deconstruct() {
        for (Client.NodeConnection nodeConnection : nodeConnections.values())
            nodeConnection.close();
    }

    /** Receiver of the responses to a set of getDocsums requests */
    public static class GetDocsumsResponseReceiver {

        private final BlockingQueue<Client.GetDocsumsResponseOrError> responses;
        private final Compressor compressor;
        private final Result result;

        /** Whether we have already logged/notified about an error - to avoid spamming */
        private boolean hasReportedError = false;

        /** The number of responses we should receive (and process) before this is complete */
        private int outstandingResponses;

        public GetDocsumsResponseReceiver(int requestCount, Compressor compressor, Result result) {
            this.compressor = compressor;
            responses = new LinkedBlockingQueue<>(requestCount);
            outstandingResponses = requestCount;
            this.result = result;
        }

        /** Called by a thread belonging to the client when a valid response becomes available */
        public void receive(Client.GetDocsumsResponseOrError response) {
            responses.add(response);
        }

        private void throwTimeout() throws TimeoutException {
            throw new TimeoutException("Timed out waiting for summary data. " + outstandingResponses + " responses outstanding.");
        }

        /**
         * Call this from the dispatcher thread to initiate and complete processing of responses.
         * This will block until all responses are available and processed, or to timeout.
         */
        public void processResponses(Query query) throws TimeoutException {
            try {
                while (outstandingResponses > 0) {
                    long timeLeftMs = query.getTimeLeft();
                    if (timeLeftMs <= 0) {
                        throwTimeout();
                    }
                    Client.GetDocsumsResponseOrError response = responses.poll(timeLeftMs, TimeUnit.MILLISECONDS);
                    if (response == null)
                        throwTimeout();
                    processResponse(response);
                    outstandingResponses--;
                }
            }
            catch (InterruptedException e) {
                // TODO: Add error
            }
        }

        private void processResponse(Client.GetDocsumsResponseOrError responseOrError) {
            if (responseOrError.error().isPresent()) {
                if (hasReportedError) return;
                String error = responseOrError.error().get();
                result.hits().addError(ErrorMessage.createBackendCommunicationError(error));
                log.log(Level.WARNING, "Error fetching summary data: "+ error);
            }
            else {
                Client.GetDocsumsResponse response = responseOrError.response().get();
                CompressionType compression = CompressionType.valueOf(response.compression());
                byte[] slimeBytes = compressor.decompress(response.compressedSlimeBytes(), compression, response.uncompressedSize());
                fill(response.hitsContext(), slimeBytes);
            }
        }

        private void fill(List<FastHit> hits, byte[] slimeBytes) {
            Inspector summaries = new SlimeAdapter(BinaryFormat.decode(slimeBytes).get().field("docsums"));
            if ( ! summaries.valid())
                throw new IllegalArgumentException("Expected a Slime root object containing a 'docsums' field");
            for (int i = 0; i < hits.size(); i++) {
                fill(hits.get(i), summaries.entry(i).field("docsum"));
            }
        }

        private void fill(FastHit hit, Inspector summary) {
            summary.traverse((String name, Inspector value) -> {
                hit.setField(name, nativeTypeOf(value));
            });
        }

        private Object nativeTypeOf(Inspector inspector) {
            switch (inspector.type()) {
                case ARRAY: return inspector;
                case OBJECT: return inspector;
                case BOOL: return inspector.asBool();
                case DATA: return inspector.asData();
                case DOUBLE: return inspector.asDouble();
                case LONG: return inspector.asLong();
                case STRING: return inspector.asString(); // TODO: Keep as utf8
                case EMPTY : return null;
                default: throw new IllegalArgumentException("Unexpected Slime type " + inspector.type());
            }
        }

    }

}
