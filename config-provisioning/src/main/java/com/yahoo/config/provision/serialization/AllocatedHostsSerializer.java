// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.provision.serialization;

import com.yahoo.config.provision.AllocatedHosts;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.NetworkPorts;
import com.yahoo.config.provision.NodeFlavors;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.slime.ArrayTraverser;
import com.yahoo.slime.Cursor;
import com.yahoo.slime.Inspector;
import com.yahoo.slime.Slime;
import com.yahoo.vespa.config.SlimeUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The hosts allocated to an application.
 * This can be serialized to/from JSON.
 * This is immutable.
 *
 * @author Ulf Lilleengen
 * @author bratseth
 */
public class AllocatedHostsSerializer {

    // WARNING: Since there are multiple servers in a ZooKeeper cluster and they upgrade one by one
    //          (and rewrite all nodes on startup), changes to the serialized format must be made
    //          such that what is serialized on version N+1 can be read by version N:
    //          - ADDING FIELDS: Always ok
    //          - REMOVING FIELDS: Stop reading the field first. Stop writing it on a later version.
    //          - CHANGING THE FORMAT OF A FIELD: Don't do it bro.

    private static final String mappingKey = "mapping";
    private static final String hostSpecKey = "hostSpec";
    private static final String hostSpecHostNameKey = "hostName";
    private static final String aliasesKey = "aliases";
    private static final String hostSpecMembershipKey = "membership";

    // Flavor can be removed when all allocated nodes are docker nodes
    private static final String flavorKey = "flavor";

    private static final String resourcesKey = "resources";
    private static final String vcpuKey = "vcpu";
    private static final String memoryKey = "memory";
    private static final String diskKey = "disk";
    private static final String diskSpeedKey = "diskSpeed";

    /** Wanted version */
    private static final String hostSpecVespaVersionKey = "vespaVersion";

    /** Current version */
    private static final String hostSpecCurrentVespaVersionKey = "currentVespaVersion";
    private static final String hostSpecNetworkPortsKey = "ports";


    public static byte[] toJson(AllocatedHosts allocatedHosts) throws IOException {
        Slime slime = new Slime();
        toSlime(allocatedHosts, slime.setObject());
        return SlimeUtils.toJsonBytes(slime);
    }

    public static void toSlime(AllocatedHosts allocatedHosts, Cursor cursor) {
        Cursor array = cursor.setArray(mappingKey);
        for (HostSpec host : allocatedHosts.getHosts())
            toSlime(host, array.addObject().setObject(hostSpecKey));
    }

    private static void toSlime(HostSpec host, Cursor cursor) {
        cursor.setString(hostSpecHostNameKey, host.hostname());
        aliasesToSlime(host, cursor);
        host.membership().ifPresent(membership -> {
            cursor.setString(hostSpecMembershipKey, membership.stringValue());
            cursor.setString(hostSpecVespaVersionKey, membership.cluster().vespaVersion().toFullString());
        });
        host.flavor().ifPresent(flavor -> toSlime(flavor, cursor));
        host.version().ifPresent(version -> cursor.setString(hostSpecCurrentVespaVersionKey, version.toFullString()));
        host.networkPorts().ifPresent(ports -> NetworkPortsSerializer.toSlime(ports, cursor.setArray(hostSpecNetworkPortsKey)));
    }

    private static void aliasesToSlime(HostSpec spec, Cursor cursor) {
        if (spec.aliases().isEmpty()) return;
        Cursor aliases = cursor.setArray(aliasesKey);
        for (String alias : spec.aliases())
            aliases.addString(alias);
    }

    private static void toSlime(Flavor flavor, Cursor object) {
        if (flavor.isConfigured()) {
            object.setString(flavorKey, flavor.name());
        }
        else {
            NodeResources resources = flavor.resources();
            Cursor resourcesObject = object.setObject(resourcesKey);
            resourcesObject.setDouble(vcpuKey, resources.vcpu());
            resourcesObject.setDouble(memoryKey, resources.memoryGb());
            resourcesObject.setDouble(diskKey, resources.diskGb());
            resourcesObject.setString(diskSpeedKey, diskSpeedToString(resources.diskSpeed()));
        }
    }


