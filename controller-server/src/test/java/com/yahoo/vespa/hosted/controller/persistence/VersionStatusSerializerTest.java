// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.persistence;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.HostName;
import com.yahoo.vespa.hosted.controller.versions.DeploymentStatistics;
import com.yahoo.vespa.hosted.controller.versions.VersionStatus;
import com.yahoo.vespa.hosted.controller.versions.VespaVersion;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static java.time.temporal.ChronoUnit.MILLIS;
import static org.junit.Assert.assertEquals;

/**
 * @author mpolden
 */
public class VersionStatusSerializerTest {

    @Test
    public void testSerialization() {
        List<VespaVersion> vespaVersions = new ArrayList<>();
        DeploymentStatistics statistics = new DeploymentStatistics(
                Version.fromString("5.0"),
                Collections.singletonList(ApplicationId.from("tenant1", "failing1", "default")),
                List.of(ApplicationId.from("tenant2", "success1", "default"),
                        ApplicationId.from("tenant2", "success2", "default")),
                List.of(ApplicationId.from("tenant1", "failing1", "default"),
                        ApplicationId.from("tenant2", "success2", "default"))
        );
        vespaVersions.add(new VespaVersion(statistics, "dead", Instant.now(), false, false,
                                           true, asHostnames("cfg1", "cfg2", "cfg3"), VespaVersion.Confidence.normal));
        vespaVersions.add(new VespaVersion(statistics, "cafe", Instant.now(), true, true,
                                           false, asHostnames("cfg1", "cfg2", "cfg3"), VespaVersion.Confidence.normal));
        VersionStatus status = new VersionStatus(vespaVersions);
        VersionStatusSerializer serializer = new VersionStatusSerializer();
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
            assertEquals(a.statistics(), b.statistics());
            assertEquals(a.systemApplicationHostnames(), b.systemApplicationHostnames());
            assertEquals(a.confidence(), b.confidence());
        }

    }

    private static List<HostName> asHostnames(String... hostname) {
        return Arrays.stream(hostname).map(HostName::from).collect(Collectors.toList());
    }

}
