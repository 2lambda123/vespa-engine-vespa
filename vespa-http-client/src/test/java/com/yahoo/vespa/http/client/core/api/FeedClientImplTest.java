package com.yahoo.vespa.http.client.core.api;

import org.junit.Test;

import java.time.Instant;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

/**
 * @author dybis
 */
public class FeedClientImplTest {

    int sleepValueMillis = 1;

    @Test
    public void testCloseWaitTimeOldTimestamp() {
        assertThat(FeedClientImpl.waitForOperations(Instant.now().minusSeconds(1000), 1, sleepValueMillis, 10), is(false));
    }

    @Test
    public void testCloseWaitTimeOutInFutureStillOperations() {
        assertThat(FeedClientImpl.waitForOperations(Instant.now(), 1, sleepValueMillis, 2000), is(true));
    }

    @Test
    public void testCloseWaitZeroOperations() {
        assertThat(FeedClientImpl.waitForOperations(Instant.now(), 0, sleepValueMillis, 2000), is(false));
    }
}