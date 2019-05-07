// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.dns;

import com.yahoo.config.provision.HostName;
import com.yahoo.config.provision.zone.ZoneId;

import java.util.Objects;

/**
 * Represents the target of an ALIAS record.
 *
 * @author mpolden
 */
public class AliasTarget {

    private final HostName name;
    private final String dnsZone;
    private final ZoneId zone;

    public AliasTarget(HostName name, String dnsZone, ZoneId zone) {
        this.name = Objects.requireNonNull(name, "name must be non-null");
        this.dnsZone = Objects.requireNonNull(dnsZone, "dnsZone must be non-null");
        this.zone = Objects.requireNonNull(zone, "zone must be non-null");
    }

    /** DNS name of this */
    public HostName name() {
        return name;
    }

    /** DNS zone of this */
    public String dnsZone() {
        return dnsZone;
    }

    /** The zone where this exists */
    public ZoneId zone() {
        return zone;
    }

    /** Returns the fields in this encoded as record data */
    public RecordData asData() {
        return RecordData.from(name.value() + "/" + dnsZone + "/" + zone.value());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AliasTarget that = (AliasTarget) o;
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return String.format("rotation target %s [zone: %s] in %s", name, dnsZone, zone);
    }

    public static AliasTarget from(RecordData data) {
        var parts = data.asString().split("/");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected data to be on format [hostname]/[DNS zone]/[zone], but got " +
                                               data.asString());
        }
        return new AliasTarget(HostName.from(parts[0]), parts[1], ZoneId.from(parts[2]));
    }

}
