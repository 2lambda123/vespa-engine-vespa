// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi;

import com.yahoo.application.container.JDisc;
import com.yahoo.application.container.handler.Request;
import com.yahoo.application.container.handler.Response;
import com.yahoo.component.ComponentSpecification;
import com.yahoo.component.Version;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.container.http.filter.FilterChainRepository;
import com.yahoo.jdisc.http.filter.SecurityRequestFilter;
import com.yahoo.jdisc.http.filter.SecurityRequestFilterChain;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.application.SystemApplication;
import com.yahoo.vespa.hosted.controller.integration.ConfigServerMock;
import com.yahoo.vespa.hosted.controller.versions.VersionStatus;
import org.junit.ComparisonFailure;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;

/**
 * Provides testing of JSON container responses
 * 
 * @author bratseth
 */
public class ContainerTester {

    private final JDisc container;
    private final String responseFilePath;
    
    public ContainerTester(JDisc container, String responseFilePath) {
        this.container = container;
        this.responseFilePath = responseFilePath;
    }
    
    public JDisc container() { return container; }

    public Controller controller() {
        return (Controller) container.components().getComponent(Controller.class.getName());
    }

    public ConfigServerMock configServer() {
        return (ConfigServerMock) container.components().getComponent(ConfigServerMock.class.getName());
    }

    public void computeVersionStatus() {
        controller().updateVersionStatus(VersionStatus.compute(controller()));
    }

    public void upgradeSystem(Version version) {
        controller().curator().writeControllerVersion(controller().hostname(), version);
        for (ZoneId zone : controller().zoneRegistry().zones().all().ids()) {
            for (SystemApplication application : SystemApplication.all()) {
                configServer().setVersion(application.id(), zone, version);
                configServer().convergeServices(application.id(), zone);
            }
        }
        computeVersionStatus();
    }

    public void assertResponse(Supplier<Request> request, File responseFile) {
        assertResponse(request.get(), responseFile);
    }

    public void assertResponse(Request request, File responseFile) {
        assertResponse(request, responseFile, 200);
    }

    public void assertResponse(Supplier<Request> request, File responseFile, int expectedStatusCode) {
        assertResponse(request.get(), responseFile, expectedStatusCode);
    }

    public void assertResponse(Request request, File responseFile, int expectedStatusCode) {
        String expectedResponse = readTestFile(responseFile.toString());
        expectedResponse = include(expectedResponse);
        expectedResponse = expectedResponse.replaceAll("(\"[^\"]*\")|\\s*", "$1"); // Remove whitespace
        FilterResult filterResult = invokeSecurityFilters(request);
        request = filterResult.request;
        Response response = filterResult.response != null ? filterResult.response : container.handleRequest(request);
        String responseString;
        try {
            responseString = response.getBodyAsString();
        } catch (CharacterCodingException e) {
            throw new UncheckedIOException(e);
        }
        if (expectedResponse.contains("(ignore)")) {
            // Convert expected response to a literal pattern and replace any ignored field with a pattern that matches
            // until the first stop character
            String stopCharacters = "[^,:\\\\[\\\\]{}]";
            String expectedResponsePattern = Pattern.quote(expectedResponse)
                                                    .replaceAll("\"?\\(ignore\\)\"?", "\\\\E" +
                                                                                      stopCharacters + "*\\\\Q");
            if (!Pattern.matches(expectedResponsePattern, responseString)) {
                throw new ComparisonFailure(responseFile.toString() + " (with ignored fields)",
                                            expectedResponsePattern, responseString);
            }
        } else {
            assertEquals(responseFile.toString(), expectedResponse, responseString);
        }
        assertEquals("Status code", expectedStatusCode, response.getStatus());
    }

    public void assertResponse(Supplier<Request> request, String expectedResponse) {
        assertResponse(request.get(), expectedResponse, 200);
    }

    public void assertResponse(Request request, String expectedResponse) {
        assertResponse(request, expectedResponse, 200);
    }

    public void assertResponse(Supplier<Request> request, String expectedResponse, int expectedStatusCode) {
        assertResponse(request.get(), expectedResponse, expectedStatusCode);
    }

    public void assertResponse(Request request, String expectedResponse, int expectedStatusCode) {
        FilterResult filterResult = invokeSecurityFilters(request);
        request = filterResult.request;
        Response response = filterResult.response != null ? filterResult.response : container.handleRequest(request);
        try {
            assertEquals(expectedResponse, response.getBodyAsString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertEquals("Status code", expectedStatusCode, response.getStatus());
    }

    // Hack to run request filters as part of the request processing chain.
    // Limitation: Bindings ignored, disc filter request wrapper only support limited set of methods.
    private FilterResult invokeSecurityFilters(Request request) {
        FilterChainRepository filterChainRepository = (FilterChainRepository) container.components().getComponent(FilterChainRepository.class.getName());
        SecurityRequestFilterChain chain = (SecurityRequestFilterChain) filterChainRepository.getFilter(ComponentSpecification.fromString("default"));
        for (SecurityRequestFilter securityRequestFilter : chain.getFilters()) {
            ApplicationRequestToDiscFilterRequestWrapper discFilterRequest = new ApplicationRequestToDiscFilterRequestWrapper(request);
            ResponseHandlerToApplicationResponseWrapper responseHandlerWrapper = new ResponseHandlerToApplicationResponseWrapper();
            securityRequestFilter.filter(discFilterRequest, responseHandlerWrapper);
            request = discFilterRequest.getUpdatedRequest();
            Optional<Response> filterResponse = responseHandlerWrapper.toResponse();
            if (filterResponse.isPresent()) {
                return new FilterResult(request, filterResponse.get());
            }
        }
        return new FilterResult(request, null);
    }

    /** Replaces @include(localFile) with the content of the file */
    private String include(String response) {
        // Please don't look at this code
        int includeIndex = response.indexOf("@include(");
        if (includeIndex < 0) return response;
        String prefix = response.substring(0, includeIndex);
        String rest = response.substring(includeIndex + "@include(".length());
        int filenameEnd = rest.indexOf(")");
        String includeFileName = rest.substring(0, filenameEnd);
        String includedContent = readTestFile(includeFileName);
        includedContent = include(includedContent);
        String postFix = rest.substring(filenameEnd + 1);
        postFix = include(postFix);
        return prefix + includedContent + postFix;
    }

    private String readTestFile(String name) {
        try {
            return new String(Files.readAllBytes(Paths.get(responseFilePath, name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static class FilterResult {
        final Request request;
        final Response response;

        FilterResult(Request request, Response response) {
            this.request = request;
            this.response = response;
        }
    }

}
    
