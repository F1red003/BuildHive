package com.buildhive.server.integration;

import com.buildhive.common.model.JobState;
import com.buildhive.common.model.JobType;
import com.buildhive.common.model.PipelineStatus;
import com.buildhive.server.dto.JobClaimResponseDto;
import com.buildhive.server.entity.*;
import com.buildhive.server.repository.*;
import com.buildhive.server.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineTransitionConcurrencyTest extends BasePostgresIntegrationTest {
    @Autowired PipelineService pipelines;
    @Autowired JobClaimingService claims;
    @Autowired JobStateTransitionService transitions;
    @Autowired ProjectRepository projects;
    @Autowired SubmissionRepository submissions;
    @Autowired PipelineRepository pipelineRepository;
    @Autowired JobRepository jobs;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void concurrentBranchesUnlockPackagingAndFinishPipeline() throws Exception {
        ProjectEntity project = projects.save(new ProjectEntity("parallel-" + UUID.randomUUID()));
        SubmissionEntity submission = submissions.save(new SubmissionEntity(project, "/test.zip", "a".repeat(64)));
        PipelineEntity pipeline = pipelines.createPipelineForSubmission(submission);
        for (int i = 0; i < 3; i++) {
            complete(claims.claimNextJob("worker", Duration.ofMinutes(5)).orElseThrow());
        }
        JobClaimResponseDto first = claims.claimNextJob("first", Duration.ofMinutes(5)).orElseThrow();
        JobClaimResponseDto second = claims.claimNextJob("second", Duration.ofMinutes(5)).orElseThrow();
        CountDownLatch firstUpdated = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> firstCompletion = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        complete(first);
                        firstUpdated.countDown();
                        await(allowCommit);
                    }));
            assertThat(firstUpdated.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> secondCompletion = executor.submit(() -> {
                secondStarted.countDown();
                complete(second);
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                // The old implementation can finish against the first branch's uncommitted state.
                secondCompletion.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException expectedWhilePipelineIsLocked) {
                // The corrected implementation waits for the first transaction to commit.
            } finally {
                allowCommit.countDown();
            }
            firstCompletion.get(10, TimeUnit.SECONDS);
            secondCompletion.get(10, TimeUnit.SECONDS);
        } finally {
            allowCommit.countDown();
        }
        assertThat(jobs.findByPipelineIdAndState(pipeline.getId(), JobState.READY))
                .extracting(JobEntity::getJobType).containsExactly(JobType.PACKAGING);
        complete(claims.claimNextJob("worker", Duration.ofMinutes(5)).orElseThrow());
        complete(claims.claimNextJob("worker", Duration.ofMinutes(5)).orElseThrow());
        assertThat(pipelineRepository.findById(pipeline.getId()).orElseThrow().getStatus())
                .isEqualTo(PipelineStatus.SUCCEEDED);
    }

    private void complete(JobClaimResponseDto claim) {
        transitions.handleJobCompleted(claim.jobId(), claim.claimToken(), true, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for transaction release");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
