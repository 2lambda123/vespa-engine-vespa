// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.system.execution;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Configurable system command executor that captures stdout and stderr.
 *
 * @author gjoranv
 * @author bjorncs
 */
public class ProcessExecutor {

    public static class Builder {
        private final Duration timeout;
        private int[] successExitCodes;

        public Builder(Duration timeout) {
            this.timeout = timeout;
        }

        public Builder successExitCodes(int... successExitCodes) {
            this.successExitCodes = successExitCodes;
            return this;
        }

        public ProcessExecutor build() {
            return new ProcessExecutor(timeout, successExitCodes);
        }
    }

    private ProcessExecutor(Duration timeout, int[] successExitCodes) {
        this.timeout = timeout;
        this.successExitCodes = successExitCodes;
    }

    private final Duration timeout;
    private final int[] successExitCodes;

    /**
     * Convenience method to execute a process with no input data. See {@link #execute(String, String)} for details.
     */
    public Optional<ProcessResult> execute(String command) throws IOException {
        return execute(command, null);
    }

    /**
     * Executes the given command synchronously.
     *
     * @param command The command to execute.
     * @param processInput Input provided to the process.
     * @return The result of the execution, or empty if the process does not terminate within the timeout set for this executor.
     * @throws IOException if the process execution failed.
     */
    public Optional<ProcessResult> execute(String command, String processInput) throws IOException {
        ByteArrayOutputStream processErr = new ByteArrayOutputStream();
        ByteArrayOutputStream processOut = new ByteArrayOutputStream();

        DefaultExecutor executor = DefaultExecutor.builder().get();
        executor.setStreamHandler(createStreamHandler(processOut, processErr, processInput));
        ExecuteWatchdog watchDog = ExecuteWatchdog.builder().setTimeout(timeout).get();
        executor.setWatchdog(watchDog);
        executor.setExitValues(successExitCodes);

        int exitCode;
        try {
            exitCode = executor.execute(CommandLine.parse(command));
        } catch (ExecuteException e) {
            exitCode = e.getExitValue();
        }
        return (watchDog.killedProcess()) ?
                Optional.empty() : Optional.of(new ProcessResult(exitCode, processOut.toString(), processErr.toString()));
    }

    private static PumpStreamHandler createStreamHandler(ByteArrayOutputStream processOut,
                                                         ByteArrayOutputStream processErr,
                                                         String input) {
        if (input != null) {
            InputStream processInput = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
            return new PumpStreamHandler(processOut, processErr, processInput);
        } else {
            return new PumpStreamHandler(processOut, processErr);
        }
    }

}
