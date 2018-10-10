// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document.restapi;

import com.yahoo.documentapi.DocumentAccess;
import com.yahoo.documentapi.ProgressToken;
import com.yahoo.documentapi.VisitorControlHandler;
import com.yahoo.documentapi.VisitorParameters;
import com.yahoo.documentapi.VisitorSession;
import com.yahoo.documentapi.SyncParameters;
import com.yahoo.documentapi.SyncSession;
import com.yahoo.documentapi.messagebus.MessageBusSyncSession;
import com.yahoo.messagebus.StaticThrottlePolicy;
import com.yahoo.metrics.simple.MetricReceiver;
import com.yahoo.vdslib.VisitorStatistics;
import com.yahoo.vespaclient.ClusterDef;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


public class OperationHandlerImplTest {

    @Test(expected = IllegalArgumentException.class)
    public void missingClusterDef() throws RestApiException {
        List<ClusterDef> clusterDef = new ArrayList<>();
        OperationHandlerImpl.resolveClusterDef(Optional.empty(), clusterDef);
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingClusterDefSpecifiedCluster() throws RestApiException {
        List<ClusterDef> clusterDef = new ArrayList<>();
        OperationHandlerImpl.resolveClusterDef(Optional.of("cluster"), clusterDef);
    }

    @Test(expected = RestApiException.class)
    public void oneClusterPresentNotMatching() throws RestApiException {
        List<ClusterDef> clusterDef = new ArrayList<>();
        clusterDef.add(new ClusterDef("foo", "configId"));
        OperationHandlerImpl.resolveClusterDef(Optional.of("cluster"), clusterDef);
    }

    private static String toRoute(ClusterDef clusterDef) {
        return OperationHandlerImpl.clusterDefToRoute(clusterDef);
    }

    @Test()
    public void oneClusterMatching() throws RestApiException {
        List<ClusterDef> clusterDef = new ArrayList<>();
        clusterDef.add(new ClusterDef("foo", "configId"));
        assertThat(toRoute(OperationHandlerImpl.resolveClusterDef(Optional.of("foo"), clusterDef)),
                is("[Storage:cluster=foo;clusterconfigid=configId]"));
    }

    @Test()
    public void oneClusterMatchingManyAvailable() throws RestApiException {
        List<ClusterDef> clusterDef = new ArrayList<>();
        clusterDef.add(new ClusterDef("foo2", "configId2"));
        clusterDef.add(new ClusterDef("foo", "configId"));
        clusterDef.add(new ClusterDef("foo3", "configId2"));
        assertThat(toRoute(OperationHandlerImpl.resolveClusterDef(Optional.of("foo"), clusterDef)),
                is("[Storage:cluster=foo;clusterconfigid=configId]"));
    }

    @Test()
    public void unknown_target_cluster_throws_exception() throws RestApiException, IOException {
        List<ClusterDef> clusterDef = new ArrayList<>();
        clusterDef.add(new ClusterDef("foo2", "configId2"));
        clusterDef.add(new ClusterDef("foo", "configId"));
        clusterDef.add(new ClusterDef("foo3", "configId2"));
        try {
            OperationHandlerImpl.resolveClusterDef(Optional.of("wrong"), clusterDef);
        } catch(RestApiException e) {
            String errorMsg = renderRestApiExceptionAsString(e);
            assertThat(errorMsg, is("{\"errors\":[{\"description\":" +
                    "\"MISSING_CLUSTER Your vespa cluster contains the content clusters foo2 (configId2), foo (configId)," +
                    " foo3 (configId2),  not wrong. Please select a valid vespa cluster.\",\"id\":-9}]}"));
            return;
        }
        fail("Expected exception");
    }

    private String renderRestApiExceptionAsString(RestApiException e) throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        e.getResponse().render(stream);
        return new String( stream.toByteArray());
    }

    private static class OperationHandlerImplFixture {
        DocumentAccess documentAccess = mock(DocumentAccess.class);
        AtomicReference<VisitorParameters> assignedParameters = new AtomicReference<>();
        VisitorControlHandler.CompletionCode completionCode = VisitorControlHandler.CompletionCode.SUCCESS;
        int bucketsVisited = 0;
        Map<String, String> bucketSpaces = new HashMap<>();
        SyncSession mockSyncSession = mock(MessageBusSyncSession.class); // MBus session needed to avoid setRoute throwing.

        OperationHandlerImplFixture() {
            bucketSpaces.put("foo", "global");
            bucketSpaces.put("document-type", "default");
        }

