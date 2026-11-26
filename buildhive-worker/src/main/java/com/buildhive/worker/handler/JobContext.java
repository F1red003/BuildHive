package com.buildhive.worker.handler;

import com.buildhive.worker.client.WorkerClient;
import com.buildhive.worker.dto.JobClaimDto;
import com.buildhive.worker.executor.MavenProcessRunner;

import java.nio.file.Path;

/**
 * Execution context provided to JobHandlers containing job details, workspace paths,
 * process runner, and server coordination client.
 */
public record JobContext(
        String workerId,
        JobClaimDto job,
        Path workspaceDir,
        Path projectRoot,
        MavenProcessRunner mavenRunner,
        WorkerClient workerClient
) {}
