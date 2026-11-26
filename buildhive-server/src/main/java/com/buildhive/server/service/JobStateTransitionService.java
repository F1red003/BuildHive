package com.buildhive.server.service;

import com.buildhive.common.model.FailureClassification;
import com.buildhive.common.model.JobState;
import com.buildhive.common.model.PipelineStatus;
import com.buildhive.server.entity.JobAttemptEntity;
import com.buildhive.server.entity.JobDependencyEntity;
import com.buildhive.server.entity.JobEntity;
import com.buildhive.server.entity.PipelineEntity;
import com.buildhive.server.repository.JobAttemptRepository;
import com.buildhive.server.repository.JobDependencyRepository;
import com.buildhive.server.repository.JobRepository;
import com.buildhive.server.repository.PipelineRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Orchestrates job state transitions, prerequisite dependency evaluation,
 * failure cascades (SKIPPED), and overall pipeline finalization.
 */
@Service
public class JobStateTransitionService {

    private final JobRepository jobRepository;
    private final JobDependencyRepository jobDependencyRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final PipelineRepository pipelineRepository;
    private final LogBroadcastService logBroadcastService;

    public JobStateTransitionService(
            JobRepository jobRepository,
            JobDependencyRepository jobDependencyRepository,
            JobAttemptRepository jobAttemptRepository,
            PipelineRepository pipelineRepository
    ) {
        this(jobRepository, jobDependencyRepository, jobAttemptRepository, pipelineRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JobStateTransitionService(
            JobRepository jobRepository,
            JobDependencyRepository jobDependencyRepository,
            JobAttemptRepository jobAttemptRepository,
            PipelineRepository pipelineRepository,
            @org.springframework.lang.Nullable LogBroadcastService logBroadcastService
    ) {
        this.jobRepository = jobRepository;
        this.jobDependencyRepository = jobDependencyRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.pipelineRepository = pipelineRepository;
        this.logBroadcastService = logBroadcastService;
    }

    /**
     * Handles the completion of a job reported by a worker.
     */
    @Transactional
    public void handleJobCompleted(UUID jobId, String claimToken, boolean success, String failureReason) {
        handleJobCompleted(jobId, claimToken, success, failureReason, null, null);
    }

    /**
     * Handles the completion of a job reported by a worker with optional exit code and failure classification.
     *
     * @param jobId                 ID of the completed job
     * @param claimToken            active lease claim token to verify authenticity
     * @param success               true if the job succeeded
     * @param failureReason         description of failure if unsuccessful
     * @param exitCode              process exit code if applicable
     * @param failureClassification classification of failure (infrastructure vs code error)
     */
    @Transactional
    public void handleJobCompleted(
            UUID jobId,
            String claimToken,
            boolean success,
            String failureReason,
            Integer exitCode,
            FailureClassification failureClassification
    ) {
        handleJobCompleted(jobId, claimToken, success, failureReason, exitCode, failureClassification, null);
    }

    @Transactional
    public void handleJobCompleted(UUID jobId, String claimToken, boolean success, String failureReason,
                                   Integer exitCode, FailureClassification failureClassification, String workerId) {
        pipelineRepository.lockForJob(jobId);
        JobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        List<JobAttemptEntity> attempts = jobAttemptRepository.findByJobIdOrderByAttemptNumberDesc(jobId);
        FailureClassification classification = success ? null : (failureClassification != null
                ? failureClassification : job.getJobType().getDefaultFailureClassification());
        JobAttemptEntity matchingAttempt = attempts.stream()
                .filter(attempt -> Objects.equals(claimToken, attempt.getClaimToken()))
                .findFirst().orElse(null);

        // A lost acknowledgement may arrive after a newer attempt has already been claimed.
        if (matchingAttempt != null && matchingAttempt.getCompletedAt() != null) {
            if (workerId != null && !workerId.equals(matchingAttempt.getWorkerId())) {
                throw new SecurityException("Worker ID mismatch for completed attempt");
            }
            if (matchingAttempt.getState() == (success ? JobState.SUCCEEDED : JobState.FAILED)
                    && Objects.equals(exitCode, matchingAttempt.getExitCode())
                    && Objects.equals(failureReason, matchingAttempt.getFailureReason())
                    && Objects.equals(classification, matchingAttempt.getFailureClassification())) {
                return;
            }
            throw new IllegalStateException("Conflicting completion for an already completed attempt");
        }
        if (job.getClaimToken() == null || !job.getClaimToken().equals(claimToken)) {
            throw new SecurityException("Invalid or expired claim token for job " + jobId);
        }
        if (workerId != null && !workerId.equals(job.getWorkerId())) {
            throw new SecurityException("Worker ID mismatch for claimed job " + jobId);
        }
        if (job.getState().isTerminal()) {
            throw new IllegalStateException("Job " + jobId + " is already terminal");
        }
        Instant now = Instant.now();
        if ((job.getState() != JobState.CLAIMED && job.getState() != JobState.RUNNING)
                || job.getLeaseExpiresAt() == null || !job.getLeaseExpiresAt().isAfter(now)) {
            throw new SecurityException("Invalid state or expired job lease");
        }

        if (!attempts.isEmpty()) {
            JobAttemptEntity latestAttempt = attempts.getFirst();
            latestAttempt.setCompletedAt(now);
            latestAttempt.setState(success ? JobState.SUCCEEDED : JobState.FAILED);
            latestAttempt.setExitCode(exitCode);
            latestAttempt.setFailureReason(failureReason);
            latestAttempt.setFailureClassification(classification);
            jobAttemptRepository.save(latestAttempt);
        }

        if (success) {
            // 3. Mark job as SUCCEEDED
            job.setState(JobState.SUCCEEDED);
            job.setCompletedAt(now);
            jobRepository.save(job);

            // 4. Evaluate child dependencies: if all parent dependencies succeeded, transition child to READY
            evaluateAndUnlockChildren(job);
        } else if (classification.isRetryable() && job.getRetryCount() < job.getMaxRetries()) {
            job.scheduleRetry(now);
            jobRepository.save(job);
        } else {
            // 5. Mark job as FAILED
            job.setState(JobState.FAILED);
            job.setCompletedAt(now);
            job.setFailureClassification(classification);
            jobRepository.save(job);

            // 6. Cascade SKIPPED state to all downstream dependent descendants
            cascadeSkippedState(job);
        }

        // 7. Check if all pipeline jobs have completed and finalize pipeline status
        checkAndFinalizePipeline(job.getPipeline());

        // 8. Close active SSE stream for this job if open
        if (job.getState().isTerminal()) {
            completeStreamAfterCommit(jobId);
        }
    }

    /**
     * Handles job failure due to lease expiration or permanent errors where retries are exhausted.
     * Transitions the job to FAILED, cascades SKIPPED to downstream jobs, and updates overall pipeline status.
     *
     * @param job            the failed job entity
     * @param classification failure classification (e.g. INFRASTRUCTURE_ERROR)
     */
    @Transactional
    public void handleJobFailureAndCascade(JobEntity job, FailureClassification classification) {
        Instant now = Instant.now();
        job.setState(JobState.FAILED);
        job.setCompletedAt(now);
        job.setFailureClassification(classification != null ? classification : FailureClassification.INFRASTRUCTURE_ERROR);
        jobRepository.save(job);

        cascadeSkippedState(job);
        checkAndFinalizePipeline(job.getPipeline());

        completeStreamAfterCommit(job.getId());
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PipelineReportService reports;

    private void completeStreamAfterCommit(UUID jobId) {
        if (logBroadcastService == null || jobId == null) {
            return;
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            logBroadcastService.completeStream(jobId);
                        }
                    });
        } else {
            logBroadcastService.completeStream(jobId);
        }
    }

