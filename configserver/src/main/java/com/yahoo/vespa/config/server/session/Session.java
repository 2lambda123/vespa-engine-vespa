// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.session;

import com.yahoo.config.provision.TenantName;
import com.yahoo.vespa.config.server.tenant.Tenants;

/**
 * A session represents an instance of an application that can be edited, prepared and activated. This
 * class represents the common stuff between sessions working on the local file
 * system ({@link LocalSession}s) and sessions working on zookeeper {@link RemoteSession}s.
 *
 * @author lulf
 * @since 5.1
 */
public abstract class Session {

    private final long sessionId;
    protected final TenantName tenant;

    protected Session(TenantName tenant, long sessionId) {
        this.tenant = tenant;
        this.sessionId = sessionId;
    }
    /**
     * Retrieve the session id for this session.
     * @return the session id.
     */
    public final long getSessionId() {
        return sessionId;
    }

    @Override
    public String toString() {
        return "Session,id=" + sessionId;
    }

    /**
     * Represents the status of this session.
     */
    public enum Status {
        NEW, PREPARE, ACTIVATE, DEACTIVATE, NONE;

        public static Status parse(String data) {
            for (Status status : Status.values()) {
                if (status.name().equals(data)) {
                    return status;
                }
            }
            return Status.NEW;
        }
    }
    
    public TenantName getTenant() {
        return tenant;
    }

    /**
     * Helper to provide a log message preamble for code dealing with sessions
     * @return log preamble
     */
    public String logPre() {
        return Tenants.logPre(getTenant());
    }

}
