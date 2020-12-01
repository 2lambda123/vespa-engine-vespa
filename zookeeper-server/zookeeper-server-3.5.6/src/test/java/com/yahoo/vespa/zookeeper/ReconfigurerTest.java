// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.zookeeper;

import com.yahoo.cloud.config.ZookeeperServerConfig;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.stream.IntStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests dynamic reconfiguration of zookeeper cluster.
 *
 * @author hmusum
 */
public class ReconfigurerTest {

    private File cfgFile;
    private File idFile;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Before
    public void setup() throws IOException {
        cfgFile = folder.newFile();
        idFile = folder.newFile("myid");
    }

    @Test
    public void testStartupAndReconfigure() {
        Reconfigurer reconfigurer = new Reconfigurer();
        reconfigurer.startOrReconfigure(createConfig(1));

        // Created config has dynamicReconfig set to false
        assertFalse(reconfigurer.shouldReconfigure(createConfig(2)));

        // Created config has dynamicReconfig set to true
        assertTrue(reconfigurer.shouldReconfigure(createConfigAllowReconfiguring(2)));

        // Test that equal config does not cause reconfiguration
        Reconfigurer reconfigurer2 = new Reconfigurer();
        reconfigurer2.startOrReconfigure(createConfigAllowReconfiguring(1));
        assertFalse(reconfigurer2.shouldReconfigure(createConfigAllowReconfiguring(1)));
    }

    private ZookeeperServerConfig createConfigAllowReconfiguring(int numberOfServers) {
        return createConfig(numberOfServers, true);
    }

    private ZookeeperServerConfig createConfig(int numberOfServers) {
        return createConfig(numberOfServers, false);
    }

    private ZookeeperServerConfig createConfig(int numberOfServers, boolean dynamicReconfiguration) {
        ZookeeperServerConfig.Builder builder = new ZookeeperServerConfig.Builder();
        builder.zooKeeperConfigFile(cfgFile.getAbsolutePath());
        builder.myidFile(idFile.getAbsolutePath());
        IntStream.range(0, numberOfServers).forEach(i -> {
            builder.server(newServer(i, "localhost", i, i + 1));
        });
        builder.myid(0);
        builder.dynamicReconfiguration(dynamicReconfiguration);
        return builder.build();
    }

    private ZookeeperServerConfig.Server.Builder newServer(int id, String hostName, int electionPort, int quorumPort) {
        ZookeeperServerConfig.Server.Builder builder = new ZookeeperServerConfig.Server.Builder();
        builder.id(id);
        builder.hostname(hostName);
        builder.electionPort(electionPort);
        builder.quorumPort(quorumPort);
        return builder;
    }

}
