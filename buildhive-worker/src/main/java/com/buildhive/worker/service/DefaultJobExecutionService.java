package com.buildhive.worker.service;

import com.buildhive.common.model.FailureClassification;
import com.buildhive.worker.client.WorkerClient;
import com.buildhive.worker.dto.JobClaimDto;
import com.buildhive.worker.dto.JobExecutionResult;
import com.buildhive.worker.executor.MavenProcessRunner;
import com.buildhive.worker.handler.JobContext;
import com.buildhive.worker.handler.JobHandler;
import com.buildhive.worker.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Standard implementation of JobExecutionService dispatching claimed jobs to registered JobHandlers.
 */
@Service
public class DefaultJobExecutionService implements JobExecutionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultJobExecutionService.class);

    private final List<JobHandler> handlers;
    private final WorkspaceManager workspaceManager;
    private final MavenProcessRunner mavenRunner;
    private final WorkerClient workerClient;

    public DefaultJobExecutionService(
            List<JobHandler> handlers,
            WorkspaceManager workspaceManager,
            MavenProcessRunner mavenRunner,
            WorkerClient workerClient
    ) {
        this.handlers = handlers;
        this.workspaceManager = workspaceManager;
        this.mavenRunner = mavenRunner;
        this.workerClient = workerClient;
    }

    @Override
    public JobExecutionResult executeJob(JobClaimDto job, String workerId) {
        try (var attemptLog = mavenRunner.openAttempt(job.pipelineId(), job.jobId(), job.attemptNumber())) {
            return executeAttempt(job, workerId);
        } catch (Exception failure) {
            return JobExecutionResult.failure("Attempt execution or log capture failed: " + failure.getMessage(),
                    FailureClassification.INFRASTRUCTURE_ERROR);
        }
    }

    private JobExecutionResult executeAttempt(JobClaimDto job, String workerId) {
        log.info("Worker '{}' executing job {} [{}] (attempt {})",
                workerId, job.jobId(), job.jobType(), job.attemptNumber());

        // 1. Locate archive
        Path archivePath = resolveArchivePath(job);
        if (archivePath == null || !Files.exists(archivePath)) {
            return JobExecutionResult.failure(
                    "Project archive file not found at " + (archivePath != null ? archivePath : job.archivePath()),
                    FailureClassification.INFRASTRUCTURE_ERROR
            );
        }

        // 2. Find matching handler
        JobHandler handler = handlers.stream()
                .filter(h -> h.supports(job.jobType()))
                .findFirst()
                .orElse(null);

        if (handler == null) {
            log.error("No JobHandler registered for JobType {}", job.jobType());
            return JobExecutionResult.failure(
                    "No handler found for stage " + job.jobType(),
                    FailureClassification.INFRASTRUCTURE_ERROR
            );
        }

        // 3. Create isolated workspace and extract archive
        Path workspaceDir;
        Path projectRoot;
        try {
            workspaceDir = workspaceManager.createWorkspace(job.pipelineId(), job.jobId(), job.attemptNumber());
            projectRoot = workspaceManager.extractArchive(archivePath, workspaceDir);
        } catch (IOException e) {
            log.error("Failed to prepare workspace for job {}: {}", job.jobId(), e.getMessage(), e);
            return JobExecutionResult.failure(
                    "Failed to prepare workspace or extract archive: " + e.getMessage(),
                    FailureClassification.INFRASTRUCTURE_ERROR
            );
        }

        // 4. Construct context and execute handler
        JobContext context = new JobContext(
                workerId,
                job,
                workspaceDir,
                projectRoot,
                mavenRunner,
                workerClient
        );

        try {
            return handler.execute(context);
        } catch (Exception e) {
            log.error("Unhandled exception executing handler for job {}: {}", job.jobId(), e.getMessage(), e);
            return JobExecutionResult.failure(
                    "Unhandled exception during stage execution: " + e.getMessage(),
                    FailureClassification.INFRASTRUCTURE_ERROR
            );
        }
    }

    private Path resolveArchivePath(JobClaimDto job) {
        if (job.archivePath() != null && !job.archivePath().isBlank()) {
            Path path = Paths.get(job.archivePath());
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }
}
