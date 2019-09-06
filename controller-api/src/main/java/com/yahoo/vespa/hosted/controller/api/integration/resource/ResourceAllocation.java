// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.resource;

/**
 * An allocation of node resources.
 *
 * @author ldalves
 */
public class ResourceAllocation {

    public static final ResourceAllocation ZERO = new ResourceAllocation(0, 0, 0);

    private final double cpuCores;
    private final double memoryGb;
    private final double diskGb;

    public ResourceAllocation(double cpuCores, double memoryGb, double diskGb) {
        this.cpuCores = cpuCores;
        this.memoryGb = memoryGb;
        this.diskGb = diskGb;
    }

    public double usageFraction(ResourceAllocation total) {
        return (cpuCores / total.cpuCores + memoryGb / total.memoryGb + diskGb / total.diskGb) / 3;
    }

    public double getCpuCores() {
        return cpuCores;
    }

    public double getMemoryGb() {
        return memoryGb;
    }

    public double getDiskGb() {
        return diskGb;
    }

    /** Returns a copy of this with the given allocation added */
    public ResourceAllocation plus(ResourceAllocation allocation) {
        return new ResourceAllocation(cpuCores + allocation.cpuCores, memoryGb + allocation.memoryGb, diskGb + allocation.diskGb);
    }

}

