// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.restapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.container.jdisc.LoggingRequestHandler;
import com.yahoo.container.logging.AccessLog;
import com.yahoo.vespa.hosted.dockerapi.metrics.MetricReceiverWrapper;
import com.yahoo.vespa.hosted.node.admin.nodeadmin.NodeAdminStateUpdater;
import com.yahoo.vespa.hosted.node.admin.provider.ComponentsProvider;

import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executor;

import static com.yahoo.jdisc.http.HttpRequest.Method.GET;
import static com.yahoo.jdisc.http.HttpRequest.Method.PUT;

/**
 * Rest API for suspending and resuming the docker host.
 * There are two non-blocking idempotent calls: /resume and /suspend.
 *
 * There is one debug call: /info
 *
 * @author dybis
 */
public class RestApiHandler extends LoggingRequestHandler{

    private final static ObjectMapper objectMapper = new ObjectMapper();
    private final NodeAdminStateUpdater refresher;
    private final MetricReceiverWrapper metricReceiverWrapper;

    public RestApiHandler(Executor executor, AccessLog accessLog, ComponentsProvider componentsProvider) {
        super(executor, accessLog);
        this.refresher = componentsProvider.getNodeAdminStateUpdater();
        this.metricReceiverWrapper = componentsProvider.getMetricReceiverWrapper();
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        if (request.getMethod() == GET) {
            return handleGet(request);
        }
        if (request.getMethod() == PUT) {
            return handlePut(request);
        }
        return new SimpleResponse(400, "Only PUT and GET are implemented.");
    }

    private HttpResponse handleGet(HttpRequest request) {
        String path = request.getUri().getPath();
        if (path.endsWith("/info")) {
            return new SimpleObjectResponse(200, refresher.getDebugPage());
        }

        if (path.endsWith("/metrics")) {
            return new HttpResponse(200) {
                @Override
                public String getContentType() {
                    return MediaType.APPLICATION_JSON;
                }

                @Override
                public void render(OutputStream outputStream) throws IOException {
                    try (PrintStream printStream = new PrintStream(outputStream)) {
                        for (MetricReceiverWrapper.DimensionMetrics dimensionMetrics : metricReceiverWrapper) {
                            String secretAgentJsonReport = dimensionMetrics.toSecretAgentReport() + "\n";
                            printStream.write(secretAgentJsonReport.getBytes(StandardCharsets.UTF_8.name()));
                        }
                    }
                }
            };
        }
        return new SimpleResponse(400, "unknown path" + path);
    }

    private HttpResponse handlePut(HttpRequest request) {
        String path = request.getUri().getPath();
        // Check paths to disallow illegal state changes
        if (path.endsWith("/resume")) {
            final Optional<String> errorMessage = refresher.setResumeStateAndCheckIfResumed(NodeAdminStateUpdater.State.RESUMED);
            if (errorMessage.isPresent()) {
                return new SimpleResponse(409, errorMessage.get());
            }
            return new SimpleResponse(200, "ok");
        }
        if (path.endsWith("/suspend")) {
            Optional<String> errorMessage = refresher.setResumeStateAndCheckIfResumed(NodeAdminStateUpdater.State.SUSPENDED);
            if (errorMessage.isPresent()) {
                return new SimpleResponse(409, errorMessage.get());
            }
            return new SimpleResponse(200, "ok");
        }
        return new SimpleResponse(400, "unknown path" + path);
    }

    private static class SimpleResponse extends HttpResponse {
        private final String jsonMessage;

        SimpleResponse(int code, String message) {
            super(code);
            ObjectNode objectNode = objectMapper.createObjectNode();
            objectNode.put("jsonMessage", message);
            this.jsonMessage = objectNode.toString();
        }

        @Override
        public String getContentType() {
            return MediaType.APPLICATION_JSON;
        }

        @Override
        public void render(OutputStream outputStream) throws IOException {
            outputStream.write(jsonMessage.getBytes(StandardCharsets.UTF_8.name()));
        }
    }

    private static class SimpleObjectResponse extends HttpResponse {
        private final Object response;

        SimpleObjectResponse(int status, Object response) {
            super(status);
            this.response = response;
        }

        @Override
        public String getContentType() {
            return MediaType.APPLICATION_JSON;
        }

        @Override
        public void render(OutputStream outputStream) throws IOException {
            objectMapper.writeValue(outputStream, response);
        }
    }
}
