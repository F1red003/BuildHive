package com.buildhive.server.integration;

import com.buildhive.common.model.JobState;
import com.buildhive.common.model.PipelineStatus;
import com.buildhive.server.entity.*;
import com.buildhive.server.repository.*;
import com.buildhive.server.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchedulerRecoveryIntegrationTest extends BasePostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PipelineService pipelines;
    @Autowired JobClaimingService claims;
    @Autowired LeaseReaperService reaper;
    @Autowired ProjectRepository projects;
    @Autowired SubmissionRepository submissions;
    @Autowired PipelineRepository pipelineRepository;
    @Autowired JobRepository jobs;
    @Autowired JobAttemptRepository attempts;
    @Autowired WorkerRepository workers;
    @Autowired JobStateTransitionService transitions;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE projects, workers CASCADE");
    }

    @Test
    void scheduledEntryPointFinalizesAnExhaustedLease() {
        ProjectEntity project = projects.save(new ProjectEntity("recovery"));
        SubmissionEntity submission = submissions.save(new SubmissionEntity(project, "/test.zip", "a".repeat(64)));
        PipelineEntity pipeline = pipelines.createPipelineForSubmission(submission);
        var claim = claims.claimNextJob("worker", Duration.ofMinutes(5)).orElseThrow();
        JobEntity job = jobs.findById(claim.jobId()).orElseThrow();
        job.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        job.setRetryCount(job.getMaxRetries());
        jobs.save(job);

        reaper.reapExpiredLeases();

        assertThat(pipelineRepository.findById(pipeline.getId()).orElseThrow().getStatus())
                .isEqualTo(PipelineStatus.FAILED);
        assertThat(jobs.findById(job.getId()).orElseThrow().getState()).isEqualTo(JobState.FAILED);
        assertThat(attempts.findByJobIdOrderByAttemptNumberDesc(job.getId()).getFirst().getCompletedAt())
                .isNotNull();
        assertThat(jobs.findByPipelineId(pipeline.getId())).allMatch(j -> j.getState().isTerminal());
    }

    private com.buildhive.server.dto.JobClaimResponseDto claim() {
        ProjectEntity project = projects.save(new ProjectEntity("attempt-" + java.util.UUID.randomUUID()));
        var submission = submissions.save(new SubmissionEntity(project, "/test.zip", "a".repeat(64)));
        pipelines.createPipelineForSubmission(submission);
        return claims.claimNextJob("worker", Duration.ofMinutes(5)).orElseThrow();
    }

    @Test
    void completionRejectsExpiredLeaseAndOldTokenAfterReclaim() {
        var first = claim();
        JobEntity job = jobs.findById(first.jobId()).orElseThrow();
        job.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        jobs.save(job);
        assertThatThrownBy(() -> transitions.handleJobCompleted(first.jobId(), first.claimToken(), true, null))
                .isInstanceOf(SecurityException.class);
        reaper.reapExpiredLeases();
        assertThat(claims.claimNextJob("replacement", Duration.ofMinutes(5))).isEmpty();
        jdbc.update("UPDATE jobs SET available_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", first.jobId());
        var second = claims.claimNextJob("replacement", Duration.ofMinutes(5)).orElseThrow();
        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThatThrownBy(() -> transitions.handleJobCompleted(first.jobId(), first.claimToken(), true, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attempts.findByJobIdOrderByAttemptNumberDesc(first.jobId())).hasSize(2);
        assertThat(jobs.findById(first.jobId()).orElseThrow().getClaimToken()).isEqualTo(second.claimToken());
    }

    @Test
    void identicalCompletionCanBeRetriedButConflictingCompletionIsRejected() {
        var job = claim();
        transitions.handleJobCompleted(job.jobId(), job.claimToken(), true, null, 0, null, "worker");
        transitions.handleJobCompleted(job.jobId(), job.claimToken(), true, null, 0, null, "worker");
        assertThatThrownBy(() -> transitions.handleJobCompleted(job.jobId(), job.claimToken(), false,
                "different", 1, null, "worker")).isInstanceOf(IllegalStateException.class);
        assertThat(attempts.findByJobIdOrderByAttemptNumberDesc(job.jobId())).hasSize(1);
        assertThat(jobs.findById(job.jobId()).orElseThrow().getState()).isEqualTo(JobState.SUCCEEDED);
    }

    @Test
    void cancellationClosesAttemptsAndRejectsLateCompletion() {
        var job = claim();
        pipelines.cancelPipeline(job.pipelineId());
        assertThatThrownBy(() -> transitions.handleJobCompleted(job.jobId(), job.claimToken(), true, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attempts.findByJobIdOrderByAttemptNumberDesc(job.jobId()).getFirst().getState())
                .isEqualTo(JobState.CANCELLED);
        assertThat(jobs.findByPipelineId(job.pipelineId())).allMatch(j -> j.getState() == JobState.CANCELLED);
        assertThat(claims.claimNextJob("worker", Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void completionRollbackLeavesJobAndAttemptUnchanged() {
        var job = claim();
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            transitions.handleJobCompleted(job.jobId(), job.claimToken(), true, null);
            status.setRollbackOnly();
        });
        assertThat(jobs.findById(job.jobId()).orElseThrow().getState()).isEqualTo(JobState.CLAIMED);
        var attempt = attempts.findByJobIdOrderByAttemptNumberDesc(job.jobId()).getFirst();
        assertThat(attempt.getState()).isEqualTo(JobState.CLAIMED);
        assertThat(attempt.getCompletedAt()).isNull();
    }

    @Test
    void completionRejectsDifferentWorker() {
        var job = claim();
        assertThatThrownBy(() -> transitions.handleJobCompleted(job.jobId(), job.claimToken(), true,
                null, 0, null, "other-worker")).isInstanceOf(SecurityException.class);
        assertThat(jobs.findById(job.jobId()).orElseThrow().getState()).isEqualTo(JobState.CLAIMED);
    }

    @Test
    void expiryWaitsForCompletionAndDoesNotRequeueItsCommittedResult() throws Exception {
        var job = claim();
        overlap(
                () -> transitions.handleJobCompleted(job.jobId(), job.claimToken(), true, null),
                () -> reaper.reapExpiredLeasesAt(Instant.now().plusSeconds(600)));
        assertThat(jobs.findById(job.jobId()).orElseThrow().getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(attempts.findByJobIdOrderByAttemptNumberDesc(job.jobId())).hasSize(1);
    }

    @Test
    void cancellationWaitsForCompletionAndClosesTheRemainingPipeline() throws Exception {
        var job = claim();
        overlap(
                () -> transitions.handleJobCompleted(job.jobId(), job.claimToken(), true, null),
                () -> pipelines.cancelPipeline(job.pipelineId()));
        assertThat(pipelineRepository.findById(job.pipelineId()).orElseThrow().getStatus())
                .isEqualTo(PipelineStatus.CANCELLED);
        assertThat(jobs.findById(job.jobId()).orElseThrow().getState()).isEqualTo(JobState.SUCCEEDED);
        assertThat(jobs.findByPipelineId(job.pipelineId())).allMatch(j -> j.getState().isTerminal());
    }

    @Test
    void idleWorkerBecomesUnresponsiveAndHeartbeatRestoresIt() {
        WorkerEntity stale = new WorkerEntity("idle", "idle", "host");
        stale.setLastHeartbeat(Instant.now().minusSeconds(120));
        workers.save(stale);
        workers.save(new WorkerEntity("fresh", "fresh", "host"));
        reaper.reapExpiredLeases();
        assertThat(workers.findById("idle").orElseThrow().getStatus()).isEqualTo("UNRESPONSIVE");
        assertThat(workers.findById("fresh").orElseThrow().getStatus()).isEqualTo("ACTIVE");
        workerApi.heartbeat("idle");
        assertThat(workers.findById("idle").orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Autowired com.buildhive.server.controller.WorkerApiController workerApi;

    private void overlap(Runnable first, Runnable second) throws Exception {
        var updated = new java.util.concurrent.CountDownLatch(1);
        var commit = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() ->
                    new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                            .executeWithoutResult(status -> {
                                first.run();
                                updated.countDown();
                                try {
                                    if (!commit.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("Commit gate timed out");
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                }
                            }));
            assertThat(updated.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var secondFuture = executor.submit(second);
            try {
                secondFuture.get(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException waitingForFirstCommit) {
                // Expected while the first transaction owns the pipeline lock.
            } finally {
                commit.countDown();
            }
            firstFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);
            secondFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            commit.countDown();
        }
    }

    @Test
    void infrastructureFailureRetriesWithBackoffAndPreservesOldAcknowledgements() {
        var first = claim();
        transitions.handleJobCompleted(first.jobId(), first.claimToken(), false, "network", 1,
                com.buildhive.common.model.FailureClassification.INFRASTRUCTURE_ERROR, "worker");
        assertThat(jobs.findById(first.jobId()).orElseThrow().getState()).isEqualTo(JobState.READY);
        assertThat(claims.claimNextJob("replacement", Duration.ofMinutes(5))).isEmpty();
        jdbc.update("UPDATE jobs SET available_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", first.jobId());
        var second = claims.claimNextJob("replacement", Duration.ofMinutes(5)).orElseThrow();
        assertThat(second.attemptNumber()).isEqualTo(2);
        transitions.handleJobCompleted(first.jobId(), first.claimToken(), false, "network", 1,
                com.buildhive.common.model.FailureClassification.INFRASTRUCTURE_ERROR, "worker");
        assertThat(jobs.findById(first.jobId()).orElseThrow().getClaimToken()).isEqualTo(second.claimToken());
        transitions.handleJobCompleted(second.jobId(), second.claimToken(), true, null, 0, null, "replacement");
        assertThat(attempts.findByJobIdOrderByAttemptNumberDesc(first.jobId())).hasSize(2);
    }

    @Test
    void exhaustedInfrastructureFailureIsTerminal() {
        var job = claim();
        var entity = jobs.findById(job.jobId()).orElseThrow();
        entity.setMaxRetries(1);
        jobs.save(entity);
        transitions.handleJobCompleted(job.jobId(), job.claimToken(), false, "network", 1,
                com.buildhive.common.model.FailureClassification.INFRASTRUCTURE_ERROR, "worker");
        assertThat(jobs.findById(job.jobId()).orElseThrow().getState()).isEqualTo(JobState.FAILED);
        assertThat(pipelineRepository.findById(job.pipelineId()).orElseThrow().getStatus()).isEqualTo(PipelineStatus.FAILED);
    }
}
