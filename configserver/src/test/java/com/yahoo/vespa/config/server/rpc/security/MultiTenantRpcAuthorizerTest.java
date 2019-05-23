package com.yahoo.vespa.config.server.rpc.security;// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

import com.yahoo.cloud.config.LbServicesConfig;
import com.yahoo.cloud.config.RoutingConfig;
import com.yahoo.config.FileReference;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.HostName;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.TenantName;
import com.yahoo.config.provision.security.NodeIdentifier;
import com.yahoo.config.provision.security.NodeIdentifierException;
import com.yahoo.config.provision.security.NodeIdentity;
import com.yahoo.jrt.Request;
import com.yahoo.jrt.SecurityContext;
import com.yahoo.jrt.StringValue;
import com.yahoo.jrt.Target;
import com.yahoo.jrt.Values;
import com.yahoo.security.KeyAlgorithm;
import com.yahoo.security.KeyUtils;
import com.yahoo.security.SignatureAlgorithm;
import com.yahoo.security.X509CertificateBuilder;
import com.yahoo.slime.Cursor;
import com.yahoo.slime.JsonFormat;
import com.yahoo.slime.Slime;
import com.yahoo.vespa.config.ConfigKey;
import com.yahoo.vespa.config.server.RequestHandler;
import com.yahoo.vespa.config.server.host.HostRegistry;
import com.yahoo.vespa.config.server.tenant.Tenant;
import com.yahoo.vespa.config.server.tenant.TenantRepository;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import static com.yahoo.vespa.config.server.rpc.security.MultiTenantRpcAuthorizer.Mode.ENFORCE;
import static java.time.temporal.ChronoUnit.DAYS;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author bjorncs
 */
public class MultiTenantRpcAuthorizerTest {

    private static final List<X509Certificate> PEER_CERTIFICATE_CHAIN = List.of(createDummyCertificate());
    private static final ApplicationId APPLICATION_ID = ApplicationId.from("mytenant", "myapplication", "default");
    private static final ApplicationId EVIL_APP_ID = ApplicationId.from("malice", "malice-app", "default");
    private static final HostName HOSTNAME = HostName.from("myhostname");
    private static final FileReference FILE_REFERENCE = new FileReference("myfilereference");

    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    @Test
    public void configserver_can_access_files_and_config() throws InterruptedException, ExecutionException {
        RpcAuthorizer authorizer = createAuthorizer(new NodeIdentity.Builder(NodeType.config).build(),
                                                    new HostRegistry<>());

        Request configRequest = createConfigRequest(new ConfigKey<>("name", "configid", "namespace"), HOSTNAME);
        authorizer.authorizeConfigRequest(configRequest)
                .get();

        Request fileRequest = createFileRequest(FILE_REFERENCE);
        authorizer.authorizeFileRequest(fileRequest)
                .get();
    }

    @Test
    public void tenant_node_can_access_its_own_files_and_config() throws ExecutionException, InterruptedException {
        NodeIdentity identity = new NodeIdentity.Builder(NodeType.tenant)
                .applicationId(APPLICATION_ID)
                .build();

        HostRegistry<TenantName> hostRegistry = new HostRegistry<>();
        hostRegistry.update(APPLICATION_ID.tenant(), List.of(HOSTNAME.value()));

        RpcAuthorizer authorizer = createAuthorizer(identity, hostRegistry);

        Request configRequest = createConfigRequest(new ConfigKey<>("name", "configid", "namespace"), HOSTNAME);
        authorizer.authorizeConfigRequest(configRequest)
                .get();

        Request fileRequest = createFileRequest(FILE_REFERENCE);
        authorizer.authorizeFileRequest(fileRequest)
                .get();
    }

    @Test
    public void proxy_node_can_access_lbservice_config() throws ExecutionException, InterruptedException {
        RpcAuthorizer authorizer = createAuthorizer(new NodeIdentity.Builder(NodeType.proxy).build(), new HostRegistry<>());

        Request configRequest = createConfigRequest(
                new ConfigKey<>(LbServicesConfig.CONFIG_DEF_NAME, "*", LbServicesConfig.CONFIG_DEF_NAMESPACE),
                HOSTNAME);
        authorizer.authorizeConfigRequest(configRequest)
                .get();
    }

    @Test
    public void tenant_node_can_access_routing_config() throws ExecutionException, InterruptedException {
        RpcAuthorizer authorizer = createAuthorizer(new NodeIdentity.Builder(NodeType.tenant).build(), new HostRegistry<>());

        Request configRequest = createConfigRequest(
                new ConfigKey<>(RoutingConfig.CONFIG_DEF_NAME, "*", RoutingConfig.CONFIG_DEF_NAMESPACE),
                HOSTNAME);
        authorizer.authorizeConfigRequest(configRequest)
                .get();
    }

    @Test
    public void tenant_node_cannot_access_lbservice_config() throws ExecutionException, InterruptedException {
        RpcAuthorizer authorizer = createAuthorizer(new NodeIdentity.Builder(NodeType.tenant).build(), new HostRegistry<>());

        Request configRequest = createConfigRequest(
                new ConfigKey<>(LbServicesConfig.CONFIG_DEF_NAME, "*", LbServicesConfig.CONFIG_DEF_NAMESPACE),
                HOSTNAME);

        exceptionRule.expectMessage("Node with type 'tenant' is not allowed to access global config [name=lb-services,namespace=cloud.config,configId=*]");
        exceptionRule.expectCause(instanceOf(AuthorizationException.class));

        authorizer.authorizeConfigRequest(configRequest)
                .get();
    }

