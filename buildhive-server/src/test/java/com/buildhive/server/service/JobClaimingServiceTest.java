package com.buildhive.server.service;

import com.buildhive.common.model.JobState;
import com.buildhive.common.model.JobType;
import com.buildhive.server.dto.JobClaimResponseDto;
import com.buildhive.server.entity.JobAttemptEntity;
import com.buildhive.server.entity.JobEntity;
import com.buildhive.server.entity.PipelineEntity;
import com.buildhive.server.repository.JobAttemptRepository;
import com.buildhive.server.repository.JobRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobClaimingServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobAttemptRepository jobAttemptRepository;

    @InjectMocks
    private JobClaimingService jobClaimingService;

    @Test
    @DisplayName("Successfully claims ready job with atomic lock, generates token and records attempt")
    void testClaimNextJobSuccess() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(UUID.randomUUID());

        JobEntity job = new JobEntity();
        job.setId(UUID.randomUUID());
        job.setPipeline(pipeline);
        job.setJobType(JobType.COMPILATION);
        job.setState(JobState.READY);
        job.setPriority(80);
        job.setRetryCount(0);

        when(jobRepository.findNextReadyJobForUpdate()).thenReturn(Optional.of(job));

        Duration leaseDuration = Duration.ofSeconds(60);
        Optional<JobClaimResponseDto> result = jobClaimingService.claimNextJob("worker-1", leaseDuration);

        assertThat(result).isPresent();
        JobClaimResponseDto claim = result.get();

        assertThat(claim.jobId()).isEqualTo(job.getId());
        assertThat(claim.pipelineId()).isEqualTo(pipeline.getId());
        assertThat(claim.jobType()).isEqualTo(JobType.COMPILATION);
        assertThat(claim.claimToken()).isNotBlank();
        assertThat(claim.leaseExpiresAt()).isAfter(Instant.now().minusSeconds(1));
        assertThat(claim.attemptNumber()).isEqualTo(1);

        // Verify JobEntity was updated to CLAIMED and saved
        assertThat(job.getState()).isEqualTo(JobState.CLAIMED);
        assertThat(job.getWorkerId()).isEqualTo("worker-1");
        assertThat(job.getClaimToken()).isEqualTo(claim.claimToken());
        assertThat(job.getRetryCount()).isEqualTo(1);
        verify(jobRepository).save(job);

        // Verify JobAttemptEntity was recorded
        ArgumentCaptor<JobAttemptEntity> attemptCaptor = ArgumentCaptor.forClass(JobAttemptEntity.class);
        verify(jobAttemptRepository).save(attemptCaptor.capture());
        JobAttemptEntity recordedAttempt = attemptCaptor.getValue();

        assertThat(recordedAttempt.getJob()).isEqualTo(job);
        assertThat(recordedAttempt.getWorkerId()).isEqualTo("worker-1");
        assertThat(recordedAttempt.getClaimToken()).isEqualTo(claim.claimToken());
        assertThat(recordedAttempt.getState()).isEqualTo(JobState.CLAIMED);
        assertThat(recordedAttempt.getAttemptNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Returns empty Optional when no job is in READY state")
    void testClaimNextJobWhenQueueEmpty() {
        when(jobRepository.findNextReadyJobForUpdate()).thenReturn(Optional.empty());

        Optional<JobClaimResponseDto> result = jobClaimingService.claimNextJob("worker-1", Duration.ofSeconds(60));

        assertThat(result).isEmpty();
        verify(jobRepository, never()).save(any());
        verify(jobAttemptRepository, never()).save(any());
    }
}
