package com.buildhive.worker.dto;

import com.buildhive.common.model.JobType;
import java.time.Instant;
import java.util.UUID;

/**
 * Details of a claimed job received from the BuildHive server.
 */
public record JobClaimDto(
        UUID jobId,
        UUID pipelineId,
        JobType jobType,
        String claimToken,
        Instant leaseExpiresAt,
        int attemptNumber,
        String archivePath,
        String archiveDownloadUrl
) {}
