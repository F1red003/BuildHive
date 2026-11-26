package com.buildhive.worker.dto;

/**
 * Payload sent to server to register a build artifact.
 */
public record ArtifactRegistrationDto(
        String artifactType,
        String fileName,
        String filePath,
        long fileSizeBytes,
        String sha256,
        String claimToken
) {
    public ArtifactRegistrationDto(String artifactType, String fileName, String filePath, long fileSizeBytes, String sha256) {
        this(artifactType, fileName, filePath, fileSizeBytes, sha256, null);
    }
}
