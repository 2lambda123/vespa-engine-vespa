package com.yahoo.vespa.hosted.node.verification.hardware.yamasreport;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yahoo.vespa.hosted.node.verification.hardware.benchmarks.HardwareResults;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by sgrostad on 12/07/2017.
 */
public class YamasHardwareReport {

    @JsonProperty
    private long timestamp;
    @JsonProperty
    private HardwareReportDimensions dimensions;
    @JsonProperty
    private HardwareReportMetrics metrics;
    @JsonProperty
    JsonObjectWrapper routing;

    public YamasHardwareReport(){
        this.timestamp = System.currentTimeMillis() / 1000L;
        setRouting();
    }

    public HardwareReportDimensions getDimensions() {return dimensions;}
    public void setDimensions(HardwareReportDimensions dimensions){this.dimensions = dimensions;}

    public HardwareReportMetrics getMetrics() {return metrics;}
    public void setMetrics(HardwareReportMetrics metrics) {this.metrics = metrics;}

    private void setRouting(){
        JsonObjectWrapper wrap = new JsonObjectWrapper("namespace", new String[] {"Vespa"});
        routing = new JsonObjectWrapper("yamas", wrap);
    }

    public void createFromHardwareResults(HardwareResults hardwareResults) {
        metrics = new HardwareReportMetrics();
        dimensions = new HardwareReportDimensions();
        metrics.setCpuCyclesPerSec(hardwareResults.getCpuCyclesPerSec());
        metrics.setDiskSpeedMbs(hardwareResults.getDiskSpeedMbs());
        metrics.setIpv6Connectivity(hardwareResults.isIpv6Connectivity());
        metrics.setMemoryWriteSpeedGBs(hardwareResults.getMemoryWriteSpeedGBs());
        metrics.setMemoryReadSpeedGBs(hardwareResults.getMemoryReadSpeedGBs());
    }

    class JsonObjectWrapper<T> {
        private Map<String, T> wrappedObjects = new HashMap<String, T>();

        public JsonObjectWrapper(String name, T wrappedObject) {
            this.wrappedObjects.put(name, wrappedObject);
        }

        @JsonAnyGetter
        public Map<String, T> any() {
            return wrappedObjects;
        }

        @JsonAnySetter
        public void set(String name, T value) {
            wrappedObjects.put(name, value);
        }
    }
}
