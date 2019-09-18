// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller;

import com.yahoo.component.Version;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.TenantName;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.slime.Slime;
import com.yahoo.test.ManualClock;
import com.yahoo.vespa.athenz.api.AthenzDomain;
import com.yahoo.vespa.athenz.api.AthenzPrincipal;
import com.yahoo.vespa.athenz.api.AthenzUser;
import com.yahoo.vespa.athenz.api.OktaAccessToken;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.curator.mock.MockCurator;
import com.yahoo.vespa.flags.InMemoryFlagSource;
import com.yahoo.vespa.hosted.controller.api.application.v4.model.DeployOptions;
import com.yahoo.vespa.hosted.controller.api.identifiers.Property;
import com.yahoo.vespa.hosted.controller.api.identifiers.PropertyId;
import com.yahoo.vespa.hosted.controller.api.integration.BuildService;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.api.integration.dns.MemoryNameService;
import com.yahoo.vespa.hosted.controller.api.integration.dns.Record;
import com.yahoo.vespa.hosted.controller.api.integration.dns.RecordName;
import com.yahoo.vespa.hosted.controller.api.integration.organization.Contact;
import com.yahoo.vespa.hosted.controller.api.integration.stubs.MockMavenRepository;
import com.yahoo.vespa.hosted.controller.application.ApplicationPackage;
import com.yahoo.vespa.hosted.controller.athenz.impl.AthenzFacade;
import com.yahoo.vespa.hosted.controller.api.integration.athenz.AthenzClientFactoryMock;
import com.yahoo.vespa.hosted.controller.api.integration.athenz.AthenzDbMock;
import com.yahoo.vespa.hosted.controller.integration.ConfigServerMock;
import com.yahoo.vespa.hosted.controller.integration.ServiceRegistryMock;
import com.yahoo.vespa.hosted.controller.integration.ZoneRegistryMock;
import com.yahoo.vespa.hosted.controller.persistence.InstanceSerializer;
import com.yahoo.vespa.hosted.controller.persistence.CuratorDb;
import com.yahoo.vespa.hosted.controller.persistence.MockCuratorDb;
import com.yahoo.vespa.hosted.controller.security.AthenzCredentials;
import com.yahoo.vespa.hosted.controller.security.AthenzTenantSpec;
import com.yahoo.vespa.hosted.controller.security.Credentials;
import com.yahoo.vespa.hosted.controller.tenant.AthenzTenant;
import com.yahoo.vespa.hosted.controller.tenant.Tenant;
import com.yahoo.vespa.hosted.controller.versions.VersionStatus;
import com.yahoo.vespa.hosted.rotation.config.RotationsConfig;

import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Logger;

import static org.junit.Assert.assertNotNull;

/**
 * Convenience methods for controller tests.
 *
 * @author bratseth
 * @author mpolden
 */
public final class ControllerTester {

    public static final int availableRotations = 10;

    private final AthenzDbMock athenzDb;
    private final ManualClock clock;
    private final ZoneRegistryMock zoneRegistry;
    private final ServiceRegistryMock serviceRegistry;
    private final CuratorDb curator;
    private final RotationsConfig rotationsConfig;

    private Controller controller;

    public ControllerTester(ManualClock clock, RotationsConfig rotationsConfig, MockCuratorDb curatorDb) {
        this(new AthenzDbMock(),
             clock,
             new ZoneRegistryMock(),
             curatorDb,
             rotationsConfig,
             new ServiceRegistryMock());
    }

    public ControllerTester(ManualClock clock) {
        this(clock, defaultRotationsConfig(), new MockCuratorDb());
    }

    public ControllerTester(RotationsConfig rotationsConfig) {
        this(new ManualClock(), rotationsConfig, new MockCuratorDb());
    }

    public ControllerTester(MockCuratorDb curatorDb) {
        this(new ManualClock(), defaultRotationsConfig(), curatorDb);
    }

    public ControllerTester() {
        this(new ManualClock());
    }

    private ControllerTester(AthenzDbMock athenzDb, ManualClock clock,
                             ZoneRegistryMock zoneRegistry,
                             CuratorDb curator, RotationsConfig rotationsConfig,
                             ServiceRegistryMock serviceRegistry) {
        this.athenzDb = athenzDb;
        this.clock = clock;
        this.zoneRegistry = zoneRegistry;
        this.serviceRegistry = serviceRegistry;
        this.curator = curator;
        this.rotationsConfig = rotationsConfig;
        this.controller = createController(curator, rotationsConfig, clock, zoneRegistry, athenzDb, serviceRegistry);

        // Make root logger use time from manual clock
        configureDefaultLogHandler(handler -> handler.setFilter(
                record -> {
                    record.setInstant(clock.instant());
                    return true;
                }));
    }

