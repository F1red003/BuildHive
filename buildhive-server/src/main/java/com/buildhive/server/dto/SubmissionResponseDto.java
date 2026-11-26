package com.buildhive.server.dto;

import com.buildhive.common.model.PipelineStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Response returned after a project archive is uploaded and a build pipeline is queued.
 */
public record SubmissionResponseDto(
        UUID submissionId,
        UUID pipelineId,
        UUID projectId,
        String projectName,
        PipelineStatus status,
        String checksumSha256,
        Instant createdAt
) {}
