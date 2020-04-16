// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.testutils;

import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.HostFilter;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.ProvisionLogger;
import com.yahoo.config.provision.Provisioner;
import com.yahoo.transaction.NestedTransaction;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author freva
 */
public class MockProvisioner implements Provisioner {

    @Override
    @Deprecated // TODO: Remove after April 2020
    public List<HostSpec> prepare(ApplicationId applicationId, ClusterSpec cluster, Capacity capacity, int groups, ProvisionLogger logger) {
        return Collections.emptyList();
    }

    @Override
    public List<HostSpec> prepare(ApplicationId applicationId, ClusterSpec cluster, Capacity capacity, ProvisionLogger logger) {
        return Collections.emptyList();
    }

    @Override
    public void activate(NestedTransaction transaction, ApplicationId application, Collection<HostSpec> hosts) {

    }

    @Override
    public void remove(NestedTransaction transaction, ApplicationId application) {

    }

    @Override
    public void restart(ApplicationId application, HostFilter filter) {

    }

}
