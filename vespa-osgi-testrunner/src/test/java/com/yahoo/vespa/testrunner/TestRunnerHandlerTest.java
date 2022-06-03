// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.testrunner;

import com.yahoo.component.ComponentId;
import com.yahoo.component.provider.ComponentRegistry;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.slime.Cursor;
import com.yahoo.slime.Inspector;
import com.yahoo.slime.SlimeUtils;
import com.yahoo.vespa.testrunner.TestReport.Node;
import com.yahoo.vespa.testrunner.TestReport.OutputNode;
import com.yahoo.vespa.testrunner.TestRunner.Status;
import com.yahoo.vespa.testrunner.TestRunner.Suite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;

import static com.yahoo.jdisc.http.HttpRequest.Method.GET;
import static com.yahoo.slime.SlimeUtils.jsonToSlimeOrThrow;
import static com.yahoo.slime.SlimeUtils.toJsonBytes;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author mortent
 */
class TestRunnerHandlerTest {

    private static final Instant testInstant = Instant.ofEpochMilli(12_000L);

    private TestRunnerHandler testRunnerHandler;
    private TestRunner aggregateRunner;

    @BeforeEach
    void setup() {
        List<LogRecord> logRecords = List.of(logRecord("Tests started"));
        Throwable exception = new RuntimeException("org.junit.ComparisonFailure: expected:<foo> but was:<bar>");
        exception.setStackTrace(new StackTraceElement[]{new StackTraceElement("Foo", "bar", "Foo.java", 1123)});

        aggregateRunner = AggregateTestRunner.of(List.of(new MockRunner(TestRunner.Status.SUCCESS, AggregateTestRunnerTest.report)));
        testRunnerHandler = new TestRunnerHandler(Executors.newSingleThreadExecutor(), aggregateRunner);
    }

    @Test
    public void createsCorrectTestReport() throws IOException {
        aggregateRunner.test(Suite.SYSTEM_TEST, new byte[0]);
        HttpResponse response = testRunnerHandler.handle(HttpRequest.createTestRequest("http://localhost:1234/tester/v1/report", GET));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.render(out);
        assertEquals(new String(toJsonBytes(jsonToSlimeOrThrow(readTestResource("/report.json")).get(), false), UTF_8),
                     new String(toJsonBytes(jsonToSlimeOrThrow(out.toByteArray()).get(), false), UTF_8));
    }

    @Test
    public void returnsCorrectLog() throws IOException {
        // Prime the aggregate runner to actually consider the wrapped runner for logs.
        aggregateRunner.test(TestRunner.Suite.SYSTEM_TEST, new byte[0]);

        HttpResponse response = testRunnerHandler.handle(HttpRequest.createTestRequest("http://localhost:1234/tester/v1/log", GET));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.render(out);
        Inspector actualRoot = jsonToSlimeOrThrow(out.toByteArray()).get();
        Inspector expectedRoot = jsonToSlimeOrThrow(readTestResource("/output.json")).get();
        boolean ok = expectedRoot.field("logRecords").entries() == actualRoot.field("logRecords").entries();
        // Need custom comparison, because sequence ID may be influenced by other tests.
        for (int i = 0; i < expectedRoot.field("logRecords").entries(); i++) {
            Inspector expectedEntry = expectedRoot.field("logRecords").entry(i);
            Inspector actualEntry = actualRoot.field("logRecords").entry(i);
            ok &= expectedEntry.field("at").equalTo(actualEntry.field("at"));
            ok &= expectedEntry.field("type").equalTo(actualEntry.field("type"));
            ok &= expectedEntry.field("message").equalTo(actualEntry.field("message"));
        }
        if ( ! ok)
            assertEquals(new String(toJsonBytes(expectedRoot, false), UTF_8),
                         new String(toJsonBytes(actualRoot, false), UTF_8));

        // Should not get old log

        long last = SlimeUtils.entriesStream(jsonToSlimeOrThrow (readTestResource("/output.json")).get().field("logRecords"))
                              .mapToLong(recordObject -> recordObject.field("id").asLong())
                              .max()
                              .orElse(0L);
        response = testRunnerHandler.handle(HttpRequest.createTestRequest("http://localhost:1234/tester/v1/log?after=" + last, GET));
        out = new ByteArrayOutputStream();
        response.render(out);
        assertEquals("{\"logRecords\":[]}", out.toString(UTF_8));
    }

    static byte[] readTestResource(String name) {
        try {
            return TestRunnerHandlerTest.class.getResourceAsStream(name).readAllBytes();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    public void returnsEmptyResponsesWhenReportNotReady() throws IOException {
        testRunnerHandler = new TestRunnerHandler(Executors.newSingleThreadExecutor(),
                                                  ComponentRegistry.singleton(new ComponentId("runner"),
                                                                              new MockRunner(Status.NOT_STARTED, null)));

        {
            HttpResponse response = testRunnerHandler.handle(HttpRequest.createTestRequest("http://localhost:1234/tester/v1/log", GET));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            response.render(out);
            assertEquals("{\"logRecords\":[]}", out.toString(UTF_8));
        }

        {
            HttpResponse response = testRunnerHandler.handle(HttpRequest.createTestRequest("http://localhost:1234/tester/v1/report", GET));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            response.render(out);
            assertEquals("", out.toString(UTF_8));
        }
    }

    /* Creates a LogRecord that has a known instant and sequence number to get predictable serialization results. */
    private static LogRecord logRecord(String logMessage) {
        LogRecord logRecord = new LogRecord(Level.INFO, logMessage);
        logRecord.setInstant(testInstant);
        logRecord.setSequenceNumber(0);
        return logRecord;
    }

    private static class MockRunner implements TestRunner {

        private final TestRunner.Status status;
        private final TestReport testReport;

        public MockRunner(TestRunner.Status status, TestReport testReport) {

            this.status = status;
            this.testReport = testReport;
        }

        @Override
        public CompletableFuture<?> test(Suite suite, byte[] testConfig) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public Collection<LogRecord> getLog(long after) {
            List<LogRecord> log = new ArrayList<>();
            if (testReport != null) addLog(log, testReport.root(), after);
            return log;
        }

        private void addLog(List<LogRecord> log, Node node, long after) {
            if (node instanceof OutputNode)
                for (LogRecord record : ((OutputNode) node).log())
                    if (record.getSequenceNumber() > after)
                        log.add(record);

            for (Node child : node.children())
                addLog(log, child, after);
        }

        @Override
        public TestRunner.Status getStatus() {
            return status;
        }

        @Override
        public TestReport getReport() {
            return testReport;
        }

    }

}
