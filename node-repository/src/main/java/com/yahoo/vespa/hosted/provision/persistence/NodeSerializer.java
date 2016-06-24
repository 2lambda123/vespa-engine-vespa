// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.persistence;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.TenantName;
import com.yahoo.slime.ArrayTraverser;
import com.yahoo.slime.Cursor;
import com.yahoo.slime.Inspector;
import com.yahoo.slime.Slime;
import com.yahoo.vespa.config.SlimeUtils;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.node.Allocation;
import com.yahoo.vespa.hosted.provision.node.Configuration;
import com.yahoo.vespa.hosted.provision.node.Generation;
import com.yahoo.vespa.hosted.provision.node.History;
import com.yahoo.vespa.hosted.provision.node.NodeFlavors;
import com.yahoo.vespa.hosted.provision.node.Status;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.yahoo.vespa.config.SlimeUtils.optionalString;

/**
 * Serializes a node to/from JSON.
 * Instances of this are multithread safe and can be reused
 *
 * @author bratseth
 */
public class NodeSerializer {

    /** The configured node flavors */
    private final NodeFlavors flavors;

    // Node fields
    private static final String hostnameKey = "hostname";
    private static final String openStackIdKey = "openStackId";
    // TODO Legacy name. Remove when 5.120 is released everywhere
    private static final String dockerHostHostNameKey = "dockerHostHostName";
    private static final String parentHostnameKey = "parentHostname";
    private static final String configurationKey ="configuration";
    private static final String historyKey = "history";
    private static final String instanceKey = "instance"; // legacy name, TODO: change to allocation with backwards compat
    private static final String rebootGenerationKey = "rebootGeneration";
    private static final String currentRebootGenerationKey = "currentRebootGeneration";
    private static final String vespaVersionKey = "vespaVersion";
    private static final String hostedVersionKey = "hostedVersion";
    private static final String stateVersionKey = "stateVersion";
    private static final String failCountKey = "failCount";
    private static final String hardwareFailureKey = "hardwareFailure";
    private static final String nodeTypeKey = "type";

    // Configuration fields
    private static final String flavorKey = "flavor";

    // Allocation fields
    private static final String tenantIdKey = "tenantId";
    private static final String applicationIdKey = "applicationId";
    private static final String instanceIdKey = "instanceId";
    private static final String serviceIdKey = "serviceId"; // legacy name, TODO: change to membership with backwards compat
    private static final String restartGenerationKey = "restartGeneration";
    private static final String currentRestartGenerationKey = "currentRestartGeneration";
    private static final String removableKey = "removable";
    //Saved as part of allocation instead of serviceId, since serviceId serialized form is not easily extendable.
    private static final String dockerImageKey = "dockerImage";

    // History event fields
    private static final String historyEventTypeKey = "type";
    private static final String atKey = "at";
    private static final String agentKey = "agent"; // retired events only

    // ---------------- Serialization ----------------------------------------------------

    public NodeSerializer(NodeFlavors flavors) {
        this.flavors = flavors;
    }

    public byte[] toJson(Node node) {
        try {
            Slime slime = new Slime();
            toSlime(node, slime.setObject());
            return SlimeUtils.toJsonBytes(slime);
        }
        catch (IOException e) {
            throw new RuntimeException("Serialization of " + node + " to json failed", e);
        }
    }

    private void toSlime(Node node, Cursor object) {
        object.setString(hostnameKey, node.hostname());
        object.setString(openStackIdKey, node.openStackId());
        node.parentHostname().ifPresent(hostname -> object.setString(parentHostnameKey, hostname));
        toSlime(node.configuration(), object.setObject(configurationKey));
        object.setLong(rebootGenerationKey, node.status().reboot().wanted());
        object.setLong(currentRebootGenerationKey, node.status().reboot().current());
        node.status().vespaVersion().ifPresent(version -> object.setString(vespaVersionKey, version.toString()));
        node.status().hostedVersion().ifPresent(version -> object.setString(hostedVersionKey, version.toString()));
        node.status().stateVersion().ifPresent(version -> object.setString(stateVersionKey, version.toString()));
        node.status().dockerImage().ifPresent(image -> object.setString(dockerImageKey, image));
        object.setLong(failCountKey, node.status().failCount());
        object.setBool(hardwareFailureKey, node.status().hardwareFailure());
        node.allocation().ifPresent(allocation -> toSlime(allocation, object.setObject(instanceKey)));
        toSlime(node.history(), object.setArray(historyKey));
        object.setString(nodeTypeKey, toString(node.type()));
    }

