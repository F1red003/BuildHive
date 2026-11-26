package com.buildhive.server.repository;

import com.buildhive.server.entity.JobAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JobAttemptRepository extends JpaRepository<JobAttemptEntity, UUID> {
    List<JobAttemptEntity> findByJobIdOrderByAttemptNumberDesc(UUID jobId);
}
