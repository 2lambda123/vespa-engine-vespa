// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.deployment;

import com.yahoo.vespa.curator.Lock;

public class LockedStep {

    private final Step step;
    LockedStep(Lock lock, Step step) { this.step = step; }
    public Step get() { return step; }

}
