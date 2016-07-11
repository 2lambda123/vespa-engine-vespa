// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.http.v2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import com.yahoo.config.provision.*;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.container.logging.AccessLog;
import com.yahoo.jdisc.Response;
import com.yahoo.jdisc.http.HttpRequest;
import com.yahoo.slime.JsonFormat;
import com.yahoo.transaction.NestedTransaction;
import com.yahoo.vespa.config.server.*;
import com.yahoo.vespa.config.server.http.HttpErrorResponse;
import com.yahoo.vespa.config.server.http.SessionHandlerTest;
import com.yahoo.vespa.config.server.provision.HostProvisionerProvider;
import com.yahoo.vespa.config.server.session.LocalSessionRepo;
import com.yahoo.vespa.config.server.session.RemoteSession;
import com.yahoo.vespa.config.server.session.RemoteSessionRepo;
import com.yahoo.vespa.config.server.session.Session;
import com.yahoo.vespa.config.server.session.SessionZooKeeperClient;

import com.yahoo.vespa.curator.mock.MockCurator;
import com.yahoo.vespa.config.server.zookeeper.ConfigCurator;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.Before;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

import com.yahoo.path.Path;
import com.yahoo.vespa.config.server.application.MemoryTenantApplications;
import com.yahoo.vespa.config.server.http.SessionActiveHandlerTestBase;
import com.yahoo.vespa.config.server.http.SessionHandler;
import com.yahoo.vespa.config.server.http.SessionCreateHandlerTestBase.MockSessionFactory;

public class SessionActiveHandlerTest extends SessionActiveHandlerTestBase {

    private MockProvisioner hostProvisioner;

    @Before
    public void setup() throws Exception {
        tenant = TenantName.from("activatetest");
        remoteSessionRepo = new RemoteSessionRepo();
        applicationRepo = new MemoryTenantApplications();
        curator = new MockCurator();
        configCurator = ConfigCurator.create(curator);
        localRepo = new LocalSessionRepo(applicationRepo);
        pathPrefix = "/application/v2/tenant/" + tenant + "/session/";
        tenantMessage = ",\"tenant\":\"" + tenant + "\"";
        pathProvider = new PathProvider(Path.createRoot());
        activatedMessage = " for tenant '" + tenant + "' activated.";
        hostProvisioner = new MockProvisioner();
    }

    @Test
    public void testActivation() throws Exception {
        activateAndAssertOK(1, 0);
    }

    @Test
    public void testActivationWithActivationInBetween() throws Exception {
        activateAndAssertOK(90l, 0l);
        activateAndAssertError(92l, 89l,
                HttpErrorResponse.errorCodes.BAD_REQUEST,
                "tenant:"+tenant+" app:default:default Cannot activate session 92 because the currently active session (90) has changed since session 92 was created (was 89 at creation time)");
    }


    @Test
    public void testActivationOfUnpreparedSession() throws Exception {
        // Needed so we can test that previous active session is still active after a failed activation
        RemoteSession firstSession = activateAndAssertOK(90l, 0l);
        long sessionId = 91L;
        ActivateRequest activateRequest = new ActivateRequest(sessionId, 0l, Session.Status.NEW, "").invoke();
        HttpResponse actResponse = activateRequest.getActResponse();
        RemoteSession session = activateRequest.getSession();
        assertThat(actResponse.getStatus(), is(Response.Status.BAD_REQUEST));
        assertThat(getRenderedString(actResponse), is("{\"error-code\":\"BAD_REQUEST\",\"message\":\"tenant:"+tenant+" app:default:default Session " + sessionId + " is not prepared\"}"));
        assertThat(session.getStatus(), is(not(Session.Status.ACTIVATE)));
        assertThat(firstSession.getStatus(), is(Session.Status.ACTIVATE));
        
    }

    @Test
    public void require_that_handler_gives_error_for_unsupported_methods() throws Exception {
        testUnsupportedMethod(SessionHandlerTest.createTestRequest(pathPrefix, HttpRequest.Method.POST, Cmd.PREPARED, 1L));
        testUnsupportedMethod(SessionHandlerTest.createTestRequest(pathPrefix, HttpRequest.Method.DELETE, Cmd.PREPARED, 1L));
        testUnsupportedMethod(SessionHandlerTest.createTestRequest(pathPrefix, HttpRequest.Method.GET, Cmd.PREPARED, 1L));
    }

