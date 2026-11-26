package com.buildhive.server.dto;

import com.buildhive.common.model.FailureClassification;
import jakarta.validation.constraints.NotBlank;

/**
 * Payload sent by a worker reporting the execution result of a job.
 */
public record JobCompletionDto(
        @NotBlank(message = "claimToken must not be blank")
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
