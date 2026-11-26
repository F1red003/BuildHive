package com.buildhive.server.repository;

import com.buildhive.common.model.JobState;
import com.buildhive.server.entity.JobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobRepository extends JpaRepository<JobEntity, UUID> {

    /**
     * Atomically selects and locks the highest priority READY job using PostgreSQL's FOR UPDATE SKIP LOCKED.
     * Guaranteed to prevent race conditions across multiple concurrent workers.
     */
    @Query(value = """
        SELECT j.* FROM jobs j JOIN pipelines p ON p.id = j.pipeline_id
        WHERE j.state = 'READY' AND p.status = 'RUNNING'
          AND (j.available_at IS NULL OR j.available_at <= CURRENT_TIMESTAMP)
        ORDER BY j.priority DESC, j.created_at ASC
        LIMIT 1
        FOR UPDATE OF p, j SKIP LOCKED
    """, nativeQuery = true)
    Optional<JobEntity> findNextReadyJobForUpdate();

    @Query("""
        SELECT j.id FROM JobEntity j
        WHERE j.state IN (com.buildhive.common.model.JobState.CLAIMED, com.buildhive.common.model.JobState.RUNNING)
          AND j.leaseExpiresAt < :cutoff
        ORDER BY j.pipeline.id, j.id
        """)
    List<UUID> findExpiredLeaseJobIds(@Param("cutoff") Instant cutoff);

    List<JobEntity> findByPipelineId(UUID pipelineId);

    interface LogProgress {
        JobState getState();
        int getRetryCount();
        Instant getLeaseExpiresAt();
    }

    // Read scalar values so a long-lived async request does not reuse a cached JobEntity.
    @Query("""
        SELECT j.state AS state, j.retryCount AS retryCount, j.leaseExpiresAt AS leaseExpiresAt
        FROM JobEntity j WHERE j.id = :id
        """)
    Optional<LogProgress> findLogProgress(@Param("id") UUID id);

    List<JobEntity> findByPipelineIdAndState(UUID pipelineId, JobState state);

    long countByState(JobState state);

    List<JobEntity> findByWorkerIdAndState(String workerId, JobState state);

    Optional<JobEntity> findByClaimToken(String claimToken);

    @Query("""
        SELECT j FROM JobEntity j
        WHERE j.state = :state AND j.leaseExpiresAt < :cutoffTime
    """)
    List<JobEntity> findOrphanedJobs(@Param("state") JobState state, @Param("cutoffTime") Instant cutoffTime);

    @Query("""
        SELECT j FROM JobEntity j
        WHERE j.state IN (:states) AND j.leaseExpiresAt < :cutoffTime
    """)
    List<JobEntity> findExpiredLeaseJobs(
            @Param("states") java.util.Collection<JobState> states,
            @Param("cutoffTime") Instant cutoffTime
    );

    default List<JobEntity> findExpiredLeaseJobs(Instant cutoffTime) {
        return findExpiredLeaseJobs(List.of(JobState.CLAIMED, JobState.RUNNING), cutoffTime);
    }
}