    @Test
    public void tenant_node_cannot_access_other_files() throws ExecutionException, InterruptedException {
        NodeIdentity identity = new NodeIdentity.Builder(NodeType.tenant)
                .applicationId(APPLICATION_ID)
                .build();

        HostRegistry<TenantName> hostRegistry = new HostRegistry<>();
        hostRegistry.update(APPLICATION_ID.tenant(), List.of(HOSTNAME.value()));

        RpcAuthorizer authorizer = createAuthorizer(identity, hostRegistry);

        Request fileRequest = createFileRequest(new FileReference("other-file-reference"));

        exceptionRule.expectMessage("Peer is not allowed to access file other-file-reference");
        exceptionRule.expectCause(instanceOf(AuthorizationException.class));

        authorizer.authorizeFileRequest(fileRequest)
                .get();
    }

    @Test
    public void tenant_node_cannot_access_other_config() throws ExecutionException, InterruptedException {
        NodeIdentity identity = new NodeIdentity.Builder(NodeType.tenant)
                .applicationId(EVIL_APP_ID)
                .build();

        HostRegistry<TenantName> hostRegistry = new HostRegistry<>();
        hostRegistry.update(APPLICATION_ID.tenant(), List.of(HOSTNAME.value()));

        RpcAuthorizer authorizer = createAuthorizer(identity, hostRegistry);

        Request configRequest = createConfigRequest(new ConfigKey<>("name", "configid", "namespace"), HOSTNAME);

        exceptionRule.expectMessage("Peer is not allowed to access config for owned by mytenant.myapplication. Peer is owned by malice.malice-app");
        exceptionRule.expectCause(instanceOf(AuthorizationException.class));

        authorizer.authorizeConfigRequest(configRequest)
                .get();
    }

    private static RpcAuthorizer createAuthorizer(NodeIdentity identity, HostRegistry<TenantName> hostRegistry) {
        return new MultiTenantRpcAuthorizer(
                new StaticNodeIdentifier(identity),
                hostRegistry,
                createTenantRepositoryMock(),
                new DirectExecutor(),
                ENFORCE);
    }

    private static Request createConfigRequest(ConfigKey<?> configKey, HostName hostName) {
        return mockJrtRpcRequest(createConfigPayload(configKey, hostName.value()));
    }

    private static Request createFileRequest(FileReference fileReference) {
        return mockJrtRpcRequest(fileReference.value());
    }

    private static TenantRepository createTenantRepositoryMock() {
        RequestHandler requestHandler = mock(RequestHandler.class);
        when(requestHandler.hasApplication(APPLICATION_ID, Optional.empty())).thenReturn(true);
        when(requestHandler.resolveApplicationId(HOSTNAME.value())).thenReturn(APPLICATION_ID);
        when(requestHandler.listFileReferences(APPLICATION_ID)).thenReturn(Set.of(FILE_REFERENCE));
        Tenant tenant = mock(Tenant.class);
        when(tenant.getRequestHandler()).thenReturn(requestHandler);

        TenantRepository tenantRepository = mock(TenantRepository.class);
        when(tenantRepository.getTenant(APPLICATION_ID.tenant())).thenReturn(tenant);
        return tenantRepository;
    }

    private static Request mockJrtRpcRequest(String payload) {
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.peerCertificateChain()).thenReturn(PEER_CERTIFICATE_CHAIN);
        Target target = mock(Target.class);
        when(target.getSecurityContext()).thenReturn(Optional.of(securityContext));
        Request request = mock(Request.class);
        when(request.target()).thenReturn(target);
        Values values = new Values();
        values.add(new StringValue(payload));
        when(request.parameters()).thenReturn(values);
        return request;
    }

    private static String createConfigPayload(ConfigKey<?> configKey, String hostname) {
        Slime data = new Slime();
        Cursor request = data.setObject();
        request.setString("defName", configKey.getName());
        request.setString("defNamespace", configKey.getNamespace());
        request.setString("defMD5", configKey.getMd5());
        request.setString("configId", configKey.getConfigId());
        request.setString("clientHostname", hostname);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            new JsonFormat(false).encode(out, data);
            return new String(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static X509Certificate createDummyCertificate() {
        return X509CertificateBuilder.fromKeypair(
                KeyUtils.generateKeypair(KeyAlgorithm.EC),
                new X500Principal("CN=" + HOSTNAME),
                Instant.EPOCH,
                Instant.EPOCH.plus(1, DAYS),
                SignatureAlgorithm.SHA256_WITH_ECDSA,
                BigInteger.ONE)
                .build();
    }

    private static class DirectExecutor implements Executor {

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static class StaticNodeIdentifier implements NodeIdentifier {
        final NodeIdentity identity;

        StaticNodeIdentifier(NodeIdentity identity) {
            this.identity = identity;
        }

        @Override
        public NodeIdentity identifyNode(List<X509Certificate> peerCertificateChain) throws NodeIdentifierException {
            return identity;
        }
    }

}