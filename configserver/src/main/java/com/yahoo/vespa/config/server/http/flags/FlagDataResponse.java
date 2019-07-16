// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.http.flags;

import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.jdisc.Response;
import com.yahoo.vespa.config.server.http.HttpConfigResponse;
import com.yahoo.vespa.flags.json.FlagData;

import java.io.OutputStream;

/**
 * @author hakonhall
 */
public class FlagDataResponse extends HttpResponse {
    private final FlagData data;

    FlagDataResponse(FlagData data) {
        super(Response.Status.OK);
        this.data = data;
    }

    @Override
    public void render(OutputStream outputStream) {
        data.serializeToOutputStream(outputStream);
    }

    @Override
    public String getContentType() {
        return HttpConfigResponse.JSON_CONTENT_TYPE;
    }
}
