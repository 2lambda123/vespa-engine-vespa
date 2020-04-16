// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document.restapi;

import com.yahoo.document.Document;
import com.yahoo.document.DocumentId;
import com.yahoo.document.DocumentRemove;
import com.yahoo.document.FixedBucketSpaces;
import com.yahoo.document.TestAndSetCondition;
import com.yahoo.document.json.JsonWriter;
import com.yahoo.document.DocumentPut;
import com.yahoo.documentapi.DocumentAccess;
import com.yahoo.documentapi.DocumentAccessException;
import com.yahoo.documentapi.ProgressToken;
import com.yahoo.documentapi.SyncParameters;
import com.yahoo.documentapi.SyncSession;
import com.yahoo.documentapi.VisitorControlHandler;
import com.yahoo.documentapi.VisitorParameters;
import com.yahoo.documentapi.VisitorSession;
import com.yahoo.documentapi.messagebus.MessageBusSyncSession;
import com.yahoo.documentapi.messagebus.protocol.DocumentProtocol;
import com.yahoo.documentapi.metrics.DocumentApiMetrics;
import com.yahoo.documentapi.metrics.DocumentOperationStatus;
import com.yahoo.documentapi.metrics.DocumentOperationType;
import com.yahoo.exception.ExceptionUtils;
import com.yahoo.messagebus.StaticThrottlePolicy;
import com.yahoo.metrics.simple.MetricReceiver;
import com.yahoo.vespaclient.ClusterDef;
import com.yahoo.vespaxmlparser.FeedOperation;
import com.yahoo.yolean.concurrent.ConcurrentResourcePool;
import com.yahoo.yolean.concurrent.ResourceFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sends operations to messagebus via document api.
 *
 * @author dybis
 */
public class OperationHandlerImpl implements OperationHandler {

    public interface ClusterEnumerator {
        List<ClusterDef> enumerateClusters();
    }

    public interface BucketSpaceResolver {
        Optional<String> clusterBucketSpaceFromDocumentType(String clusterId, String docType);
    }

    public static class BucketSpaceRoute {
        private final String clusterRoute;
        private final String bucketSpace;

        public BucketSpaceRoute(String clusterRoute, String bucketSpace) {
            this.clusterRoute = clusterRoute;
            this.bucketSpace = bucketSpace;
        }

        public String getClusterRoute() {
            return clusterRoute;
        }

        public String getBucketSpace() {
            return bucketSpace;
        }
    }

    public static final int VISIT_TIMEOUT_MS = 120000;
    public static final int WANTED_DOCUMENT_COUNT_UPPER_BOUND = 1000; // Approximates the max default size of a bucket
    private final DocumentAccess documentAccess;
    private final DocumentApiMetrics metricsHelper;
    private final ClusterEnumerator clusterEnumerator;
    private final BucketSpaceResolver bucketSpaceResolver;

    private static final class SyncSessionFactory extends ResourceFactory<SyncSession> {
        private final DocumentAccess documentAccess;
        SyncSessionFactory(DocumentAccess documentAccess) {
            this.documentAccess = documentAccess;
        }
        @Override
        public SyncSession create() {
            return documentAccess.createSyncSession(new SyncParameters.Builder().build());
        }
    }

    private final ConcurrentResourcePool<SyncSession> syncSessions;

    public OperationHandlerImpl(DocumentAccess documentAccess, ClusterEnumerator clusterEnumerator,
                                BucketSpaceResolver bucketSpaceResolver, MetricReceiver metricReceiver) {
        this.documentAccess = documentAccess;
        this.clusterEnumerator = clusterEnumerator;
        this.bucketSpaceResolver = bucketSpaceResolver;
        syncSessions = new ConcurrentResourcePool<>(new SyncSessionFactory(documentAccess));
        metricsHelper = new DocumentApiMetrics(metricReceiver, "documentV1");
    }

    @Override
    public void shutdown() {
        for (SyncSession session : syncSessions) {
            session.destroy();
        }
        documentAccess.shutdown();
    }

    private static final int HTTP_STATUS_BAD_REQUEST = 400;
    private static final int HTTP_STATUS_INSUFFICIENT_STORAGE = 507;
    private static final int HTTP_PRE_CONDIDTION_FAILED = 412;

