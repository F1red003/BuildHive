package com.buildhive.server.entity;

import com.buildhive.common.model.FailureClassification;
import com.buildhive.common.model.JobState;
import com.buildhive.common.model.JobType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class JobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private PipelineEntity pipeline;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false)
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private JobState state = JobState.BLOCKED;

    @Column(name = "priority", nullable = false)
    private int priority = 50;

    @Column(name = "worker_id", length = 100)
    private String workerId;

    @Column(name = "claim_token", length = 100)
    private String claimToken;

    @Column(name = "claim_time")
    private Instant claimTime;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "available_at")
    private Instant availableAt;

    // These legacy column names count total attempts, including the initial claim.
    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 3;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_classification")
    private FailureClassification failureClassification;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public JobEntity() {
    }

    public JobEntity(PipelineEntity pipeline, JobType jobType, JobState state, int priority) {
        this.pipeline = pipeline;
        this.jobType = jobType;
        this.state = state;
        this.priority = priority;
        this.createdAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    /** Reset attempt ownership and schedule bounded exponential retry backoff. */
    public void scheduleRetry(Instant now) {
        state = JobState.READY;
        workerId = null;
        claimToken = null;
        claimTime = null;
        leaseExpiresAt = null;
        startedAt = null;
        completedAt = null;
        failureClassification = null;
        availableAt = now.plusSeconds(Math.min(60, 1L << Math.min(Math.max(retryCount, 0), 6)));
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public PipelineEntity getPipeline() {
        return pipeline;
    }

    public void setPipeline(PipelineEntity pipeline) {
        this.pipeline = pipeline;
    }

    public JobType getJobType() {
        return jobType;
    }

    public void setJobType(JobType jobType) {
        this.jobType = jobType;
    }

    public JobState getState() {
        return state;
    }

    public void setState(JobState state) {
        this.state = state;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getClaimToken() {
        return claimToken;
    }

    public void setClaimToken(String claimToken) {
        this.claimToken = claimToken;
    }

    public Instant getClaimTime() {
        return claimTime;
    }

    public void setClaimTime(Instant claimTime) {
        this.claimTime = claimTime;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public FailureClassification getFailureClassification() {
        return failureClassification;
    }

    public void setFailureClassification(FailureClassification failureClassification) {
        this.failureClassification = failureClassification;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
