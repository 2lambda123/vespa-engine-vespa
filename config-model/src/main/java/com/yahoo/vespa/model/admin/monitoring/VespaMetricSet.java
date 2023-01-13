// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.admin.monitoring;

import com.yahoo.metrics.ContainerMetrics;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.yahoo.vespa.model.admin.monitoring.DefaultVespaMetrics.defaultVespaMetricSet;
import static java.util.Collections.singleton;

/**
 * Encapsulates vespa service metrics.
 *
 * @author gjoranv
 */
public class VespaMetricSet {

    public static final MetricSet vespaMetricSet = new MetricSet("vespa",
                                                                 getVespaMetrics(),
                                                                 singleton(defaultVespaMetricSet));

    private static Set<Metric> getVespaMetrics() {
        Set<Metric> metrics = new LinkedHashSet<>();

        metrics.addAll(getSearchNodeMetrics());
        metrics.addAll(getStorageMetrics());
        metrics.addAll(getDistributorMetrics());
        metrics.addAll(getDocprocMetrics());
        metrics.addAll(getClusterControllerMetrics());
        metrics.addAll(getSearchChainMetrics());
        metrics.addAll(getContainerMetrics());
        metrics.addAll(getConfigServerMetrics());
        metrics.addAll(getSentinelMetrics());
        metrics.addAll(getOtherMetrics());

        return Collections.unmodifiableSet(metrics);
    }

    private static Set<Metric> getSentinelMetrics() {
        Set<Metric> metrics = new LinkedHashSet<>();

        metrics.add(new Metric("sentinel.restarts.count"));
        metrics.add(new Metric("sentinel.totalRestarts.last"));
        metrics.add(new Metric("sentinel.uptime.last"));

        metrics.add(new Metric("sentinel.running.count"));
        metrics.add(new Metric("sentinel.running.last"));

        return metrics;
    }

    private static Set<Metric> getOtherMetrics() {
        Set<Metric> metrics = new LinkedHashSet<>();

        metrics.add(new Metric("slobrok.heartbeats.failed.count"));
        metrics.add(new Metric("slobrok.missing.consensus.count"));

        metrics.add(new Metric("logd.processed.lines.count"));
        metrics.add(new Metric("worker.connections.max"));
        metrics.add(new Metric("endpoint.certificate.expiry.seconds"));

        // Java (JRT) TLS metrics
        metrics.add(new Metric("jrt.transport.tls-certificate-verification-failures"));
        metrics.add(new Metric("jrt.transport.peer-authorization-failures"));
        metrics.add(new Metric("jrt.transport.server.tls-connections-established"));
        metrics.add(new Metric("jrt.transport.client.tls-connections-established"));
        metrics.add(new Metric("jrt.transport.server.unencrypted-connections-established"));
        metrics.add(new Metric("jrt.transport.client.unencrypted-connections-established"));

        // C++ TLS metrics
        metrics.add(new Metric("vds.server.network.tls-handshakes-failed"));
        metrics.add(new Metric("vds.server.network.peer-authorization-failures"));
        metrics.add(new Metric("vds.server.network.client.tls-connections-established"));
        metrics.add(new Metric("vds.server.network.server.tls-connections-established"));
        metrics.add(new Metric("vds.server.network.client.insecure-connections-established"));
        metrics.add(new Metric("vds.server.network.server.insecure-connections-established"));
        metrics.add(new Metric("vds.server.network.tls-connections-broken"));
        metrics.add(new Metric("vds.server.network.failed-tls-config-reloads"));

        // C++ Fnet metrics
        metrics.add(new Metric("vds.server.fnet.num-connections"));

        // Node certificate
        metrics.add(new Metric("node-certificate.expiry.seconds"));

        return metrics;
    }

    private static Set<Metric> getConfigServerMetrics() {
        Set<Metric> metrics =new LinkedHashSet<>();

        metrics.add(new Metric("configserver.requests.count"));
        metrics.add(new Metric("configserver.failedRequests.count"));
        metrics.add(new Metric("configserver.latency.max"));
        metrics.add(new Metric("configserver.latency.sum"));
        metrics.add(new Metric("configserver.latency.count"));
        metrics.add(new Metric("configserver.cacheConfigElems.last"));
        metrics.add(new Metric("configserver.cacheChecksumElems.last"));
        metrics.add(new Metric("configserver.hosts.last"));
        metrics.add(new Metric("configserver.delayedResponses.count"));
        metrics.add(new Metric("configserver.sessionChangeErrors.count"));

        metrics.add(new Metric("configserver.zkZNodes.last"));
        metrics.add(new Metric("configserver.zkAvgLatency.last"));
        metrics.add(new Metric("configserver.zkMaxLatency.last"));
        metrics.add(new Metric("configserver.zkConnections.last"));
        metrics.add(new Metric("configserver.zkOutstandingRequests.last"));

        return metrics;
    }

