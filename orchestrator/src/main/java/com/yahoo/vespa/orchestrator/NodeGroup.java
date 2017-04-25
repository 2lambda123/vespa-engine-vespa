// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.orchestrator;

import com.yahoo.vespa.applicationmodel.ApplicationInstance;
import com.yahoo.vespa.applicationmodel.HostName;
import com.yahoo.vespa.service.monitor.ServiceMonitorStatus;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A group of nodes belonging to the same application instance.
 */
public class NodeGroup {
    private final ApplicationInstance<ServiceMonitorStatus> application;
    private final Set<HostName> hostNames = new HashSet<>();

    public NodeGroup(ApplicationInstance<ServiceMonitorStatus> application, HostName... hostNames) {
        this.application = application;
        this.hostNames.addAll(Arrays.asList(hostNames));
    }

    public void addNode(HostName hostName) {
        if (!this.hostNames.add(hostName)) {
            throw new IllegalArgumentException("Node " + hostName + " is already in the group");
        }
    }

    // TODO: Remove application from node group and fix class comment
    public ApplicationInstance<ServiceMonitorStatus> getApplication() {
        return application;
    }

    public boolean contains(HostName hostName) {
        return hostNames.contains(hostName);
    }

    // TODO: Remove. Later: Why?
    public List<HostName> getHostNames() {
        return hostNames.stream().collect(Collectors.toList()).stream().sorted().collect(Collectors.toList());
    }

    public String toCommaSeparatedString() {
        return hostNames.stream().map(HostName::toString).collect(Collectors.joining(","));
    }

    @Override
    public String toString() {
        return "NodeGroup{" +
                "application=" + application.reference() +
                ", hostNames=" + hostNames +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NodeGroup)) return false;
        NodeGroup nodeGroup = (NodeGroup) o;
        return Objects.equals(application, nodeGroup.application) &&
                Objects.equals(hostNames, nodeGroup.hostNames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(application, hostNames);
    }
}
