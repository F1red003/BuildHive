package com.buildhive.server.repository;

import com.buildhive.server.entity.ArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ArtifactRepository extends JpaRepository<ArtifactEntity, UUID> {
    @org.springframework.data.jpa.repository.Query(value = "SELECT id FROM artifacts WHERE id = :id FOR UPDATE", nativeQuery = true)
    UUID lockById(@org.springframework.data.repository.query.Param("id") UUID id);
    java.util.Optional<ArtifactEntity> findByPublicationKey(String publicationKey);
    List<ArtifactEntity> findByPipelineId(UUID pipelineId);
    List<ArtifactEntity> findByJobId(UUID jobId);
}
