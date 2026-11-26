package com.buildhive.worker.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Server response after registering an artifact.
 */
public record ArtifactResponseDto(
        UUID id,
        UUID jobId,
        UUID pipelineId,
        String fileName,
        String sha256,
        Instant createdAt
) {}
