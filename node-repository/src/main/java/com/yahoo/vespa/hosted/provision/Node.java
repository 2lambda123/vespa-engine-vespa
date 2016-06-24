// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.vespa.hosted.provision.node.Allocation;
import com.yahoo.vespa.hosted.provision.node.Configuration;
import com.yahoo.vespa.hosted.provision.node.Flavor;
import com.yahoo.vespa.hosted.provision.node.History;
import com.yahoo.vespa.hosted.provision.node.Status;
import com.yahoo.vespa.hosted.provision.node.Generation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A node in the node repository. The identity of a node is given by its id.
 * The classes making up the node model are found in the node package.
 * This (and hence all classes referenced from it) is immutable.
 *
 * @author bratseth
 */
public final class Node {

    private final String id;
    private final String hostname;
    private final String openStackId;
    private final Optional<String> parentHostname;
    private final Configuration configuration;
    private final Status status;
    private final State state;
    private final Type type;

    /** Record of the last event of each type happening to this node */
    private final History history;

    /** The current allocation of this node, if any */
    private Optional<Allocation> allocation;

    /** Creates a node in the initial state (provisioned) */
    public static Node create(String openStackId, String hostname, Optional<String> parentHostname, Configuration configuration, Type type) {
        return new Node(openStackId, hostname, parentHostname, configuration, Status.initial(), State.provisioned,
                        Optional.empty(), History.empty(), type);
    }

    /** Do not use. Construct nodes by calling {@link NodeRepository#createNode} */
    public Node(String openStackId, String hostname, Optional<String> parentHostname,
                Configuration configuration, Status status, State state, Allocation allocation, History history, Type type) {
        this(openStackId, hostname, parentHostname, configuration, status, state, Optional.of(allocation), history, type);
    }

    public Node(String openStackId, String hostname, Optional<String> parentHostname,
                Configuration configuration, Status status, State state, Optional<Allocation> allocation,
                History history, Type type) {
        Objects.requireNonNull(openStackId, "A node must have an openstack id");
        Objects.requireNonNull(hostname, "A node must have a hostname");
        Objects.requireNonNull(parentHostname, "A null parentHostname is not permitted.");
        Objects.requireNonNull(configuration, "A node must have a configuration");
        Objects.requireNonNull(status, "A node must have a status");
        Objects.requireNonNull(state, "A null node state is not permitted");
        Objects.requireNonNull(allocation, "A null node allocation is not permitted");
        Objects.requireNonNull(history, "A null node history is not permitted");
        Objects.requireNonNull(type, "A null node type is not permitted");

        this.id = hostname;
        this.hostname = hostname;
        this.parentHostname = parentHostname;
        this.openStackId = openStackId;
        this.configuration = configuration;
        this.status = status;
        this.state = state;
        this.allocation = allocation;
        this.history = history;
        this.type = type;
    }

    /**
     * Returns the unique id of this host.
     * This may be the host name or some other opaque id which is unique across hosts
     */
    public String id() { return id; }

    /** Returns the host name of this node */
    public String hostname() { return hostname; }

    // TODO: Different meaning for vms and docker hosts?
    /** Returns the OpenStack id of this node, or of its docker host if this is a virtual node */
    public String openStackId() { return openStackId; }

    /** Returns the parent hostname for this node if this node is a docker container or a VM (i.e. it has a parent host). Otherwise, empty **/
    public Optional<String> parentHostname() { return parentHostname; }

    /** Returns the hardware configuration of this node */
    public Configuration configuration() { return configuration; }

    /** Returns the known information about the nodes ephemeral status */
    public Status status() { return status; }

    /** Returns the current state of this node (in the node state machine) */
    public State state() { return state; }

    /** Returns the type of this node */
    public Type type() { return type; }

    /** Returns the current allocation of this, if any */
    public Optional<Allocation> allocation() { return allocation; }

    /** Returns a history of the last events happening to this node */
    public History history() { return history; }

    /**
     * Returns a copy of this node which is retired by the application owning it.
     * If the node was already retired it is returned as-is.
     */
    public Node retireByApplication(Instant retiredAt) {
        if (allocation().get().membership().retired()) return this;
        return setAllocation(allocation.get().retire())
               .setHistory(history.record(new History.RetiredEvent(retiredAt, History.RetiredEvent.Agent.application)));
    }