    public static int getHTTPStatusCode(Set<Integer> errorCodes) {
        if (errorCodes.size() == 1 && errorCodes.contains(DocumentProtocol.ERROR_NO_SPACE)) {
            return HTTP_STATUS_INSUFFICIENT_STORAGE;
        }
        if (errorCodes.contains(DocumentProtocol.ERROR_TEST_AND_SET_CONDITION_FAILED)) {
            return HTTP_PRE_CONDIDTION_FAILED;
        }
        return HTTP_STATUS_BAD_REQUEST;
    }

    private static Response createErrorResponse(DocumentAccessException documentException, RestUri restUri) {
        if (documentException.hasConditionNotMetError()) {
            return Response.createErrorResponse(getHTTPStatusCode(documentException.getErrorCodes()), "Condition did not match document.",
                    restUri, RestUri.apiErrorCodes.DOCUMENT_CONDITION_NOT_MET);
        }
        return Response.createErrorResponse(getHTTPStatusCode(documentException.getErrorCodes()), documentException.getMessage(), restUri,
                RestUri.apiErrorCodes.DOCUMENT_EXCEPTION);
    }

    @Override
    public VisitResult visit(RestUri restUri, String documentSelection, VisitOptions options) throws RestApiException {
        VisitorParameters visitorParameters = createVisitorParameters(restUri, documentSelection, options);

        VisitorControlHandler visitorControlHandler = new VisitorControlHandler();
        visitorParameters.setControlHandler(visitorControlHandler);
        LocalDataVisitorHandler localDataVisitorHandler = new LocalDataVisitorHandler();
        visitorParameters.setLocalDataHandler(localDataVisitorHandler);

        final VisitorSession visitorSession;
        try {
            visitorSession = documentAccess.createVisitorSession(visitorParameters);
            // Not sure if this line is required
            visitorControlHandler.setSession(visitorSession);
        } catch (Exception e) {
            throw new RestApiException(Response.createErrorResponse(
                    500,
                    "Failed during parsing of arguments for visiting: " + ExceptionUtils.getStackTraceAsString(e),
                    restUri,
                    RestUri.apiErrorCodes.VISITOR_ERROR));
        }
        try {
            return doVisit(visitorControlHandler, localDataVisitorHandler, restUri);
        } finally {
            visitorSession.destroy();
        }
    }

    private static void throwIfFatalVisitingError(VisitorControlHandler handler, RestUri restUri) throws RestApiException {
        final VisitorControlHandler.Result result = handler.getResult();
        if (result.getCode() == VisitorControlHandler.CompletionCode.TIMEOUT) {
            if (! handler.hasVisitedAnyBuckets()) {
                throw new RestApiException(Response.createErrorResponse(500, "Timed out", restUri, RestUri.apiErrorCodes.TIME_OUT));
            } // else: some progress has been made, let client continue with new token.
        } else if (result.getCode() != VisitorControlHandler.CompletionCode.SUCCESS) {
            throw new RestApiException(Response.createErrorResponse(400, result.toString(), RestUri.apiErrorCodes.VISITOR_ERROR));
        }
    }

    private VisitResult doVisit(VisitorControlHandler visitorControlHandler,
                                LocalDataVisitorHandler localDataVisitorHandler,
                                RestUri restUri) throws RestApiException {
        try {
            visitorControlHandler.waitUntilDone(); // VisitorParameters' session timeout implicitly triggers timeout failures.
            throwIfFatalVisitingError(visitorControlHandler, restUri);
        } catch (InterruptedException e) {
            throw new RestApiException(Response.createErrorResponse(500, ExceptionUtils.getStackTraceAsString(e), restUri, RestUri.apiErrorCodes.INTERRUPTED));
        }
        if (localDataVisitorHandler.getErrors().isEmpty()) {
            Optional<String> continuationToken;
            if (! visitorControlHandler.getProgress().isFinished()) {
                continuationToken = Optional.of(visitorControlHandler.getProgress().serializeToString());
            } else {
                continuationToken = Optional.empty();
            }
            return new VisitResult(continuationToken, localDataVisitorHandler.getCommaSeparatedJsonDocuments());
        }
        throw new RestApiException(Response.createErrorResponse(500, localDataVisitorHandler.getErrors(), restUri, RestUri.apiErrorCodes.UNSPECIFIED));
    }

