package com.buildhive.worker.daemon;

import com.buildhive.common.model.FailureClassification;
import com.buildhive.common.model.JobType;
import com.buildhive.worker.client.WorkerClient;
import com.buildhive.worker.dto.*;
import com.buildhive.worker.service.JobExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkerDaemonTest {

    @Mock
    private WorkerClient workerClient;

    @Mock
    private JobExecutionService jobExecutionService;

    private WorkerDaemon workerDaemon;

    @BeforeEach
    void setUp() {
        workerDaemon = new WorkerDaemon(
                workerClient,
                jobExecutionService,
                "worker-test",
                "test-host",
                300L,
                10000L,
                50L,
                200L,
                false // disable background loop for deterministic unit tests
        );

        when(workerClient.register(eq("worker-test"), eq("test-host"), isNull()))
                .thenReturn(new WorkerRegisterResponse("w-custom-1", "ACTIVE"));
    }

    @AfterEach
    void clearShutdownInterrupt() {
        workerDaemon.shutdown();
        Thread.interrupted();
    }

    @Test
    @DisplayName("Worker registers and receives worker ID upon register")
    void testRegistration() {
        workerDaemon.register();
        assertThat(workerDaemon.getWorkerId()).isEqualTo("w-custom-1");
        verify(workerClient).register("worker-test", "test-host", null);
    }

    @Test
    @DisplayName("pollOnce successfully executes claimed job and reports completion")
    void testPollOnceSuccess() {
        workerDaemon.register();

        UUID jobId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        JobClaimDto claim = new JobClaimDto(
                jobId,
                pipelineId,
                JobType.COMPILATION,
                "token-456",
                Instant.now().plusSeconds(300),
                1,
                "/data/project.zip",
                "/api/pipelines/" + pipelineId + "/archive"
        );

        when(workerClient.claimJob(eq("w-custom-1"), eq(300L))).thenReturn(Optional.of(claim));
        when(jobExecutionService.executeJob(claim, "w-custom-1")).thenReturn(JobExecutionResult.successful());

        boolean result = workerDaemon.pollOnce();

        assertThat(result).isTrue();
        verify(workerClient).startJob("w-custom-1", jobId, "token-456");
        verify(jobExecutionService).executeJob(claim, "w-custom-1");

        ArgumentCaptor<JobCompletionDto> captor = ArgumentCaptor.forClass(JobCompletionDto.class);
        verify(workerClient).completeJob(eq("w-custom-1"), eq(jobId), captor.capture());

        JobCompletionDto completion = captor.getValue();
        assertThat(completion.claimToken()).isEqualTo("token-456");
        assertThat(completion.success()).isTrue();
        assertThat(completion.exitCode()).isEqualTo(0);
        assertThat(workerDaemon.getActiveJob()).isNull();
    }

    @Test
    @DisplayName("pollOnce returns false when no jobs are available (HTTP 204)")
    void testPollOnceNoJobs() {
        workerDaemon.register();

        when(workerClient.claimJob(eq("w-custom-1"), eq(300L))).thenReturn(Optional.empty());

        boolean result = workerDaemon.pollOnce();

        assertThat(result).isFalse();
        verify(jobExecutionService, never()).executeJob(any());
        verify(workerClient, never()).completeJob(any(), any(), any());
    }

    @Test
    @DisplayName("pollOnce reports infrastructure failure when job execution throws exception")
    void testPollOnceExecutionException() {
        workerDaemon.register();

        UUID jobId = UUID.randomUUID();
        JobClaimDto claim = new JobClaimDto(
                jobId,
                UUID.randomUUID(),
                JobType.UNIT_TESTS,
                "token-err",
                Instant.now().plusSeconds(300),
                1,
                "/data/project.zip",
                null
        );

        when(workerClient.claimJob(eq("w-custom-1"), eq(300L))).thenReturn(Optional.of(claim));
        when(jobExecutionService.executeJob(claim, "w-custom-1")).thenThrow(new RuntimeException("Docker failure"));

        boolean result = workerDaemon.pollOnce();

        assertThat(result).isTrue();
        ArgumentCaptor<JobCompletionDto> captor = ArgumentCaptor.forClass(JobCompletionDto.class);
        verify(workerClient).completeJob(eq("w-custom-1"), eq(jobId), captor.capture());

        JobCompletionDto completion = captor.getValue();
        assertThat(completion.success()).isFalse();
        assertThat(completion.failureClassification()).isEqualTo(FailureClassification.INFRASTRUCTURE_ERROR);
        assertThat(completion.failureReason()).contains("Docker failure");
        assertThat(workerDaemon.getActiveJob()).isNull();
    }

    @Test
    @DisplayName("shutdown gracefully reports failure for active job to release lease")
    void testGracefulShutdownWithActiveJob() {
        workerDaemon.register();

        UUID jobId = UUID.randomUUID();
        JobClaimDto claim = new JobClaimDto(
                jobId,
                UUID.randomUUID(),
                JobType.COMPILATION,
                "token-shutdown",
                Instant.now().plusSeconds(300),
                1,
                "/data/project.zip",
                null
        );

        when(workerClient.claimJob(eq("w-custom-1"), eq(300L))).thenReturn(Optional.of(claim));
        doAnswer(inv -> {
            // Trigger shutdown while job is running
            workerDaemon.shutdown();
            return JobExecutionResult.successful();
        }).when(jobExecutionService).executeJob(claim, "w-custom-1");

        workerDaemon.pollOnce();

        // Verify shutdown reported job failure with exit code 130
        verify(workerClient).completeJob(
                eq("w-custom-1"),
                eq(jobId),
                argThat(dto -> !dto.success() && dto.exitCode() == 130 && dto.failureClassification() == FailureClassification.INFRASTRUCTURE_ERROR)
        );
    }

    private JobClaimDto leaseClaim(Instant expiry) {
        return new JobClaimDto(UUID.randomUUID(), UUID.randomUUID(), JobType.COMPILATION,
                "lease-token", expiry, 1, "/data/project.zip", null);
    }

    @Test
    void successfulRenewalKeepsExecutionAlivePastOriginalExpiry() throws Exception {
        var claim = leaseClaim(Instant.now().plusMillis(100));
        when(workerClient.claimJob("w-custom-1", 300)).thenReturn(Optional.of(claim));
        when(workerClient.renewLease("w-custom-1", claim.jobId(), claim.claimToken(), 300))
                .thenReturn(new com.buildhive.common.dto.LeaseRenewalDto(Instant.now().plusSeconds(300)));
        when(jobExecutionService.executeJob(claim, "w-custom-1")).thenAnswer(inv -> {
            workerDaemon.refreshLeaseAndHeartbeat();
            Thread.sleep(150);
            workerDaemon.expireLocalLease();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            return JobExecutionResult.successful();
        });
        workerDaemon.pollOnce();
        verify(workerClient).completeJob(eq("w-custom-1"), eq(claim.jobId()), argThat(JobCompletionDto::success));
    }

    @Test
    void rejectedRenewalInterruptsOnlyTheCurrentJobAndAllowsFurtherPolling() {
        var claim = leaseClaim(Instant.now().plusSeconds(300));
        when(workerClient.claimJob("w-custom-1", 300)).thenReturn(Optional.of(claim), Optional.empty());
        when(workerClient.renewLease("w-custom-1", claim.jobId(), claim.claimToken(), 300))
                .thenThrow(new org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.CONFLICT));
        when(jobExecutionService.executeJob(claim, "w-custom-1")).thenAnswer(inv -> {
            workerDaemon.refreshLeaseAndHeartbeat();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            return JobExecutionResult.successful();
        });
        workerDaemon.pollOnce();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(workerDaemon.pollOnce()).isFalse();
        verify(workerClient, never()).completeJob(any(), any(), any());
    }

    @Test
    void renewalOutageCannotKeepAnExpiredAttemptRunning() {
        var claim = leaseClaim(Instant.now().minusSeconds(1));
        when(workerClient.claimJob("w-custom-1", 300)).thenReturn(Optional.of(claim));
        when(workerClient.renewLease("w-custom-1", claim.jobId(), claim.claimToken(), 300))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("network unavailable"));
        when(jobExecutionService.executeJob(claim, "w-custom-1")).thenAnswer(inv -> {
            workerDaemon.refreshLeaseAndHeartbeat();
            workerDaemon.expireLocalLease();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            return JobExecutionResult.successful();
        });
        workerDaemon.pollOnce();
        verify(workerClient, never()).completeJob(any(), any(), any());
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void lostCompletionResponseRetriesTheOriginalSuccessResult() {
        var claim = leaseClaim(Instant.now().plusSeconds(300));
        when(workerClient.claimJob("w-custom-1", 300)).thenReturn(Optional.of(claim));
        when(jobExecutionService.executeJob(claim, "w-custom-1")).thenReturn(JobExecutionResult.successful());
        doThrow(new org.springframework.web.client.ResourceAccessException("response lost"))
                .doNothing().when(workerClient).completeJob(eq("w-custom-1"), eq(claim.jobId()), any());
        workerDaemon.pollOnce();
        ArgumentCaptor<JobCompletionDto> results = ArgumentCaptor.forClass(JobCompletionDto.class);
        verify(workerClient, times(2)).completeJob(eq("w-custom-1"), eq(claim.jobId()), results.capture());
        assertThat(results.getAllValues()).allMatch(JobCompletionDto::success);
        assertThat(results.getAllValues().get(0)).isEqualTo(results.getAllValues().get(1));
    }
}
