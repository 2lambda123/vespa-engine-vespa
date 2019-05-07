package com.yahoo.vespa.hosted.controller.restapi;

import com.yahoo.application.container.handler.Request;
import com.yahoo.config.provision.SystemName;
import com.yahoo.vespa.hosted.controller.api.role.Role;
import com.yahoo.vespa.hosted.controller.api.role.SecurityContext;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Controller container test with services.xml which accommodates cloud user management.
 *
 * @author jonmv
 */
public class ControllerContainerCloudTest extends ControllerContainerTest {

    @Override
    protected SystemName system() {
        return SystemName.Public;
    }

    @Override
    protected String variablePartXml() {
        return "  <component id='com.yahoo.vespa.hosted.controller.security.CloudAccessControlRequests'/>\n" +
               "  <component id='com.yahoo.vespa.hosted.controller.security.CloudAccessControl'/>\n" +
               "  <component id='com.yahoo.vespa.hosted.controller.api.integration.stubs.MockUserManagement'/>\n" +
               "  <component id='com.yahoo.vespa.hosted.controller.api.integration.stubs.MockMarketplace'/>\n" +

               "  <handler id='com.yahoo.vespa.hosted.controller.restapi.application.ApplicationApiHandler'>\n" +
               "    <binding>http://*/application/v4/*</binding>\n" +
               "    <binding>http://*/api/application/v4/*</binding>\n" +
               "  </handler>\n" +

               "  <handler id='com.yahoo.vespa.hosted.controller.restapi.user.UserApiHandler'>\n" +
               "    <binding>http://*/user/v1/*</binding>\n" +
               "    <binding>http://*/api/user/v1/*</binding>\n" +
               "  </handler>\n" +

               "  <http>\n" +
               "    <server id='default' port='8080' />\n" +
               "    <filtering>\n" +
               "      <request-chain id='default'>\n" +
               "        <filter id='com.yahoo.vespa.hosted.controller.restapi.filter.ControllerAuthorizationFilter'/>\n" +
               "        <binding>http://*/*</binding>\n" +
               "      </request-chain>\n" +
               "    </filtering>\n" +
               "  </http>\n";
    }

    protected static final String accessDenied = "{\n" +
                                                 "  \"code\" : 403,\n" +
                                                 "  \"message\" : \"Access denied\"\n" +
                                                 "}";

    protected RequestBuilder request(String path) { return new RequestBuilder(path, Request.Method.GET); }
    protected RequestBuilder request(String path, Request.Method method) { return new RequestBuilder(path, method); }

    protected class RequestBuilder implements Supplier<Request> {
        private final String path;
        private final Request.Method method;
        private byte[] data = new byte[0];
        private Principal user = () -> "user@test";
        private Set<Role> roles = Set.of(Role.everyone());

        private RequestBuilder(String path, Request.Method method) {
            this.path = path;
            this.method = method;
        }

        public RequestBuilder data(byte[] data) { this.data = data; return this; }
        public RequestBuilder data(String data) { this.data = data.getBytes(StandardCharsets.UTF_8); return this; }
        public RequestBuilder user(String user) { this.user = () -> user; return this; }
        public RequestBuilder roles(Set<Role> roles) { this.roles = roles; return this; }

        @Override
        public Request get() {
            Request request = new Request("http://localhost:8080" + path, data, method, user);
            request.getAttributes().put(SecurityContext.ATTRIBUTE_NAME, new SecurityContext(user, roles));
            request.getHeaders().put("Content-Type", "application/json");
            return request;
        }
    }

}
