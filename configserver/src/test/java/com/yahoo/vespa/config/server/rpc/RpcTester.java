// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.rpc;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.config.provision.HostLivenessTracker;
import com.yahoo.config.provision.TenantName;
import com.yahoo.jrt.Request;
import com.yahoo.jrt.Spec;
import com.yahoo.jrt.Supervisor;
import com.yahoo.jrt.Transport;
import com.yahoo.net.HostName;
import com.yahoo.test.ManualClock;
import com.yahoo.vespa.config.GenerationCounter;
import com.yahoo.vespa.config.server.*;
import com.yahoo.vespa.config.server.filedistribution.FileServer;
import com.yahoo.vespa.config.server.host.ConfigRequestHostLivenessTracker;
import com.yahoo.vespa.config.server.host.HostRegistries;
import com.yahoo.vespa.config.server.monitoring.Metrics;
import com.yahoo.vespa.config.server.tenant.MockTenantProvider;
import com.yahoo.vespa.config.server.tenant.TenantHandlerProvider;
import com.yahoo.vespa.flags.InMemoryFlagSource;
import org.junit.After;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.yahoo.vespa.config.server.SuperModelRequestHandlerTest.emptyNodeFlavors;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Tester for running rpc server.
 *
 * @author Ulf Lilleengen
 */
public class RpcTester implements AutoCloseable {

    private final ManualClock clock = new ManualClock(Instant.ofEpochMilli(100));
    private final String myHostname = HostName.getLocalhost();
    private final HostLivenessTracker hostLivenessTracker = new ConfigRequestHostLivenessTracker(clock);
    private final MockTenantProvider tenantProvider;
    private final GenerationCounter generationCounter;
    private final Spec spec;

    private RpcServer rpcServer;
    private Thread t;
    private Supervisor sup;

    private List<Integer> allocatedPorts;

    private final TemporaryFolder temporaryFolder;
    private final ConfigserverConfig configserverConfig;

    RpcTester(TemporaryFolder temporaryFolder) throws InterruptedException, IOException {
        this(temporaryFolder, new ConfigserverConfig.Builder());
    }

    RpcTester(TemporaryFolder temporaryFolder, ConfigserverConfig.Builder configBuilder) throws InterruptedException, IOException {
        this.temporaryFolder = temporaryFolder;
        allocatedPorts = new ArrayList<>();
        int port = allocatePort();
        spec = createSpec(port);
        tenantProvider = new MockTenantProvider();
        generationCounter = new MemoryGenerationCounter();
        configBuilder.rpcport(port);
        configserverConfig = new ConfigserverConfig(configBuilder);
        createAndStartRpcServer();
        assertFalse(hostLivenessTracker.lastRequestFrom(myHostname).isPresent());
    }

    public void close() {
        for (Integer port : allocatedPorts) {
            PortRangeAllocator.releasePort(port);
        }
    }

    private int allocatePort() throws InterruptedException {
        int port = PortRangeAllocator.findAvailablePort();
        allocatedPorts.add(port);
        return port;
    }

    void createAndStartRpcServer() throws IOException {
        rpcServer = new RpcServer(configserverConfig,
                                  new SuperModelRequestHandler(new TestConfigDefinitionRepo(),
                                                               configserverConfig,
                                                               new SuperModelManager(
                                                                       configserverConfig,
                                                                       emptyNodeFlavors(),
                                                                       generationCounter,
                                                                       new InMemoryFlagSource())),
                                  Metrics.createTestMetrics(), new HostRegistries(),
                                  hostLivenessTracker, new FileServer(temporaryFolder.newFolder()));
        rpcServer.onTenantCreate(TenantName.from("default"), tenantProvider);
        t = new Thread(rpcServer);
        t.start();
        sup = new Supervisor(new Transport());
        pingServer();
    }

    @After
    public void stopRpc() throws InterruptedException {
        rpcServer.stop();
        t.join();
    }

    private Spec createSpec(int port) {
        return new Spec("tcp/localhost:" + port);
    }

    private void pingServer() {
        long endTime = System.currentTimeMillis() + 60_000;
        Request req = new Request("ping");
        while (System.currentTimeMillis() < endTime) {
            performRequest(req);
            if (!req.isError() && req.returnValues().size() > 0 && req.returnValues().get(0).asInt32() == 0) {
                break;
            }
            req = new Request("ping");
        }
        assertFalse(req.isError());
        assertTrue(req.returnValues().size() > 0);
        assertThat(req.returnValues().get(0).asInt32(), is(0));
    }

    void performRequest(Request req) {
        clock.advance(Duration.ofMillis(10));
        sup.connect(spec).invokeSync(req, 120.0);
        if (req.methodName().equals(RpcServer.getConfigMethodName))
            assertEquals(clock.instant(), hostLivenessTracker.lastRequestFrom(myHostname).get());
    }

    RpcServer rpcServer() {
        return rpcServer;
    }

    TenantHandlerProvider tenantProvider() {
        return tenantProvider;
    }

}
