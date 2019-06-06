// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.provision.zone;

import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.RegionName;

import java.util.List;

/**
 * Provides filters for and access to a list of ZoneIds.
 *
 * This is typically offered after an initial filter from {@link ZoneFilter} has been applied.
 * This forces the developer to consider which zones to process.
 *
 * @author jonmv
 */
public interface ZoneList extends ZoneFilter {

    /** Negates the next filter. */
    @Override
    ZoneList not();

    /** Zones in one of the given environments. */
    ZoneList in(Environment... environments);

    /** Zones in one of the given regions. */
    ZoneList in(RegionName... regions);

    /** Only the given zones — combine with not() for best effect! */
    ZoneList among(ZoneId... zones);

    /** Returns the ZoneApi of all zones in this list. */
    List<? extends ZoneApi> zones();

    /** Returns the id of all zones in this list as — you guessed it — a list. */
    List<ZoneId> ids();

}
