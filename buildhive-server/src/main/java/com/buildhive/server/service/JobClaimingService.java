package com.buildhive.server.service;

import com.buildhive.common.model.JobState;
import com.buildhive.server.dto.JobClaimResponseDto;
import com.buildhive.server.entity.JobAttemptEntity;
import com.buildhive.server.entity.JobEntity;
import com.buildhive.server.repository.JobAttemptRepository;
import com.buildhive.server.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles atomic, race-free job claiming by workers using PostgreSQL row-level locks.
 */
@Service
public class JobClaimingService {

    private final JobRepository jobRepository;
    private final JobAttemptRepository jobAttemptRepository;

    public JobClaimingService(JobRepository jobRepository, JobAttemptRepository jobAttemptRepository) {
        this.jobRepository = jobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
    }

    public static void validateLeaseDuration(Duration leaseDuration) {
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
        if (leaseDuration.isNegative() || leaseDuration.isZero() || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Lease duration must be greater than zero and at most 300 seconds");
        }
    }

    /**
     * Atomically claims the next READY job for the requesting worker.
     *
     * @param workerId      unique identifier of the requesting worker
     * @param leaseDuration duration the lease will remain valid before expiration
     * @return Optional containing JobClaimResponseDto if a job was claimed, or empty if no READY job exists
     */
    @Transactional
    public Optional<JobClaimResponseDto> claimNextJob(String workerId, Duration leaseDuration) {
        Objects.requireNonNull(workerId, "workerId must not be null");
        validateLeaseDuration(leaseDuration);

        // 1. Atomically query and lock next available READY job using FOR UPDATE SKIP LOCKED
        Optional<JobEntity> readyJobOpt = jobRepository.findNextReadyJobForUpdate();
        if (readyJobOpt.isEmpty()) {
            return Optional.empty();
        }

        JobEntity job = readyJobOpt.get();
        String claimToken = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant leaseExpiresAt = now.plus(leaseDuration);

        // 2. Transition job to CLAIMED and assign lease metadata
        job.setState(JobState.CLAIMED);
        job.setWorkerId(workerId);
        job.setClaimToken(claimToken);
        job.setClaimTime(now);
        job.setLeaseExpiresAt(leaseExpiresAt);
        job.setRetryCount(job.getRetryCount() + 1);

        jobRepository.save(job);

        // 3. Create persistent audit attempt record
        JobAttemptEntity attempt = new JobAttemptEntity();
        attempt.setJob(job);
        attempt.setAttemptNumber(job.getRetryCount());
        attempt.setWorkerId(workerId);
        attempt.setClaimToken(claimToken);
        attempt.setState(JobState.CLAIMED);
        attempt.setStartedAt(now);

        jobAttemptRepository.save(attempt);

        String archivePath = null;
        if (job.getPipeline() != null && job.getPipeline().getSubmission() != null) {
            archivePath = job.getPipeline().getSubmission().getArchivePath();
        }

        return Optional.of(new JobClaimResponseDto(
                job.getId(),
                job.getPipeline().getId(),
                job.getJobType(),
                claimToken,
                leaseExpiresAt,
                job.getRetryCount(),
                archivePath
        ));
    }
}
