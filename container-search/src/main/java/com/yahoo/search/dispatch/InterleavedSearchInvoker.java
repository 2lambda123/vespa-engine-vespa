// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.dispatch;

import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.dispatch.searchcluster.SearchCluster;
import com.yahoo.search.result.Coverage;
import com.yahoo.search.result.ErrorMessage;
import com.yahoo.search.result.Hit;
import com.yahoo.search.result.HitGroup;
import com.yahoo.search.searchchain.Execution;
import com.yahoo.vespa.config.search.DispatchConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.yahoo.container.handler.Coverage.DEGRADED_BY_ADAPTIVE_TIMEOUT;
import static com.yahoo.container.handler.Coverage.DEGRADED_BY_MATCH_PHASE;
import static com.yahoo.container.handler.Coverage.DEGRADED_BY_TIMEOUT;

/**
 * InterleavedSearchInvoker uses multiple {@link SearchInvoker} objects to interface with content
 * nodes in parallel. Operationally it first sends requests to all contained invokers and then
 * collects the results. The user of this class is responsible for merging the results if needed.
 *
 * @author ollivir
 */
public class InterleavedSearchInvoker extends SearchInvoker implements ResponseMonitor<SearchInvoker> {
    private static final Logger log = Logger.getLogger(InterleavedSearchInvoker.class.getName());

    private final Set<SearchInvoker> invokers;
    private final SearchCluster searchCluster;
    private final LinkedBlockingQueue<SearchInvoker> availableForProcessing;
    private final Set<Integer> alreadyFailedNodes;
    private Query query;

    private boolean adaptiveTimeoutCalculated = false;
    private long adaptiveTimeoutMin = 0;
    private long adaptiveTimeoutMax = 0;
    private long deadline = 0;

    private Result result = null;

    private long answeredDocs = 0;
    private long answeredActiveDocs = 0;
    private long answeredSoonActiveDocs = 0;
    private int askedNodes = 0;
    private int answeredNodes = 0;
    private int answeredNodesParticipated = 0;
    private boolean timedOut = false;
    private boolean degradedByMatchPhase = false;

    public InterleavedSearchInvoker(Collection<SearchInvoker> invokers, SearchCluster searchCluster, Set<Integer> alreadyFailedNodes) {
        super(Optional.empty());
        this.invokers = Collections.newSetFromMap(new IdentityHashMap<>());
        this.invokers.addAll(invokers);
        this.searchCluster = searchCluster;
        this.availableForProcessing = newQueue();
        this.alreadyFailedNodes = alreadyFailedNodes;
    }

    /**
     * Sends search queries to the contained {@link SearchInvoker} sub-invokers. If the search
     * query has an offset other than zero, it will be reset to zero and the expected hit amount
     * will be adjusted accordingly.
     */
    @Override
    protected void sendSearchRequest(Query query) throws IOException {
        this.query = query;
        invokers.forEach(invoker -> invoker.setMonitor(this));
        deadline = currentTime() + query.getTimeLeft();

        int originalHits = query.getHits();
        int originalOffset = query.getOffset();
        query.setHits(query.getHits() + query.getOffset());
        query.setOffset(0);

        for (SearchInvoker invoker : invokers) {
            invoker.sendSearchRequest(query);
            askedNodes++;
        }

        query.setHits(originalHits);
        query.setOffset(originalOffset);
    }

