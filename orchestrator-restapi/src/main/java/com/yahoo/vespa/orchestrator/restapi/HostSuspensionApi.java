// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.orchestrator.restapi;

import com.yahoo.vespa.orchestrator.restapi.wire.BatchOperationResult;

import javax.ws.rs.Consumes;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author hakonhall
 */
public interface HostSuspensionApi {
    /**
     * Path prefix for this api. Resources implementing this API should use this with a @Path annotation.
     */
    String PATH_PREFIX = "/v1/suspensions/hosts";

    /**
     * Ask for permission to temporarily suspend all services on a set of hosts (nodes).
     *
     * See HostApi::suspend for semantics of suspending a node.
     */
    @PUT
    @Path("/{hostname}")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    BatchOperationResult suspendAll(@PathParam("hostname") String parentHostname,
                                    @QueryParam("hostname") List<String> hostnames);
}