    public static AllocatedHosts fromJson(byte[] json, Optional<NodeFlavors> nodeFlavors) {
        return fromSlime(SlimeUtils.jsonToSlime(json).get(), nodeFlavors);
    }

    public static AllocatedHosts fromSlime(Inspector inspector, Optional<NodeFlavors> nodeFlavors) {
        Inspector array = inspector.field(mappingKey);
        Set<HostSpec> hosts = new LinkedHashSet<>();
        array.traverse((ArrayTraverser)(i, host) -> hosts.add(hostFromSlime(host.field(hostSpecKey), nodeFlavors)));
        return AllocatedHosts.withHosts(hosts);
    }

    private static HostSpec hostFromSlime(Inspector object, Optional<NodeFlavors> nodeFlavors) {
        Optional<ClusterMembership> membership =
                object.field(hostSpecMembershipKey).valid() ? Optional.of(membershipFromSlime(object)) : Optional.empty();
        Optional<Flavor> flavor = flavorFromSlime(object, nodeFlavors);
        Optional<com.yahoo.component.Version> version =
                optionalString(object.field(hostSpecCurrentVespaVersionKey)).map(com.yahoo.component.Version::new);
        Optional<NetworkPorts> networkPorts =
                NetworkPortsSerializer.fromSlime(object.field(hostSpecNetworkPortsKey));
        return new HostSpec(object.field(hostSpecHostNameKey).asString(), aliasesFromSlime(object), flavor, membership, version, networkPorts);
    }

    private static List<String> aliasesFromSlime(Inspector object) {
        if ( ! object.field(aliasesKey).valid()) return Collections.emptyList();
        List<String> aliases = new ArrayList<>();
        object.field(aliasesKey).traverse((ArrayTraverser)(index, alias) -> aliases.add(alias.asString()));
        return aliases;
    }

    private static Optional<Flavor> flavorFromSlime(Inspector object, Optional<NodeFlavors> nodeFlavors) {
        if (object.field(flavorKey).valid() && nodeFlavors.isPresent() && nodeFlavors.get().exists(object.field(flavorKey).asString())) {
            return nodeFlavors.get().getFlavor(object.field(flavorKey).asString());
        }
        else if (object.field(resourcesKey).valid()) {
            Inspector resources = object.field(resourcesKey);
            return Optional.of(new Flavor(new NodeResources(resources.field(vcpuKey).asDouble(),
                                                            resources.field(memoryKey).asDouble(),
                                                            resources.field(diskKey).asDouble(),
                                                            diskSpeedFromSlime(resources.field(diskSpeedKey)))));
        }
        else {
            return Optional.empty();
        }
    }

    private static NodeResources.DiskSpeed diskSpeedFromSlime(Inspector diskSpeed) {
        if ( ! diskSpeed.valid()) return NodeResources.DiskSpeed.fast; // TODO: Remove this line after June 2019
        switch (diskSpeed.asString()) {
            case "fast" : return NodeResources.DiskSpeed.fast;
            case "slow" : return NodeResources.DiskSpeed.slow;
            case "any" : return NodeResources.DiskSpeed.any;
            default: throw new IllegalStateException("Illegal disk-speed value '" + diskSpeed.asString() + "'");
        }
    }

    private static String diskSpeedToString(NodeResources.DiskSpeed diskSpeed) {
        switch (diskSpeed) {
            case fast : return "fast";
            case slow : return "slow";
            case any : return "any";
            default: throw new IllegalStateException("Illegal disk-speed value '" + diskSpeed + "'");
        }

    }

    private static ClusterMembership membershipFromSlime(Inspector object) {
        return ClusterMembership.from(object.field(hostSpecMembershipKey).asString(),
                                      com.yahoo.component.Version.fromString(object.field(hostSpecVespaVersionKey).asString()));
    }

    private static Optional<String> optionalString(Inspector inspector) {
        if ( ! inspector.valid()) return Optional.empty();
        return Optional.of(inspector.asString());
    }
}
