package com.yahoo.vespa.hosted.controller.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yahoo.config.application.api.DeploymentSpec;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.zone.ZoneId;
import com.yahoo.vespa.hosted.controller.api.integration.billing.Quota;
import com.yahoo.vespa.hosted.controller.api.integration.noderepository.ApplicationData;
import org.junit.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.*;

public class DeploymentQuotaCalculatorTest {

    @Test
    public void quota_is_divided_among_prod_instances() {
        Quota calculated = DeploymentQuotaCalculator.calculate(Quota.unlimited().withBudget(10), List.of(), ApplicationId.defaultId(), ZoneId.defaultId(),
                DeploymentSpec.fromXml(
                        "<deployment version='1.0'>\n" +
                                "  <instance id='instance1'> \n" +
                                "    <test />\n" +
                                "    <staging />\n" +
                                "    <prod>\n" +
                                "      <region active=\"true\">us-east-1</region>\n" +
                                "      <region active=\"false\">us-west-1</region>\n" +
                                "    </prod>\n" +
                                "  </instance>\n" +
                                "  <instance id='instance2'>\n" +
                                "    <perf/>\n" +
                                "    <dev/>\n" +
                                "    <prod>\n" +
                                "      <region active=\"true\">us-north-1</region>\n" +
                                "    </prod>\n" +
                                "  </instance>\n" +
                                "</deployment>"));
        assertEquals(10d/3, calculated.budget().get().doubleValue(), 1e-5);
    }

    @Test
    public void unlimited_quota_remains_unlimited() {
        Quota calculated = DeploymentQuotaCalculator.calculate(Quota.unlimited(), List.of(), ApplicationId.defaultId(), ZoneId.defaultId(), DeploymentSpec.empty);
        assertTrue(calculated.isUnlimited());
    }

    @Test
    public void zero_quota_remains_zero() {
        Quota calculated = DeploymentQuotaCalculator.calculate(Quota.zero(), List.of(), ApplicationId.defaultId(), ZoneId.defaultId(), DeploymentSpec.empty);
        assertEquals(calculated.budget().get().doubleValue(), 0, 1e-5);
    }

    @Test
    public void using_highest_resource_use() throws IOException, URISyntaxException {
        var content = new String(Files.readAllBytes(Paths.get("src/test/java/com/yahoo/vespa/hosted/controller/application/response/application.json")));
        var mapper = new ObjectMapper();
        var application = mapper.readValue(content, ApplicationData.class).toApplication();
        var usage = DeploymentQuotaCalculator.calculateQuotaUsage(application);
        assertEquals(1.164, usage.rate(), 0.001);
    }

    @Test
    public void tenant_quota_in_pipeline() {
        var tenantQuota = Quota.unlimited().withBudget(42);
        var calculated = DeploymentQuotaCalculator.calculate(tenantQuota, List.of(), ApplicationId.defaultId(), ZoneId.from("test", "apac1"), DeploymentSpec.empty);
        assertEquals(tenantQuota, calculated);
    }
}