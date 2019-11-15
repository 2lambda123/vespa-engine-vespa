// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.dispatch;

import com.google.inject.Inject;
import com.yahoo.cloud.config.ClusterInfoConfig;
import com.yahoo.component.AbstractComponent;
import com.yahoo.component.ComponentId;
import com.yahoo.container.handler.VipStatus;
import com.yahoo.jdisc.Metric;
import com.yahoo.prelude.fastsearch.VespaBackEndSearcher;
import com.yahoo.processing.request.CompoundName;
import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.dispatch.SearchPath.InvalidSearchPathException;
import com.yahoo.search.dispatch.rpc.RpcInvokerFactory;
import com.yahoo.search.dispatch.rpc.RpcResourcePool;
import com.yahoo.search.dispatch.searchcluster.Group;
import com.yahoo.search.dispatch.searchcluster.Node;
import com.yahoo.search.dispatch.searchcluster.PingFactory;
import com.yahoo.search.dispatch.searchcluster.SearchCluster;
import com.yahoo.search.query.profile.types.FieldDescription;
import com.yahoo.search.query.profile.types.FieldType;
import com.yahoo.search.query.profile.types.QueryProfileType;
import com.yahoo.search.result.ErrorMessage;
import com.yahoo.vespa.config.search.DispatchConfig;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * A dispatcher communicates with search nodes to perform queries and fill hits.
 *
 * This class allocates {@link SearchInvoker} and {@link FillInvoker} objects based
 * on query properties and general system status. The caller can then use the provided
 * invocation object to execute the search or fill.
 *
 * This class is multithread safe.
 *
 * @author bratseth
 * @author ollvir
 */
public class Dispatcher extends AbstractComponent {

    public static final String DISPATCH = "dispatch";
    private static final String INTERNAL = "internal";
    private static final String PROTOBUF = "protobuf";

    private static final String FDISPATCH_METRIC = "dispatch_fdispatch";
    private static final String INTERNAL_METRIC = "dispatch_internal";

    private static final int MAX_GROUP_SELECTION_ATTEMPTS = 3;

    /** If enabled, search queries will use protobuf rpc */
    public static final CompoundName dispatchProtobuf = CompoundName.fromComponents(DISPATCH, PROTOBUF);

    /** A model of the search cluster this dispatches to */
    private final SearchCluster searchCluster;

    private final LoadBalancer loadBalancer;
    private final boolean multilevelDispatch;

    private final InvokerFactory invokerFactory;

    private final Metric metric;
    private final Metric.Context metricContext;

    private static final QueryProfileType argumentType;

    static {
        argumentType = new QueryProfileType(DISPATCH);
        argumentType.setStrict(true);
        argumentType.setBuiltin(true);
        argumentType.addField(new FieldDescription(INTERNAL, FieldType.booleanType));
        argumentType.addField(new FieldDescription(PROTOBUF, FieldType.booleanType));
        argumentType.freeze();
    }

    public static QueryProfileType getArgumentType() { return argumentType; }

    @Inject
    public Dispatcher(ComponentId clusterId,
                      DispatchConfig dispatchConfig,
                      ClusterInfoConfig clusterInfoConfig,
                      VipStatus vipStatus,
                      Metric metric) {
        this(new SearchCluster(clusterId.stringValue(), dispatchConfig, clusterInfoConfig.nodeCount(), vipStatus),
             dispatchConfig,
             metric);
    }

    private Dispatcher(SearchCluster searchCluster,
                      DispatchConfig dispatchConfig,
                      Metric metric) {
        this(searchCluster,
             dispatchConfig,
             new RpcInvokerFactory(new RpcResourcePool(dispatchConfig), searchCluster),
             metric);
    }

    /* Protected for simple mocking in tests. Beware that searchCluster is shutdown on in deconstruct() */
    protected Dispatcher(SearchCluster searchCluster,
                         DispatchConfig dispatchConfig,
                         RpcInvokerFactory rcpInvokerFactory,
                         Metric metric) {
        this(searchCluster, dispatchConfig, rcpInvokerFactory, rcpInvokerFactory, metric);
    }

