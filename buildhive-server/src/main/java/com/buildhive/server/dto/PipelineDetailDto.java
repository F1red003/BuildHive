package com.buildhive.server.dto;

import com.buildhive.common.model.PipelineStatus;
import java.time.Instant;
import java.util.UUID;

public record PipelineDetailDto(
        UUID id,
        UUID submissionId,
        String projectName,
        PipelineStatus status,
        boolean cancellationRequested,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {}
