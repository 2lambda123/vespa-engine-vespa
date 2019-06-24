// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.session;

import com.yahoo.component.Version;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.config.model.api.ModelContext;
import com.yahoo.config.model.api.TlsSecrets;
import com.yahoo.config.model.application.provider.BaseDeployLogger;
import com.yahoo.config.model.application.provider.FilesApplicationPackage;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.Rotation;
import com.yahoo.config.provision.TenantName;
import com.yahoo.io.IOUtils;
import com.yahoo.log.LogLevel;
import com.yahoo.path.Path;
import com.yahoo.slime.Slime;
import com.yahoo.vespa.config.server.MockReloadHandler;
import com.yahoo.vespa.config.server.MockSecretStore;
import com.yahoo.vespa.config.server.TestComponentRegistry;
import com.yahoo.vespa.config.server.TimeoutBudgetTest;
import com.yahoo.vespa.config.server.application.PermanentApplicationPackage;
import com.yahoo.vespa.config.server.application.TenantApplications;
import com.yahoo.vespa.config.server.deploy.DeployHandlerLogger;
import com.yahoo.vespa.config.server.host.HostRegistry;
import com.yahoo.vespa.config.server.http.InvalidApplicationException;
import com.yahoo.vespa.config.server.model.TestModelFactory;
import com.yahoo.vespa.config.server.modelfactory.ModelFactoryRegistry;
import com.yahoo.vespa.config.server.provision.HostProvisionerProvider;
import com.yahoo.config.model.api.ContainerEndpoint;
import com.yahoo.vespa.config.server.tenant.ContainerEndpointsCache;
import com.yahoo.vespa.config.server.tenant.Rotations;
import com.yahoo.vespa.config.server.tenant.TlsSecretsKeys;
import com.yahoo.vespa.config.server.zookeeper.ConfigCurator;
import com.yahoo.vespa.curator.mock.MockCurator;
import com.yahoo.vespa.flags.InMemoryFlagSource;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.contains;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * @author Ulf Lilleengen
 */
public class SessionPreparerTest {

    private static final Path tenantPath = Path.createRoot();
    private static final Path sessionsPath = tenantPath.append("sessions").append("testapp");
    private static final File testApp = new File("src/test/apps/app");
    private static final File invalidTestApp = new File("src/test/apps/illegalApp");
    private static final Version version123 = new Version(1, 2, 3);
    private static final Version version321 = new Version(3, 2, 1);

    private final InMemoryFlagSource flagSource = new InMemoryFlagSource();
    private MockCurator curator;
    private ConfigCurator configCurator;
    private SessionPreparer preparer;
    private TestComponentRegistry componentRegistry;
    private MockFileDistributionFactory fileDistributionFactory;
    private MockSecretStore secretStore = new MockSecretStore();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Before
    public void setUp() {
        curator = new MockCurator();
        configCurator = ConfigCurator.create(curator);
        componentRegistry = new TestComponentRegistry.Builder().curator(curator).build();
        fileDistributionFactory = (MockFileDistributionFactory)componentRegistry.getFileDistributionFactory();
        preparer = createPreparer();
    }

    private SessionPreparer createPreparer() {
        return createPreparer(HostProvisionerProvider.empty());
    }

    private SessionPreparer createPreparer(HostProvisionerProvider hostProvisionerProvider) {
        ModelFactoryRegistry modelFactoryRegistry =
                new ModelFactoryRegistry(Arrays.asList(new TestModelFactory(version123), new TestModelFactory(version321)));
        return createPreparer(modelFactoryRegistry, hostProvisionerProvider);
    }

    private SessionPreparer createPreparer(ModelFactoryRegistry modelFactoryRegistry,
                                           HostProvisionerProvider hostProvisionerProvider) {
        return new SessionPreparer(
                modelFactoryRegistry,
                componentRegistry.getFileDistributionFactory(),
                hostProvisionerProvider,
                new PermanentApplicationPackage(componentRegistry.getConfigserverConfig()),
                componentRegistry.getConfigserverConfig(),
                componentRegistry.getStaticConfigDefinitionRepo(),
                curator,
                componentRegistry.getZone(),
                flagSource,
                secretStore);
    }

