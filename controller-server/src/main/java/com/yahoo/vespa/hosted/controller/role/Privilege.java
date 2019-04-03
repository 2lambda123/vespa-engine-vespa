// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.role;

import com.yahoo.config.provision.SystemName;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * This describes a privilege in the controller. A privilege expresses the actions (e.g. create or read) granted
 * for a particular group of REST API paths. A privilege is valid in one or more systems.
 *
 * @author mpolden
 */
public class Privilege {

    private final Set<SystemName> systems;
    private final Set<Action> actions;
    private final Set<PathGroup> pathGroups;

    private Privilege(Set<SystemName> systems, Set<Action> actions, Set<PathGroup> pathGroups) {
        this.systems = EnumSet.copyOf(Objects.requireNonNull(systems, "system must be non-null"));
        this.actions = EnumSet.copyOf(Objects.requireNonNull(actions, "actions must be non-null"));
        this.pathGroups = EnumSet.copyOf(Objects.requireNonNull(pathGroups, "pathGroups must be non-null"));
        if (systems.isEmpty()) {
            throw new IllegalArgumentException("systems must be non-empty");
        }
    }

    /** Systems where this applies */
    public Set<SystemName> systems() {
        return systems;
    }

    /** Actions allowed by this */
    public Set<Action> actions() {
        return actions;
    }

    /** Path groups where this applies */
    public Set<PathGroup> pathGroups() {
        return pathGroups;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Privilege privilege = (Privilege) o;
        return systems.equals(privilege.systems) &&
               actions.equals(privilege.actions) &&
               pathGroups.equals(privilege.pathGroups);
    }

    @Override
    public int hashCode() {
        return Objects.hash(systems, actions, pathGroups);
    }

    public static PrivilegeBuilder grant(Action... actions) {
        return grant(Set.of(actions));
    }

    public static PrivilegeBuilder grant(Set<Action> actions) {
        return new PrivilegeBuilder(actions);
    }

    public static class PrivilegeBuilder {

        private Set<Action> actions;
        private Set<PathGroup> pathGroups;

        private PrivilegeBuilder(Set<Action> actions) {
            this.actions = EnumSet.copyOf(actions);
            this.pathGroups = new LinkedHashSet<>();
        }

        public PrivilegeBuilder on(PathGroup... pathGroups) {
            return on(Set.of(pathGroups));
        }

        public PrivilegeBuilder on(Set<PathGroup> pathGroups) {
            this.pathGroups.addAll(pathGroups);
            return this;
        }

        public Privilege in(SystemName... systems) {
            return in(Set.of(systems));
        }

        public Privilege in(Set<SystemName> systems) {
            return new Privilege(systems, actions, pathGroups);
        }

    }

}