    /**
     * Evaluates all direct child jobs of a completed parent. If ALL prerequisite parents for a child
     * have SUCCEEDED, the child transitions from BLOCKED to READY.
     */
    void evaluateAndUnlockChildren(JobEntity parentJob) {
        List<JobDependencyEntity> outgoingDeps = jobDependencyRepository.findByParentJobId(parentJob.getId());

        for (JobDependencyEntity dep : outgoingDeps) {
            JobEntity child = dep.getChildJob();

            if (child.getState() == JobState.BLOCKED) {
                List<JobDependencyEntity> incomingDeps = jobDependencyRepository.findByChildJobId(child.getId());
                boolean allParentsSucceeded = incomingDeps.stream()
                        .allMatch(d -> d.getParentJob().getState() == JobState.SUCCEEDED);

                if (allParentsSucceeded) {
                    child.setState(JobState.READY);
                    jobRepository.save(child);
                }
            }
        }
    }

    /**
     * Cascades the SKIPPED state to all downstream descendants of a failed job using BFS.
     */
    void cascadeSkippedState(JobEntity failedJob) {
        Queue<JobEntity> queue = new ArrayDeque<>();
        queue.add(failedJob);

        Instant now = Instant.now();

        while (!queue.isEmpty()) {
            JobEntity current = queue.poll();
            List<JobDependencyEntity> outgoing = jobDependencyRepository.findByParentJobId(current.getId());

            for (JobDependencyEntity dep : outgoing) {
                JobEntity child = dep.getChildJob();

                if (child.getState() == JobState.BLOCKED) {
                    child.setState(JobState.SKIPPED);
                    child.setCompletedAt(now);
                    jobRepository.save(child);
                    queue.add(child);
                }
            }
        }
    }

    /**
     * Checks if all jobs in the pipeline are in a terminal state (SUCCEEDED, FAILED, SKIPPED, CANCELLED).
     * If so, sets the pipeline's overall status to SUCCEEDED or FAILED.
     */
    void checkAndFinalizePipeline(PipelineEntity pipeline) {
        if (pipeline == null) {
            return;
        }
        List<JobEntity> allJobs = jobRepository.findByPipelineId(pipeline.getId());
        boolean allTerminal = allJobs.stream().allMatch(j -> j.getState().isTerminal());

        if (allTerminal && !pipeline.getStatus().isTerminal()) {
            boolean anyFailed = allJobs.stream().anyMatch(j -> j.getState() == JobState.FAILED);
            boolean anyCancelled = allJobs.stream().anyMatch(j -> j.getState() == JobState.CANCELLED);

            if (anyCancelled) {
                pipeline.setStatus(PipelineStatus.CANCELLED);
            } else if (anyFailed) {
                pipeline.setStatus(PipelineStatus.FAILED);
            } else {
                pipeline.setStatus(PipelineStatus.SUCCEEDED);
            }
            pipeline.setCompletedAt(Instant.now());
            pipelineRepository.save(pipeline);
            if (reports != null) reports.publish(pipeline);
        }
    }
}
