package com.yahoo.vespa.config.server.http;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.yahoo.container.jdisc.HttpResponse;
import org.junit.Rule;
import org.junit.Test;


import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.Assert.assertEquals;

public class LogRetrieverTest {

    private String logServerHostName = "http://localhost:8080/";
    @Rule
    public final WireMockRule wireMock = new WireMockRule(options().port(8080), true);

    @Test
    public void testThatLogHandlerPropagatesResponseBody() throws IOException {
        String expectedBody = "{logs-json}";
        stubFor(get(urlEqualTo("/")).willReturn(okJson(expectedBody)));
        HttpResponse response = LogRetriever.getLogs(logServerHostName);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        response.render(byteArrayOutputStream);
        assertEquals(expectedBody, byteArrayOutputStream.toString());
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testThatNotFoundLogServerReturns404() throws IOException {
        stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200)));
        HttpResponse response = LogRetriever.getLogs("http://wrong-host:8080/");
        assertEquals(404, response.getStatus());
    }



}