package com.buildhive.server.service;

import com.buildhive.common.model.JobState;
import com.buildhive.common.model.JobType;
import com.buildhive.common.model.PipelineStatus;
import com.buildhive.server.entity.JobDependencyEntity;
import com.buildhive.server.entity.JobEntity;
import com.buildhive.server.entity.PipelineEntity;
import com.buildhive.server.repository.JobAttemptRepository;
import com.buildhive.server.repository.JobDependencyRepository;
import com.buildhive.server.repository.JobRepository;
import com.buildhive.server.repository.PipelineRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobStateTransitionServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobDependencyRepository jobDependencyRepository;

    @Mock
    private JobAttemptRepository jobAttemptRepository;

    @Mock
    private PipelineRepository pipelineRepository;

    @InjectMocks
    private JobStateTransitionService jobStateTransitionService;

    @Test
    @DisplayName("Completing a job successfully unlocks its single child dependency")
    void testSuccessUnlocksChildJob() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(UUID.randomUUID());

        String claimToken = "valid-token-123";

        JobEntity parentJob = new JobEntity(pipeline, JobType.PROJECT_VALIDATION, JobState.RUNNING, 100);
        parentJob.setId(UUID.randomUUID());
        parentJob.setClaimToken(claimToken);
        parentJob.setLeaseExpiresAt(java.time.Instant.now().plusSeconds(300));

        JobEntity childJob = new JobEntity(pipeline, JobType.DEPENDENCY_RESOLUTION, JobState.BLOCKED, 90);
        childJob.setId(UUID.randomUUID());

        JobDependencyEntity dep = new JobDependencyEntity(parentJob, childJob);

        when(jobRepository.findById(parentJob.getId())).thenReturn(Optional.of(parentJob));
        when(jobDependencyRepository.findByParentJobId(parentJob.getId())).thenReturn(List.of(dep));
        when(jobDependencyRepository.findByChildJobId(childJob.getId())).thenReturn(List.of(dep));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberDesc(parentJob.getId())).thenReturn(Collections.emptyList());
        when(jobRepository.findByPipelineId(pipeline.getId())).thenReturn(List.of(parentJob, childJob));

        jobStateTransitionService.handleJobCompleted(parentJob.getId(), claimToken, true, null);

        assertThat(parentJob.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(childJob.getState()).isEqualTo(JobState.READY);
        verify(jobRepository).save(parentJob);
        verify(jobRepository).save(childJob);
    }

    @Test
    @DisplayName("Child with multiple parents remains BLOCKED until ALL parents SUCCEED")
    void testChildWithMultipleParentsWaitsForAll() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(UUID.randomUUID());

        String token1 = "token-test";

        JobEntity unitTestJob = new JobEntity(pipeline, JobType.UNIT_TESTS, JobState.RUNNING, 70);
        unitTestJob.setId(UUID.randomUUID());
        unitTestJob.setClaimToken(token1);
        unitTestJob.setLeaseExpiresAt(java.time.Instant.now().plusSeconds(300));

        JobEntity staticAnalysisJob = new JobEntity(pipeline, JobType.STATIC_ANALYSIS, JobState.RUNNING, 70);
        staticAnalysisJob.setId(UUID.randomUUID());

        JobEntity packagingJob = new JobEntity(pipeline, JobType.PACKAGING, JobState.BLOCKED, 60);
        packagingJob.setId(UUID.randomUUID());

        JobDependencyEntity dep1 = new JobDependencyEntity(unitTestJob, packagingJob);
        JobDependencyEntity dep2 = new JobDependencyEntity(staticAnalysisJob, packagingJob);

        when(jobRepository.findById(unitTestJob.getId())).thenReturn(Optional.of(unitTestJob));
        when(jobDependencyRepository.findByParentJobId(unitTestJob.getId())).thenReturn(List.of(dep1));
        // child has 2 parents: unitTestJob (now SUCCEEDED) and staticAnalysisJob (still RUNNING)
        when(jobDependencyRepository.findByChildJobId(packagingJob.getId())).thenReturn(List.of(dep1, dep2));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberDesc(unitTestJob.getId())).thenReturn(Collections.emptyList());
        when(jobRepository.findByPipelineId(pipeline.getId())).thenReturn(List.of(unitTestJob, staticAnalysisJob, packagingJob));

        jobStateTransitionService.handleJobCompleted(unitTestJob.getId(), token1, true, null);

        // unitTestJob succeeded, but packagingJob must still be BLOCKED
        assertThat(unitTestJob.getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(packagingJob.getState()).isEqualTo(JobState.BLOCKED);
        verify(jobRepository, never()).save(packagingJob);
    }

    @Test
    @DisplayName("Job failure cascades SKIPPED to downstream jobs and marks pipeline FAILED")
    void testFailureCascadesSkippedState() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(UUID.randomUUID());

        String claimToken = "token-compile";

        JobEntity compileJob = new JobEntity(pipeline, JobType.COMPILATION, JobState.RUNNING, 80);
        compileJob.setId(UUID.randomUUID());
        compileJob.setClaimToken(claimToken);
        compileJob.setLeaseExpiresAt(java.time.Instant.now().plusSeconds(300));

        JobEntity testJob = new JobEntity(pipeline, JobType.UNIT_TESTS, JobState.BLOCKED, 70);
        testJob.setId(UUID.randomUUID());

        JobEntity packageJob = new JobEntity(pipeline, JobType.PACKAGING, JobState.BLOCKED, 60);
        packageJob.setId(UUID.randomUUID());

        JobDependencyEntity dep1 = new JobDependencyEntity(compileJob, testJob);
        JobDependencyEntity dep2 = new JobDependencyEntity(testJob, packageJob);

        when(jobRepository.findById(compileJob.getId())).thenReturn(Optional.of(compileJob));
        when(jobDependencyRepository.findByParentJobId(compileJob.getId())).thenReturn(List.of(dep1));
        when(jobDependencyRepository.findByParentJobId(testJob.getId())).thenReturn(List.of(dep2));
        when(jobDependencyRepository.findByParentJobId(packageJob.getId())).thenReturn(Collections.emptyList());
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberDesc(compileJob.getId())).thenReturn(Collections.emptyList());
        when(jobRepository.findByPipelineId(pipeline.getId())).thenReturn(List.of(compileJob, testJob, packageJob));

        jobStateTransitionService.handleJobCompleted(compileJob.getId(), claimToken, false, "Syntax error in App.java");

        assertThat(compileJob.getState()).isEqualTo(JobState.FAILED);
        assertThat(testJob.getState()).isEqualTo(JobState.SKIPPED);
        assertThat(packageJob.getState()).isEqualTo(JobState.SKIPPED);
        assertThat(pipeline.getStatus()).isEqualTo(PipelineStatus.FAILED);

        verify(pipelineRepository).save(pipeline);
    }

    @Test
    @DisplayName("Claim token mismatch throws SecurityException")
    void testInvalidTokenThrowsSecurityException() {
        JobEntity job = new JobEntity();
        job.setId(UUID.randomUUID());
        job.setClaimToken("correct-token");
        job.setLeaseExpiresAt(java.time.Instant.now().plusSeconds(300));
        job.setState(JobState.RUNNING);

        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> jobStateTransitionService.handleJobCompleted(job.getId(), "wrong-token", true, null))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Invalid or expired claim token");
    }
}