        OperationHandlerImpl createHandler() throws Exception {
            VisitorSession visitorSession = mock(VisitorSession.class);
            // Pre-bake an already completed session
            when(documentAccess.createVisitorSession(any(VisitorParameters.class))).thenAnswer(p -> {
                VisitorParameters params = (VisitorParameters)p.getArguments()[0];
                assignedParameters.set(params);

                VisitorStatistics statistics = new VisitorStatistics();
                statistics.setBucketsVisited(bucketsVisited);
                params.getControlHandler().onVisitorStatistics(statistics);

                ProgressToken progress = new ProgressToken();
                params.getControlHandler().onProgress(progress);

                params.getControlHandler().onDone(completionCode, "bork bork");
                return visitorSession;
            });
            when(documentAccess.createSyncSession(any(SyncParameters.class))).thenReturn(mockSyncSession);
            OperationHandlerImpl.ClusterEnumerator clusterEnumerator = () -> Arrays.asList(new ClusterDef("foo", "configId"));
            OperationHandlerImpl.BucketSpaceResolver bucketSpaceResolver = (configId, docType) -> Optional.ofNullable(bucketSpaces.get(docType));
            return new OperationHandlerImpl(documentAccess, clusterEnumerator, bucketSpaceResolver, MetricReceiver.nullImplementation);
        }
    }

    private static OperationHandler.VisitOptions.Builder optionsBuilder() {
        return OperationHandler.VisitOptions.builder();
    }

    private static RestUri dummyVisitUri() throws Exception {
        return new RestUri(new URI("http://localhost/document/v1/namespace/document-type/docid/"));
    }

    private static RestUri apiRootVisitUri() throws Exception {
        return new RestUri(new URI("http://localhost/document/v1/"));
    }

    private static RestUri dummyGetUri() throws Exception {
        return new RestUri(new URI("http://localhost/document/v1/namespace/document-type/docid/foo"));
    }

    private static OperationHandler.VisitOptions visitOptionsWithWantedDocumentCount(int wantedDocumentCount) {
        return optionsBuilder().wantedDocumentCount(wantedDocumentCount).build();
    }

    private static OperationHandler.VisitOptions emptyVisitOptions() {
        return optionsBuilder().build();
    }

    @Test
    public void timeout_without_buckets_visited_throws_timeout_error() throws Exception {
        OperationHandlerImplFixture fixture = new OperationHandlerImplFixture();
        fixture.completionCode = VisitorControlHandler.CompletionCode.TIMEOUT;
        fixture.bucketsVisited = 0;
        // RestApiException hides its guts internally, so cannot trivially use @Rule directly to check for error category
        try {
            OperationHandlerImpl handler = fixture.createHandler();
            handler.visit(dummyVisitUri(), "", emptyVisitOptions());
            fail("Exception expected");
        } catch (RestApiException e) {
            assertThat(e.getResponse().getStatus(), is(500));
            assertThat(renderRestApiExceptionAsString(e), containsString("Timed out"));
        }
    }

    @Test
    public void timeout_with_buckets_visited_does_not_throw_timeout_error() throws Exception {
        OperationHandlerImplFixture fixture = new OperationHandlerImplFixture();
        fixture.completionCode = VisitorControlHandler.CompletionCode.TIMEOUT;
        fixture.bucketsVisited = 1;

        OperationHandlerImpl handler = fixture.createHandler();
        handler.visit(dummyVisitUri(), "", emptyVisitOptions());
    }

    @Test
    public void handler_sets_default_visitor_session_timeout_parameter() throws Exception {
        OperationHandlerImplFixture fixture = new OperationHandlerImplFixture();
        OperationHandlerImpl handler = fixture.createHandler();

        handler.visit(dummyVisitUri(), "", emptyVisitOptions());

        assertThat(fixture.assignedParameters.get().getSessionTimeoutMs(), is((long)OperationHandlerImpl.VISIT_TIMEOUT_MS));
    }

    private static VisitorParameters generatedVisitParametersFrom(RestUri restUri, String documentSelection,
                                                                  OperationHandler.VisitOptions options) throws Exception {
        OperationHandlerImplFixture fixture = new OperationHandlerImplFixture();
        OperationHandlerImpl handler = fixture.createHandler();

        handler.visit(restUri, documentSelection, options);
        return fixture.assignedParameters.get();
    }

    private static VisitorParameters generatedParametersFromVisitOptions(OperationHandler.VisitOptions options) throws Exception {
        return generatedVisitParametersFrom(dummyVisitUri(), "", options);
    }

    @Test
    public void document_type_is_mapped_to_correct_bucket_space() throws Exception {
        OperationHandlerImplFixture fixture = new OperationHandlerImplFixture();
        fixture.bucketSpaces.put("document-type", "langbein");
        OperationHandlerImpl handler = fixture.createHandler();
        handler.visit(dummyVisitUri(), "", emptyVisitOptions());

        VisitorParameters parameters = fixture.assignedParameters.get();
        assertEquals("langbein", parameters.getBucketSpace());
    }

