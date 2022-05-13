package com.yahoo.vespa.testrunner;

import ai.vespa.cloud.Environment;
import ai.vespa.cloud.Zone;
import ai.vespa.hosted.cd.Deployment;
import ai.vespa.hosted.cd.TestRuntime;
import com.yahoo.vespa.testrunner.TestRunner.Suite;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.EngineDiscoveryOrchestrator;
import org.junit.platform.launcher.core.EngineExecutionOrchestrator;
import org.junit.platform.launcher.core.LauncherDiscoveryResult;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static ai.vespa.hosted.cd.internal.TestRuntimeProvider.testRuntime;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.junit.platform.launcher.core.EngineDiscoveryOrchestrator.Phase.EXECUTION;

/**
 * @author jonmv
 */
class JunitRunnerTest {

    @TestFactory
    Stream<DynamicTest> runSampleTests() {
        String packageName = "com.yahoo.vespa.test.samples";
        InputStream classes = getClass().getClassLoader().getResourceAsStream(packageName.replace(".", "/"));
        BufferedReader reader = new BufferedReader(new InputStreamReader(requireNonNull(classes, packageName + " should contain sample tests")));
        return reader.lines()
                     .filter(line -> line.endsWith("Test.class"))
                     .map(name -> {
                         try {
                             Class<?> testClass = getClass().getClassLoader().loadClass(packageName + "." + name.replace(".class", ""));
                             return dynamicTest(testClass.getSimpleName(), () -> verify(testClass));
                         }
                         catch (ClassNotFoundException e) {
                             throw new IllegalStateException(e);
                         }
                     });
    }

    static void verify(Class<?> testClass) {
        assertEquals(getTestReport(testClass),
                     test(getSuite(testClass), new byte[0], testClass).getReport());
    }

    static Suite getSuite(Class<?> testClass) {
        for (Annotation annotation : testClass.getAnnotations()) {
            switch (annotation.annotationType().getSimpleName()) {
                case "SystemTest": return Suite.SYSTEM_TEST;
                case "StagingSetup": return Suite.STAGING_SETUP_TEST;
                case "StagingTest": return Suite.STAGING_TEST;
                case "ProductionTest": return Suite.PRODUCTION_TEST;
            }
        }
        return null;
    }

    static TestReport getTestReport(Class<?> testClass) {
        Expect outcomes = requireNonNull(testClass.getAnnotation(Expect.class), "sample tests must be annotated with @Expect");
        return TestReport.builder()
                         .withSuccessCount(outcomes.success())
                         .withFailedCount(outcomes.failure() + outcomes.error())
                         .withAbortedCount(outcomes.aborted())
                         .withIgnoredCount(outcomes.skipped())
                         .withInconclusiveCount(outcomes.inconclusive())
                         .build();
    }

    static TestRunner test(Suite suite, byte[] testConfig, Class<?>... testClasses) {
        JunitRunner runner = new JunitRunner(config -> { assertSame(testConfig, config); testRuntime.set(new MockTestRuntime()); },
                                             __ -> List.of(testClasses),
                                             JunitRunnerTest::execute);
        try {
            runner.test(suite, testConfig).get();
        }
        catch (Exception e) {
            fail(e);
        }
        return runner;
    }


    // For some inane reason, the JUnit test framework makes it impossible to simply launch a new instance of itself
    // from inside a unit test (run by itself) in the standard way, so this kludge is necessary to work around that.
    static void execute(LauncherDiscoveryRequest discoveryRequest, TestExecutionListener... listeners) {
        TestEngine testEngine = new JupiterTestEngine();
        LauncherDiscoveryResult discoveryResult = new EngineDiscoveryOrchestrator(Set.of(testEngine), Set.of()).discover(discoveryRequest, EXECUTION);
        TestDescriptor engineTestDescriptor = discoveryResult.getEngineTestDescriptor(testEngine);
        TestPlan plan = TestPlan.from(List.of(engineTestDescriptor), discoveryRequest.getConfigurationParameters());
        for (TestExecutionListener listener : listeners) listener.testPlanExecutionStarted(plan);
        new EngineExecutionOrchestrator().execute(discoveryResult, new ExecutionListenerAdapter(plan, listeners));
        for (TestExecutionListener listener : listeners) listener.testPlanExecutionFinished(plan);
    }

    static class ExecutionListenerAdapter implements EngineExecutionListener {

        private final TestPlan plan;
        private final List<TestExecutionListener> listeners;

        public ExecutionListenerAdapter(TestPlan plan, TestExecutionListener... listeners) {
            this.plan = plan;
            this.listeners = List.of(listeners);
        }

        private TestIdentifier getTestIdentifier(TestDescriptor testDescriptor) {
            return plan.getTestIdentifier(testDescriptor.getUniqueId().toString());
        }

        @Override public void dynamicTestRegistered(TestDescriptor testDescriptor) {
            TestIdentifier id = TestIdentifier.from(testDescriptor);
            plan.addInternal(id);
            for (TestExecutionListener listener : listeners)
                listener.dynamicTestRegistered(id);
        }

        @Override public void executionSkipped(TestDescriptor testDescriptor, String reason) {
            for (TestExecutionListener listener : listeners)
                listener.executionSkipped(getTestIdentifier(testDescriptor), reason);
        }

        @Override public void executionStarted(TestDescriptor testDescriptor) {
            for (TestExecutionListener listener : listeners)
                listener.executionStarted(getTestIdentifier(testDescriptor));
        }

        @Override public void executionFinished(TestDescriptor testDescriptor, TestExecutionResult testExecutionResult) {
            for (TestExecutionListener listener : listeners)
                listener.executionFinished(getTestIdentifier(testDescriptor), testExecutionResult);
        }

        @Override public void reportingEntryPublished(TestDescriptor testDescriptor, ReportEntry entry) {
            for (TestExecutionListener listener : listeners)
                listener.reportingEntryPublished(getTestIdentifier(testDescriptor), entry);
        }

    }


    static class MockTestRuntime implements TestRuntime {

        @Override
        public Deployment deploymentToTest() {
            return __ -> null;
        }

        @Override
        public Zone zone() {
            return new Zone(Environment.test, "name");
        }

    }

}
