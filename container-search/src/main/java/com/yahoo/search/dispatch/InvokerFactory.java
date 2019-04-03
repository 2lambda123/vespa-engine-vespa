// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.dispatch;

import com.yahoo.prelude.Pong;
import com.yahoo.prelude.fastsearch.VespaBackEndSearcher;
import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.cluster.ClusterMonitor;
import com.yahoo.search.dispatch.searchcluster.Node;
import com.yahoo.search.dispatch.searchcluster.SearchCluster;
import com.yahoo.search.result.Coverage;
import com.yahoo.search.result.ErrorMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * @author ollivir
 */
public abstract class InvokerFactory {
    protected final SearchCluster searchCluster;

    public InvokerFactory(SearchCluster searchCluster) {
        this.searchCluster = searchCluster;
    }

    protected abstract Optional<SearchInvoker> createNodeSearchInvoker(VespaBackEndSearcher searcher, Query query, Node node);

    public abstract Optional<FillInvoker> createFillInvoker(VespaBackEndSearcher searcher, Result result);

    public abstract Callable<Pong> createPinger(Node node, ClusterMonitor<Node> monitor);

    /**
     * Create a {@link SearchInvoker} for a list of content nodes.
     *
     * @param searcher
     *            the searcher processing the query
     * @param query
     *            the search query being processed
     * @param groupId
     *            the id of the node group to which the nodes belong
     * @param nodes
     *            pre-selected list of content nodes
     * @param acceptIncompleteCoverage
     *            if some of the nodes are unavailable and this parameter is
     *            <b>false</b>, verify that the remaining set of nodes has enough
     *            coverage
     * @return Optional containing the SearchInvoker or <i>empty</i> if some node in the
     *         list is invalid and the remaining coverage is not sufficient
     */
    public Optional<SearchInvoker> createSearchInvoker(VespaBackEndSearcher searcher, Query query, OptionalInt groupId, List<Node> nodes,
            boolean acceptIncompleteCoverage) {
        List<SearchInvoker> invokers = new ArrayList<>(nodes.size());
        Set<Integer> failed = null;
        for (Node node : nodes) {
            boolean nodeAdded = false;
            if (node.isWorking()) {
                Optional<SearchInvoker> invoker = createNodeSearchInvoker(searcher, query, node);
                if(invoker.isPresent()) {
                    invokers.add(invoker.get());
                    nodeAdded = true;
                }
            }

            if (!nodeAdded) {
                if (failed == null) {
                    failed = new HashSet<>();
                }
                failed.add(node.key());
            }
        }

        if (failed != null) {
            List<Node> success = new ArrayList<>(nodes.size() - failed.size());
            for (Node node : nodes) {
                if (!failed.contains(node.key())) {
                    success.add(node);
                }
            }
            if (!searchCluster.isPartialGroupCoverageSufficient(groupId, success) && !acceptIncompleteCoverage) {
                return Optional.empty();
            }
            if(invokers.size() == 0) {
                return Optional.of(createCoverageErrorInvoker(nodes, failed));
            }
        }

        if (invokers.size() == 1 && failed == null) {
            return Optional.of(invokers.get(0));
        } else {
            return Optional.of(new InterleavedSearchInvoker(invokers, searchCluster, failed));
        }
    }

    protected static SearchInvoker createCoverageErrorInvoker(List<Node> nodes, Set<Integer> failed) {
        StringBuilder down = new StringBuilder("Connection failure on nodes with distribution-keys: ");
        int count = 0;
        for (Node node : nodes) {
            if (failed.contains(node.key())) {
                if (count > 0) {
                    down.append(", ");
                }
                count++;
                down.append(node.key());
            }
        }
        Coverage coverage = new Coverage(0, 0, 0);
        coverage.setNodesTried(count);
        return new SearchErrorInvoker(ErrorMessage.createBackendCommunicationError(down.toString()), coverage);
    }
}
