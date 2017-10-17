// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.athenz;

import com.yahoo.vespa.hosted.controller.api.identifiers.AthensDomain;

import java.util.Objects;

/**
 * @author bjorncs
 */
public class AthenzService {

    private final AthensDomain domain;
    private final String serviceName;

    public AthenzService(AthensDomain domain, String serviceName) {
        this.domain = domain;
        this.serviceName = serviceName;
    }

    public AthenzService(String domain, String serviceName) {
        this(new AthensDomain(domain), serviceName);
    }

    public String toFullServiceName() {
        return domain.id() + "." + serviceName;
    }

    public AthensDomain getDomain() {
        return domain;
    }

    public String getServiceName() {
        return serviceName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AthenzService that = (AthenzService) o;
        return Objects.equals(domain, that.domain) &&
                Objects.equals(serviceName, that.serviceName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(domain, serviceName);
    }

    @Override
    public String toString() {
        return String.format("AthenzService(%s)", toFullServiceName());
    }
}
