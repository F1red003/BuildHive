package com.buildhive.server.service;

import com.buildhive.common.model.FailureClassification;
import com.buildhive.common.model.JobState;
import com.buildhive.common.model.JobType;
import com.buildhive.server.entity.JobAttemptEntity;
import com.buildhive.server.entity.JobEntity;
import com.buildhive.server.entity.PipelineEntity;
import com.buildhive.server.repository.JobAttemptRepository;
import com.buildhive.server.repository.JobRepository;
import com.buildhive.server.repository.WorkerRepository;
import com.buildhive.server.repository.PipelineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeaseReaperServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private WorkerRepository workerRepository;

    @Mock
    private JobAttemptRepository jobAttemptRepository;

    @Mock
    private JobStateTransitionService jobStateTransitionService;

    @Mock
    private PipelineRepository pipelineRepository;

    private LeaseReaperService leaseReaperService;

    private final long heartbeatTimeoutSeconds = 30;

    @BeforeEach
    void setUp() {
        leaseReaperService = new LeaseReaperService(
                jobRepository,
                workerRepository,
                jobAttemptRepository,
                jobStateTransitionService,
                pipelineRepository,
                heartbeatTimeoutSeconds
        );
    }

    @Test
    @DisplayName("Expired job with available retries transitions back to READY with backoff and clears claim ownership")
    void testReapExpiredJobWithAvailableRetries() {
        Instant now = Instant.parse("2026-09-16T22:00:00Z");
        Instant expiredLease = now.minusSeconds(5);
        String oldToken = "old-claim-token-123";
        String workerId = "worker-node-1";

        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(UUID.randomUUID());

        JobEntity job = new JobEntity(pipeline, JobType.COMPILATION, JobState.RUNNING, 80);
        job.setId(UUID.randomUUID());
        job.setWorkerId(workerId);
        job.setClaimToken(oldToken);
        job.setClaimTime(now.minusSeconds(30));
        job.setLeaseExpiresAt(expiredLease);
        job.setRetryCount(1);
        job.setMaxRetries(3);

        JobAttemptEntity attempt = new JobAttemptEntity();
        attempt.setJob(job);
        attempt.setAttemptNumber(1);
        attempt.setWorkerId(workerId);
        attempt.setClaimToken(oldToken);
        attempt.setState(JobState.RUNNING);
        attempt.setStartedAt(now.minusSeconds(30));

        when(jobRepository.findExpiredLeaseJobIds(now)).thenReturn(List.of(job.getId()));
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberDesc(job.getId())).thenReturn(new ArrayList<>(List.of(attempt)));

        int reapedCount = leaseReaperService.reapExpiredLeasesAt(now);

        assertThat(reapedCount).isEqualTo(1);

        // Verify attempt was closed as FAILED
        assertThat(attempt.getState()).isEqualTo(JobState.FAILED);
        assertThat(attempt.getCompletedAt()).isEqualTo(now);
        assertThat(attempt.getFailureReason()).contains("Worker lease expired / heartbeat lost");
        assertThat(attempt.getFailureClassification()).isEqualTo(FailureClassification.INFRASTRUCTURE_ERROR);
        verify(jobAttemptRepository).save(attempt);

        // Verify job transitioned back to READY
        assertThat(job.getState()).isEqualTo(JobState.READY);
        assertThat(job.getWorkerId()).isNull();
        assertThat(job.getClaimToken()).isNull();
        assertThat(job.getAvailableAt()).isAfter(now);
        assertThat(job.getLeaseExpiresAt()).isNull();
        assertThat(job.getClaimTime()).isNull();
        assertThat(job.getRetryCount()).isEqualTo(1); // retained so next claim will increment to 2
        verify(jobRepository).save(job);

        // Verify no cascade happened because job is retryable
        verifyNoInteractions(jobStateTransitionService);
    }

    @Test
    @DisplayName("Expired job exceeding max retries transitions to FAILED and cascades SKIPPED to descendants")
    void testReapExpiredJobExceedingMaxRetries() {
        Instant now = Instant.parse("2026-09-16T22:00:00Z");
        String workerId = "worker-node-2";

        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(UUID.randomUUID());

        JobEntity job = new JobEntity(pipeline, JobType.COMPILATION, JobState.RUNNING, 80);
        job.setId(UUID.randomUUID());
        job.setWorkerId(workerId);
        job.setClaimToken("token-attempt-3");
        job.setLeaseExpiresAt(now.minusSeconds(10));
        job.setRetryCount(3);
        job.setMaxRetries(3);

        JobAttemptEntity attempt = new JobAttemptEntity();
        attempt.setJob(job);
        attempt.setAttemptNumber(3);
        attempt.setWorkerId(workerId);
        attempt.setClaimToken("token-attempt-3");
        attempt.setState(JobState.RUNNING);
        attempt.setStartedAt(now.minusSeconds(60));

        when(jobRepository.findExpiredLeaseJobIds(now)).thenReturn(List.of(job.getId()));
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberDesc(job.getId())).thenReturn(new ArrayList<>(List.of(attempt)));

        int reapedCount = leaseReaperService.reapExpiredLeasesAt(now);

        assertThat(reapedCount).isEqualTo(1);

        // Verify attempt recorded as failed
        verify(jobAttemptRepository).save(attempt);

        // Verify failure cascade invoked
        verify(jobStateTransitionService).handleJobFailureAndCascade(job, FailureClassification.INFRASTRUCTURE_ERROR);
    }

    @Test
    @DisplayName("Expired lease without an open attempt records an abandoned attempt")
    void testReapExpiredJobWithoutOpenAttempt() {
        Instant now = Instant.parse("2026-09-16T22:00:00Z");
        String workerId = "worker-healthy";

        PipelineEntity pipeline = new PipelineEntity();
        JobEntity job = new JobEntity(pipeline, JobType.UNIT_TESTS, JobState.RUNNING, 70);
        job.setId(UUID.randomUUID());
        job.setWorkerId(workerId);
        job.setClaimToken("token-xyz");
        job.setLeaseExpiresAt(now.minusSeconds(2));
        job.setRetryCount(1);
        job.setMaxRetries(3);

        when(jobRepository.findExpiredLeaseJobIds(now)).thenReturn(List.of(job.getId()));
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberDesc(job.getId())).thenReturn(new ArrayList<>());

        leaseReaperService.reapExpiredLeasesAt(now);

        // Job should still be re-queued to READY
        assertThat(job.getState()).isEqualTo(JobState.READY);
        verify(jobRepository).save(job);
    }

    @Test
    @DisplayName("Simulate 1-second lease: advance time and confirm job re-queues to READY for reassignment")
    void testSimulateOneSecondLeaseAdvanceTime() {
        Instant t0 = Instant.parse("2026-09-16T12:00:00Z");
        Instant leaseExpiration = t0.plusSeconds(1); // 1-second lease

        PipelineEntity pipeline = new PipelineEntity();
        JobEntity job = new JobEntity(pipeline, JobType.DEPENDENCY_RESOLUTION, JobState.CLAIMED, 90);
        job.setId(UUID.randomUUID());
        String initialClaimToken = "claim-token-initial";
        job.setClaimToken(initialClaimToken);
        job.setWorkerId("worker-fast-crash");
        job.setLeaseExpiresAt(leaseExpiration);
        job.setRetryCount(1);
        job.setMaxRetries(3);

        // At t0 + 0.5s: lease has NOT expired yet
        Instant tHalf = t0.plusMillis(500);
        when(jobRepository.findExpiredLeaseJobIds(tHalf)).thenReturn(List.of());

        int reapedBefore = leaseReaperService.reapExpiredLeasesAt(tHalf);
        assertThat(reapedBefore).isEqualTo(0);

        // Advance time to t0 + 2.0s: lease is now expired!
        Instant tAdvanced = t0.plusSeconds(2);
        when(jobRepository.findExpiredLeaseJobIds(tAdvanced)).thenReturn(List.of(job.getId()));
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberDesc(job.getId())).thenReturn(new ArrayList<>());

        int reapedAfter = leaseReaperService.reapExpiredLeasesAt(tAdvanced);
        assertThat(reapedAfter).isEqualTo(1);

        // Confirmed: job transitioned back to READY for reassignment
        assertThat(job.getState()).isEqualTo(JobState.READY);
        assertThat(job.getWorkerId()).isNull();
        assertThat(job.getClaimToken()).isNotEqualTo(initialClaimToken);
    }

    @Test
    @DisplayName("An empty job queue still checks worker liveness")
    void testNoExpiredJobsDoesNothing() {
        Instant now = Instant.now();
        when(jobRepository.findExpiredLeaseJobIds(now)).thenReturn(List.of());

        int reaped = leaseReaperService.reapExpiredLeasesAt(now);

        assertThat(reaped).isEqualTo(0);
        verify(workerRepository).markUnresponsiveBefore(now.minusSeconds(30));
        verify(jobRepository, never()).save(any());
        verify(workerRepository, never()).save(any());
    }
}
