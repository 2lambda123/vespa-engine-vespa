// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.jdisc.test.MockMetric;
import com.yahoo.vespa.flags.Flags;
import com.yahoo.vespa.hosted.controller.ControllerTester;
import com.yahoo.vespa.hosted.controller.integration.SecretStoreMock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.yahoo.vespa.hosted.controller.maintenance.CertPoolMaintainer.CertificatePool.requested;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author andreer
 */
public class CertificatePoolMaintainerTest {

    private final ControllerTester tester = new ControllerTester();
    private final SecretStoreMock secretStore = (SecretStoreMock) tester.controller().secretStore();
    private final CertPoolMaintainer maintainer = new CertPoolMaintainer(tester.controller(), new MockMetric(), Duration.ofHours(1));

    @Test
    void new_certs_are_requested_until_limit() {
        tester.flagSource().withIntFlag(Flags.CERT_POOL_SIZE.id(), 3);
        assertNumCerts(1);
        assertNumCerts(2);
        assertNumCerts(3);
        assertNumCerts(3);
    }

    private void assertNumCerts(int n) {
        assertEquals(0.0, maintainer.maintain(), 0.0000001);
        assertEquals(n, tester.curator().readCertificatePool(requested.name()).entrySet().size());
    }
}
