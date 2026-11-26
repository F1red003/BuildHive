package com.buildhive.server.service;

import com.buildhive.common.model.FailureClassification;
import com.buildhive.common.model.JobState;
import com.buildhive.server.entity.JobAttemptEntity;
import com.buildhive.server.entity.JobEntity;
import com.buildhive.server.repository.JobAttemptRepository;
import com.buildhive.server.repository.JobRepository;
import com.buildhive.server.repository.WorkerRepository;
import com.buildhive.server.repository.PipelineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Periodically detects expired job leases and unresponsive workers.
 * Re-queues retryable jobs to READY or permanently transitions them to FAILED if max retries are exceeded.
 */
@Service
public class LeaseReaperService {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaperService.class);

    private final JobRepository jobRepository;
    private final WorkerRepository workerRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final JobStateTransitionService jobStateTransitionService;
    private final long heartbeatTimeoutSeconds;
    private final PipelineRepository pipelineRepository;

    public LeaseReaperService(
            JobRepository jobRepository,
            WorkerRepository workerRepository,
            JobAttemptRepository jobAttemptRepository,
            JobStateTransitionService jobStateTransitionService,
            PipelineRepository pipelineRepository,
            @Value("${buildhive.worker.heartbeat-timeout-seconds:30}") long heartbeatTimeoutSeconds
    ) {
        this.pipelineRepository = pipelineRepository;
        this.jobRepository = jobRepository;
        this.workerRepository = workerRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.jobStateTransitionService = jobStateTransitionService;
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    /**
     * Scheduled task running every 15 seconds to reap expired leases.
     */
    @Transactional
    @Scheduled(fixedDelay = 15000)
    public void reapExpiredLeases() {
        reapExpiredLeasesAt(Instant.now());
    }

    /**
     * Reaps expired leases relative to the provided cutoff timestamp.
     *
     * @param now the reference timestamp
     * @return number of expired jobs reaped
     */
    @Transactional
    public int reapExpiredLeasesAt(Instant now) {
        workerRepository.markUnresponsiveBefore(now.minusSeconds(heartbeatTimeoutSeconds));
        List<UUID> expiredIds = jobRepository.findExpiredLeaseJobIds(now);
        int reaped = 0;
        for (UUID jobId : expiredIds) {
            pipelineRepository.lockForJob(jobId);
            JobEntity job = jobRepository.findById(jobId).orElse(null);
            // A completion, cancellation, or renewal may have committed while we waited.
            if (job != null && (job.getState() == JobState.CLAIMED || job.getState() == JobState.RUNNING)
                    && job.getLeaseExpiresAt() != null && job.getLeaseExpiresAt().isBefore(now)) {
                processExpiredJob(job, now);
                reaped++;
            }
        }
        return reaped;
    }

    private void processExpiredJob(JobEntity job, Instant now) {
        String workerId = job.getWorkerId();
        log.warn("Reaping job {} [{}]: leased to worker '{}' until {}, current retryCount={}/{}",
                job.getId(), job.getJobType(), workerId, job.getLeaseExpiresAt(),
                job.getRetryCount(), job.getMaxRetries());

        // Record an abandoned attempt in job_attempts
        recordAbandonedAttempt(job, now);

        // Evaluate retry count vs max retries
        if (job.getRetryCount() < job.getMaxRetries()) {
            job.scheduleRetry(now);
            jobRepository.save(job);

            log.info("Job {} re-queued to READY for reassignment (attempt {} of {})",
                    job.getId(), job.getRetryCount(), job.getMaxRetries());
        } else {
            // Retry limit exceeded: transition to FAILED and cascade SKIPPED to descendants
            log.error("Job {} exceeded maximum retries ({}); marking as FAILED with INFRASTRUCTURE_ERROR",
                    job.getId(), job.getMaxRetries());
            jobStateTransitionService.handleJobFailureAndCascade(job, FailureClassification.INFRASTRUCTURE_ERROR);
        }
    }

    private void recordAbandonedAttempt(JobEntity job, Instant now) {
        List<JobAttemptEntity> attempts = jobAttemptRepository.findByJobIdOrderByAttemptNumberDesc(job.getId());
        if (!attempts.isEmpty()) {
            JobAttemptEntity latestAttempt = attempts.getFirst();
            if (latestAttempt.getCompletedAt() == null) {
                latestAttempt.setCompletedAt(now);
                latestAttempt.setState(JobState.FAILED);
                latestAttempt.setFailureReason("Worker lease expired / heartbeat lost");
                latestAttempt.setFailureClassification(FailureClassification.INFRASTRUCTURE_ERROR);
                jobAttemptRepository.save(latestAttempt);
                return;
            }
        }

        // Fallback: If no open attempt exists, record a new abandoned attempt
        JobAttemptEntity attempt = new JobAttemptEntity();
        attempt.setJob(job);
        attempt.setAttemptNumber(job.getRetryCount());
        attempt.setWorkerId(job.getWorkerId() != null ? job.getWorkerId() : "UNKNOWN");
        attempt.setClaimToken(job.getClaimToken() != null ? job.getClaimToken() : "EXPIRED");
        attempt.setState(JobState.FAILED);
        attempt.setStartedAt(job.getClaimTime() != null ? job.getClaimTime() : now);
        attempt.setCompletedAt(now);
        attempt.setFailureReason("Worker lease expired / heartbeat lost");
        attempt.setFailureClassification(FailureClassification.INFRASTRUCTURE_ERROR);
        jobAttemptRepository.save(attempt);
    }
}
