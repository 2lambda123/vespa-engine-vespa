// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin;

import com.yahoo.vespa.hosted.dockerapi.ContainerName;
import com.yahoo.vespa.hosted.dockerapi.DockerImage;
import com.yahoo.vespa.hosted.provision.Node;

import java.util.Objects;
import java.util.Optional;

/**
 * @author stiankri
 */
public class ContainerNodeSpec {
    public final String hostname;
    public final Optional<DockerImage> wantedDockerImage;
    public final ContainerName containerName;
    public final Node.State nodeState;
    public final String nodeType;
    public final String nodeFlavor;
    public final Optional<String> vespaVersion;
    public final Optional<Owner> owner;
    public final Optional<Membership> membership;
    public final Optional<Long> wantedRestartGeneration;
    public final Optional<Long> currentRestartGeneration;
    public final Optional<Double> minCpuCores;
    public final Optional<Double> minMainMemoryAvailableGb;
    public final Optional<Double> minDiskAvailableGb;

    public ContainerNodeSpec(
            final String hostname,
            final Optional<DockerImage> wantedDockerImage,
            final ContainerName containerName,
            final Node.State nodeState,
            final String nodeType,
            final String nodeFlavor,
            final Optional<String> vespaVersion,
            final Optional<Owner> owner,
            final Optional<Membership> membership,
            final Optional<Long> wantedRestartGeneration,
            final Optional<Long> currentRestartGeneration,
            final Optional<Double> minCpuCores,
            final Optional<Double> minMainMemoryAvailableGb,
            final Optional<Double> minDiskAvailableGb) {
        this.hostname = hostname;
        this.wantedDockerImage = wantedDockerImage;
        this.containerName = containerName;
        this.nodeState = nodeState;
        this.nodeType = nodeType;
        this.nodeFlavor = nodeFlavor;
        this.vespaVersion = vespaVersion;
        this.owner = owner;
        this.membership = membership;
        this.wantedRestartGeneration = wantedRestartGeneration;
        this.currentRestartGeneration = currentRestartGeneration;
        this.minCpuCores = minCpuCores;
        this.minMainMemoryAvailableGb = minMainMemoryAvailableGb;
        this.minDiskAvailableGb = minDiskAvailableGb;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ContainerNodeSpec)) return false;

        ContainerNodeSpec that = (ContainerNodeSpec) o;

        return Objects.equals(hostname, that.hostname) &&
                Objects.equals(wantedDockerImage, that.wantedDockerImage) &&
                Objects.equals(containerName, that.containerName) &&
                Objects.equals(nodeState, that.nodeState) &&
                Objects.equals(nodeType, that.nodeType) &&
                Objects.equals(nodeFlavor, that.nodeFlavor) &&
                Objects.equals(vespaVersion, that.vespaVersion) &&
                Objects.equals(owner, that.owner) &&
                Objects.equals(membership, that.membership) &&
                Objects.equals(wantedRestartGeneration, that.wantedRestartGeneration) &&
                Objects.equals(currentRestartGeneration, that.currentRestartGeneration) &&
                Objects.equals(minCpuCores, that.minCpuCores) &&
                Objects.equals(minMainMemoryAvailableGb, that.minMainMemoryAvailableGb) &&
                Objects.equals(minDiskAvailableGb, that.minDiskAvailableGb);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                hostname,
                wantedDockerImage,
                containerName,
                nodeState,
                nodeType,
                nodeFlavor,
                vespaVersion,
                owner,
                membership,
                wantedRestartGeneration,
                currentRestartGeneration,
                minCpuCores,
                minMainMemoryAvailableGb,
                minDiskAvailableGb);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " {"
                + " hostname=" + hostname
                + " wantedDockerImage=" + wantedDockerImage
                + " containerName=" + containerName
                + " nodeState=" + nodeState
                + " nodeType = " + nodeType
                + " nodeFlavor = " + nodeFlavor
                + " vespaVersion = " + vespaVersion
                + " owner = " + owner
                + " membership = " + membership
                + " wantedRestartGeneration=" + wantedRestartGeneration
                + " minCpuCores=" + minCpuCores
                + " currentRestartGeneration=" + currentRestartGeneration
                + " minMainMemoryAvailableGb=" + minMainMemoryAvailableGb
                + " minDiskAvailableGb=" + minDiskAvailableGb
                + " }";
    }

    public static class Owner {
        public final String tenant;
        public final String application;
        public final String instance;

        public Owner(String tenant, String application, String instance) {
            this.tenant = tenant;
            this.application = application;
            this.instance = instance;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Owner owner = (Owner) o;

            if (!tenant.equals(owner.tenant)) return false;
            if (!application.equals(owner.application)) return false;
            return instance.equals(owner.instance);

        }

        @Override
        public int hashCode() {
            int result = tenant.hashCode();
            result = 31 * result + application.hashCode();
            result = 31 * result + instance.hashCode();
            return result;
        }

        public String toString() {
            return "Owner {" +
                    " tenant = " + tenant +
                    " application = " + application +
                    " instance = " + instance +
                    " }";
        }
    }

    public static class Membership {
        public final String clusterType;
        public final String clusterId;
        public final String group;
        public final int index;
        public final boolean retired;

        public Membership(String clusterType, String clusterId, String group, int index, boolean retired) {
            this.clusterType = clusterType;
            this.clusterId = clusterId;
            this.group = group;
            this.index = index;
            this.retired = retired;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Membership that = (Membership) o;

            if (index != that.index) return false;
            if (retired != that.retired) return false;
            if (!clusterType.equals(that.clusterType)) return false;
            if (!clusterId.equals(that.clusterId)) return false;
            return group.equals(that.group);

        }

        @Override
        public int hashCode() {
            int result = clusterType.hashCode();
            result = 31 * result + clusterId.hashCode();
            result = 31 * result + group.hashCode();
            result = 31 * result + index;
            result = 31 * result + (retired ? 1 : 0);
            return result;
        }

        @Override
        public String toString() {
            return "Membership {" +
                    " clusterType = " + clusterType +
                    " clusterId = " + clusterId +
                    " group = " + group +
                    " index = " + index +
                    " retired = " + retired +
                    " }";
        }
    }
}
