// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.routing;

import com.yahoo.cloud.config.LbServicesConfig;
import com.yahoo.config.ConfigInstance;
import com.yahoo.config.subscription.ConfigSet;
import com.yahoo.test.ManualClock;
import com.yahoo.vespa.config.ConfigKey;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author mpolden
 */
public class RoutingGeneratorTest {

    @Test()
    @Timeout(5)
    public void config_subscription() {
        RouterMock router = new RouterMock();
        RoutingGenerator generator = new RoutingGenerator(new ConfigSetMock(), router, new ManualClock());
        try {
            router.awaitLoad();
            assertNotNull(router.currentTable, "Router loads table");
            assertEquals(generator.routingTable().get(),
                         router.currentTable,
                         "Routing generator and router has same table");
        } finally {
            generator.deconstruct();
        }
    }

    private static class RouterMock implements Router {

        private final CountDownLatch latch = new CountDownLatch(1);

        private volatile RoutingTable currentTable = null;

        @Override
        public void load(RoutingTable table) {
            currentTable = table;
            latch.countDown();
        }

        public void awaitLoad() {
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

    }

    private static class ConfigSetMock extends ConfigSet {

        private int attempt = 0;

        public ConfigSetMock() {
            addBuilder("*", new LbServicesConfig.Builder());
        }

        @Override
        public ConfigInstance.Builder get(ConfigKey<?> key) {
            incrementGeneration();
            if (++attempt <= 5) {
                throw new RuntimeException("Failed to get config on attempt " + attempt);
            }
            return super.get(key);
        }

    }

}
