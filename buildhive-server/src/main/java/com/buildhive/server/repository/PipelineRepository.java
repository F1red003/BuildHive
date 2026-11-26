package com.buildhive.server.repository;

import com.buildhive.common.model.PipelineStatus;
import com.buildhive.server.entity.PipelineEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PipelineRepository extends JpaRepository<PipelineEntity, UUID> {
    /** Lock before loading jobs so dependency checks see the preceding committed transition. */
    @Query(value = """
        SELECT p.id FROM pipelines p JOIN jobs j ON j.pipeline_id = p.id
        WHERE j.id = :jobId FOR UPDATE OF p
        """, nativeQuery = true)
    UUID lockForJob(@Param("jobId") UUID jobId);

    @Query(value = "SELECT id FROM pipelines WHERE id = :pipelineId FOR UPDATE", nativeQuery = true)
    UUID lockById(@Param("pipelineId") UUID pipelineId);

    List<PipelineEntity> findBySubmissionId(UUID submissionId);
    List<PipelineEntity> findByStatus(PipelineStatus status);

    @EntityGraph(attributePaths = {"submission", "submission.project"})
    Optional<PipelineEntity> findById(UUID id);

    @EntityGraph(attributePaths = {"submission", "submission.project"})
    List<PipelineEntity> findAllByOrderByCreatedAtDesc();
}
