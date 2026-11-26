package com.buildhive.common.security;

import java.util.Objects;

/**
 * Metadata extracted from a validated project archive's root Maven pom.xml.
 */
public record MavenProjectMetadata(
        String groupId,
        String artifactId,
        String version,
        String packaging,
        String pomPathInArchive
) {
    public MavenProjectMetadata {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(artifactId, "artifactId must not be null");
        Objects.requireNonNull(version, "version must not be null");
        packaging = (packaging == null || packaging.isBlank()) ? "jar" : packaging;
    }

    /**
     * Standard Maven coordinate representation (groupId:artifactId:version).
     */
    public String getCoordinates() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