    private void setRoute(SyncSession session, Optional<String> route) throws RestApiException {
        if (! (session instanceof MessageBusSyncSession)) {
            // Not sure if this ever could happen but better be safe.
            throw new RestApiException(Response.createErrorResponse(
                    400, "Can not set route since the API is not using message bus.",
                    RestUri.apiErrorCodes.NO_ROUTE_WHEN_NOT_PART_OF_MESSAGEBUS));
        }
        ((MessageBusSyncSession) session).setRoute(route.orElse("default"));
    }

    @Override
    public void put(RestUri restUri, FeedOperation data, Optional<String> route) throws RestApiException {
        SyncSession syncSession = syncSessions.alloc();
        Response response;
        try {
            Instant startTime = Instant.now();
            DocumentPut put = new DocumentPut(data.getDocument());
            put.setCondition(data.getCondition());
            setRoute(syncSession, route);
            syncSession.put(put);
            metricsHelper.reportSuccessful(DocumentOperationType.PUT, startTime);
            return;
        } catch (DocumentAccessException documentException) {
            response = createErrorResponse(documentException, restUri);
        } catch (Exception e) {
            response = Response.createErrorResponse(500, ExceptionUtils.getStackTraceAsString(e), restUri, RestUri.apiErrorCodes.INTERNAL_EXCEPTION);
        } finally {
            syncSessions.free(syncSession);
        }

        metricsHelper.reportFailure(DocumentOperationType.PUT, DocumentOperationStatus.fromHttpStatusCode(response.getStatus()));
        throw new RestApiException(response);
    }

    @Override
    public void update(RestUri restUri, FeedOperation data, Optional<String> route) throws RestApiException {
        SyncSession syncSession = syncSessions.alloc();
        Response response;
        try {
            Instant startTime = Instant.now();
            setRoute(syncSession, route);
            syncSession.update(data.getDocumentUpdate());
            metricsHelper.reportSuccessful(DocumentOperationType.UPDATE, startTime);
            return;
        } catch (DocumentAccessException documentException) {
            response = createErrorResponse(documentException, restUri);
        } catch (Exception e) {
            response = Response.createErrorResponse(500, ExceptionUtils.getStackTraceAsString(e), restUri, RestUri.apiErrorCodes.INTERNAL_EXCEPTION);
        } finally {
            syncSessions.free(syncSession);
        }

        metricsHelper.reportFailure(DocumentOperationType.UPDATE, DocumentOperationStatus.fromHttpStatusCode(response.getStatus()));
        throw new RestApiException(response);
    }

    @Override
    public void delete(RestUri restUri, String condition, Optional<String> route) throws RestApiException {
        SyncSession syncSession = syncSessions.alloc();
        Response response;
        try {
            Instant startTime = Instant.now();
            DocumentId id = new DocumentId(restUri.generateFullId());
            DocumentRemove documentRemove = new DocumentRemove(id);
            setRoute(syncSession, route);
            if (condition != null && ! condition.isEmpty()) {
                documentRemove.setCondition(new TestAndSetCondition(condition));
            }
            syncSession.remove(documentRemove);
            metricsHelper.reportSuccessful(DocumentOperationType.REMOVE, startTime);
            return;
        } catch (DocumentAccessException documentException) {
            if (documentException.hasConditionNotMetError()) {
                response = Response.createErrorResponse(412, "Condition not met: " + documentException.getMessage(),
                        restUri, RestUri.apiErrorCodes.DOCUMENT_CONDITION_NOT_MET);
            } else {
                response = Response.createErrorResponse(400, documentException.getMessage(), restUri, RestUri.apiErrorCodes.DOCUMENT_EXCEPTION);
            }
        } catch (Exception e) {
            response = Response.createErrorResponse(500, ExceptionUtils.getStackTraceAsString(e), restUri, RestUri.apiErrorCodes.UNSPECIFIED);
        } finally {
            syncSessions.free(syncSession);
        }

        metricsHelper.reportFailure(DocumentOperationType.REMOVE, DocumentOperationStatus.fromHttpStatusCode(response.getStatus()));
        throw new RestApiException(response);
    }

