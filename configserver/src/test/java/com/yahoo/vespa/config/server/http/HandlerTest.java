// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.http;

import com.yahoo.container.jdisc.HttpResponse;

import java.io.IOException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;

/**
 * Base class for handler tests
 *
 * @author hmusum
 * @since 5.1.14
 */
public class HandlerTest {

    public static void assertHttpStatusCodeErrorCodeAndMessage(HttpResponse response, int statusCode, HttpErrorResponse.errorCodes errorCode, String message) throws IOException {
        assertNotNull(response);
        String renderedString = SessionHandlerTest.getRenderedString(response);
        if (renderedString == null) {
            renderedString = "assert failed";
        }
        assertThat(renderedString, response.getStatus(), is(statusCode));
        if (errorCode != null) {
            assertThat(renderedString, containsString(errorCode.name()));
        }
        assertThat(renderedString, containsString(message));
    }

    public static void assertHttpStatusCodeAndMessage(HttpResponse response, int statusCode, String message) throws IOException {
        assertHttpStatusCodeErrorCodeAndMessage(response, statusCode, null, message);
    }

}