    /* Protected for simple mocking in tests. Beware that searchCluster is shutdown on in deconstruct() */
    protected Dispatcher(SearchCluster searchCluster,
                         DispatchConfig dispatchConfig,
                         InvokerFactory invokerFactory,
                         PingFactory pingFactory,
                         Metric metric) {
        this.searchCluster = searchCluster;
        this.loadBalancer = new LoadBalancer(searchCluster,
                                  dispatchConfig.distributionPolicy() == DispatchConfig.DistributionPolicy.ROUNDROBIN);
        this.invokerFactory = invokerFactory;
        this.multilevelDispatch = dispatchConfig.useMultilevelDispatch();
        this.metric = metric;
        this.metricContext = metric.createContext(null);

        searchCluster.startClusterMonitoring(pingFactory);
    }

    /** Returns the search cluster this dispatches to */
    public SearchCluster searchCluster() {
        return searchCluster;
    }

    @Override
    public void deconstruct() {
        invokerFactory.release();
        searchCluster.shutDown();
    }

    public Optional<FillInvoker> getFillInvoker(Result result, VespaBackEndSearcher searcher) {
        return invokerFactory.createFillInvoker(searcher, result);
    }

    public Optional<SearchInvoker> getSearchInvoker(Query query, VespaBackEndSearcher searcher) {
        if (multilevelDispatch) {
            emitDispatchMetric(Optional.empty());
            return Optional.empty();
        }

        Optional<SearchInvoker> invoker = getSearchPathInvoker(query, searcher);

        if (invoker.isEmpty()) {
            invoker = getInternalInvoker(query, searcher);
        }
        if (invoker.isPresent() && query.properties().getBoolean(com.yahoo.search.query.Model.ESTIMATE)) {
            query.setHits(0);
            query.setOffset(0);
        }
        emitDispatchMetric(invoker);

        return invoker;
    }

    /** Builds an invoker based on searchpath */
    private Optional<SearchInvoker> getSearchPathInvoker(Query query, VespaBackEndSearcher searcher) {
        String searchPath = query.getModel().getSearchPath();
        if (searchPath == null) return Optional.empty();

        try {
            List<Node> nodes = SearchPath.selectNodes(searchPath, searchCluster);
            if (nodes.isEmpty()) return Optional.empty();

            query.trace(false, 2, "Dispatching internally with search path ", searchPath);
            return invokerFactory.createSearchInvoker(searcher, query, OptionalInt.empty(), nodes, true);
        } catch (InvalidSearchPathException e) {
            return Optional.of(new SearchErrorInvoker(ErrorMessage.createIllegalQuery(e.getMessage())));
        }
    }

    private Optional<SearchInvoker> getInternalInvoker(Query query, VespaBackEndSearcher searcher) {
        Optional<Node> directNode = searchCluster.localCorpusDispatchTarget();
        if (directNode.isPresent()) {
            Node node = directNode.get();
            query.trace(false, 2, "Dispatching directly to ", node);
            return invokerFactory.createSearchInvoker(searcher, query, OptionalInt.empty(), Arrays.asList(node), true);
        }

        int covered = searchCluster.groupsWithSufficientCoverage();
        int groups = searchCluster.orderedGroups().size();
        int max = Integer.min(Integer.min(covered + 1, groups), MAX_GROUP_SELECTION_ATTEMPTS);
        Set<Integer> rejected = null;
        for (int i = 0; i < max; i++) {
            Optional<Group> groupInCluster = loadBalancer.takeGroup(rejected);
            if (groupInCluster.isEmpty()) break; // No groups available

            Group group = groupInCluster.get();
            boolean acceptIncompleteCoverage = (i == max - 1);
            Optional<SearchInvoker> invoker = invokerFactory.createSearchInvoker(searcher,
                                                                                 query,
                                                                                 OptionalInt.of(group.id()),
                                                                                 group.nodes(),
                                                                                 acceptIncompleteCoverage);
            if (invoker.isPresent()) {
                query.trace(false, 2, "Dispatching internally to search group ", group.id());
                query.getModel().setSearchPath("/" + group.id());
                invoker.get().teardown((success, time) -> loadBalancer.releaseGroup(group, success, time));
                return invoker;
            } else {
                loadBalancer.releaseGroup(group, false, 0);
                if (rejected == null) {
                    rejected = new HashSet<>();
                }
                rejected.add(group.id());
            }
        }

        return Optional.empty();
    }

    private void emitDispatchMetric(Optional<SearchInvoker> invoker) {
        if (invoker.isEmpty()) {
            metric.add(FDISPATCH_METRIC, 1, metricContext);
        } else {
            metric.add(INTERNAL_METRIC, 1, metricContext);
        }
    }

}