    private static Set<Metric> getContainerMetrics() {
        Set<Metric> metrics = new LinkedHashSet<>();

        addMetric(metrics, "jdisc.http.requests", List.of("rate", "count"));

        addMetric(metrics, ContainerMetrics.HANDLED_REQUESTS.count());
        addMetric(metrics, ContainerMetrics.HANDLED_LATENCY.baseName(), List.of("max", "sum", "count"));
        
        metrics.add(new Metric("serverRejectedRequests.rate"));     // TODO: Remove on Vespa 9. Use jdisc.thread_pool.rejected_tasks.
        metrics.add(new Metric("serverRejectedRequests.count"));    // TODO: Remove on Vespa 9. Use jdisc.thread_pool.rejected_tasks.

        metrics.add(new Metric("serverThreadPoolSize.max"));        // TODO: Remove on Vespa 9. Use jdisc.thread_pool.size.
        metrics.add(new Metric("serverThreadPoolSize.last"));       // TODO: Remove on Vespa 9. Use jdisc.thread_pool.size.

        metrics.add(new Metric("serverActiveThreads.min"));         // TODO: Remove on Vespa 9. Use jdisc.thread_pool.active_threads.
        metrics.add(new Metric("serverActiveThreads.max"));         // TODO: Remove on Vespa 9. Use jdisc.thread_pool.active_threads.
        metrics.add(new Metric("serverActiveThreads.sum"));         // TODO: Remove on Vespa 9. Use jdisc.thread_pool.active_threads.
        metrics.add(new Metric("serverActiveThreads.count"));       // TODO: Remove on Vespa 9. Use jdisc.thread_pool.active_threads.
        metrics.add(new Metric("serverActiveThreads.last"));        // TODO: Remove on Vespa 9. Use jdisc.thread_pool.active_threads.

        addMetric(metrics, ContainerMetrics.SERVER_NUM_OPEN_CONNECTIONS.baseName(), List.of("max", "last", "average"));
        addMetric(metrics, ContainerMetrics.SERVER_NUM_CONNECTIONS.baseName(), List.of("max", "last", "average"));

        addMetric(metrics, ContainerMetrics.SERVER_BYTES_RECEIVED.baseName(), List.of("sum", "count"));
        addMetric(metrics, ContainerMetrics.SERVER_BYTES_SENT.baseName(), List.of("sum", "count"));

        {
            List<String> suffixes = List.of("sum", "count", "last", "min", "max");
            addMetric(metrics, ContainerMetrics.JDISC_THREAD_POOL_UNHANDLED_EXCEPTIONS.baseName(), suffixes);
            addMetric(metrics, ContainerMetrics.JDISC_THREAD_POOL_WORK_QUEUE_CAPACITY.baseName(), suffixes);
            addMetric(metrics, ContainerMetrics.JDISC_THREAD_POOL_WORK_QUEUE_SIZE.baseName(), suffixes);
            addMetric(metrics, ContainerMetrics.JDISC_THREAD_POOL_REJECTED_TASKS.baseName(), suffixes);
            addMetric(metrics, ContainerMetrics.JDISC_THREAD_POOL_SIZE.baseName(), suffixes);
            addMetric(metrics, ContainerMetrics.JDISC_THREAD_POOL_MAX_ALLOWED_SIZE.baseName(), suffixes);
            addMetric(metrics, ContainerMetrics.JDISC_THREAD_POOL_ACTIVE_THREADS.baseName(), suffixes);

            addMetric(metrics, ContainerMetrics.JETTY_THREADPOOL_MAX_THREADS.baseName(), suffixes);
            addMetric(metrics, ContainerMetrics.JETTY_THREADPOOL_MIN_THREADS.baseName(), suffixes);
            addMetric(metrics, ContainerMetrics.JETTY_THREADPOOL_RESERVED_THREADS.baseName(), suffixes);
            addMetric(metrics, ContainerMetrics.JETTY_THREADPOOL_BUSY_THREADS.baseName(), suffixes);
            addMetric(metrics, ContainerMetrics.JETTY_THREADPOOL_TOTAL_THREADS.baseName(), suffixes);
            addMetric(metrics, ContainerMetrics.JETTY_THREADPOOL_QUEUE_SIZE.baseName(), suffixes);
        }

        addMetric(metrics, ContainerMetrics.HTTPAPI_LATENCY.baseName(), List.of("max", "sum", "count"));
        addMetric(metrics, ContainerMetrics.HTTPAPI_PENDING.baseName(), List.of("max", "sum", "count"));
        addMetric(metrics, ContainerMetrics.HTTPAPI_NUM_OPERATIONS.rate());
        addMetric(metrics, ContainerMetrics.HTTPAPI_NUM_UPDATES.rate());
        addMetric(metrics, ContainerMetrics.HTTPAPI_NUM_REMOVES.rate());
        addMetric(metrics, ContainerMetrics.HTTPAPI_NUM_PUTS.rate());
        addMetric(metrics, ContainerMetrics.HTTPAPI_SUCCEEDED.rate());
        addMetric(metrics, ContainerMetrics.HTTPAPI_FAILED.rate());
        addMetric(metrics, ContainerMetrics.HTTPAPI_PARSE_ERROR.rate());
        addMetric(metrics, ContainerMetrics.HTTPAPI_CONDITION_NOT_MET.rate());
        addMetric(metrics, ContainerMetrics.HTTPAPI_NOT_FOUND.rate());

        addMetric(metrics, ContainerMetrics.MEM_HEAP_TOTAL.average());
        addMetric(metrics, ContainerMetrics.MEM_HEAP_FREE.average());
        addMetric(metrics, ContainerMetrics.MEM_HEAP_USED.baseName(), List.of("average", "max"));
        addMetric(metrics, ContainerMetrics.MEM_DIRECT_TOTAL.average());
        addMetric(metrics, ContainerMetrics.MEM_DIRECT_FREE.average());
        addMetric(metrics, ContainerMetrics.MEM_DIRECT_USED.baseName(), List.of("average", "max"));
        addMetric(metrics, ContainerMetrics.MEM_DIRECT_COUNT.max());
        addMetric(metrics, ContainerMetrics.MEM_NATIVE_TOTAL.average());
        addMetric(metrics, ContainerMetrics.MEM_NATIVE_FREE.average());
        addMetric(metrics, ContainerMetrics.MEM_NATIVE_USED.average());
                
        metrics.add(new Metric("jdisc.memory_mappings.max"));
        metrics.add(new Metric("jdisc.open_file_descriptors.max"));

        addMetric(metrics, ContainerMetrics.JDISC_GC_COUNT.baseName(), List.of("average", "max", "last"));
        addMetric(metrics, ContainerMetrics.JDISC_GC_MS.baseName(), List.of("average", "max", "last"));

        metrics.add(new Metric("jdisc.deactivated_containers.total.last"));
        metrics.add(new Metric("jdisc.deactivated_containers.with_retained_refs.last"));

        metrics.add(new Metric("jdisc.singleton.is_active.last"));
        metrics.add(new Metric("jdisc.singleton.activation.count.last"));
        metrics.add(new Metric("jdisc.singleton.activation.failure.count.last"));
        metrics.add(new Metric("jdisc.singleton.activation.millis.last"));
        metrics.add(new Metric("jdisc.singleton.deactivation.count.last"));
        metrics.add(new Metric("jdisc.singleton.deactivation.failure.count.last"));
        metrics.add(new Metric("jdisc.singleton.deactivation.millis.last"));

        metrics.add(new Metric("athenz-tenant-cert.expiry.seconds.last"));
        metrics.add(new Metric("container-iam-role.expiry.seconds"));

        metrics.add(new Metric("jdisc.http.request.prematurely_closed.rate"));
        addMetric(metrics, "jdisc.http.request.requests_per_connection", List.of("sum", "count", "min", "max", "average"));

        metrics.add(new Metric(ContainerMetrics.HTTP_STATUS_1XX.rate()));
        metrics.add(new Metric(ContainerMetrics.HTTP_STATUS_2XX.rate()));
        metrics.add(new Metric(ContainerMetrics.HTTP_STATUS_3XX.rate()));
        metrics.add(new Metric(ContainerMetrics.HTTP_STATUS_4XX.rate()));
        metrics.add(new Metric(ContainerMetrics.HTTP_STATUS_5XX.rate()));

        metrics.add(new Metric("jdisc.http.request.uri_length.max"));
        metrics.add(new Metric("jdisc.http.request.uri_length.sum"));
        metrics.add(new Metric("jdisc.http.request.uri_length.count"));
        metrics.add(new Metric("jdisc.http.request.content_size.max"));
        metrics.add(new Metric("jdisc.http.request.content_size.sum"));
        metrics.add(new Metric("jdisc.http.request.content_size.count"));

        metrics.add(new Metric("jdisc.http.ssl.handshake.failure.missing_client_cert.rate"));
        metrics.add(new Metric("jdisc.http.ssl.handshake.failure.expired_client_cert.rate"));
        metrics.add(new Metric("jdisc.http.ssl.handshake.failure.invalid_client_cert.rate"));
        metrics.add(new Metric("jdisc.http.ssl.handshake.failure.incompatible_protocols.rate"));
        metrics.add(new Metric("jdisc.http.ssl.handshake.failure.incompatible_ciphers.rate"));
        metrics.add(new Metric("jdisc.http.ssl.handshake.failure.unknown.rate"));
        metrics.add(new Metric("jdisc.http.ssl.handshake.failure.connection_closed.rate"));

        metrics.add(new Metric("jdisc.http.handler.unhandled_exceptions.rate"));

        addMetric(metrics, "jdisc.http.filtering.request.handled", List.of("rate"));
        addMetric(metrics, "jdisc.http.filtering.request.unhandled", List.of("rate"));
        addMetric(metrics, "jdisc.http.filtering.response.handled", List.of("rate"));
        addMetric(metrics, "jdisc.http.filtering.response.unhandled", List.of("rate"));

        addMetric(metrics, "jdisc.application.failed_component_graphs", List.of("rate"));

        addMetric(metrics, "jdisc.http.filter.rule.blocked_requests", List.of("rate"));
        addMetric(metrics, "jdisc.http.filter.rule.allowed_requests", List.of("rate"));
        addMetric(metrics, "jdisc.jvm", List.of("last"));

        return metrics;
    }

