// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.log;

import static com.yahoo.vespa.defaults.Defaults.getDefaults;

/**
 *
 * @author Bjorn Borud
 * @author arnej27959
 * @author bjorncs
 *
 */
public class Util {

    public static String getHostName () {
        return getDefaults().vespaHostname();
    }

    public static String getPID() {
        return Long.toString(ProcessHandle.current().pid());
    }

}
