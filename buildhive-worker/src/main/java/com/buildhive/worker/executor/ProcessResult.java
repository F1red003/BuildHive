package com.buildhive.worker.executor;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Encapsulates the execution outcome, duration, and output log file of a process run.
 */
public record ProcessResult(
        int exitCode,
        long durationMs,
        boolean timedOut,
        Path logFilePath,
        List<String> outputLines
) {
    public ProcessResult(int exitCode, long durationMs, boolean timedOut, Path logFilePath) {
        this(exitCode, durationMs, timedOut, logFilePath, Collections.emptyList());
    }

    public boolean isSuccess() {
        return exitCode == 0 && !timedOut;
    }
}
