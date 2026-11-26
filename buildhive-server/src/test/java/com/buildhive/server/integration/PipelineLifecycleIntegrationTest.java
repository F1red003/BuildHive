package com.buildhive.server.integration;

import com.buildhive.common.model.JobState;
import com.buildhive.common.model.JobType;
import com.buildhive.common.model.PipelineStatus;
import com.buildhive.server.dto.JobClaimResponseDto;
import com.buildhive.server.entity.JobEntity;
import com.buildhive.server.entity.PipelineEntity;
import com.buildhive.server.entity.ProjectEntity;
import com.buildhive.server.entity.SubmissionEntity;
import com.buildhive.server.repository.*;
import com.buildhive.server.service.JobClaimingService;
import com.buildhive.server.service.JobStateTransitionService;
import com.buildhive.server.service.PipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end database integration test simulating all 7 stages of a pipeline DAG lifecycle
 * against a real PostgreSQL 18 container.
 */
class PipelineLifecycleIntegrationTest extends BasePostgresIntegrationTest {

    @Autowired
    private PipelineService pipelineService;

    @Autowired
    private JobClaimingService jobClaimingService;

    @Autowired
    private JobStateTransitionService jobStateTransitionService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobAttemptRepository jobAttemptRepository;

    @Autowired
    private JobDependencyRepository jobDependencyRepository;

