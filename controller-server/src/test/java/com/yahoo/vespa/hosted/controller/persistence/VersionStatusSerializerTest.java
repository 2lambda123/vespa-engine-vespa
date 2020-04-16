// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.persistence;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.HostName;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.hosted.controller.versions.DeploymentStatistics;
import com.yahoo.vespa.hosted.controller.versions.NodeVersion;
import com.yahoo.vespa.hosted.controller.versions.NodeVersions;
import com.yahoo.vespa.hosted.controller.versions.VersionStatus;
import com.yahoo.vespa.hosted.controller.versions.VespaVersion;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.time.temporal.ChronoUnit.MILLIS;
import static org.junit.Assert.assertEquals;

/**
 * @author mpolden
 */
public class VersionStatusSerializerTest {

    @Test
    public void testSerialization() {
        List<VespaVersion> vespaVersions = new ArrayList<>();
        Version version = Version.fromString("5.0");
        vespaVersions.add(new VespaVersion(version, "dead", Instant.now(), false, false,
                                           true, nodeVersions(Version.fromString("5.0"), Version.fromString("5.1"),
                                                              Instant.ofEpochMilli(123), "cfg1", "cfg2", "cfg3"), VespaVersion.Confidence.normal));
        vespaVersions.add(new VespaVersion(version, "cafe", Instant.now(), true, true,
                                           false, nodeVersions(Version.fromString("5.0"), Version.fromString("5.1"),
                                                               Instant.ofEpochMilli(456), "cfg1", "cfg2", "cfg3"), VespaVersion.Confidence.normal));
        VersionStatus status = new VersionStatus(vespaVersions);
        VersionStatusSerializer serializer = new VersionStatusSerializer(new NodeVersionSerializer());
        VersionStatus deserialized = serializer.fromSlime(serializer.toSlime(status));

        assertEquals(status.versions().size(), deserialized.versions().size());
        for (int i = 0; i < status.versions().size(); i++) {
            VespaVersion a = status.versions().get(i);
            VespaVersion b = deserialized.versions().get(i);
            assertEquals(a.releaseCommit(), b.releaseCommit());
            assertEquals(a.committedAt().truncatedTo(MILLIS), b.committedAt());
            assertEquals(a.isControllerVersion(), b.isControllerVersion());
            assertEquals(a.isSystemVersion(), b.isSystemVersion());
            assertEquals(a.isReleased(), b.isReleased());
            assertEquals(a.versionNumber(), b.versionNumber());
            assertEquals(a.nodeVersions(), b.nodeVersions());
            assertEquals(a.confidence(), b.confidence());
        }

    }

    private static NodeVersions nodeVersions(Version version, Version wantedVersion, Instant changedAt, String... hostnames) {
        var nodeVersions = new ArrayList<NodeVersion>();
        for (var hostname : hostnames) {
            nodeVersions.add(new NodeVersion(HostName.from(hostname), ZoneId.from("prod", "us-north-1"), version, wantedVersion, changedAt));
        }
        return NodeVersions.EMPTY.with(nodeVersions);
    }

}