    public void configureDefaultLogHandler(Consumer<Handler> configureFunc) {
        Arrays.stream(Logger.getLogger("").getHandlers())
              // Do not mess with log configuration if a custom one has been set
              .filter(ignored -> System.getProperty("java.util.logging.config.file") == null)
              .findFirst()
              .ifPresent(configureFunc);
    }

    public static BuildService.BuildJob buildJob(Instance instance, JobType jobType) {
        return BuildService.BuildJob.of(instance.id(), instance.deploymentJobs().projectId().getAsLong(), jobType.jobName());
    }

    public Controller controller() { return controller; }

    public CuratorDb curator() { return curator; }

    public ManualClock clock() { return clock; }

    public AthenzDbMock athenzDb() { return athenzDb; }

    public MemoryNameService nameService() { return serviceRegistry.nameServiceMock(); }

    public ZoneRegistryMock zoneRegistry() { return zoneRegistry; }

    public ConfigServerMock configServer() { return serviceRegistry.configServerMock(); }

    public ServiceRegistryMock serviceRegistry() { return serviceRegistry; }

    public Optional<Record> findCname(String name) {
        return serviceRegistry.nameService().findRecords(Record.Type.CNAME, RecordName.from(name)).stream().findFirst();
    }

    /** Create a new controller instance. Useful to verify that controller state is rebuilt from persistence */
    public final void createNewController() {
        controller = createController(curator, rotationsConfig, clock, zoneRegistry, athenzDb,
                                      serviceRegistry);
    }

    /** Creates the given tenant and application and deploys it */
    public Instance createAndDeploy(String tenantName, String domainName, String applicationName, Environment environment, long projectId, Long propertyId) {
        return createAndDeploy(tenantName, domainName, applicationName, toZone(environment), projectId, propertyId);
    }

    /** Creates the given tenant and application and deploys it */
    public Instance createAndDeploy(String tenantName, String domainName, String applicationName,
                                    String instanceName, ZoneId zone, long projectId, Long propertyId) {
        TenantName tenant = createTenant(tenantName, domainName, propertyId);
        Instance instance = createApplication(tenant, applicationName, instanceName, projectId);
        deploy(instance, zone);
        return instance;
    }

    /** Creates the given tenant and application and deploys it */
    public Instance createAndDeploy(String tenantName, String domainName, String applicationName, ZoneId zone, long projectId, Long propertyId) {
        return createAndDeploy(tenantName, domainName, applicationName, "default", zone, projectId, propertyId);
    }

    /** Creates the given tenant and application and deploys it */
    public Instance createAndDeploy(String tenantName, String domainName, String applicationName, Environment environment, long projectId) {
        return createAndDeploy(tenantName, domainName, applicationName, environment, projectId, null);
    }

    /** Create application from slime */
    public Instance createApplication(Slime slime) {
        InstanceSerializer serializer = new InstanceSerializer();
        Instance instance = serializer.fromSlime(slime);
        try (Lock lock = controller().applications().lock(instance.id())) {
            controller().applications().store(new LockedInstance(instance, lock));
        }
        return instance;
    }

    public ZoneId toZone(Environment environment) {
        switch (environment) {
            case dev: case test:
                return ZoneId.from(environment, RegionName.from("us-east-1"));
            case staging:
                return ZoneId.from(environment, RegionName.from("us-east-3"));
            default:
                return ZoneId.from(environment, RegionName.from("us-west-1"));
        }
    }

    public AthenzDomain createDomainWithAdmin(String domainName, AthenzUser user) {
        AthenzDomain domain = new AthenzDomain(domainName);
        athenzDb.getOrCreateDomain(domain).admin(user);
        return domain;
    }

    public Optional<AthenzDomain> domainOf(ApplicationId id) {
        Tenant tenant = controller().tenants().require(id.tenant());
        return tenant.type() == Tenant.Type.athenz ? Optional.of(((AthenzTenant) tenant).domain()) : Optional.empty();
    }

