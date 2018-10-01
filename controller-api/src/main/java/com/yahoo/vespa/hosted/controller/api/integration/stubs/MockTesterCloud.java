// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.stubs;

import com.google.common.collect.ImmutableList;
import com.yahoo.vespa.hosted.controller.api.integration.LogEntry;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.TesterCloud;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.yahoo.vespa.hosted.controller.api.integration.deployment.TesterCloud.Status.NOT_STARTED;
import static com.yahoo.vespa.hosted.controller.api.integration.deployment.TesterCloud.Status.RUNNING;

public class MockTesterCloud implements TesterCloud {

    private List<LogEntry> log = new ArrayList<>();
    private Status status = NOT_STARTED;
    private byte[] config;
    private URI testerUrl;

    @Override
    public void startTests(URI testerUrl, Suite suite, byte[] config) {
        this.status = RUNNING;
        this.config = config;
        this.testerUrl = testerUrl;
    }

    @Override
    public List<LogEntry> getLog(URI testerUrl, long after) {
        return log.stream().filter(entry -> entry.id() > after).collect(Collectors.toList());
    }

    @Override
    public Status getStatus(URI testerUrl) {
        return status;
    }

    @Override
    public boolean ready(URI resterUrl) {
        return true;
    }

    public void add(LogEntry entry) {
        log.add(entry);
    }

    public void set(Status status) {
        this.status = status;
    }

    public byte[] config() {
        return config;
    }

    public URI testerUrl() {
        return testerUrl;
    }

}
