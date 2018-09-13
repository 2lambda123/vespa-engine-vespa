// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.google.common.collect.ImmutableSet;
import com.yahoo.component.Version;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.Node;
import com.yahoo.vespa.hosted.controller.api.integration.zone.CloudName;
import com.yahoo.vespa.hosted.controller.api.integration.zone.ZoneId;
import com.yahoo.vespa.hosted.controller.application.SystemApplication;
import com.yahoo.vespa.hosted.controller.versions.OsVersion;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Maintenance job that schedules upgrades of OS / kernel on nodes in the system.
 *
 * @author mpolden
 */
public class OsUpgrader extends InfrastructureUpgrader {

    private static final Logger log = Logger.getLogger(OsUpgrader.class.getName());

    private static final Set<Node.State> upgradableNodeStates = ImmutableSet.of(
            Node.State.ready,
            Node.State.active,
            Node.State.reserved
    );

    private final CloudName cloud;

    public OsUpgrader(Controller controller, Duration interval, JobControl jobControl, CloudName cloud) {
        super(controller, interval, jobControl, controller.zoneRegistry().osUpgradePolicy(cloud), name(cloud));
        this.cloud = cloud;
    }

    @Override
    protected void upgrade(Version target, SystemApplication application, ZoneId zone) {
        if (wantedVersion(zone, application, target).equals(target)) {
            return;
        }
        log.info(String.format("Upgrading OS of %s to version %s in %s", application.id(), target, zone));
        application.nodeTypesWithUpgradableOs().forEach(nodeType -> controller().configServer().nodeRepository()
                                                                                .upgradeOs(zone, nodeType, target));
    }

    @Override
    protected boolean convergedOn(Version target, SystemApplication application, ZoneId zone) {
        return currentVersion(zone, application, target).equals(target);
    }

    @Override
    protected boolean requireUpgradeOf(Node node, SystemApplication application, ZoneId zone) {
        return cloud.equals(zone.cloud()) && eligibleForUpgrade(node, application);
    }

    @Override
    protected Optional<Version> targetVersion() {
        // Return target if we have nodes in this cloud on a lower version
        return controller().osVersion(cloud)
                           .filter(target -> controller().osVersionStatus().nodeVersionsIn(cloud).stream()
                                                         .anyMatch(node -> node.version().isBefore(target.version())))
                           .map(OsVersion::version);
    }

    private Version currentVersion(ZoneId zone, SystemApplication application, Version defaultVersion) {
        return minVersion(zone, application, Node::currentOsVersion).orElse(defaultVersion);
    }

    private Version wantedVersion(ZoneId zone, SystemApplication application, Version defaultVersion) {
        return minVersion(zone, application, Node::wantedOsVersion).orElse(defaultVersion);
    }

    /** Returns whether node in application should be upgraded by this */
    public static boolean eligibleForUpgrade(Node node, SystemApplication application) {
        return upgradableNodeStates.contains(node.state()) &&
               application.nodeTypesWithUpgradableOs().contains(node.type());
    }

    private static String name(CloudName cloud) {
        return capitalize(cloud.value()) + OsUpgrader.class.getSimpleName(); // Prefix maintainer name with cloud name
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) {
            return s;
        }
        char firstLetter = Character.toUpperCase(s.charAt(0));
        if (s.length() > 1) {
            return firstLetter + s.substring(1).toLowerCase();
        }
        return String.valueOf(firstLetter);
    }

}
