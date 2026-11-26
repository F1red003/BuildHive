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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates race-free, atomic job claiming with PostgreSQL FOR UPDATE SKIP LOCKED
 * under heavy multithreaded contention.
 */
class JobClaimingConcurrencyTest extends BasePostgresIntegrationTest {

    @Autowired
    private JobClaimingService jobClaimingService;

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
    @DisplayName("10 concurrent threads attempt to claim 1 READY job: exactly 1 wins, 9 receive empty, 0 deadlocks")
    void testConcurrentJobClaiming() throws Exception {
        // 1. Arrange: Insert 1 single job in READY state
        ProjectEntity project = projectRepository.save(new ProjectEntity("concurrency-test-project"));
        SubmissionEntity submission = submissionRepository.save(
                new SubmissionEntity(project, "/uploads/test.zip", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        );
        PipelineEntity pipeline = new PipelineEntity(submission);
        pipeline.setStatus(PipelineStatus.RUNNING);
        pipeline = pipelineRepository.save(pipeline);

        JobEntity job = new JobEntity(pipeline, JobType.PROJECT_VALIDATION, JobState.READY, 100);
        jobRepository.save(job);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);

        List<Optional<JobClaimResponseDto>> claimResults = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        // 2. Act: Dispatch 10 worker threads simultaneously
        for (int i = 0; i < threadCount; i++) {
            final String workerId = "worker-thread-" + i;
            executor.submit(() -> {
                try {
                    startGate.await();
                    Optional<JobClaimResponseDto> claim = jobClaimingService.claimNextJob(workerId, Duration.ofMinutes(5));
                    claimResults.add(claim);
                } catch (Throwable t) {
                    exceptions.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        // Release the latch so all 10 threads fire concurrently
        startGate.countDown();
        boolean completedInTime = doneGate.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        // 3. Assert
        assertThat(completedInTime).as("All worker threads must complete within timeout").isTrue();
        assertThat(exceptions).as("No database deadlocks or unhandled exceptions occurred").isEmpty();

        long successfulClaims = claimResults.stream().filter(Optional::isPresent).count();
        long emptyResults = claimResults.stream().filter(Optional::isEmpty).count();

        // Exactly ONE thread claimed the job
        assertThat(successfulClaims)
                .as("Exactly ONE worker must claim the single ready job")
                .isEqualTo(1);

        // Exactly 9 threads received empty result
        assertThat(emptyResults)
                .as("9 competing workers must receive empty without blocking or crashing")
                .isEqualTo(9);

        // Verify database state for the claimed job
        JobEntity claimedDbJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(claimedDbJob.getState()).isEqualTo(JobState.CLAIMED);
        assertThat(claimedDbJob.getWorkerId()).startsWith("worker-thread-");
        assertThat(claimedDbJob.getClaimToken()).isNotNull().isNotBlank();
        assertThat(claimedDbJob.getRetryCount()).isEqualTo(1);
        assertThat(claimedDbJob.getLeaseExpiresAt()).isNotNull();

        // Verify that attempt was registered in job_attempts table
        assertThat(jobAttemptRepository.findByJobIdOrderByAttemptNumberDesc(job.getId())).hasSize(1);
    }
}