    @Test(expected = InvalidApplicationException.class)
    public void require_that_application_validation_exception_is_not_caught() throws IOException {
        prepare(invalidTestApp);
    }

    @Test
    public void require_that_application_validation_exception_is_ignored_if_forced() throws IOException {
        prepare(invalidTestApp, new PrepareParams.Builder().ignoreValidationErrors(true).timeoutBudget(TimeoutBudgetTest.day()).build());
    }

    @Test
    public void require_that_zookeeper_is_not_written_to_if_dryrun() throws IOException {
        prepare(testApp, new PrepareParams.Builder().dryRun(true).timeoutBudget(TimeoutBudgetTest.day()).build());
        assertFalse(configCurator.exists(sessionsPath.append(ConfigCurator.USERAPP_ZK_SUBPATH).append("services.xml").getAbsolute()));
    }

    @Test
    public void require_that_filedistribution_is_ignored_on_dryrun() throws IOException {
        prepare(testApp, new PrepareParams.Builder().dryRun(true).timeoutBudget(TimeoutBudgetTest.day()).build());
        assertThat(fileDistributionFactory.mockFileDistributionProvider.timesCalled, is(0));
    }

    @Test
    public void require_that_application_is_prepared() throws Exception {
        prepare(testApp);
        assertThat(fileDistributionFactory.mockFileDistributionProvider.timesCalled, is(1)); // Only builds the newest version
        assertTrue(configCurator.exists(sessionsPath.append(ConfigCurator.USERAPP_ZK_SUBPATH).append("services.xml").getAbsolute()));
    }

    @Test(expected = InvalidApplicationException.class)
    public void require_exception_for_overlapping_host() throws IOException {
        SessionContext ctx = getContext(getApplicationPackage(testApp));
        ((HostRegistry<ApplicationId>)ctx.getHostValidator()).update(applicationId("foo"), Collections.singletonList("mytesthost"));
        preparer.prepare(ctx, new BaseDeployLogger(), new PrepareParams.Builder().build(), Optional.empty(), tenantPath, Instant.now());
    }
    
    @Test
    public void require_no_warning_for_overlapping_host_for_same_appid() throws IOException {
        SessionContext ctx = getContext(getApplicationPackage(testApp));
        ((HostRegistry<ApplicationId>)ctx.getHostValidator()).update(applicationId("default"), Collections.singletonList("mytesthost"));
        final StringBuilder logged = new StringBuilder();
        DeployLogger logger = (level, message) -> {
            System.out.println(level + ": "+message);
            if (level.equals(LogLevel.WARNING) && message.contains("The host mytesthost is already in use")) logged.append("ok");
        };
        preparer.prepare(ctx, logger, new PrepareParams.Builder().build(), Optional.empty(), tenantPath, Instant.now());
        assertEquals(logged.toString(), "");
    }

    @Test
    public void require_that_application_id_is_written_in_prepare() throws IOException {
        TenantName tenant = TenantName.from("tenant");
        ApplicationId origId = new ApplicationId.Builder()
                               .tenant(tenant)
                               .applicationName("foo").instanceName("quux").build();
        PrepareParams params = new PrepareParams.Builder().applicationId(origId).build();
        prepare(testApp, params);
        SessionZooKeeperClient zkc = new SessionZooKeeperClient(curator, sessionsPath);
        assertTrue(configCurator.exists(sessionsPath.append(SessionZooKeeperClient.APPLICATION_ID_PATH).getAbsolute()));
        assertThat(zkc.readApplicationId(), is(origId));
    }

    private List<ContainerEndpoint> readContainerEndpoints(ApplicationId application) {
        return new ContainerEndpointsCache(tenantPath, curator).read(application);
    }

