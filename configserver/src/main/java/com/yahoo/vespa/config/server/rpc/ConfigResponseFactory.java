// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.rpc;

import com.yahoo.config.codegen.InnerCNode;
import com.yahoo.vespa.config.ConfigPayload;
import com.yahoo.vespa.config.protocol.ConfigResponse;

/**
 * Represents a component that creates config responses from a payload. Different implementations
 * can do transformations of the payload such as compression.
 *
 * @author lulf
 * @since 5.19
 */
public interface ConfigResponseFactory {

    /**
     * Create a {@link ConfigResponse} for a given payload and generation.
     * @param payload The {@link com.yahoo.vespa.config.ConfigPayload} to put in the response.
     * @param defFile The {@link com.yahoo.config.codegen.InnerCNode} def file for this config.
     * @param generation The payload generation.  @return A {@link ConfigResponse} that can be sent to the client.
     */
    ConfigResponse createResponse(ConfigPayload payload, InnerCNode defFile, long generation);

}