    @Test
    @Ignore
    public void require_that_handler_gives_error_when_provisioner_activated_fails() throws Exception {
        hostProvisioner = new FailingMockProvisioner();
        hostProvisioner.activated = false;
        activateAndAssertError(1, 0, HttpErrorResponse.errorCodes.BAD_REQUEST, "Cannot activate application");
        assertFalse(hostProvisioner.activated);
    }

    @Override
    protected RemoteSession activateAndAssertOK(long sessionId, long previousSessionId) throws Exception {
        ActivateRequest activateRequest = activateAndAssertOKPut(sessionId, previousSessionId, "");
        return activateRequest.getSession();
    }

    @Override
    protected Session activateAndAssertOK(long sessionId, long previousSessionId, String subPath) throws Exception {
        ActivateRequest activateRequest = activateAndAssertOKPut(sessionId, previousSessionId, subPath);
        return activateRequest.getSession();
    }
    
    @Override
    protected void assertActivationMessageOK(ActivateRequest activateRequest, String message) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        new JsonFormat(true).encode(byteArrayOutputStream, activateRequest.getMetaData().getSlime());
        assertThat(message, containsString("\"tenant\":\"" + tenant + "\",\"message\":\"Session " + activateRequest.getSessionId() + activatedMessage));
        assertThat(message, containsString("/application/v2/tenant/" + tenant +
                "/application/" + appName +
                "/environment/" + "prod" +
                "/region/" + "default" +
                "/instance/" + "default"));
        assertTrue(hostProvisioner.activated);
        assertThat(hostProvisioner.lastHosts.size(), is(1));
    }

    @Override
    protected void activateAndAssertError(long sessionId, long previousSessionId, HttpErrorResponse.errorCodes errorCode, String expectedError) throws Exception {
        hostProvisioner.activated = false;
        activateAndAssertErrorPut(sessionId, previousSessionId, errorCode, expectedError);
        assertFalse(hostProvisioner.activated);
    }

    @Override
    protected void writeApplicationId(SessionZooKeeperClient zkc, String applicationName) {
        ApplicationId id = ApplicationId.from(tenant,
                                              ApplicationName.from(applicationName), InstanceName.defaultName());
        zkc.writeApplicationId(id);
    }

    @Override
    protected String getActivateLogPre() {
        return "tenant:testtenant, app:default:default ";
    }

    @Override
    protected SessionHandler createHandler() throws Exception {
        final MockSessionFactory sessionFactory = new MockSessionFactory();
        TestTenantBuilder testTenantBuilder = new TestTenantBuilder();
        testTenantBuilder.createTenant(tenant)
                .withSessionFactory(sessionFactory)
                .withLocalSessionRepo(localRepo)
                .withRemoteSessionRepo(remoteSessionRepo)
                .withApplicationRepo(applicationRepo)
                .build();
        return new SessionActiveHandler(new Executor() {
            @SuppressWarnings("NullableProblems")
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        }, AccessLog.voidAccessLog(), testTenantBuilder.createTenants(), HostProvisionerProvider.withProvisioner(hostProvisioner), Zone.defaultZone());
    }

    public static class MockProvisioner implements Provisioner {

        boolean activated = false;
        boolean removed = false;
        boolean restarted = false;
        ApplicationId lastApplicationId;
        Collection<HostSpec> lastHosts;

        @Override
        public List<HostSpec> prepare(ApplicationId applicationId, ClusterSpec cluster, Capacity capacity, int groups, ProvisionLogger logger) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void activate(NestedTransaction transaction, ApplicationId application, Collection<HostSpec> hosts) {
            transaction.commit();
            activated = true;
            lastApplicationId = application;
            lastHosts = hosts;
        }

        @Override
        public void remove(NestedTransaction transaction, ApplicationId application) {
            removed = true;
            lastApplicationId = application;
        }

        @Override
        public void restart(ApplicationId application, HostFilter filter) {
            restarted = true;
            lastApplicationId = application;
        }

    }

    public static class FailingMockProvisioner extends MockProvisioner {

        @Override
        public void activate(NestedTransaction transaction, ApplicationId application, Collection<HostSpec> hosts) {
            throw new IllegalArgumentException("Cannot activate application");
        }

        @Override
        public void remove(NestedTransaction transaction, ApplicationId application) {
            throw new IllegalArgumentException("Cannot remove application");
        }

    }
}