    @Override
    public Optional<String> get(RestUri restUri, Optional<String> fieldSet, Optional<String> cluster) throws RestApiException {
        SyncSession syncSession = syncSessions.alloc();
        // Explicit unary used instead of map() due to unhandled exceptions, blargh.
        Optional<String> route = cluster.isPresent()
                ? Optional.of(clusterDefToRoute(resolveClusterDef(cluster, clusterEnumerator.enumerateClusters())))
                : Optional.empty();
        setRoute(syncSession, route);
        try {
            DocumentId id = new DocumentId(restUri.generateFullId());
            final Document document = syncSession.get(id, fieldSet.orElse(restUri.getDocumentType() + ":[document]"), DocumentProtocol.Priority.NORMAL_1);
            if (document == null) {
                return Optional.empty();
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            JsonWriter jsonWriter = new JsonWriter(outputStream);
            jsonWriter.write(document);
            return Optional.of(outputStream.toString(StandardCharsets.UTF_8.name()));

        } catch (Exception e) {
            throw new RestApiException(Response.createErrorResponse(500, ExceptionUtils.getStackTraceAsString(e), restUri, RestUri.apiErrorCodes.UNSPECIFIED));
        } finally {
            syncSessions.free(syncSession);
        }
    }

    @Override
    public Optional<String> get(RestUri restUri, Optional<String> fieldSet) throws RestApiException {
        return get(restUri, fieldSet, Optional.empty());
    }

    @Override
    public Optional<String> get(RestUri restUri) throws RestApiException {
        return get(restUri, Optional.empty());
    }

    private static boolean isValidBucketSpace(String spaceName) {
        // TODO need bucket space repo in Java as well
        return (FixedBucketSpaces.defaultSpace().equals(spaceName)
                || FixedBucketSpaces.globalSpace().equals(spaceName));
    }

    protected BucketSpaceRoute resolveBucketSpaceRoute(Optional<String> wantedCluster,
                                                       Optional<String> wantedBucketSpace,
                                                       RestUri restUri) throws RestApiException {
        final List<ClusterDef> clusters = clusterEnumerator.enumerateClusters();
        ClusterDef clusterDef = resolveClusterDef(wantedCluster, clusters);

        String targetBucketSpace;
        if (!restUri.isRootOnly()) {
            String docType = restUri.getDocumentType();
            Optional<String> resolvedSpace = bucketSpaceResolver.clusterBucketSpaceFromDocumentType(clusterDef.getName(), docType);
            if (!resolvedSpace.isPresent()) {
                throw new RestApiException(Response.createErrorResponse(400, String.format(
                        "Document type '%s' in cluster '%s' is not mapped to a known bucket space", docType, clusterDef.getName()),
                        RestUri.apiErrorCodes.UNKNOWN_BUCKET_SPACE));
            }
            targetBucketSpace = resolvedSpace.get();
        } else {
            if (wantedBucketSpace.isPresent() && !isValidBucketSpace(wantedBucketSpace.get())) {
                // TODO enumerate known bucket spaces from a repo instead of having a fixed set
                throw new RestApiException(Response.createErrorResponse(400, String.format(
                        "Bucket space '%s' is not a known bucket space (expected '%s' or '%s')",
                        wantedBucketSpace.get(), FixedBucketSpaces.defaultSpace(), FixedBucketSpaces.globalSpace()),
                        RestUri.apiErrorCodes.UNKNOWN_BUCKET_SPACE));
            }
            targetBucketSpace = wantedBucketSpace.orElse(FixedBucketSpaces.defaultSpace());
        }

        return new BucketSpaceRoute(clusterDefToRoute(clusterDef), targetBucketSpace);
    }

    protected static ClusterDef resolveClusterDef(Optional<String> wantedCluster, List<ClusterDef> clusters) throws RestApiException {
        if (clusters.size() == 0) {
            throw new IllegalArgumentException("Your Vespa cluster does not have any content clusters " +
                                               "declared. Visiting feature is not available.");
        }
        if (! wantedCluster.isPresent()) {
            if (clusters.size() != 1) {
                String message = "Several clusters exist: " +
                                 clusters.stream().map(c -> "'" + c.getName() + "'").collect(Collectors.joining(", ")) +
                                 ". You must specify one.";
                throw new RestApiException(Response.createErrorResponse(400,
                                                                        message,
                                                                        RestUri.apiErrorCodes.SEVERAL_CLUSTERS));
            }
            return clusters.get(0);
        }

        for (ClusterDef clusterDef : clusters) {
            if (clusterDef.getName().equals(wantedCluster.get())) {
                return clusterDef;
            }
        }
        String message = "Your vespa cluster contains the content clusters " +
                         clusters.stream().map(c -> "'" + c.getName() + "'").collect(Collectors.joining(", ")) +
                         ", not '" + wantedCluster.get() + "'. Please select a valid vespa cluster.";
        throw new RestApiException(Response.createErrorResponse(400,
                                                                message,
                                                                RestUri.apiErrorCodes.MISSING_CLUSTER));
    }

    protected static String clusterDefToRoute(ClusterDef clusterDef) {
        return "[Storage:cluster=" + clusterDef.getName() + ";clusterconfigid=" + clusterDef.getConfigId() + "]";
    }

    private static String buildAugmentedDocumentSelection(RestUri restUri, String  documentSelection) {
        if (restUri.isRootOnly()) {
            return documentSelection; // May be empty, that's fine.
        }
        StringBuilder selection = new StringBuilder();
        if (! documentSelection.isEmpty()) {
            selection.append("((").append(documentSelection).append(") and ");
        }
        selection.append(restUri.getDocumentType()).append(" and (id.namespace=='").append(restUri.getNamespace()).append("')");
        if (! documentSelection.isEmpty()) {
            selection.append(")");
        }
        return selection.toString();
    }

    private VisitorParameters createVisitorParameters(
            RestUri restUri,
            String documentSelection,
            VisitOptions options)
            throws RestApiException {

        if (restUri.isRootOnly() && !options.cluster.isPresent()) {
            throw new RestApiException(Response.createErrorResponse(400,
                    "Must set 'cluster' parameter to a valid content cluster id when visiting at a root /document/v1/ level",
                    RestUri.apiErrorCodes.MISSING_CLUSTER));
        }

        String augmentedSelection = buildAugmentedDocumentSelection(restUri, documentSelection);

        VisitorParameters params = new VisitorParameters(augmentedSelection);
        // Only return fieldset that is part of the document, unless we're visiting across all
        // document types in which case we can't explicitly state a single document type.
        // This matches legacy /visit API and vespa-visit tool behavior.
        params.fieldSet(options.fieldSet.orElse(
                restUri.isRootOnly() ? "[all]" : restUri.getDocumentType() + ":[document]"));
        params.setMaxBucketsPerVisitor(1);
        params.setMaxPending(32);
        params.setMaxFirstPassHits(1);
        params.setMaxTotalHits(options.wantedDocumentCount
                .map(n -> Math.min(Math.max(n, 1), WANTED_DOCUMENT_COUNT_UPPER_BOUND))
                .orElse(1));
        params.setThrottlePolicy(new StaticThrottlePolicy().setMaxPendingCount(options.concurrency.orElse(1)));
        params.setToTimestamp(0L);
        params.setFromTimestamp(0L);
        params.setSessionTimeoutMs(VISIT_TIMEOUT_MS);

        params.visitInconsistentBuckets(true); // TODO document this as part of consistency doc

        BucketSpaceRoute bucketSpaceRoute = resolveBucketSpaceRoute(options.cluster, options.bucketSpace, restUri);
        params.setRoute(bucketSpaceRoute.getClusterRoute());
        params.setBucketSpace(bucketSpaceRoute.getBucketSpace());

        params.setTraceLevel(0);
        params.setPriority(DocumentProtocol.Priority.NORMAL_4);
        params.setVisitRemoves(false);

        if (options.continuation.isPresent()) {
            try {
                params.setResumeToken(ProgressToken.fromSerializedString(options.continuation.get()));
            } catch (Exception e) {
                throw new RestApiException(Response.createErrorResponse(500, ExceptionUtils.getStackTraceAsString(e), restUri, RestUri.apiErrorCodes.UNSPECIFIED));
            }
        }
        return params;
    }

}
