// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.provider;

import com.yahoo.vespa.hosted.node.admin.nodeadmin.NodeAdminStateUpdater;
import com.yahoo.vespa.hosted.node.admin.restapi.SecretAgentHandler;

/**
 * Class for setting up instances of classes; enables testing.
 *
 * @author dybis
 */
public interface ComponentsProvider {
    NodeAdminStateUpdater getNodeAdminStateUpdater();

    SecretAgentHandler getSecretAgentHandler();
}
