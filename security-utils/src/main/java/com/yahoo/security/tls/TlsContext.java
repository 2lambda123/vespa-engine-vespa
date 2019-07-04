// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.security.tls;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.util.Set;

/**
 * A simplified version of {@link SSLContext} modelled as an interface.
 *
 * @author bjorncs
 */
public interface TlsContext extends AutoCloseable {

    Set<String> ALLOWED_CIPHER_SUITES = Set.of(
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_AES_128_GCM_SHA256", // TLSv1.3
            "TLS_AES_256_GCM_SHA384", // TLSv1.3
            "TLS_CHACHA20_POLY1305_SHA256"); // TLSv1.3

    Set<String> ALLOWED_PROTOCOLS = Set.of("TLSv1.2"); // TODO Enable TLSv1.3

    SSLContext context();

    SSLParameters parameters();

    SSLEngine createSslEngine();

    SSLEngine createSslEngine(String peerHost, int peerPort);

    @Override default void close() {}

}