    private Set<Rotation> readRotationsFromZK(ApplicationId applicationId) {
        return new Rotations(curator, tenantPath).readRotationsFromZooKeeper(applicationId);
    }

    @Test
    public void require_that_rotations_are_written_in_prepare() throws IOException {
        final String rotations = "mediasearch.msbe.global.vespa.yahooapis.com";
        final ApplicationId applicationId = applicationId("test");
        PrepareParams params = new PrepareParams.Builder().applicationId(applicationId).rotations(rotations).build();
        prepare(new File("src/test/resources/deploy/app"), params);
        assertThat(readRotationsFromZK(applicationId), contains(new Rotation(rotations)));
    }

    @Test
    public void require_that_rotations_are_read_from_zookeeper_and_used() throws IOException {
        final TestModelFactory modelFactory = new TestModelFactory(version123);
        preparer = createPreparer(new ModelFactoryRegistry(Collections.singletonList(modelFactory)),
                HostProvisionerProvider.empty());

        final String rotations = "foo.msbe.global.vespa.yahooapis.com";
        final ApplicationId applicationId = applicationId("test");
        new Rotations(curator, tenantPath).writeRotationsToZooKeeper(applicationId, Collections.singleton(new Rotation(rotations)));
        final PrepareParams params = new PrepareParams.Builder().applicationId(applicationId).build();
        final File app = new File("src/test/resources/deploy/app");
        prepare(app, params);

        // check that the rotation from zookeeper were used
        final ModelContext modelContext = modelFactory.getModelContext();
        final Set<Rotation> rotationSet = modelContext.properties().rotations();
        assertThat(rotationSet, contains(new Rotation(rotations)));

        // Check that the persisted value is still the same
        assertThat(readRotationsFromZK(applicationId), contains(new Rotation(rotations)));
    }

    @Test
    public void require_that_rotations_are_written_as_container_endpoints() throws Exception {
        var rotations = "app1.tenant1.global.vespa.example.com,rotation-042.vespa.global.routing";
        var applicationId = applicationId("test");
        var params = new PrepareParams.Builder().applicationId(applicationId).rotations(rotations).build();
        prepare(new File("src/test/resources/deploy/hosted-app"), params);

        var expected = List.of(new ContainerEndpoint("qrs",
                                                     List.of("app1.tenant1.global.vespa.example.com",
                                                             "rotation-042.vespa.global.routing")));
        assertEquals(expected, readContainerEndpoints(applicationId));
    }

    @Test
    public void require_that_container_endpoints_are_written() throws Exception {
        var endpoints = "[\n" +
                        "  {\n" +
                        "    \"clusterId\": \"foo\",\n" +
                        "    \"names\": [\n" +
                        "      \"foo.app1.tenant1.global.vespa.example.com\",\n" +
                        "      \"rotation-042.vespa.global.routing\"\n" +
                        "    ]\n" +
                        "  },\n" +
                        "  {\n" +
                        "    \"clusterId\": \"bar\",\n" +
                        "    \"names\": [\n" +
                        "      \"bar.app1.tenant1.global.vespa.example.com\",\n" +
                        "      \"rotation-043.vespa.global.routing\"\n" +
                        "    ]\n" +
                        "  }\n" +
                        "]";
        var applicationId = applicationId("test");
        var params = new PrepareParams.Builder().applicationId(applicationId)
                                                .containerEndpoints(endpoints)
                                                .build();
        prepare(new File("src/test/resources/deploy/hosted-app"), params);

        var expected = List.of(new ContainerEndpoint("foo",
                                                     List.of("foo.app1.tenant1.global.vespa.example.com",
                                                             "rotation-042.vespa.global.routing")),
                               new ContainerEndpoint("bar",
                                                     List.of("bar.app1.tenant1.global.vespa.example.com",
                                                             "rotation-043.vespa.global.routing")));
        assertEquals(expected, readContainerEndpoints(applicationId));
    }

