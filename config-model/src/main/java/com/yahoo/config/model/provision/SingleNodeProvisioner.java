// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.model.provision;

import com.yahoo.config.model.api.HostProvisioner;
import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterMembership;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.ProvisionLogger;
import com.yahoo.net.HostName;

import java.util.ArrayList;
import java.util.List;

/**
 * A host provisioner used when there is no hosts.xml file (using localhost as the only host)
 * No state in this provisioner, i.e it does not know anything about the active
 * application if one exists.
 *
 * @author hmusum
 */
public class SingleNodeProvisioner implements HostProvisioner {

    private final Host host; // the only host in this system
    private final HostSpec hostSpec;
    private int counter = 0;

    public SingleNodeProvisioner() {
        host = new Host(HostName.getLocalhost());
        this.hostSpec = new HostSpec(host.hostname(), host.aliases());
    }

    public SingleNodeProvisioner(Flavor flavor) {
        host = new Host(HostName.getLocalhost());
        this.hostSpec = new HostSpec(host.hostname(), host.aliases(), flavor);
    }

    @Override
    public HostSpec allocateHost(String alias) {
        return hostSpec;
    }

    @Override
    @Deprecated // TODO: Remove after April 2020
    public List<HostSpec> prepare(ClusterSpec cluster, Capacity capacity, int groups, ProvisionLogger logger) {
        return prepare(cluster, capacity.withGroups(groups), logger);
    }

    @Override
    public List<HostSpec> prepare(ClusterSpec cluster, Capacity capacity, ProvisionLogger logger) {
        // TODO: This should fail if capacity requested is more than 1
        List<HostSpec> hosts = new ArrayList<>();
        hosts.add(new HostSpec(host.hostname(), host.aliases(), ClusterMembership.from(cluster, counter++)));
        return hosts;
    }

}
