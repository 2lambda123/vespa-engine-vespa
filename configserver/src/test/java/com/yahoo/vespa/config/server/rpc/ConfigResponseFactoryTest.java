// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.rpc;

import com.yahoo.config.SimpletypesConfig;
import com.yahoo.config.codegen.DefParser;
import com.yahoo.config.codegen.InnerCNode;
import com.yahoo.text.StringUtilities;
import com.yahoo.vespa.config.ConfigPayload;
import com.yahoo.vespa.config.protocol.CompressionType;
import com.yahoo.vespa.config.protocol.ConfigResponse;
import com.yahoo.vespa.config.server.rpc.LZ4ConfigResponseFactory;
import com.yahoo.vespa.config.server.rpc.UncompressedConfigResponseFactory;
import org.junit.Before;
import org.junit.Test;

import java.io.StringReader;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

/**
 * @author lulf
 * @since 5.19
 */
public class ConfigResponseFactoryTest {
    private InnerCNode def;


    @Before
    public void setup() {
        DefParser dParser = new DefParser(SimpletypesConfig.getDefName(), new StringReader(StringUtilities.implode(SimpletypesConfig.CONFIG_DEF_SCHEMA, "\n")));
        def = dParser.getTree();
    }

    @Test
    public void testUncompressedFacory() {
        UncompressedConfigResponseFactory responseFactory = new UncompressedConfigResponseFactory();
        ConfigResponse response = responseFactory.createResponse(ConfigPayload.empty(), def, 3);
        assertThat(response.getCompressionInfo().getCompressionType(), is(CompressionType.UNCOMPRESSED));
        assertThat(response.getGeneration(), is(3l));
        assertThat(response.getPayload().getByteLength(), is(2));
    }

    @Test
    public void testLZ4CompressedFacory() {
        LZ4ConfigResponseFactory responseFactory = new LZ4ConfigResponseFactory();
        ConfigResponse response = responseFactory.createResponse(ConfigPayload.empty(), def, 3);
        assertThat(response.getCompressionInfo().getCompressionType(), is(CompressionType.LZ4));
        assertThat(response.getGeneration(), is(3l));
        assertThat(response.getPayload().getByteLength(), is(3));
    }
}