    @Test
    public void require_that_tlssecretkey_is_written() throws IOException {
        var tlskey = "vespa.tlskeys.tenant1--app1";
        var applicationId = applicationId("test");
        var params = new PrepareParams.Builder().applicationId(applicationId).tlsSecretsKeyName(tlskey).build();
        secretStore.put(tlskey+"-cert", "CERT");
        secretStore.put(tlskey+"-key", "KEY");
        prepare(new File("src/test/resources/deploy/hosted-app"), params);

        // Read from zk and verify cert and key are available
        Optional<TlsSecrets> tlsSecrets = new TlsSecretsKeys(curator, tenantPath, secretStore).readTlsSecretsKeyFromZookeeper(applicationId);
        assertTrue(tlsSecrets.isPresent());
        assertEquals("KEY", tlsSecrets.get().key());
        assertEquals("CERT", tlsSecrets.get().certificate());
    }

    @Test
    public void require_that_tlssecretkey_is_missing_when_not_in_secretstore() throws IOException {
        var tlskey = "vespa.tlskeys.tenant1--app1";
        var applicationId = applicationId("test");
        var params = new PrepareParams.Builder().applicationId(applicationId).tlsSecretsKeyName(tlskey).build();
        prepare(new File("src/test/resources/deploy/hosted-app"), params);

        // Read from zk and verify key/cert is missing
        Optional<TlsSecrets> tlsSecrets = new TlsSecretsKeys(curator, tenantPath, secretStore).readTlsSecretsKeyFromZookeeper(applicationId);
        assertTrue(tlsSecrets.isPresent());
        assertTrue(tlsSecrets.get().isMissing());
    }

    @Test
    public void require_that_tlssecretkey_is_missing_when_certificate_not_in_secretstore() throws IOException {
        var tlskey = "vespa.tlskeys.tenant1--app1";
        var applicationId = applicationId("test");
        var params = new PrepareParams.Builder().applicationId(applicationId).tlsSecretsKeyName(tlskey).build();
        secretStore.put(tlskey+"-key", "KEY");
        prepare(new File("src/test/resources/deploy/hosted-app"), params);

        // Read from zk and verify key/cert is missing
        Optional<TlsSecrets> tlsSecrets = new TlsSecretsKeys(curator, tenantPath, secretStore).readTlsSecretsKeyFromZookeeper(applicationId);
        assertTrue(tlsSecrets.isPresent());
        assertTrue(tlsSecrets.get().isMissing());
    }

    private void prepare(File app) throws IOException {
        prepare(app, new PrepareParams.Builder().build());
    }

    private void prepare(File app, PrepareParams params) throws IOException {
        preparer.prepare(getContext(getApplicationPackage(app)), getLogger(), params, Optional.empty(), tenantPath, Instant.now());
    }

    private SessionContext getContext(FilesApplicationPackage app) throws IOException {
        return new SessionContext(app,
                                  new SessionZooKeeperClient(curator, sessionsPath),
                                  app.getAppDir(),
                                  TenantApplications.create(componentRegistry, new MockReloadHandler(), TenantName.from("tenant")),
                                  new HostRegistry<>(),
                                  flagSource);
    }

    private FilesApplicationPackage getApplicationPackage(File testFile) throws IOException {
        File appDir = folder.newFolder();
        IOUtils.copyDirectory(testFile, appDir);
        return FilesApplicationPackage.fromFile(appDir);
    }

    private DeployHandlerLogger getLogger() {
        return new DeployHandlerLogger(new Slime().get(), false /*verbose */,
                                       new ApplicationId.Builder().tenant("testtenant").applicationName("testapp").build());
    }

    private ApplicationId applicationId(String applicationName) {
        return ApplicationId.from(TenantName.defaultName(),
                                  ApplicationName.from(applicationName), InstanceName.defaultName());
    }

}