    private static Set<Metric> getClusterControllerMetrics() {
        Set<Metric> metrics =new LinkedHashSet<>();

        metrics.add(new Metric("cluster-controller.down.count.last"));
        metrics.add(new Metric("cluster-controller.initializing.count.last"));
        metrics.add(new Metric("cluster-controller.maintenance.count.last"));
        metrics.add(new Metric("cluster-controller.retired.count.last"));
        metrics.add(new Metric("cluster-controller.stopping.count.last"));
        metrics.add(new Metric("cluster-controller.up.count.last"));
        metrics.add(new Metric("cluster-controller.cluster-state-change.count"));
        metrics.add(new Metric("cluster-controller.busy-tick-time-ms.last"));
        metrics.add(new Metric("cluster-controller.busy-tick-time-ms.max"));
        metrics.add(new Metric("cluster-controller.busy-tick-time-ms.sum"));
        metrics.add(new Metric("cluster-controller.busy-tick-time-ms.count"));
        metrics.add(new Metric("cluster-controller.idle-tick-time-ms.last"));
        metrics.add(new Metric("cluster-controller.idle-tick-time-ms.max"));
        metrics.add(new Metric("cluster-controller.idle-tick-time-ms.sum"));
        metrics.add(new Metric("cluster-controller.idle-tick-time-ms.count"));

        metrics.add(new Metric("cluster-controller.work-ms.last"));
        metrics.add(new Metric("cluster-controller.work-ms.sum"));
        metrics.add(new Metric("cluster-controller.work-ms.count"));

        metrics.add(new Metric("cluster-controller.is-master.last"));
        metrics.add(new Metric("cluster-controller.remote-task-queue.size.last"));
        // TODO(hakonhall): Update this name once persistent "count" metrics has been implemented.
        // DO NOT RELY ON THIS METRIC YET.
        metrics.add(new Metric("cluster-controller.node-event.count"));

        metrics.add(new Metric("cluster-controller.resource_usage.nodes_above_limit.last"));
        metrics.add(new Metric("cluster-controller.resource_usage.nodes_above_limit.max"));
        metrics.add(new Metric("cluster-controller.resource_usage.max_memory_utilization.last"));
        metrics.add(new Metric("cluster-controller.resource_usage.max_memory_utilization.max"));
        metrics.add(new Metric("cluster-controller.resource_usage.max_disk_utilization.last"));
        metrics.add(new Metric("cluster-controller.resource_usage.max_disk_utilization.max"));
        metrics.add(new Metric("cluster-controller.resource_usage.disk_limit.last"));
        metrics.add(new Metric("cluster-controller.resource_usage.memory_limit.last"));

        metrics.add(new Metric("reindexing.progress.last"));

        return metrics;
    }

    private static Set<Metric> getDocprocMetrics() {
        Set<Metric> metrics = new LinkedHashSet<>();

        // per chain
        metrics.add(new Metric("documents_processed.rate"));

        return metrics;
    }

    private static Set<Metric> getSearchChainMetrics() {
        Set<Metric> metrics = new LinkedHashSet<>();

        addMetric(metrics, ContainerMetrics.PEAK_QPS.max());
        addMetric(metrics, ContainerMetrics.SEARCH_CONNECTIONS.baseName(), Set.of("sum", "count", "max"));
        addMetric(metrics, ContainerMetrics.FEED_LATENCY.baseName(), Set.of("sum", "count", "max"));
        addMetric(metrics, ContainerMetrics.FEED_HTTP_REQUESTS.baseName(), Set.of("count", "rate"));
        addMetric(metrics, ContainerMetrics.QUERIES.rate());
        addMetric(metrics, ContainerMetrics.QUERY_CONTAINER_LATENCY.baseName(), Set.of("sum", "count", "max"));
        addMetric(metrics, ContainerMetrics.QUERY_LATENCY.baseName(), Set.of("sum", "count", "max", "95percentile", "99percentile"));
        addMetric(metrics, ContainerMetrics.QUERY_TIMEOUT.baseName(), Set.of("sum", "count", "max", "min", "95percentile", "99percentile"));
        addMetric(metrics, ContainerMetrics.FAILED_QUERIES.rate());
        addMetric(metrics, ContainerMetrics.DEGRADED_QUERIES.rate());
        addMetric(metrics, ContainerMetrics.HITS_PER_QUERY.baseName(), Set.of("sum", "count", "max", "95percentile", "99percentile"));
        addMetric(metrics, ContainerMetrics.SEARCH_CONNECTIONS.baseName(), Set.of("sum", "count", "max"));
        addMetric(metrics, ContainerMetrics.QUERY_HIT_OFFSET.baseName(), Set.of("sum", "count", "max"));
        addMetric(metrics, ContainerMetrics.DOCUMENTS_COVERED.count());
        addMetric(metrics, ContainerMetrics.DOCUMENTS_TOTAL.count());
        addMetric(metrics, ContainerMetrics.DOCUMENTS_TARGET_TOTAL.count());
        addMetric(metrics, ContainerMetrics.JDISC_RENDER_LATENCY.baseName(), Set.of("min", "max", "count", "sum", "last", "average"));
        addMetric(metrics, ContainerMetrics.QUERY_ITEM_COUNT.baseName(), Set.of("max", "sum", "count"));
        addMetric(metrics, ContainerMetrics.TOTAL_HITS_PER_QUERY.baseName(), Set.of("sum", "count", "max", "95percentile", "99percentile"));
        addMetric(metrics, ContainerMetrics.EMPTY_RESULTS.rate());
        addMetric(metrics, ContainerMetrics.REQUESTS_OVER_QUOTA.baseName(), Set.of("rate", "count"));

        addMetric(metrics, ContainerMetrics.RELEVANCE_AT_1.baseName(), Set.of("sum", "count"));
        addMetric(metrics, ContainerMetrics.RELEVANCE_AT_3.baseName(), Set.of("sum", "count"));
        addMetric(metrics, ContainerMetrics.RELEVANCE_AT_10.baseName(), Set.of("sum", "count"));
                
        // Errors from search container
        addMetric(metrics, ContainerMetrics.ERROR_TIMEOUT.rate());
        addMetric(metrics, ContainerMetrics.ERROR_BACKENDS_OOS.rate());
        addMetric(metrics, ContainerMetrics.ERROR_PLUGIN_FAILURE.rate());
        addMetric(metrics, ContainerMetrics.ERROR_BACKEND_COMMUNICATION_ERROR.rate());
        addMetric(metrics, ContainerMetrics.ERROR_EMPTY_DOCUMENT_SUMMARIES.rate());
        addMetric(metrics, ContainerMetrics.ERROR_INVALID_QUERY_PARAMETER.rate());
        addMetric(metrics, ContainerMetrics.ERROR_INTERNAL_SERVER_ERROR.rate());
        addMetric(metrics, ContainerMetrics.ERROR_MISCONFIGURED_SERVER.rate());
        addMetric(metrics, ContainerMetrics.ERROR_INVALID_QUERY_TRANSFORMATION.rate());
        addMetric(metrics, ContainerMetrics.ERROR_RESULT_WITH_ERRORS.rate());
        addMetric(metrics, ContainerMetrics.ERROR_UNSPECIFIED.rate());
        addMetric(metrics, ContainerMetrics.ERROR_UNHANDLED_EXCEPTION.rate());

        return metrics;
    }