    @Override
    protected Result getSearchResult(Execution execution) throws IOException {

        List<Hit> merged = Collections.emptyList();
        long nextTimeout = query.getTimeLeft();
        try {
            while (!invokers.isEmpty() && nextTimeout >= 0) {
                SearchInvoker invoker = availableForProcessing.poll(nextTimeout, TimeUnit.MILLISECONDS);
                if (invoker == null) {
                    log.fine(() -> "Search timed out with " + askedNodes + " requests made, " + answeredNodes + " responses received");
                    break;
                } else {
                    merged = mergeResult(invoker.getSearchResult(execution), merged);
                    log.info("Merged " + invokers.size() + ":" + merged.toString());
                    ejectInvoker(invoker);
                }
                nextTimeout = nextTimeout();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while waiting for search results", e);
        }

        if (result == null) {
            result = new Result(query);
        }
        insertNetworkErrors();
        result.setCoverage(createCoverage());
        int needed = query.getOffset() + query.getHits();
        for (int index = query.getOffset(); (index < merged.size()) && (index < needed); index++) {
            result.hits().add(merged.get(index));
        }
        Result ret = result;
        result = null;
        return ret;
    }

    private void insertNetworkErrors() {
        // Network errors will be reported as errors only when all nodes fail, otherwise they are just traced
        boolean asErrors = answeredNodes == 0;

        if (!invokers.isEmpty()) {
            String keys = invokers.stream().map(SearchInvoker::distributionKey).map(dk -> dk.map(i -> i.toString()).orElse("(unspecified)"))
                    .collect(Collectors.joining(", "));

            if (asErrors) {
                result.hits().addError(ErrorMessage
                        .createTimeout("Backend communication timeout on all nodes in group (distribution-keys: " + keys + ")"));
            } else {
                query.trace("Backend communication timeout on nodes with distribution-keys: " + keys, 2);
            }
            timedOut = true;
        }
        if (alreadyFailedNodes != null) {
            var message = "Connection failure on nodes with distribution-keys: "
                    + alreadyFailedNodes.stream().map(v -> Integer.toString(v)).collect(Collectors.joining(", "));
            if (asErrors) {
                result.hits().addError(ErrorMessage.createBackendCommunicationError(message));
            } else {
                query.trace(message, 2);
            }
            int failed = alreadyFailedNodes.size();
            askedNodes += failed;
            answeredNodes += failed;
        }
    }

    private long nextTimeout() {
        DispatchConfig config = searchCluster.dispatchConfig();
        double minimumCoverage = config.minSearchCoverage();

        if (askedNodes == answeredNodes || minimumCoverage >= 100.0) {
            return query.getTimeLeft();
        }
        int minimumResponses = (int) Math.ceil(askedNodes * minimumCoverage / 100.0);

        if (answeredNodes < minimumResponses) {
            return query.getTimeLeft();
        }

        long timeLeft = query.getTimeLeft();
        if (!adaptiveTimeoutCalculated) {
            adaptiveTimeoutMin = (long) (timeLeft * config.minWaitAfterCoverageFactor());
            adaptiveTimeoutMax = (long) (timeLeft * config.maxWaitAfterCoverageFactor());
            adaptiveTimeoutCalculated = true;
        }

        long now = currentTime();
        int pendingQueries = askedNodes - answeredNodes;
        double missWidth = ((100.0 - config.minSearchCoverage()) * askedNodes) / 100.0 - 1.0;
        double slopedWait = adaptiveTimeoutMin;
        if (pendingQueries > 1 && missWidth > 0.0) {
            slopedWait += ((adaptiveTimeoutMax - adaptiveTimeoutMin) * (pendingQueries - 1)) / missWidth;
        }
        long nextAdaptive = (long) slopedWait;
        if (now + nextAdaptive >= deadline) {
            return deadline - now;
        }
        deadline = now + nextAdaptive;

        return nextAdaptive;
    }

    private List<Hit> mergeResult(Result partialResult, List<Hit> current) {
        collectCoverage(partialResult.getCoverage(true));

        if (result == null) {
            result = partialResult;
            return result.hits().asUnorderedHits();
        }
        result.mergeWith(partialResult);
        int needed = query.getOffset() + query.getHits();
        List<Hit> partial = partialResult.hits().asUnorderedHits();
        List<Hit> merged = new ArrayList<>(needed);
        int indexCurrent = 0;
        int indexPartial = 0;
        while (indexCurrent < current.size() && indexPartial < partial.size() && merged.size() < needed) {
            int cmpRes = current.get(indexCurrent).compareTo(partial.get(indexPartial));
            if (cmpRes <= 0) {
                merged.add(current.get(indexCurrent++));
            } else {
                merged.add(partial.get(indexPartial++));
            }
        }
        while ((indexCurrent < current.size()) && (merged.size() < needed)) {
            merged.add(current.get(indexCurrent++));
        }
        while ((indexPartial < partial.size()) && (merged.size() < needed)) {
            merged.add(partial.get(indexPartial++));
        }
        return merged;
    }

    private void collectCoverage(Coverage source) {
        answeredDocs += source.getDocs();
        answeredActiveDocs += source.getActive();
        answeredSoonActiveDocs += source.getSoonActive();
        answeredNodesParticipated += source.getNodes();
        answeredNodes++;
        degradedByMatchPhase |= source.isDegradedByMatchPhase();
        timedOut |= source.isDegradedByTimeout();
    }

    private Coverage createCoverage() {
        adjustDegradedCoverage();

        Coverage coverage = new Coverage(answeredDocs, answeredActiveDocs, answeredNodesParticipated, 1);
        coverage.setNodesTried(askedNodes);
        coverage.setSoonActive(answeredSoonActiveDocs);
        int degradedReason = 0;
        if (timedOut) {
            degradedReason |= (adaptiveTimeoutCalculated ? DEGRADED_BY_ADAPTIVE_TIMEOUT : DEGRADED_BY_TIMEOUT);
        }
        if (degradedByMatchPhase) {
            degradedReason |= DEGRADED_BY_MATCH_PHASE;
        }
        coverage.setDegradedReason(degradedReason);
        return coverage;
    }

    private void adjustDegradedCoverage() {
        if (askedNodes == answeredNodesParticipated) {
            return;
        }
        int notAnswered = askedNodes - answeredNodesParticipated;

        if (adaptiveTimeoutCalculated && answeredNodesParticipated > 0) {
            answeredActiveDocs += (notAnswered * answeredActiveDocs / answeredNodesParticipated);
            answeredSoonActiveDocs += (notAnswered * answeredSoonActiveDocs / answeredNodesParticipated);
        } else {
            if (askedNodes > answeredNodesParticipated) {
                int searchableCopies = (int) searchCluster.dispatchConfig().searchableCopies();
                int missingNodes = notAnswered - (searchableCopies - 1);
                if (answeredNodesParticipated > 0) {
                    answeredActiveDocs += (missingNodes * answeredActiveDocs / answeredNodesParticipated);
                    answeredSoonActiveDocs += (missingNodes * answeredSoonActiveDocs / answeredNodesParticipated);
                    timedOut = true;
                }
            }
        }
    }

    private void ejectInvoker(SearchInvoker invoker) {
        invokers.remove(invoker);
        invoker.release();
    }

    @Override
    protected void release() {
        if (!invokers.isEmpty()) {
            invokers.forEach(SearchInvoker::close);
            invokers.clear();
        }
    }

    @Override
    public void responseAvailable(SearchInvoker from) {
        if (availableForProcessing != null) {
            availableForProcessing.add(from);
        }
    }

    @Override
    protected void setMonitor(ResponseMonitor<SearchInvoker> monitor) {
        // never to be called
    }

    // For overriding in tests
    protected long currentTime() {
        return System.currentTimeMillis();
    }

    // For overriding in tests
    protected LinkedBlockingQueue<SearchInvoker> newQueue() {
        return new LinkedBlockingQueue<>();
    }
}