    /** Returns a copy of this node which is retired by the system */
    // We will use this when we support operators retiring a flavor completely from hosted Vespa
    public Node retireBySystem(Instant retiredAt) {
        return setAllocation(allocation.get().retire())
               .setHistory(history.record(new History.RetiredEvent(retiredAt, History.RetiredEvent.Agent.system)));
    }

    /** Returns a copy of this node which is not retired */
    public Node unretire() {
        return setAllocation(allocation.get().unretire());
    }

    /** Returns a copy of this with the current generation set to generation */
    public Node setRestart(Generation generation) {
        final Optional<Allocation> allocation = this.allocation;
        if ( ! allocation.isPresent())
            throw new IllegalArgumentException("Cannot set restart generation for  " + hostname() + ": The node is unallocated");

        return setAllocation(allocation.get().setRestart(generation));
    }

    /** Returns a node with the status assigned to the given value */
    public Node setStatus(Status status) {
        return new Node(openStackId, hostname, parentHostname, configuration, status, state, allocation, history, type);
    }

    /** Returns a node with the type assigned to the given value */
    public Node setType(Type type) {
        return new Node(openStackId, hostname, parentHostname, configuration, status, state, allocation, history, type);
    }

    /** Returns a node with the hardware configuration assigned to the given value */
    public Node setConfiguration(Configuration configuration) {
        return new Node(openStackId, hostname, parentHostname, configuration, status, state, allocation, history, type);
    }

    /** Returns a copy of this with the current generation set to generation */
    public Node setReboot(Generation generation) {
        return new Node(openStackId, hostname, parentHostname, configuration, status.setReboot(generation), state,
                        allocation, history, type);
    }

    /** Returns a copy of this with the flavor set to flavor */
    public Node setFlavor(Flavor flavor) {
        return new Node(openStackId, hostname, parentHostname, new Configuration(flavor), status, state,
                        allocation, history, type);
    }

    /** Returns a copy of this with a history record saying it was detected to be down at this instant */
    public Node setDown(Instant instant) {
        return setHistory(history.record(new History.Event(History.Event.Type.down, instant)));
    }

    /** Returns a copy of this with any history record saying it has been detected down removed */
    public Node setUp() {
        return setHistory(history.clear(History.Event.Type.down));
    }

    /** Returns a copy of this with allocation set as specified. <code>node.state</code> is *not* changed. */
    public Node allocate(ApplicationId owner, ClusterMembership membership, Instant at) {
        return setAllocation(new Allocation(owner, membership, new Generation(0, 0), false))
               .setHistory(history.record(new History.Event(History.Event.Type.reserved, at)));
    }

    /**
     * Returns a copy of this node with the allocation assigned to the given allocation.
     * Do not use this to allocate a node.
     */
    public Node setAllocation(Allocation allocation) {
        return new Node(openStackId, hostname, parentHostname, configuration, status, state, allocation, history, type);
    }

    /** Returns a copy of this node with the parent hostname assigned to the given value. */
    public Node setParentHostname(String parentHostname) {
        return new Node(openStackId, hostname, Optional.of(parentHostname), configuration, status, state, allocation, history, type);
    }

    /** Returns a copy of this node with the given history. */
    private Node setHistory(History history) {
        return new Node(openStackId, hostname, parentHostname, configuration, status, state, allocation, history, type);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if ( ! other.getClass().equals(this.getClass())) return false;
        return ((Node)other).id.equals(this.id);
    }

    @Override
    public String toString() {
        return state + " node " +
               (hostname !=null ? hostname : id) +
               (allocation.isPresent() ? " " + allocation.get() : "") +
               (parentHostname.isPresent() ? " [on: " + parentHostname.get() + "]" : "");
    }

    public enum State {

        /** This node has been requested (from OpenStack) but is not yet read for use */
        provisioned,

        /** This node is free and ready for use */
        ready,

        /** This node has been reserved by an application but is not yet used by it */
        reserved,

        /** This node is in active use by an application */
        active,

        /** This node has been used by an application, is still allocated to it and retains the data needed for its allocated role */
        inactive,

        /** This node is not allocated to an application but may contain data which must be cleaned before it is ready */
        dirty,

        /** This node has failed and must be repaired or removed. The node retains any allocation data for diagnosis. */
        failed,

        /** 
         * This node should not currently be used. 
         * This state follows the same rules as failed except that it will never be automatically moved out of 
         * this state.
         */
        parked;

        /** Returns whether this is a state where the node is assigned to an application */
        public boolean isAllocated() {
            return this == reserved || this == active || this == inactive || this == failed || this == parked;
        }
    }

    public enum Type {
        tenant,
        host;
    }

}
