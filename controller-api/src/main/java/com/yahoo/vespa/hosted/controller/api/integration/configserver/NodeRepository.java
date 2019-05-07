// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.configserver;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.zone.ZoneId;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A minimal interface to the node repository, providing only the operations used by the controller.
 *
 * @author mpolden
 */
public interface NodeRepository {

    /** List all nodes in zone owned by given application */
    List<Node> list(ZoneId zone, ApplicationId application);

    /** List all nodes in states, in zone owned by given application */
    default List<Node> list(ZoneId zone, ApplicationId application, Set<Node.State> states) {
        return list(zone, application).stream()
                                      .filter(node -> states.contains(node.state()))
                                      .collect(Collectors.toList());
    }

    /** Upgrade all nodes of given type to a new version */
    void upgrade(ZoneId zone, NodeType type, Version version);

    /** Upgrade OS for all nodes of given type to a new version */
    void upgradeOs(ZoneId zone, NodeType type, Version version);

    /** Requests firmware checks on all hosts in the given zone. */
    void requestFirmwareCheck(ZoneId zone);

    /** Cancels firmware checks on all hosts in the given zone. */
    void cancelFirmwareCheck(ZoneId zone);

}
