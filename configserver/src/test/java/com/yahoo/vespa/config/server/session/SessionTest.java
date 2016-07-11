// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.session;

import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.path.Path;
import com.yahoo.vespa.config.server.application.ApplicationSet;
import com.yahoo.vespa.config.server.configchange.ConfigChangeActions;
import com.yahoo.vespa.curator.mock.MockCurator;

import java.util.ArrayList;
import java.util.Optional;

/**
 * @author lulf
 * @since 5.1
 */
public class SessionTest {

    public static class MockSessionPreparer extends SessionPreparer {
        public boolean isPrepared = false;

        public MockSessionPreparer() {
            super(null, null, null, null, null, null, new MockCurator(), null);
        }

        @Override
        public ConfigChangeActions prepare(SessionContext context, DeployLogger logger, PrepareParams params, Optional<ApplicationSet> currentActiveApplicationSet, Path tenantPath) {
            isPrepared = true;
            return new ConfigChangeActions(new ArrayList<>());
        }
    }

}
