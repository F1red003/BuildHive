package com.buildhive.server.repository;

import com.buildhive.server.entity.JobDependencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JobDependencyRepository extends JpaRepository<JobDependencyEntity, UUID> {
    List<JobDependencyEntity> findByParentJobId(UUID parentJobId);
    List<JobDependencyEntity> findByChildJobId(UUID childJobId);
}
