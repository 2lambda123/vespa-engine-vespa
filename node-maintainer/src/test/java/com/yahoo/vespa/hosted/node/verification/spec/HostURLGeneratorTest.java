package com.yahoo.vespa.hosted.node.verification.spec;

import com.yahoo.vespa.hosted.node.verification.mock.MockCommandExecutor;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Created by olaa on 14/07/2017.
 */
public class HostURLGeneratorTest {

    private MockCommandExecutor mockCommandExecutor;
    private HostURLGenerator hostURLGenerator;
    private static final String CAT_NODE_HOST_NAME_PATH = "cat src/test/java/com/yahoo/vespa/hosted/node/verification/spec/resources/hostURLGeneratorTest";
    private static final String CAT_CONFIG_SERVER_HOST_NAME_PATH = "cat src/test/java/com/yahoo/vespa/hosted/node/verification/spec/resources/nodeHostNameOutput";
    private static final String CAT_WRONG_HOSTNAME_PATH = "cat src/test/java/com/yahoo/vespa/hosted/node/verification/spec/resources/hostURLGeneratorExceptionTest";
    private static final String CONFIG_SERVER_HOSTNAME = "http://cfg1.prod.corp-us-east-1.vespahosted.corp.bf1.yahoo.com:4080";
    private static final String NODE_HOSTNAME_PREFIX = "/nodes/v2/node/";
    private static final String EXPECTED_HOSTNAME = "13305821.ostk.bm2.prod.gq1.yahoo.com";

    @Before
    public void setup() {
        mockCommandExecutor = new MockCommandExecutor();
        hostURLGenerator = new HostURLGenerator();
    }

    @Test
    public void generateNodeInfoUrl_test_if_url_is_formatted_correctly() throws Exception {
        mockCommandExecutor.addCommand(CAT_CONFIG_SERVER_HOST_NAME_PATH);
        mockCommandExecutor.addCommand(CAT_NODE_HOST_NAME_PATH);
        ArrayList<URL> url = hostURLGenerator.generateNodeInfoUrl(mockCommandExecutor);
        String expectedUrl = CONFIG_SERVER_HOSTNAME + NODE_HOSTNAME_PREFIX + EXPECTED_HOSTNAME;
        String actualUrl = url.get(0).toString();
        assertEquals(expectedUrl, actualUrl);

    }

    @Test
    public void generateNodeInfoURL_expected_IOException(){
        try{
            mockCommandExecutor.addCommand(CAT_CONFIG_SERVER_HOST_NAME_PATH);
            mockCommandExecutor.addCommand(CAT_WRONG_HOSTNAME_PATH);
            hostURLGenerator.generateNodeInfoUrl(mockCommandExecutor);
            fail("Expected an IOExeption to be thrown");
        } catch (IOException e){
            String expectedExceptionMessage = "Unexpected output from \"hostname\" command.";
            assertEquals(expectedExceptionMessage, e.getMessage());
        }
    }

}