package com.yahoo.vespa.hosted.testrunner;

import org.apache.commons.io.IOUtils;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * @author valerijf
 */
public class PomXmlGeneratorTest {

    @Test
    public void write_system_tests_pom_xml() throws IOException {
        List<Path> artifacts = Arrays.asList(
                Paths.get("components/my-comp.jar"),
                Paths.get("main.jar"));

        String actual = PomXmlGenerator.generatePomXml(TestProfile.SYSTEM_TEST, artifacts, artifacts.get(1));
        assertFile("/pom.xml_system_tests", actual);
    }

    private void assertFile(String resourceFile, String actual) throws IOException {
        String expected = IOUtils.toString(this.getClass().getResourceAsStream(resourceFile));
        assertEquals(resourceFile, expected, actual);
    }
}