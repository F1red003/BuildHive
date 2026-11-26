package com.buildhive.server.repository;

import com.buildhive.server.entity.SubmissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SubmissionRepository extends JpaRepository<SubmissionEntity, UUID> {
    List<SubmissionEntity> findByProjectId(UUID projectId);
}
