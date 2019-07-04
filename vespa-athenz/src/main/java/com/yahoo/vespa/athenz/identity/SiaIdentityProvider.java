// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.athenz.identity;

import com.google.inject.Inject;
import com.yahoo.component.AbstractComponent;
import com.yahoo.security.KeyStoreType;
import com.yahoo.security.SslContextBuilder;
import com.yahoo.security.tls.AutoReloadingX509KeyManager;
import com.yahoo.vespa.athenz.api.AthenzIdentity;
import com.yahoo.vespa.athenz.api.AthenzService;
import com.yahoo.vespa.athenz.utils.SiaUtils;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A {@link ServiceIdentityProvider} that provides the credentials stored on file system.
 *
 * @author mortent
 * @author bjorncs
 */
public class SiaIdentityProvider extends AbstractComponent implements ServiceIdentityProvider {

    private final AutoReloadingX509KeyManager keyManager;
    private final SSLContext sslContext;
    private final AthenzIdentity service;
    private final File privateKeyFile;
    private final File certificateFile;
    private final File trustStoreFile;

    @Inject
    public SiaIdentityProvider(SiaProviderConfig config) {
        this(new AthenzService(config.athenzDomain(), config.athenzService()),
             SiaUtils.getPrivateKeyFile(Paths.get(config.keyPathPrefix()), new AthenzService(config.athenzDomain(), config.athenzService())).toFile(),
             SiaUtils.getCertificateFile(Paths.get(config.keyPathPrefix()), new AthenzService(config.athenzDomain(), config.athenzService())).toFile(),
             new File(config.trustStorePath()));
    }

    public SiaIdentityProvider(AthenzIdentity service,
                               Path siaPath,
                               File trustStoreFile) {
        this(service,
             SiaUtils.getPrivateKeyFile(siaPath, service).toFile(),
             SiaUtils.getCertificateFile(siaPath, service).toFile(),
             trustStoreFile);
    }

    public SiaIdentityProvider(AthenzIdentity service,
                               File privateKeyFile,
                               File certificateFile,
                               File trustStoreFile) {
        this.service = service;
        this.privateKeyFile = privateKeyFile;
        this.certificateFile = certificateFile;
        this.trustStoreFile = trustStoreFile;
        this.keyManager = AutoReloadingX509KeyManager.fromPemFiles(privateKeyFile.toPath(), certificateFile.toPath());
        this.sslContext = createIdentitySslContext(keyManager, trustStoreFile.toPath());
    }

    @Override
    public AthenzIdentity identity() {
        return service;
    }

    @Override
    public SSLContext getIdentitySslContext() {
        return sslContext;
    }

    private static SSLContext createIdentitySslContext(AutoReloadingX509KeyManager keyManager, Path trustStoreFile) {
        return new SslContextBuilder()
                .withTrustStore(trustStoreFile, KeyStoreType.JKS)
                .withKeyManager(keyManager)
                .build();
    }

    @Override
    public void deconstruct() {
        keyManager.close();
    }
}
