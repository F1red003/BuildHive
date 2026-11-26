package com.buildhive.server.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload sent by a worker to register a build artifact produced by a job.
 */
public record ArtifactRegistrationDto(
        @NotBlank(message = "artifactType must not be blank")
        String artifactType,

        @NotBlank(message = "fileName must not be blank")
        String fileName,

        @NotBlank(message = "filePath must not be blank")
        String filePath,

        long fileSizeBytes,

        @NotBlank(message = "sha256 must not be blank")
        String sha256,

        String claimToken
) {
    public ArtifactRegistrationDto(String artifactType, String fileName, String filePath, long fileSizeBytes, String sha256) {
        this(artifactType, fileName, filePath, fileSizeBytes, sha256, null);
    }
}