    private static void addSearchNodeExecutorMetrics(Set<Metric> metrics, String prefix) {
        metrics.add(new Metric(prefix + ".queuesize.max"));
        metrics.add(new Metric(prefix + ".queuesize.sum"));
        metrics.add(new Metric(prefix + ".queuesize.count"));
        metrics.add(new Metric(prefix + ".accepted.rate"));
        metrics.add(new Metric(prefix + ".wakeups.rate"));
        metrics.add(new Metric(prefix + ".utilization.max"));
        metrics.add(new Metric(prefix + ".utilization.sum"));
        metrics.add(new Metric(prefix + ".utilization.count"));
    }

    private static Set<Metric> getSearchNodeMetrics() {
        Set<Metric> metrics = new LinkedHashSet<>();

        metrics.add(new Metric("content.proton.documentdb.documents.total.last"));
        metrics.add(new Metric("content.proton.documentdb.documents.ready.last"));
        metrics.add(new Metric("content.proton.documentdb.documents.active.last"));
        metrics.add(new Metric("content.proton.documentdb.documents.removed.last"));

        metrics.add(new Metric("content.proton.documentdb.index.docs_in_memory.last"));
        metrics.add(new Metric("content.proton.documentdb.disk_usage.last"));
        metrics.add(new Metric("content.proton.documentdb.memory_usage.allocated_bytes.max"));
        metrics.add(new Metric("content.proton.documentdb.heart_beat_age.last"));
        metrics.add(new Metric("content.proton.transport.query.count.rate"));
        metrics.add(new Metric("content.proton.docsum.docs.rate"));
        metrics.add(new Metric("content.proton.docsum.latency.max"));
        metrics.add(new Metric("content.proton.docsum.latency.sum"));
        metrics.add(new Metric("content.proton.docsum.latency.count"));
        metrics.add(new Metric("content.proton.transport.query.latency.max"));
        metrics.add(new Metric("content.proton.transport.query.latency.sum"));
        metrics.add(new Metric("content.proton.transport.query.latency.count"));

        // Search protocol
        metrics.add(new Metric("content.proton.search_protocol.query.latency.max"));
        metrics.add(new Metric("content.proton.search_protocol.query.latency.sum"));
        metrics.add(new Metric("content.proton.search_protocol.query.latency.count"));
        metrics.add(new Metric("content.proton.search_protocol.query.request_size.max"));
        metrics.add(new Metric("content.proton.search_protocol.query.request_size.sum"));
        metrics.add(new Metric("content.proton.search_protocol.query.request_size.count"));
        metrics.add(new Metric("content.proton.search_protocol.query.reply_size.max"));
        metrics.add(new Metric("content.proton.search_protocol.query.reply_size.sum"));
        metrics.add(new Metric("content.proton.search_protocol.query.reply_size.count"));
        metrics.add(new Metric("content.proton.search_protocol.docsum.latency.max"));
        metrics.add(new Metric("content.proton.search_protocol.docsum.latency.sum"));
        metrics.add(new Metric("content.proton.search_protocol.docsum.latency.count"));
        metrics.add(new Metric("content.proton.search_protocol.docsum.request_size.max"));
        metrics.add(new Metric("content.proton.search_protocol.docsum.request_size.sum"));
        metrics.add(new Metric("content.proton.search_protocol.docsum.request_size.count"));
        metrics.add(new Metric("content.proton.search_protocol.docsum.reply_size.max"));
        metrics.add(new Metric("content.proton.search_protocol.docsum.reply_size.sum"));
        metrics.add(new Metric("content.proton.search_protocol.docsum.reply_size.count"));
        metrics.add(new Metric("content.proton.search_protocol.docsum.requested_documents.count"));        
        
        // Executors shared between all document dbs
        addSearchNodeExecutorMetrics(metrics, "content.proton.executor.proton");
        addSearchNodeExecutorMetrics(metrics, "content.proton.executor.flush");
        addSearchNodeExecutorMetrics(metrics, "content.proton.executor.match");
        addSearchNodeExecutorMetrics(metrics, "content.proton.executor.docsum");
        addSearchNodeExecutorMetrics(metrics, "content.proton.executor.shared");
        addSearchNodeExecutorMetrics(metrics, "content.proton.executor.warmup");
        addSearchNodeExecutorMetrics(metrics, "content.proton.executor.field_writer");

        // jobs
        metrics.add(new Metric("content.proton.documentdb.job.total.average"));
        metrics.add(new Metric("content.proton.documentdb.job.attribute_flush.average"));
        metrics.add(new Metric("content.proton.documentdb.job.memory_index_flush.average"));
        metrics.add(new Metric("content.proton.documentdb.job.disk_index_fusion.average"));
        metrics.add(new Metric("content.proton.documentdb.job.document_store_flush.average"));
        metrics.add(new Metric("content.proton.documentdb.job.document_store_compact.average"));
        metrics.add(new Metric("content.proton.documentdb.job.bucket_move.average"));
        metrics.add(new Metric("content.proton.documentdb.job.lid_space_compact.average"));
        metrics.add(new Metric("content.proton.documentdb.job.removed_documents_prune.average"));

        // Threading service (per document db)
        addSearchNodeExecutorMetrics(metrics, "content.proton.documentdb.threading_service.master");
        addSearchNodeExecutorMetrics(metrics, "content.proton.documentdb.threading_service.index");
        addSearchNodeExecutorMetrics(metrics, "content.proton.documentdb.threading_service.summary");
        addSearchNodeExecutorMetrics(metrics, "content.proton.documentdb.threading_service.index_field_inverter");
        addSearchNodeExecutorMetrics(metrics, "content.proton.documentdb.threading_service.index_field_writer");
        addSearchNodeExecutorMetrics(metrics, "content.proton.documentdb.threading_service.attribute_field_writer");

        // lid space
        metrics.add(new Metric("content.proton.documentdb.ready.lid_space.lid_bloat_factor.average"));
        metrics.add(new Metric("content.proton.documentdb.notready.lid_space.lid_bloat_factor.average"));
        metrics.add(new Metric("content.proton.documentdb.removed.lid_space.lid_bloat_factor.average"));
        metrics.add(new Metric("content.proton.documentdb.ready.lid_space.lid_fragmentation_factor.average"));
        metrics.add(new Metric("content.proton.documentdb.notready.lid_space.lid_fragmentation_factor.average"));
        metrics.add(new Metric("content.proton.documentdb.removed.lid_space.lid_fragmentation_factor.average"));
        metrics.add(new Metric("content.proton.documentdb.ready.lid_space.lid_limit.last"));
        metrics.add(new Metric("content.proton.documentdb.notready.lid_space.lid_limit.last"));
        metrics.add(new Metric("content.proton.documentdb.removed.lid_space.lid_limit.last"));
        metrics.add(new Metric("content.proton.documentdb.ready.lid_space.highest_used_lid.last"));
        metrics.add(new Metric("content.proton.documentdb.notready.lid_space.highest_used_lid.last"));
        metrics.add(new Metric("content.proton.documentdb.removed.lid_space.highest_used_lid.last"));
        metrics.add(new Metric("content.proton.documentdb.ready.lid_space.used_lids.last"));
        metrics.add(new Metric("content.proton.documentdb.notready.lid_space.used_lids.last"));
        metrics.add(new Metric("content.proton.documentdb.removed.lid_space.used_lids.last"));

        // bucket move
        metrics.add(new Metric("content.proton.documentdb.bucket_move.buckets_pending.last"));

        // resource usage
        metrics.add(new Metric("content.proton.resource_usage.disk.average"));
        metrics.add(new Metric("content.proton.resource_usage.disk_usage.total.max"));
        metrics.add(new Metric("content.proton.resource_usage.disk_usage.total_utilization.max"));
        metrics.add(new Metric("content.proton.resource_usage.disk_usage.transient.max"));
        metrics.add(new Metric("content.proton.resource_usage.memory.average"));
        metrics.add(new Metric("content.proton.resource_usage.memory_usage.total.max"));
        metrics.add(new Metric("content.proton.resource_usage.memory_usage.total_utilization.max"));
        metrics.add(new Metric("content.proton.resource_usage.memory_usage.transient.max"));
        metrics.add(new Metric("content.proton.resource_usage.memory_mappings.max"));
        metrics.add(new Metric("content.proton.resource_usage.open_file_descriptors.max"));
        metrics.add(new Metric("content.proton.resource_usage.feeding_blocked.max"));
        metrics.add(new Metric("content.proton.resource_usage.malloc_arena.max"));
        metrics.add(new Metric("content.proton.documentdb.attribute.resource_usage.address_space.max"));
        metrics.add(new Metric("content.proton.documentdb.attribute.resource_usage.feeding_blocked.max"));

        // CPU util
        metrics.add(new Metric("content.proton.resource_usage.cpu_util.setup.max"));
        metrics.add(new Metric("content.proton.resource_usage.cpu_util.setup.sum"));
        metrics.add(new Metric("content.proton.resource_usage.cpu_util.setup.count"));
        metrics.add(new Metric("content.proton.resource_usage.cpu_util.read.max"));
        metrics.add(new Metric("content.proton.resource_usage.cpu_util.read.sum"));
        metrics.add(new Metric("content.proton.resource_usage.cpu_util.read.count"));
        metrics.add(new Metric("content.proton.resource_usage.cpu_util.write.max"));
        metrics.add(new Metric("content.proton.resource_usage.cpu_util.write.sum"));
        metrics.add(new Metric("content.proton.resource_usage.cpu_util.write.count"));
        metrics.add(new Metric("content.proton.resource_usage.cpu_util.compact.max"));
        metrics.add(new Metric("content.proton.resource_usage.cpu_util.compact.sum"));
        metrics.add(new Metric("content.proton.resource_usage.cpu_util.compact.count"));
        metrics.add(new Metric("content.proton.resource_usage.cpu_util.other.max"));
        metrics.add(new Metric("content.proton.resource_usage.cpu_util.other.sum"));
        metrics.add(new Metric("content.proton.resource_usage.cpu_util.other.count"));

        // transaction log
        metrics.add(new Metric("content.proton.transactionlog.entries.average"));
        metrics.add(new Metric("content.proton.transactionlog.disk_usage.average"));
        metrics.add(new Metric("content.proton.transactionlog.replay_time.last"));

        // document store
        metrics.add(new Metric("content.proton.documentdb.ready.document_store.disk_usage.average"));
        metrics.add(new Metric("content.proton.documentdb.ready.document_store.disk_bloat.average"));
        metrics.add(new Metric("content.proton.documentdb.ready.document_store.max_bucket_spread.average"));
        metrics.add(new Metric("content.proton.documentdb.ready.document_store.memory_usage.allocated_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.ready.document_store.memory_usage.used_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.ready.document_store.memory_usage.dead_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.ready.document_store.memory_usage.onhold_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.notready.document_store.disk_usage.average"));
        metrics.add(new Metric("content.proton.documentdb.notready.document_store.disk_bloat.average"));
        metrics.add(new Metric("content.proton.documentdb.notready.document_store.max_bucket_spread.average"));
        metrics.add(new Metric("content.proton.documentdb.notready.document_store.memory_usage.allocated_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.notready.document_store.memory_usage.used_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.notready.document_store.memory_usage.dead_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.notready.document_store.memory_usage.onhold_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.removed.document_store.disk_usage.average"));
        metrics.add(new Metric("content.proton.documentdb.removed.document_store.disk_bloat.average"));
        metrics.add(new Metric("content.proton.documentdb.removed.document_store.max_bucket_spread.average"));
        metrics.add(new Metric("content.proton.documentdb.removed.document_store.memory_usage.allocated_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.removed.document_store.memory_usage.used_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.removed.document_store.memory_usage.dead_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.removed.document_store.memory_usage.onhold_bytes.average"));

        // document store cache
        metrics.add(new Metric("content.proton.documentdb.ready.document_store.cache.memory_usage.average"));
        metrics.add(new Metric("content.proton.documentdb.ready.document_store.cache.hit_rate.average"));
        metrics.add(new Metric("content.proton.documentdb.ready.document_store.cache.lookups.rate"));
        metrics.add(new Metric("content.proton.documentdb.ready.document_store.cache.invalidations.rate"));
        metrics.add(new Metric("content.proton.documentdb.notready.document_store.cache.memory_usage.average"));
        metrics.add(new Metric("content.proton.documentdb.notready.document_store.cache.hit_rate.average"));
        metrics.add(new Metric("content.proton.documentdb.notready.document_store.cache.lookups.rate"));
        metrics.add(new Metric("content.proton.documentdb.notready.document_store.cache.invalidations.rate"));

        // attribute
        metrics.add(new Metric("content.proton.documentdb.ready.attribute.memory_usage.allocated_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.ready.attribute.memory_usage.used_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.ready.attribute.memory_usage.dead_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.ready.attribute.memory_usage.onhold_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.notready.attribute.memory_usage.allocated_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.notready.attribute.memory_usage.used_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.notready.attribute.memory_usage.dead_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.notready.attribute.memory_usage.onhold_bytes.average"));

        // index
        metrics.add(new Metric("content.proton.documentdb.index.memory_usage.allocated_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.index.memory_usage.used_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.index.memory_usage.dead_bytes.average"));
        metrics.add(new Metric("content.proton.documentdb.index.memory_usage.onhold_bytes.average"));

        // matching
        metrics.add(new Metric("content.proton.documentdb.matching.queries.rate"));
        metrics.add(new Metric("content.proton.documentdb.matching.soft_doomed_queries.rate"));
        metrics.add(new Metric("content.proton.documentdb.matching.query_latency.max"));
        metrics.add(new Metric("content.proton.documentdb.matching.query_latency.sum"));
        metrics.add(new Metric("content.proton.documentdb.matching.query_latency.count"));
        metrics.add(new Metric("content.proton.documentdb.matching.query_setup_time.max"));
        metrics.add(new Metric("content.proton.documentdb.matching.query_setup_time.sum"));
        metrics.add(new Metric("content.proton.documentdb.matching.query_setup_time.count"));
        metrics.add(new Metric("content.proton.documentdb.matching.docs_matched.rate"));
        metrics.add(new Metric("content.proton.documentdb.matching.docs_matched.count"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.queries.rate"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.soft_doomed_queries.rate"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.soft_doom_factor.min"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.soft_doom_factor.max"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.soft_doom_factor.sum"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.soft_doom_factor.count"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.query_latency.max"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.query_latency.sum"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.query_latency.count"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.query_setup_time.max"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.query_setup_time.sum"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.query_setup_time.count"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.grouping_time.max"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.grouping_time.sum"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.grouping_time.count"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.rerank_time.max"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.rerank_time.sum"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.rerank_time.count"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.docs_matched.rate"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.docs_matched.count"));
        metrics.add(new Metric("content.proton.documentdb.matching.rank_profile.limited_queries.rate"));

        // feeding
        metrics.add(new Metric("content.proton.documentdb.feeding.commit.operations.max"));
        metrics.add(new Metric("content.proton.documentdb.feeding.commit.operations.sum"));
        metrics.add(new Metric("content.proton.documentdb.feeding.commit.operations.count"));
        metrics.add(new Metric("content.proton.documentdb.feeding.commit.operations.rate"));
        metrics.add(new Metric("content.proton.documentdb.feeding.commit.latency.max"));
        metrics.add(new Metric("content.proton.documentdb.feeding.commit.latency.sum"));
        metrics.add(new Metric("content.proton.documentdb.feeding.commit.latency.count"));

        return metrics;
    }