    @Test
    public void unknown_bucket_space_mapping_throws_exception() throws Exception {
        OperationHandlerImplFixture fixture = new OperationHandlerImplFixture();
        fixture.bucketSpaces.remove("document-type");
        try {
            OperationHandlerImpl handler = fixture.createHandler();
            handler.visit(dummyVisitUri(), "", emptyVisitOptions());
            fail("Exception expected");
        } catch (RestApiException e) {
            assertThat(e.getResponse().getStatus(), is(400));
            String errorMsg = renderRestApiExceptionAsString(e);
            // FIXME isn't this really more of a case of unknown document type..?
            assertThat(errorMsg, is("{\"errors\":[{\"description\":" +
                    "\"UNKNOWN_BUCKET_SPACE Document type 'document-type' in cluster 'foo' is not mapped to a known bucket space\",\"id\":-16}]}"));
        }
    }

    @Test
    public void provided_wanted_document_count_is_propagated_to_visitor_parameters() throws Exception {
        VisitorParameters params = generatedParametersFromVisitOptions(visitOptionsWithWantedDocumentCount(123));
        assertThat(params.getMaxTotalHits(), is((long)123));
    }

    @Test
    public void wanted_document_count_is_1_unless_specified() throws Exception {
        VisitorParameters params = generatedParametersFromVisitOptions(emptyVisitOptions());
        assertThat(params.getMaxTotalHits(), is((long)1));
    }

    @Test
    public void too_low_wanted_document_count_is_bounded_to_1() throws Exception {
        VisitorParameters params = generatedParametersFromVisitOptions(visitOptionsWithWantedDocumentCount(-1));
        assertThat(params.getMaxTotalHits(), is((long)1));

        params = generatedParametersFromVisitOptions(visitOptionsWithWantedDocumentCount(Integer.MIN_VALUE));
        assertThat(params.getMaxTotalHits(), is((long)1));

        params = generatedParametersFromVisitOptions(visitOptionsWithWantedDocumentCount(0));
        assertThat(params.getMaxTotalHits(), is((long)1));
    }

    @Test
    public void too_high_wanted_document_count_is_bounded_to_upper_bound() throws Exception {
        VisitorParameters params = generatedParametersFromVisitOptions(visitOptionsWithWantedDocumentCount(OperationHandlerImpl.WANTED_DOCUMENT_COUNT_UPPER_BOUND + 1));
        assertThat(params.getMaxTotalHits(), is((long)OperationHandlerImpl.WANTED_DOCUMENT_COUNT_UPPER_BOUND));

        params = generatedParametersFromVisitOptions(visitOptionsWithWantedDocumentCount(Integer.MAX_VALUE));
        assertThat(params.getMaxTotalHits(), is((long)OperationHandlerImpl.WANTED_DOCUMENT_COUNT_UPPER_BOUND));
    }

    @Test
    public void visit_field_set_covers_all_fields_by_default() throws Exception {
        VisitorParameters params = generatedParametersFromVisitOptions(emptyVisitOptions());
        assertThat(params.fieldSet(), equalTo("document-type:[document]"));
    }

    @Test
    public void provided_visit_fieldset_is_propagated_to_visitor_parameters() throws Exception {
        VisitorParameters params = generatedParametersFromVisitOptions(optionsBuilder().fieldSet("document-type:bjarne").build());
        assertThat(params.fieldSet(), equalTo("document-type:bjarne"));
    }

    @Test
    public void visit_concurrency_is_1_by_default() throws Exception {
        VisitorParameters params = generatedParametersFromVisitOptions(emptyVisitOptions());
        assertThat(params.getThrottlePolicy(), instanceOf(StaticThrottlePolicy.class));
        assertThat(((StaticThrottlePolicy)params.getThrottlePolicy()).getMaxPendingCount(), is((int)1));
    }

    @Test
    public void visit_concurrency_is_propagated_to_visitor_parameters() throws Exception {
        VisitorParameters params = generatedParametersFromVisitOptions(optionsBuilder().concurrency(3).build());
        assertThat(params.getThrottlePolicy(), instanceOf(StaticThrottlePolicy.class));
        assertThat(((StaticThrottlePolicy)params.getThrottlePolicy()).getMaxPendingCount(), is((int)3));
    }

    @Test
    public void get_field_covers_all_fields_by_default() throws Exception {
        OperationHandlerImplFixture fixture = new OperationHandlerImplFixture();
        OperationHandlerImpl handler = fixture.createHandler();
        handler.get(dummyGetUri(), Optional.empty());

        verify(fixture.mockSyncSession).get(any(), eq("document-type:[document]"), any());
    }

