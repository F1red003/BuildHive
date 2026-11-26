package com.buildhive.worker.dto;

import com.buildhive.common.model.FailureClassification;

/**
 * Result returned by JobExecutionService.
 */
public record JobExecutionResult(
        boolean success,
        Integer exitCode,
        String failureReason,
        FailureClassification failureClassification
) {
    public static JobExecutionResult successful() {
        return new JobExecutionResult(true, 0, null, null);
    }

    public static JobExecutionResult failure(String reason, FailureClassification classification) {
        return new JobExecutionResult(false, 1, reason, classification);
    }

    public static JobExecutionResult failure(int exitCode, String reason, FailureClassification classification) {
        return new JobExecutionResult(false, exitCode, reason, classification);
    }
}