    private void toSlime(Configuration configuration, Cursor object) {
        object.setString(flavorKey, configuration.flavor().name());
    }

    private void toSlime(Allocation allocation, Cursor object) {
        object.setString(tenantIdKey, allocation.owner().tenant().value());
        object.setString(applicationIdKey, allocation.owner().application().value());
        object.setString(instanceIdKey, allocation.owner().instance().value());
        object.setString(serviceIdKey, allocation.membership().stringValue());
        object.setLong(restartGenerationKey, allocation.restartGeneration().wanted());
        object.setLong(currentRestartGenerationKey, allocation.restartGeneration().current());
        object.setBool(removableKey, allocation.removable());
        allocation.membership().cluster().dockerImage().ifPresent( dockerImage ->
                object.setString(dockerImageKey, dockerImage));
    }

    private void toSlime(History history, Cursor array) {
        for (History.Event event : history.events())
            toSlime(event, array.addObject());
    }

    private void toSlime(History.Event event, Cursor object) {
        object.setString(historyEventTypeKey, toString(event.type()));
        object.setLong(atKey, event.at().toEpochMilli());
        if (event instanceof History.RetiredEvent)
            object.setString(agentKey, toString(((History.RetiredEvent)event).agent()));
    }

    // ---------------- Deserialization --------------------------------------------------

    public Node fromJson(Node.State state, byte[] data) {
        return nodeFromSlime(state, SlimeUtils.jsonToSlime(data).get());
    }

    private Node nodeFromSlime(Node.State state, Inspector object) {
        return new Node(object.field(openStackIdKey).asString(),
                        object.field(hostnameKey).asString(),
                        parentHostnameFromSlime(object),
                        configurationFromSlime(object.field(configurationKey)),
                        statusFromSlime(object),
                        state,
                        allocationFromSlime(object.field(instanceKey)),
                        historyFromSlime(object.field(historyKey)),
                        typeFromSlime(object.field(nodeTypeKey)));
    }

    private Status statusFromSlime(Inspector object) {
        return new Status(
                generationFromSlime(object, rebootGenerationKey, currentRebootGenerationKey),
                softwareVersionFromSlime(object.field(vespaVersionKey)),
                softwareVersionFromSlime(object.field(hostedVersionKey)),
                optionalString(object.field(stateVersionKey)),
                optionalString(object.field(dockerImageKey)),
                (int)object.field(failCountKey).asLong(),
                object.field(hardwareFailureKey).asBool());
    }

    private Configuration configurationFromSlime(Inspector object) {
        return new Configuration(flavors.getFlavorOrThrow(object.field(flavorKey).asString()));
    }

    private Optional<Allocation> allocationFromSlime(Inspector object) {
        if ( ! object.valid()) return Optional.empty();
        return Optional.of(new Allocation(
                applicationIdFromSlime(object),
                ClusterMembership.from(object.field(serviceIdKey).asString(), optionalString(object.field(dockerImageKey))),
                generationFromSlime(object, restartGenerationKey, currentRestartGenerationKey),
                object.field(removableKey).asBool()));
    }

    private ApplicationId applicationIdFromSlime(Inspector object) {
        return ApplicationId.from(TenantName.from(object.field(tenantIdKey).asString()),
                ApplicationName.from(object.field(applicationIdKey).asString()),
                InstanceName.from(object.field(instanceIdKey).asString()));
    }

