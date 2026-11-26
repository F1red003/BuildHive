package com.buildhive.server.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload returned after an artifact is registered and when listing pipeline artifacts.
 */
public record ArtifactResponseDto(
        UUID id,
        UUID jobId,
        UUID pipelineId,
        String artifactType,
        String fileName,
        long fileSizeBytes,
        String sha256,
        String downloadUrl,
        Instant createdAt
) {
    /**
     * Backward-compatible constructor for Step 9 worker registration responses.
     */
    public ArtifactResponseDto(UUID id, UUID jobId, UUID pipelineId, String fileName, String sha256, Instant createdAt) {
        this(id, jobId, pipelineId, null, fileName, 0L, sha256, "/api/artifacts/" + id + "/download", createdAt);
    }
}
