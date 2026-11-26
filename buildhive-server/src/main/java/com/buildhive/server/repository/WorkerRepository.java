package com.buildhive.server.repository;

import com.buildhive.server.entity.WorkerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface WorkerRepository extends JpaRepository<WorkerEntity, String> {
    @Modifying
    @Query("UPDATE WorkerEntity w SET w.status = 'UNRESPONSIVE' WHERE w.status = 'ACTIVE' AND w.lastHeartbeat <= :cutoff")
    int markUnresponsiveBefore(@Param("cutoff") Instant cutoff);

    List<WorkerEntity> findByStatus(String status);
    long countByStatus(String status);
}
