package com.yahoo.vespa.model.application.validation;

import com.yahoo.config.model.api.Quota;
import com.yahoo.config.model.deploy.TestProperties;
import com.yahoo.config.provision.Environment;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class QuotaValidatorTest {

    private final Quota quota = new Quota(Optional.of(5), Optional.empty());

    @Test
    public void test_deploy_under_quota() {
        var tester = new ValidationTester(5, new TestProperties().setHostedVespa(true).setQuota(quota));
        tester.deploy(null, getServices("testCluster", 5), Environment.prod, null);
    }

    @Test
    public void test_deploy_above_quota() {
        var tester = new ValidationTester(6, new TestProperties().setHostedVespa(true).setQuota(quota));
        try {
            tester.deploy(null, getServices("testCluster", 6), Environment.prod, null);
            fail();
        } catch (RuntimeException e) {
            assertEquals("Clusters testCluster exceeded max cluster size of 5", e.getMessage());
        }
    }

    private static String getServices(String contentClusterId, int nodeCount) {
        return "<services version='1.0'>" +
                "  <content id='" + contentClusterId + "' version='1.0'>" +
                "    <redundancy>1</redundancy>" +
                "    <engine>" +
                "    <proton/>" +
                "    </engine>" +
                "    <documents>" +
                "      <document type='music' mode='index'/>" +
                "    </documents>" +
                "    <nodes count='" + nodeCount + "'/>" +
                "   </content>" +
                "</services>";
    }
}