    private static Set<Metric> getStorageMetrics() {
        Set<Metric> metrics = new LinkedHashSet<>();

        // TODO: For the purpose of this file and likely elsewhere, all but the last aggregate specifier,
        // TODO: such as 'average' and 'sum' in the metric names below are just confusing and can be mentally
        // TODO: disregarded when considering metric names. Consider cleaning up for Vespa 9.
        metrics.add(new Metric("vds.datastored.alldisks.buckets.average"));
        metrics.add(new Metric("vds.datastored.alldisks.docs.average"));
        metrics.add(new Metric("vds.datastored.alldisks.bytes.average"));
        metrics.add(new Metric("vds.visitor.allthreads.averagevisitorlifetime.max"));
        metrics.add(new Metric("vds.visitor.allthreads.averagevisitorlifetime.sum"));
        metrics.add(new Metric("vds.visitor.allthreads.averagevisitorlifetime.count"));
        metrics.add(new Metric("vds.visitor.allthreads.averagequeuewait.max"));
        metrics.add(new Metric("vds.visitor.allthreads.averagequeuewait.sum"));
        metrics.add(new Metric("vds.visitor.allthreads.averagequeuewait.count"));
        metrics.add(new Metric("vds.visitor.allthreads.queuesize.max"));
        metrics.add(new Metric("vds.visitor.allthreads.queuesize.sum"));
        metrics.add(new Metric("vds.visitor.allthreads.queuesize.count"));
        metrics.add(new Metric("vds.visitor.allthreads.completed.rate"));
        metrics.add(new Metric("vds.visitor.allthreads.created.rate"));
        metrics.add(new Metric("vds.visitor.allthreads.failed.rate"));
        metrics.add(new Metric("vds.visitor.allthreads.averagemessagesendtime.max"));
        metrics.add(new Metric("vds.visitor.allthreads.averagemessagesendtime.sum"));
        metrics.add(new Metric("vds.visitor.allthreads.averagemessagesendtime.count"));
        metrics.add(new Metric("vds.visitor.allthreads.averageprocessingtime.max"));
        metrics.add(new Metric("vds.visitor.allthreads.averageprocessingtime.sum"));
        metrics.add(new Metric("vds.visitor.allthreads.averageprocessingtime.count"));

        metrics.add(new Metric("vds.filestor.queuesize.max"));
        metrics.add(new Metric("vds.filestor.queuesize.sum"));
        metrics.add(new Metric("vds.filestor.queuesize.count"));
        metrics.add(new Metric("vds.filestor.averagequeuewait.max"));
        metrics.add(new Metric("vds.filestor.averagequeuewait.sum"));
        metrics.add(new Metric("vds.filestor.averagequeuewait.count"));
        metrics.add(new Metric("vds.filestor.active_operations.size.max"));
        metrics.add(new Metric("vds.filestor.active_operations.size.sum"));
        metrics.add(new Metric("vds.filestor.active_operations.size.count"));
        metrics.add(new Metric("vds.filestor.active_operations.latency.max"));
        metrics.add(new Metric("vds.filestor.active_operations.latency.sum"));
        metrics.add(new Metric("vds.filestor.active_operations.latency.count"));
        metrics.add(new Metric("vds.filestor.throttle_window_size.max"));
        metrics.add(new Metric("vds.filestor.throttle_window_size.sum"));
        metrics.add(new Metric("vds.filestor.throttle_window_size.count"));
        metrics.add(new Metric("vds.filestor.throttle_waiting_threads.max"));
        metrics.add(new Metric("vds.filestor.throttle_waiting_threads.sum"));
        metrics.add(new Metric("vds.filestor.throttle_waiting_threads.count"));
        metrics.add(new Metric("vds.filestor.throttle_active_tokens.max"));
        metrics.add(new Metric("vds.filestor.throttle_active_tokens.sum"));
        metrics.add(new Metric("vds.filestor.throttle_active_tokens.count"));
        metrics.add(new Metric("vds.filestor.allthreads.mergemetadatareadlatency.max"));
        metrics.add(new Metric("vds.filestor.allthreads.mergemetadatareadlatency.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.mergemetadatareadlatency.count"));
        metrics.add(new Metric("vds.filestor.allthreads.mergedatareadlatency.max"));
        metrics.add(new Metric("vds.filestor.allthreads.mergedatareadlatency.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.mergedatareadlatency.count"));
        metrics.add(new Metric("vds.filestor.allthreads.mergedatawritelatency.max"));
        metrics.add(new Metric("vds.filestor.allthreads.mergedatawritelatency.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.mergedatawritelatency.count"));
        metrics.add(new Metric("vds.filestor.allthreads.put_latency.max"));
        metrics.add(new Metric("vds.filestor.allthreads.put_latency.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.put_latency.count"));
        metrics.add(new Metric("vds.filestor.allthreads.remove_latency.max"));
        metrics.add(new Metric("vds.filestor.allthreads.remove_latency.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.remove_latency.count"));
        metrics.add(new Metric("vds.filestor.allstripes.throttled_rpc_direct_dispatches.rate"));
        metrics.add(new Metric("vds.filestor.allstripes.throttled_persistence_thread_polls.rate"));
        metrics.add(new Metric("vds.filestor.allstripes.timeouts_waiting_for_throttle_token.rate"));
        
        metrics.add(new Metric("vds.filestor.allthreads.put.count.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.put.failed.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.put.test_and_set_failed.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.put.latency.max"));
        metrics.add(new Metric("vds.filestor.allthreads.put.latency.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.put.latency.count"));
        metrics.add(new Metric("vds.filestor.allthreads.put.request_size.max"));
        metrics.add(new Metric("vds.filestor.allthreads.put.request_size.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.put.request_size.count"));
        metrics.add(new Metric("vds.filestor.allthreads.remove.count.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.remove.failed.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.remove.test_and_set_failed.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.remove.latency.max"));
        metrics.add(new Metric("vds.filestor.allthreads.remove.latency.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.remove.latency.count"));
        metrics.add(new Metric("vds.filestor.allthreads.remove.request_size.max"));
        metrics.add(new Metric("vds.filestor.allthreads.remove.request_size.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.remove.request_size.count"));
        metrics.add(new Metric("vds.filestor.allthreads.get.count.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.get.failed.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.get.latency.max"));
        metrics.add(new Metric("vds.filestor.allthreads.get.latency.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.get.latency.count"));
        metrics.add(new Metric("vds.filestor.allthreads.get.request_size.max"));
        metrics.add(new Metric("vds.filestor.allthreads.get.request_size.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.get.request_size.count"));
        metrics.add(new Metric("vds.filestor.allthreads.update.count.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.update.failed.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.update.test_and_set_failed.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.update.latency.max"));
        metrics.add(new Metric("vds.filestor.allthreads.update.latency.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.update.latency.count"));
        metrics.add(new Metric("vds.filestor.allthreads.update.request_size.max"));
        metrics.add(new Metric("vds.filestor.allthreads.update.request_size.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.update.request_size.count"));
        metrics.add(new Metric("vds.filestor.allthreads.createiterator.latency.max"));
        metrics.add(new Metric("vds.filestor.allthreads.createiterator.latency.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.createiterator.latency.count"));
        metrics.add(new Metric("vds.filestor.allthreads.createiterator.count.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.visit.count.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.visit.latency.max"));
        metrics.add(new Metric("vds.filestor.allthreads.visit.latency.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.visit.latency.count"));
        metrics.add(new Metric("vds.filestor.allthreads.remove_location.count.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.remove_location.latency.max"));
        metrics.add(new Metric("vds.filestor.allthreads.remove_location.latency.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.remove_location.latency.count"));
        metrics.add(new Metric("vds.filestor.allthreads.splitbuckets.count.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.joinbuckets.count.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.deletebuckets.count.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.deletebuckets.failed.rate"));
        metrics.add(new Metric("vds.filestor.allthreads.deletebuckets.latency.max"));
        metrics.add(new Metric("vds.filestor.allthreads.deletebuckets.latency.sum"));
        metrics.add(new Metric("vds.filestor.allthreads.deletebuckets.latency.count"));
        metrics.add(new Metric("vds.filestor.allthreads.setbucketstates.count.rate"));
        return metrics;
    }
    private static Set<Metric> getDistributorMetrics() {
        Set<Metric> metrics = new LinkedHashSet<>();
        metrics.add(new Metric("vds.idealstate.buckets_rechecking.average"));
        metrics.add(new Metric("vds.idealstate.idealstate_diff.average"));
        metrics.add(new Metric("vds.idealstate.buckets_toofewcopies.average"));
        metrics.add(new Metric("vds.idealstate.buckets_toomanycopies.average"));
        metrics.add(new Metric("vds.idealstate.buckets.average"));
        metrics.add(new Metric("vds.idealstate.buckets_notrusted.average"));
        metrics.add(new Metric("vds.idealstate.bucket_replicas_moving_out.average"));
        metrics.add(new Metric("vds.idealstate.bucket_replicas_copying_out.average"));
        metrics.add(new Metric("vds.idealstate.bucket_replicas_copying_in.average"));
        metrics.add(new Metric("vds.idealstate.bucket_replicas_syncing.average"));
        metrics.add(new Metric("vds.idealstate.max_observed_time_since_last_gc_sec.average"));
        metrics.add(new Metric("vds.idealstate.delete_bucket.done_ok.rate"));
        metrics.add(new Metric("vds.idealstate.delete_bucket.done_failed.rate"));
        metrics.add(new Metric("vds.idealstate.delete_bucket.pending.average"));
        metrics.add(new Metric("vds.idealstate.merge_bucket.done_ok.rate"));
        metrics.add(new Metric("vds.idealstate.merge_bucket.done_failed.rate"));
        metrics.add(new Metric("vds.idealstate.merge_bucket.pending.average"));
        metrics.add(new Metric("vds.idealstate.merge_bucket.blocked.rate"));
        metrics.add(new Metric("vds.idealstate.merge_bucket.throttled.rate"));
        metrics.add(new Metric("vds.idealstate.merge_bucket.source_only_copy_changed.rate"));
        metrics.add(new Metric("vds.idealstate.merge_bucket.source_only_copy_delete_blocked.rate"));
        metrics.add(new Metric("vds.idealstate.merge_bucket.source_only_copy_delete_failed.rate"));
        metrics.add(new Metric("vds.idealstate.split_bucket.done_ok.rate"));
        metrics.add(new Metric("vds.idealstate.split_bucket.done_failed.rate"));
        metrics.add(new Metric("vds.idealstate.split_bucket.pending.average"));
        metrics.add(new Metric("vds.idealstate.join_bucket.done_ok.rate"));
        metrics.add(new Metric("vds.idealstate.join_bucket.done_failed.rate"));
        metrics.add(new Metric("vds.idealstate.join_bucket.pending.average"));
        metrics.add(new Metric("vds.idealstate.garbage_collection.done_ok.rate"));
        metrics.add(new Metric("vds.idealstate.garbage_collection.done_failed.rate"));
        metrics.add(new Metric("vds.idealstate.garbage_collection.pending.average"));
        metrics.add(new Metric("vds.idealstate.garbage_collection.documents_removed.count"));
        metrics.add(new Metric("vds.idealstate.garbage_collection.documents_removed.rate"));

        metrics.add(new Metric("vds.distributor.puts.latency.max"));
        metrics.add(new Metric("vds.distributor.puts.latency.sum"));
        metrics.add(new Metric("vds.distributor.puts.latency.count"));
        metrics.add(new Metric("vds.distributor.puts.ok.rate"));
        metrics.add(new Metric("vds.distributor.puts.failures.total.rate"));
        metrics.add(new Metric("vds.distributor.puts.failures.notfound.rate"));
        metrics.add(new Metric("vds.distributor.puts.failures.test_and_set_failed.rate"));
        metrics.add(new Metric("vds.distributor.puts.failures.concurrent_mutations.rate"));
        metrics.add(new Metric("vds.distributor.puts.failures.notconnected.rate"));
        metrics.add(new Metric("vds.distributor.puts.failures.notready.rate"));
        metrics.add(new Metric("vds.distributor.puts.failures.wrongdistributor.rate"));
        metrics.add(new Metric("vds.distributor.puts.failures.safe_time_not_reached.rate"));
        metrics.add(new Metric("vds.distributor.puts.failures.storagefailure.rate"));
        metrics.add(new Metric("vds.distributor.puts.failures.timeout.rate"));
        metrics.add(new Metric("vds.distributor.puts.failures.busy.rate"));
        metrics.add(new Metric("vds.distributor.puts.failures.inconsistent_bucket.rate"));
        metrics.add(new Metric("vds.distributor.removes.latency.max"));
        metrics.add(new Metric("vds.distributor.removes.latency.sum"));
        metrics.add(new Metric("vds.distributor.removes.latency.count"));
        metrics.add(new Metric("vds.distributor.removes.ok.rate"));
        metrics.add(new Metric("vds.distributor.removes.failures.total.rate"));
        metrics.add(new Metric("vds.distributor.removes.failures.notfound.rate"));
        metrics.add(new Metric("vds.distributor.removes.failures.test_and_set_failed.rate"));
        metrics.add(new Metric("vds.distributor.removes.failures.concurrent_mutations.rate"));
        metrics.add(new Metric("vds.distributor.updates.latency.max"));
        metrics.add(new Metric("vds.distributor.updates.latency.sum"));
        metrics.add(new Metric("vds.distributor.updates.latency.count"));
        metrics.add(new Metric("vds.distributor.updates.ok.rate"));
        metrics.add(new Metric("vds.distributor.updates.failures.total.rate"));
        metrics.add(new Metric("vds.distributor.updates.failures.notfound.rate"));
        metrics.add(new Metric("vds.distributor.updates.failures.test_and_set_failed.rate"));
        metrics.add(new Metric("vds.distributor.updates.failures.concurrent_mutations.rate"));
        metrics.add(new Metric("vds.distributor.updates.diverging_timestamp_updates.rate"));
        metrics.add(new Metric("vds.distributor.removelocations.ok.rate"));
        metrics.add(new Metric("vds.distributor.removelocations.failures.total.rate"));
        metrics.add(new Metric("vds.distributor.gets.latency.max"));
        metrics.add(new Metric("vds.distributor.gets.latency.sum"));
        metrics.add(new Metric("vds.distributor.gets.latency.count"));
        metrics.add(new Metric("vds.distributor.gets.ok.rate"));
        metrics.add(new Metric("vds.distributor.gets.failures.total.rate"));
        metrics.add(new Metric("vds.distributor.gets.failures.notfound.rate"));
        metrics.add(new Metric("vds.distributor.visitor.latency.max"));
        metrics.add(new Metric("vds.distributor.visitor.latency.sum"));
        metrics.add(new Metric("vds.distributor.visitor.latency.count"));
        metrics.add(new Metric("vds.distributor.visitor.ok.rate"));
        metrics.add(new Metric("vds.distributor.visitor.failures.total.rate"));
        metrics.add(new Metric("vds.distributor.visitor.failures.notready.rate"));
        metrics.add(new Metric("vds.distributor.visitor.failures.notconnected.rate"));
        metrics.add(new Metric("vds.distributor.visitor.failures.wrongdistributor.rate"));
        metrics.add(new Metric("vds.distributor.visitor.failures.safe_time_not_reached.rate"));
        metrics.add(new Metric("vds.distributor.visitor.failures.storagefailure.rate"));
        metrics.add(new Metric("vds.distributor.visitor.failures.timeout.rate"));
        metrics.add(new Metric("vds.distributor.visitor.failures.busy.rate"));
        metrics.add(new Metric("vds.distributor.visitor.failures.inconsistent_bucket.rate"));
        metrics.add(new Metric("vds.distributor.visitor.failures.notfound.rate"));

        metrics.add(new Metric("vds.distributor.docsstored.average"));
        metrics.add(new Metric("vds.distributor.bytesstored.average"));

        metrics.add(new Metric("vds.bouncer.clock_skew_aborts.count"));

        metrics.add(new Metric("vds.mergethrottler.averagequeuewaitingtime.max"));
        metrics.add(new Metric("vds.mergethrottler.averagequeuewaitingtime.sum"));
        metrics.add(new Metric("vds.mergethrottler.averagequeuewaitingtime.count"));
        metrics.add(new Metric("vds.mergethrottler.queuesize.max"));
        metrics.add(new Metric("vds.mergethrottler.queuesize.sum"));
        metrics.add(new Metric("vds.mergethrottler.queuesize.count"));
        metrics.add(new Metric("vds.mergethrottler.active_window_size.max"));
        metrics.add(new Metric("vds.mergethrottler.active_window_size.sum"));
        metrics.add(new Metric("vds.mergethrottler.active_window_size.count"));
        metrics.add(new Metric("vds.mergethrottler.bounced_due_to_back_pressure.rate"));
        metrics.add(new Metric("vds.mergethrottler.locallyexecutedmerges.ok.rate"));
        metrics.add(new Metric("vds.mergethrottler.mergechains.ok.rate"));
        metrics.add(new Metric("vds.mergethrottler.mergechains.failures.busy.rate"));
        metrics.add(new Metric("vds.mergethrottler.mergechains.failures.total.rate"));
        return metrics;
    }

    private static void addMetric(Set<Metric> metrics, String nameWithSuffix) {
        metrics.add(new Metric(nameWithSuffix));
    }

    private static void addMetric(Set<Metric> metrics, String metricName, Iterable<String> aggregateSuffices) {
        for (String suffix : aggregateSuffices) {
            metrics.add(new Metric(metricName + "." + suffix));
        }
    }

}
