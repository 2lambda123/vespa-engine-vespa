// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.config.provision.CloudAccount;
import com.yahoo.vespa.flags.PermanentFlags;
import com.yahoo.vespa.hosted.controller.ControllerTester;
import com.yahoo.vespa.hosted.controller.api.integration.aws.MockEnclaveAccessService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author jonmv
 */
class EnclaveAccessMaintainerTest {

    @Test
    void test() {
        ControllerTester tester = new ControllerTester();
        MockEnclaveAccessService amis = tester.serviceRegistry().enclaveAccessService();
        EnclaveAccessMaintainer sharer = new EnclaveAccessMaintainer(tester.controller(), Duration.ofHours(1));
        CloudAccountVerifier accountVerifier = new CloudAccountVerifier(tester.controller(), Duration.ofHours(1));
        assertEquals(Set.of(), amis.currentAccounts());

        assertEquals(1, sharer.maintain());
        assertEquals(Set.of(), amis.currentAccounts());

        tester.createTenant("tanten");
        accountVerifier.maintain();
        assertEquals(1, sharer.maintain());
        assertEquals(Set.of(), amis.currentAccounts());

        tester.flagSource().withListFlag(PermanentFlags.CLOUD_ACCOUNTS.id(), List.of("123123123123", "321321321321"), String.class);
        accountVerifier.maintain();
        assertEquals(1, sharer.maintain());
        assertEquals(Set.of(CloudAccount.from("aws:123123123123"), CloudAccount.from("aws:321321321321")), amis.currentAccounts());
    }

}
