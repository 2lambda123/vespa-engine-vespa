// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document.json;

import com.google.common.base.Joiner;
import static org.hamcrest.MatcherAssert.assertThat;
import static uk.co.datumedge.hamcrest.json.SameJSONAs.sameJSONAs;

/**
 * @author Vegard Sjonfjell
 */
public class JsonTestHelper {

    /**
     * Convenience method to input JSON without escaping double quotes and newlines
     * Each parameter represents a line of JSON encoded data
     * The lines are joined with newline and single quotes are replaced with double quotes
     */
    public static String inputJson(String... lines) {
        return Joiner.on("\n").join(lines).replaceAll("'", "\"");
    }

    /**
     * Structurally compare two JSON encoded strings
     */
    public static void assertJsonEquals(String inputJson, String expectedJson) {
        assertThat(inputJson, sameJSONAs(expectedJson));
    }

}
