package com.buildhive.server.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "job_dependencies", uniqueConstraints = {
        @UniqueConstraint(name = "uq_job_dependencies", columnNames = {"parent_job_id", "child_job_id"})
})
public class JobDependencyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_job_id", nullable = false)
    private JobEntity parentJob;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "child_job_id", nullable = false)
    private JobEntity childJob;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public JobDependencyEntity() {
    }

    public JobDependencyEntity(JobEntity parentJob, JobEntity childJob) {
        this.parentJob = parentJob;
        this.childJob = childJob;
        this.createdAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public JobEntity getParentJob() {
        return parentJob;
    }

    public void setParentJob(JobEntity parentJob) {
        this.parentJob = parentJob;
    }

    public JobEntity getChildJob() {
        return childJob;
    }

    public void setChildJob(JobEntity childJob) {
        this.childJob = childJob;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