    @Test
    public void provided_get_fieldset_is_propagated_to_sync_session() throws Exception {
        OperationHandlerImplFixture fixture = new OperationHandlerImplFixture();
        OperationHandlerImpl handler = fixture.createHandler();
        handler.get(dummyGetUri(), Optional.of("donald,duck"));

        verify(fixture.mockSyncSession).get(any(), eq("donald,duck"), any());
    }

    @Test
    public void api_root_visit_uri_requires_cluster_set() throws Exception {
        OperationHandlerImplFixture fixture = new OperationHandlerImplFixture();
        OperationHandlerImpl handler = fixture.createHandler();
        try {
            handler.visit(apiRootVisitUri(), "", emptyVisitOptions());
            fail("Exception expected");
        } catch (RestApiException e) {
            assertThat(e.getResponse().getStatus(), is(400));
            assertThat(renderRestApiExceptionAsString(e), containsString(
                    "MISSING_CLUSTER Must set 'cluster' parameter to a valid content cluster id " +
                            "when visiting at a root /document/v1/ level"));
        }
    }

    @Test
    public void api_root_visiting_propagates_request_route() throws Exception {
        VisitorParameters parameters = generatedVisitParametersFrom(apiRootVisitUri(), "", optionsBuilder().cluster("foo").build());
        assertEquals("[Storage:cluster=foo;clusterconfigid=configId]", parameters.getRoute().toString());
    }

    @Test
    public void api_root_visiting_targets_default_bucket_space_by_default() throws Exception {
        VisitorParameters parameters = generatedVisitParametersFrom(apiRootVisitUri(), "", optionsBuilder().cluster("foo").build());
        assertEquals("default", parameters.getBucketSpace());
    }

    @Test
    public void api_root_visiting_can_explicitly_specify_bucket_space() throws Exception {
        VisitorParameters parameters = generatedVisitParametersFrom(apiRootVisitUri(), "",
                optionsBuilder().cluster("foo").bucketSpace("global").build());
        assertEquals("global", parameters.getBucketSpace());
    }

    @Test
    public void api_root_visiting_throws_exception_on_unknown_bucket_space_name() throws Exception {
        try {
            VisitorParameters parameters = generatedVisitParametersFrom(apiRootVisitUri(), "",
                    optionsBuilder().cluster("foo").bucketSpace("langbein").build());
        } catch (RestApiException e) {
            assertThat(e.getResponse().getStatus(), is(400));
            assertThat(renderRestApiExceptionAsString(e), containsString(
                    "UNKNOWN_BUCKET_SPACE Bucket space 'langbein' is not a known bucket space " +
                            "(expected 'default' or 'global')"));
        }
    }

    @Test
    public void api_root_visiting_has_empty_document_selection_by_default() throws Exception {
        VisitorParameters parameters = generatedVisitParametersFrom(apiRootVisitUri(), "", optionsBuilder().cluster("foo").build());
        assertEquals("", parameters.getDocumentSelection());
    }

    @Test
    public void api_root_visiting_propagates_provided_document_selection() throws Exception {
        VisitorParameters parameters = generatedVisitParametersFrom(apiRootVisitUri(), "baz.blarg", optionsBuilder().cluster("foo").build());
        // Note: syntax correctness of selection is checked and enforced by RestApi
        assertEquals("baz.blarg", parameters.getDocumentSelection());
    }

    @Test
    public void api_root_visiting_uses_all_fieldset_by_default() throws Exception {
        VisitorParameters parameters = generatedVisitParametersFrom(apiRootVisitUri(), "", optionsBuilder().cluster("foo").build());
        assertEquals("[all]", parameters.getFieldSet());
    }

    @Test
    public void api_root_visiting_propagates_provided_fieldset() throws Exception {
        VisitorParameters parameters = generatedVisitParametersFrom(apiRootVisitUri(), "",
                optionsBuilder().cluster("foo").fieldSet("zoidberg:[document]").build());
        assertEquals("zoidberg:[document]", parameters.getFieldSet());
    }

    @Test
    public void namespace_and_doctype_augmented_selection_has_parenthesized_selection_sub_expression() throws Exception {
        VisitorParameters parameters = generatedVisitParametersFrom(dummyVisitUri(), "1 != 2", optionsBuilder().cluster("foo").build());
        assertEquals("((1 != 2) and document-type and (id.namespace=='namespace'))", parameters.getDocumentSelection());
    }

    @Test
    public void namespace_and_doctype_visit_without_selection_does_not_contain_selection_sub_expression() throws Exception {
        VisitorParameters parameters = generatedVisitParametersFrom(dummyVisitUri(), "", optionsBuilder().cluster("foo").build());
        assertEquals("document-type and (id.namespace=='namespace')", parameters.getDocumentSelection());
    }

}
