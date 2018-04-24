// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.athenz.identity;

import com.google.inject.Inject;
import com.yahoo.component.AbstractComponent;
import com.yahoo.container.jdisc.athenz.AthenzIdentityProvider;
import com.yahoo.log.LogLevel;
import com.yahoo.vespa.athenz.api.AthenzService;
import com.yahoo.vespa.athenz.tls.KeyStoreType;
import com.yahoo.vespa.athenz.tls.SslContextBuilder;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * @author mortent
 * @author bjorncs
 */
public class SiaIdentityProvider extends AbstractComponent implements AthenzIdentityProvider {

    private static final Logger log = Logger.getLogger(SiaIdentityProvider.class.getName());

    private static final Duration REFRESH_INTERVAL = Duration.ofHours(1);

    private final AtomicReference<SSLContext> sslContext = new AtomicReference<>();
    private final AthenzService service;
    private final File privateKeyFile;
    private final File certificateFile;
    private final File trustStoreFile;
    private final ScheduledExecutorService scheduler;
    private final Set<Consumer<SSLContext>> listeners = new ConcurrentSkipListSet<>();

    @Inject
    public SiaIdentityProvider(SiaProviderConfig config) {
        this(new AthenzService(config.athenzDomain(), config.athenzService()),
             getPrivateKeyFile(config.keyPathPrefix(), config.athenzDomain(), config.athenzService()),
             getCertificateFile(config.keyPathPrefix(), config.athenzDomain(), config.athenzService()),
             new File(config.trustStorePath()),
             createScheduler());
    }

    public SiaIdentityProvider(AthenzService service,
                               Path siaPath,
                               File trustStoreFile) {
        this(service,
             getPrivateKeyFile(siaPath.toString(), service.getDomain().getName(), service.getName()),
             getCertificateFile(siaPath.toString(), service.getDomain().getName(), service.getName()),
             trustStoreFile,
             createScheduler());
    }

    public SiaIdentityProvider(AthenzService service,
                               File privateKeyFile,
                               File certificateFile,
                               File trustStoreFile,
                               ScheduledExecutorService scheduler) {
        this.service = service;
        this.privateKeyFile = privateKeyFile;
        this.certificateFile = certificateFile;
        this.trustStoreFile = trustStoreFile;
        this.scheduler = scheduler;
        this.sslContext.set(createIdentitySslContext());
        scheduler.scheduleAtFixedRate(this::reloadSslContext, REFRESH_INTERVAL.toMinutes(), REFRESH_INTERVAL.toMinutes(), TimeUnit.MINUTES);
    }

    private static ScheduledThreadPoolExecutor createScheduler() {
        return new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("sia-identity-provider-sslcontext-updater");
            return thread;
        });
    }

    @Override
    public String getDomain() {
        return service.getDomain().getName();
    }

    @Override
    public String getService() {
        return service.getName();
    }

    @Override
    public SSLContext getIdentitySslContext() {
        return sslContext.get();
    }

    public void addReloadListener(Consumer<SSLContext> listener) {
        listeners.add(listener);
    }

    public void removeReloadListener(Consumer<SSLContext> listener) {
        listeners.remove(listener);
    }

    private SSLContext createIdentitySslContext() {
        return new SslContextBuilder()
                .withTrustStore(trustStoreFile, KeyStoreType.JKS)
                .withKeyStore(privateKeyFile, certificateFile)
                .build();
    }

    private void reloadSslContext() {
        log.log(LogLevel.DEBUG, "Updating SSLContext for identity " + service.getFullName());
        try {
            SSLContext sslContext = createIdentitySslContext();
            this.sslContext.set(sslContext);
            listeners.forEach(listener -> listener.accept(sslContext));
        } catch (Exception e) {
            log.log(LogLevel.SEVERE, "Failed to update SSLContext: " + e.getMessage(), e);
        }
    }

    private static File getCertificateFile(String rootPath, String domain, String service) {
        return Paths.get(rootPath, "certs", String.format("%s.%s.cert.pem", domain, service)).toFile();
    }

    private static File getPrivateKeyFile(String rootPath, String domain, String service) {
        return Paths.get(rootPath, "keys", String.format("%s.%s.key.pem", domain, service)).toFile();
    }

    @Override
    public void deconstruct() {
        try {
            scheduler.shutdownNow();
            scheduler.awaitTermination(90, TimeUnit.SECONDS);
            listeners.clear();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