    @Autowired
    private PipelineRepository pipelineRepository;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @BeforeEach
    void setUp() {
        jobAttemptRepository.deleteAll();
        jobDependencyRepository.deleteAll();
        jobRepository.deleteAll();
        pipelineRepository.deleteAll();
        submissionRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    @DisplayName("Complete 7-stage pipeline execution: parallel branches unlock and pipeline reaches SUCCEEDED")
    void testFullPipelineLifecycle() {
        // 1. Arrange: Create submission and instantiate 7-job DAG
        ProjectEntity project = projectRepository.save(new ProjectEntity("lifecycle-test-project"));
        SubmissionEntity submission = submissionRepository.save(
                new SubmissionEntity(project, "/uploads/lifecycle-app.zip", "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789")
        );

        PipelineEntity pipeline = pipelineService.createPipelineForSubmission(submission);
        assertThat(pipeline.getStatus()).isEqualTo(PipelineStatus.RUNNING);

        List<JobEntity> initialJobs = jobRepository.findByPipelineId(pipeline.getId());
        assertThat(initialJobs).hasSize(7);

        // 2. Stage 1: PROJECT_VALIDATION is the sole READY job
        JobClaimResponseDto claim1 = jobClaimingService.claimNextJob("worker-1", Duration.ofMinutes(5))
                .orElseThrow(() -> new IllegalStateException("PROJECT_VALIDATION should be claimable"));
        assertThat(claim1.jobType()).isEqualTo(JobType.PROJECT_VALIDATION);
        jobStateTransitionService.handleJobCompleted(claim1.jobId(), claim1.claimToken(), true, null);

        // 3. Stage 2: DEPENDENCY_RESOLUTION unlocks to READY
        JobClaimResponseDto claim2 = jobClaimingService.claimNextJob("worker-1", Duration.ofMinutes(5))
                .orElseThrow(() -> new IllegalStateException("DEPENDENCY_RESOLUTION should be claimable"));
        assertThat(claim2.jobType()).isEqualTo(JobType.DEPENDENCY_RESOLUTION);
        jobStateTransitionService.handleJobCompleted(claim2.jobId(), claim2.claimToken(), true, null);

        // 4. Stage 3: COMPILATION unlocks to READY
        JobClaimResponseDto claim3 = jobClaimingService.claimNextJob("worker-1", Duration.ofMinutes(5))
                .orElseThrow(() -> new IllegalStateException("COMPILATION should be claimable"));
        assertThat(claim3.jobType()).isEqualTo(JobType.COMPILATION);
        jobStateTransitionService.handleJobCompleted(claim3.jobId(), claim3.claimToken(), true, null);

        // 5. Verification: Both UNIT_TESTS and STATIC_ANALYSIS must be READY simultaneously!
        List<JobEntity> parallelReadyJobs = jobRepository.findByPipelineIdAndState(pipeline.getId(), JobState.READY);
        assertThat(parallelReadyJobs)
                .as("UNIT_TESTS and STATIC_ANALYSIS must run in parallel and be READY concurrently")
                .hasSize(2)
                .extracting(JobEntity::getJobType)
                .containsExactlyInAnyOrder(JobType.UNIT_TESTS, JobType.STATIC_ANALYSIS);

        // 6. Claim and complete first parallel branch (e.g. UNIT_TESTS)
        JobClaimResponseDto claim4a = jobClaimingService.claimNextJob("worker-1", Duration.ofMinutes(5)).orElseThrow();
        jobStateTransitionService.handleJobCompleted(claim4a.jobId(), claim4a.claimToken(), true, null);

        // Verification: PACKAGING must remain BLOCKED because STATIC_ANALYSIS has not finished yet
        JobEntity packagingJob = getJobByType(pipeline.getId(), JobType.PACKAGING);
        assertThat(packagingJob.getState())
                .as("PACKAGING must remain BLOCKED until BOTH unit tests and static analysis succeed")
                .isEqualTo(JobState.BLOCKED);

        // 7. Claim and complete second parallel branch (STATIC_ANALYSIS)
        JobClaimResponseDto claim4b = jobClaimingService.claimNextJob("worker-2", Duration.ofMinutes(5)).orElseThrow();
        jobStateTransitionService.handleJobCompleted(claim4b.jobId(), claim4b.claimToken(), true, null);

        // Verification: Now that both prerequisite parents succeeded, PACKAGING must transition to READY!
        packagingJob = getJobByType(pipeline.getId(), JobType.PACKAGING);
        assertThat(packagingJob.getState())
                .as("PACKAGING must become READY once all parents have SUCCEEDED")
                .isEqualTo(JobState.READY);

        // 8. Stage 6: PACKAGING runs
        JobClaimResponseDto claim5 = jobClaimingService.claimNextJob("worker-1", Duration.ofMinutes(5)).orElseThrow();
        assertThat(claim5.jobType()).isEqualTo(JobType.PACKAGING);
        jobStateTransitionService.handleJobCompleted(claim5.jobId(), claim5.claimToken(), true, null);

        // 9. Stage 7: REPORT_GENERATION unlocks and runs
        JobEntity reportJob = getJobByType(pipeline.getId(), JobType.REPORT_GENERATION);
        assertThat(reportJob.getState()).isEqualTo(JobState.READY);

        JobClaimResponseDto claim6 = jobClaimingService.claimNextJob("worker-1", Duration.ofMinutes(5)).orElseThrow();
        assertThat(claim6.jobType()).isEqualTo(JobType.REPORT_GENERATION);
        jobStateTransitionService.handleJobCompleted(claim6.jobId(), claim6.claimToken(), true, null);

        // 10. Final verification: Entire pipeline terminates with SUCCEEDED!
        PipelineEntity finalPipeline = pipelineRepository.findById(pipeline.getId()).orElseThrow();
        assertThat(finalPipeline.getStatus())
                .as("Overall pipeline must transition to SUCCEEDED")
                .isEqualTo(PipelineStatus.SUCCEEDED);
        assertThat(finalPipeline.getCompletedAt()).isNotNull();

        List<JobEntity> allJobs = jobRepository.findByPipelineId(pipeline.getId());
        assertThat(allJobs)
                .as("All 7 jobs in pipeline must be in SUCCEEDED state")
                .allMatch(j -> j.getState() == JobState.SUCCEEDED);
    }

    private JobEntity getJobByType(UUID pipelineId, JobType jobType) {
        return jobRepository.findByPipelineId(pipelineId).stream()
                .filter(j -> j.getJobType() == jobType)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Job with type " + jobType + " not found"));
    }
}
