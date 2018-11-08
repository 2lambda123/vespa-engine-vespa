// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.security;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * @author bjorncs
 */
class BouncyCastleProviderHolder {

    private static BouncyCastleProvider bcProvider;

    synchronized static BouncyCastleProvider getInstance() {
        if (bcProvider == null) {
            bcProvider = new BouncyCastleProvider();
        }
        return bcProvider;
    }
}