    public TenantName createTenant(String tenantName, String domainName, Long propertyId, Optional<Contact> contact) {
        TenantName name = TenantName.from(tenantName);
        Optional<Tenant> existing = controller().tenants().get(name);
        if (existing.isPresent()) return name;
        AthenzUser user = new AthenzUser("user");
        AthenzDomain domain = createDomainWithAdmin(domainName, user);
        AthenzTenantSpec tenantSpec = new AthenzTenantSpec(name,
                                                           domain,
                                                           new Property("Property" + propertyId),
                                                           Optional.ofNullable(propertyId).map(Object::toString).map(PropertyId::new));
        AthenzCredentials credentials = new AthenzCredentials(new AthenzPrincipal(user), domain, new OktaAccessToken("okta-token"));
        controller().tenants().create(tenantSpec, credentials);
        if (contact.isPresent())
            controller().tenants().lockOrThrow(name, LockedTenant.Athenz.class, tenant ->
                    controller().tenants().store(tenant.with(contact.get())));
        assertNotNull(controller().tenants().get(name));
        return name;
    }

    public TenantName createTenant(String tenantName, String domainName, Long propertyId) {
        return createTenant(tenantName, domainName, propertyId, Optional.empty());
    }

    public Optional<Credentials> credentialsFor(ApplicationId id) {
        return domainOf(id).map(domain -> new AthenzCredentials(new AthenzPrincipal(new AthenzUser("user")),
                                                                domain,
                                                                new OktaAccessToken("okta-token")));
    }

    public Instance createApplication(TenantName tenant, String applicationName, String instanceName, long projectId) {
        ApplicationId applicationId = ApplicationId.from(tenant.value(), applicationName, instanceName);
        controller().applications().createApplication(applicationId, credentialsFor(applicationId));
        controller().applications().lockOrThrow(applicationId, lockedInstance ->
                controller().applications().store(lockedInstance.withProjectId(OptionalLong.of(projectId))));
        return controller().applications().require(applicationId);
    }

    public void deploy(Instance instance, ZoneId zone) {
        deploy(instance, zone, new ApplicationPackage(new byte[0]));
    }

    public void deploy(Instance instance, ZoneId zone, ApplicationPackage applicationPackage) {
        deploy(instance, zone, applicationPackage, false);
    }

    public void deploy(Instance instance, ZoneId zone, ApplicationPackage applicationPackage, boolean deployCurrentVersion) {
        deploy(instance, zone, Optional.of(applicationPackage), deployCurrentVersion);
    }

    public void deploy(Instance instance, ZoneId zone, Optional<ApplicationPackage> applicationPackage, boolean deployCurrentVersion) {
        deploy(instance, zone, applicationPackage, deployCurrentVersion, Optional.empty());
    }

    public void deploy(Instance instance, ZoneId zone, Optional<ApplicationPackage> applicationPackage, boolean deployCurrentVersion, Optional<Version> version) {
        controller().applications().deploy(instance.id(),
                                           zone,
                                           applicationPackage,
                                           new DeployOptions(false, version, false, deployCurrentVersion));
    }

    public Supplier<Instance> application(ApplicationId application) {
        return () -> controller().applications().require(application);
    }

    /** Used by ApplicationSerializerTest to avoid breaking encapsulation. Should not be used by anything else */
    public static LockedInstance writable(Instance instance) {
        return new LockedInstance(instance, new Lock("/test", new MockCurator()));
    }

    private static Controller createController(CuratorDb curator, RotationsConfig rotationsConfig,
                                               ManualClock clock,
                                               ZoneRegistryMock zoneRegistryMock,
                                               AthenzDbMock athensDb,
                                               ServiceRegistryMock serviceRegistry) {
        Controller controller = new Controller(curator,
                                               rotationsConfig,
                                               zoneRegistryMock,
                                               clock,
                                               new AthenzFacade(new AthenzClientFactoryMock(athensDb)),
                                               () -> "test-controller",
                                               new InMemoryFlagSource(),
                                               new MockMavenRepository(),
                                               serviceRegistry);
        // Calculate initial versions
        controller.updateVersionStatus(VersionStatus.compute(controller));
        return controller;
    }

    private static RotationsConfig defaultRotationsConfig() {
        RotationsConfig.Builder builder = new RotationsConfig.Builder();
        for (int i = 1; i <= availableRotations; i++) {
            String id = String.format("%02d", i);
            builder = builder.rotations("rotation-id-" + id, "rotation-fqdn-" + id);
        }
        return new RotationsConfig(builder);
    }

}
