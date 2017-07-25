package com.yahoo.vespa.hosted.node.verification.hardware.benchmarks;

/**
 * Created by sgrostad on 11/07/2017.
 * Stores results from benchmarks
 */
public class HardwareResults {

    private double cpuCyclesPerSec;
    private double diskSpeedMbs;
    private boolean ipv6Connectivity;
    private Double memoryWriteSpeedGBs;
    private Double memoryReadSpeedGBs;


    public Double getMemoryWriteSpeedGBs() {
        return memoryWriteSpeedGBs;
    }

    public void setMemoryWriteSpeedGBs(Double memoryWriteSpeedGBs) {
        this.memoryWriteSpeedGBs = memoryWriteSpeedGBs;
    }

    public Double getMemoryReadSpeedGBs() {
        return memoryReadSpeedGBs;
    }

    public void setMemoryReadSpeedGBs(Double memoryReadSpeedGBs) {
        this.memoryReadSpeedGBs = memoryReadSpeedGBs;
    }

    public double getCpuCyclesPerSec() {
        return cpuCyclesPerSec;
    }

    public void setCpuCyclesPerSec(double cpuCycles) {
        this.cpuCyclesPerSec = cpuCycles;
    }

    public double getDiskSpeedMbs() {
        return diskSpeedMbs;
    }

    public void setDiskSpeedMbs(double diskSpeedMbs) {
        this.diskSpeedMbs = diskSpeedMbs;
    }

    public boolean isIpv6Connectivity() {
        return ipv6Connectivity;
    }

    public void setIpv6Connectivity(boolean ipv6Connectivity) {
        this.ipv6Connectivity = ipv6Connectivity;
    }

}
