package com.buildhive.server.dto;

import com.buildhive.common.model.JobType;
import java.time.Instant;
import java.util.UUID;

/**
 * Data returned to a worker after successfully claiming an available READY job.
 */
public record JobClaimResponseDto(
        UUID jobId,
        UUID pipelineId,
        JobType jobType,
        String claimToken,
        Instant leaseExpiresAt,
        int attemptNumber,
        String archivePath,
        String archiveDownloadUrl
) {
    public JobClaimResponseDto(UUID jobId, UUID pipelineId, JobType jobType, String claimToken, Instant leaseExpiresAt, int attemptNumber) {
        this(jobId, pipelineId, jobType, claimToken, leaseExpiresAt, attemptNumber, null, null);
    }

    public JobClaimResponseDto(UUID jobId, UUID pipelineId, JobType jobType, String claimToken, Instant leaseExpiresAt, int attemptNumber, String archivePath) {
        this(jobId, pipelineId, jobType, claimToken, leaseExpiresAt, attemptNumber, archivePath,
                pipelineId != null ? "/api/pipelines/" + pipelineId + "/archive" : null);
    }
}
