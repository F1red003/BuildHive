package com.buildhive.server.dto;

import com.buildhive.common.model.FailureClassification;
import com.buildhive.common.model.JobState;
import com.buildhive.common.model.JobType;
import java.time.Instant;
import java.util.UUID;

public record JobDetailDto(
        UUID id,
        UUID pipelineId,
        JobType jobType,
        JobState state,
        int priority,
        String workerId,
        int retryCount,
        FailureClassification failureClassification,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {}
