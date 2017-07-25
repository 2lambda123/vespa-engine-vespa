package com.yahoo.vespa.hosted.node.verification.hardware.benchmarks;


import com.yahoo.vespa.hosted.node.verification.commons.ParseResult;
import com.yahoo.vespa.hosted.node.verification.mock.*;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;

import static org.junit.Assert.*;
/**
 * Created by sgrostad on 11/07/2017.
 */
public class CPUBenchmarkTest {
    private final String cpuEuropeanDelimiters = "src/test/java/com/yahoo/vespa/hosted/node/verification/hardware/resources/cpuCyclesWithDotsTimeWithCommaTest.txt";
    private final String cpuAlternativeDelimiters = "src/test/java/com/yahoo/vespa/hosted/node/verification/hardware/resources/cpuCyclesWithCommasTimeWithDotTest.txt";
    private final String cpuWrongOutput = "src/test/java/com/yahoo/vespa/hosted/node/verification/hardware/resources/cpuWrongOutputTest.txt";
    private HardwareResults hardwareResults;
    private MockCommandExecutor commandExecutor;
    private CPUBenchmark cpu;
    private double delta = 0.01;

    @Before
    public void setup(){
        commandExecutor = new MockCommandExecutor();
        hardwareResults = new HardwareResults();
        cpu = new CPUBenchmark(hardwareResults, commandExecutor);
    }

    @Test
    public void test_doBenchmark_find_correct_cpuCyclesPerSec() {
        String mockCommand = "cat " + cpuAlternativeDelimiters;
        commandExecutor.addCommand(mockCommand);
        cpu.doBenchmark();
        double result = hardwareResults.getCpuCyclesPerSec();
        double expected = 2.1576482291815062;
        assertEquals(expected,result,delta);
    }

    @Test
    public void test_doBenchmark_wrong_output_stores_frequency_of_zero() {
        String mockCommand = "cat " + cpuWrongOutput;
        commandExecutor.addCommand(mockCommand);
        cpu.doBenchmark();
        double result = hardwareResults.getCpuCyclesPerSec();
        double expected = 0;
        assertEquals(expected,result,delta);
    }

    @Test
    public void test_parseCpuCyclesPerSec_return_correct_ArrayList() throws IOException{
        ArrayList<String> mockCommandOutput = commandExecutor.readFromFile(cpuEuropeanDelimiters);
        ArrayList<ParseResult> parseResults = cpu.parseCpuCyclesPerSec(mockCommandOutput);
        ParseResult expectedParseCyclesResult = new ParseResult("cycles","2.066.201.729");
        ParseResult expectedParseSecondsResult = new ParseResult("seconds","0,957617512");
        assertEquals(expectedParseCyclesResult, parseResults.get(0));
        assertEquals(expectedParseSecondsResult, parseResults.get(1));
    }

    @Test
    public void test_if_setCpuCyclesPerSec_reads_output_correctly() throws IOException{
        ArrayList<ParseResult> parseResults = new ArrayList<>();
        parseResults.add(new ParseResult("cycles","2.066.201.729"));
        parseResults.add(new ParseResult("seconds","0,957617512"));
        cpu.setCpuCyclesPerSec(parseResults);
        double expectedCpuCyclesPerSec = 2.1576482291815062;
        assertEquals(expectedCpuCyclesPerSec, hardwareResults.getCpuCyclesPerSec(), delta);
    }

    @Test
    public void test_if_makeCyclesDouble_converts_European_and_alternative_delimiters_correctly() {
        String toBeConvertedEuropean = "2.066.201.729";
        String toBEConvertedAlternative = "2,066,201,729";
        double expectedCycles = 2066201729;
        assertEquals(expectedCycles, cpu.makeCyclesDouble(toBeConvertedEuropean), delta);
        assertEquals(expectedCycles, cpu.makeCyclesDouble(toBEConvertedAlternative), delta);
    }
    @Test
    public void test_if_makeSecondsDouble_converts_European_and_alternative_delimiters_correctly() {
        String toBeConvertedEuropean = "0,957617512";
        String toBEConvertedAlternative = "0.957617512";
        double expectedSeconds = 0.957617512;
        assertEquals(expectedSeconds, cpu.makeSecondsDouble(toBeConvertedEuropean), delta);
        assertEquals(expectedSeconds, cpu.makeSecondsDouble(toBEConvertedAlternative), delta);
    }

    @Test
    public void test_if_checkIfNumber_returns_true(){
        String number = "125.5";
        assertTrue(cpu.checkIfNumber(number));
    }

    @Test
    public void test_if_checkIfNumber_returns_false(){
        String notANumber = "125.5a";
        assertFalse(cpu.checkIfNumber(notANumber));
    }

    @Test
    public void test_if_convertToGHz_converts_correctly(){
        double cycles = 2066201729;
        double seconds = 0.957617512;
        double expectedGHz = 2.1576482291815062;
        assertEquals(expectedGHz, cpu.convertToGHz(cycles, seconds), delta);
    }
}