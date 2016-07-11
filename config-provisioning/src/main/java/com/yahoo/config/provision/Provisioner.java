// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.provision;

import com.yahoo.transaction.NestedTransaction;

import java.util.Collection;
import java.util.List;

/**
 * Interface used by the config system to acquire hosts.
 *
 * @author lulf
 * @since 5.11
 */
public interface Provisioner {

    /**
     * Prepares allocation of a set of hosts with a given type, common id and the amount.
     *
     * @param applicationId the application requesting hosts
     * @param cluster the specification of the cluster to allocate nodes for
     * @param capacity the capacity requested
     * @param groups the number of node groups to divide the requested capacity into
     * @param logger a logger which receives messages which are returned to the requestor
     * @return the specification of the hosts allocated
     */
    List<HostSpec> prepare(ApplicationId applicationId, ClusterSpec cluster, Capacity capacity, int groups, ProvisionLogger logger);

    /**
     * Activates the allocation of nodes to this application captured in the hosts argument.
     *
     * @param transaction Transaction with operations to commit together with any operations done within the provisioner.
     * @param application The {@link ApplicationId} that was activated.
     * @param hosts a set of {@link HostSpec}.
     */
    void activate(NestedTransaction transaction, ApplicationId application, Collection<HostSpec> hosts);

    /**
     * Notifies provisioner that an application has been removed.
     *
     * @param application The {@link ApplicationId} that was removed.
     * @deprecated use remove(transaction, application) instead
     */
    @Deprecated
    default void removed(ApplicationId application) {
        throw new IllegalStateException("Unexpected use of deprecated method");
    }

    /**
     * Transactionally remove this application.
     * This default implementation delegates to removed(application), i.e performs the removal non-transactional.
     * 
     * @param application
     */
    // TODO: Remove the default implementation in this when
    //       no applications are on a version before 5.17
    @SuppressWarnings("deprecation")
    default void remove(NestedTransaction transaction, ApplicationId application) {
        removed(application);
    }

    /**
     * Requests a restart of the services of the given application
     *
     * @param application the application to restart
     * @param filter a filter which matches the application nodes to restart
     */
    void restart(ApplicationId application, HostFilter filter);

}