    private History historyFromSlime(Inspector array) {
        List<History.Event> events = new ArrayList<>();
        array.traverse((ArrayTraverser) (int i, Inspector item) -> {
            History.Event event = eventFromSlime(item);
            if (event != null)
                events.add(event);
        });
        return new History(events);
    }

    private History.Event eventFromSlime(Inspector object) {
        History.Event.Type type = eventTypeFromString(object.field(historyEventTypeKey).asString());
        if (type == null) return null;
        Instant at = Instant.ofEpochMilli(object.field(atKey).asLong());
        if (type.equals(History.Event.Type.retired))
            return new History.RetiredEvent(at, eventAgentFromString(object.field(agentKey).asString()));
        else
            return new History.Event(type, at);

    }

    private Generation generationFromSlime(Inspector object, String wantedField, String currentField) {
        Inspector current = object.field(currentField);
        return new Generation(object.field(wantedField).asLong(), current.asLong());
    }

    private Optional<Version> softwareVersionFromSlime(Inspector object) {
        if ( ! object.valid()) return Optional.empty();
        return Optional.of(Version.fromString(object.asString()));
    }

    private Optional<String> parentHostnameFromSlime(Inspector object) {
        if (object.field(parentHostnameKey).valid())
            return Optional.of(object.field(parentHostnameKey).asString());
        // TODO Remove when 5.120 is released everywhere
        else if (object.field(dockerHostHostNameKey).valid())
            return Optional.of(object.field(dockerHostHostNameKey).asString());
        else
            return Optional.empty();
    }

    // Enum <-> string mappings
    
    /** Returns the event type, or null if this event type should be ignored */
    private History.Event.Type eventTypeFromString(String eventTypeString) {
        switch (eventTypeString) {
            case "readied" : return History.Event.Type.readied;
            case "reserved" : return History.Event.Type.reserved;
            case "activated" : return History.Event.Type.activated;
            case "retired" : return History.Event.Type.retired;
            case "deactivated" : return History.Event.Type.deactivated;
            case "failed" : return History.Event.Type.failed;
            case "deallocated" : return History.Event.Type.deallocated;
            case "down" : return History.Event.Type.down;
        }
        throw new IllegalArgumentException("Unknown node event type '" + eventTypeString + "'");
    }
    private String toString(History.Event.Type nodeEventType) {
        switch (nodeEventType) {
            case readied : return "readied";
            case reserved : return "reserved";
            case activated : return "activated";
            case retired : return "retired";
            case deactivated : return "deactivated";
            case failed : return "failed";
            case deallocated : return "deallocated";
            case down : return "down";
        }
        throw new IllegalArgumentException("Serialized form of '" + nodeEventType + "' not defined");
    }

    private History.RetiredEvent.Agent eventAgentFromString(String eventAgentString) {
        switch (eventAgentString) {
            case "application" : return History.RetiredEvent.Agent.application;
            case "system" : return History.RetiredEvent.Agent.system;
        }
        throw new IllegalArgumentException("Unknown node event agent '" + eventAgentString + "'");
    }
    private String toString(History.RetiredEvent.Agent agent) {
        switch (agent) {
            case application : return "application";
            case system : return "system";
        }
        throw new IllegalArgumentException("Serialized form of '" + agent + "' not defined");
    }

    private Node.Type typeFromSlime(Inspector object) {
        if ( ! object.valid()) return Node.Type.tenant; // TODO: Remove this and change to pass string line when 6.13 is released everywhere
        switch (object.asString()) {
            case "tenant" : return Node.Type.tenant;
            case "host" : return Node.Type.host;
            default : throw new IllegalArgumentException("Unknown node type '" + object.asString() + "'");
        }
    }
    private String toString(Node.Type type) {
        switch (type) {
            case tenant: return "tenant";
            case host: return "host";
        }
        throw new IllegalArgumentException("Unknown node type '" + type.toString() + "'");
    }

}
