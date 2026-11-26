package com.buildhive.worker.dto;

import com.buildhive.common.model.FailureClassification;

/**
 * Report payload sent to server upon job completion.
 */
public record JobCompletionDto(
        String claimToken,
        boolean success,
        Integer exitCode,
        String failureReason,
        FailureClassification failureClassification
) {
    public JobCompletionDto(String claimToken, boolean success, String failureReason) {
        this(claimToken, success, null, failureReason, null);
    }
}
