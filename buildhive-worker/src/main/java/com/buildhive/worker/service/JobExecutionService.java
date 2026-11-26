package com.buildhive.worker.service;

import com.buildhive.worker.dto.JobClaimDto;
import com.buildhive.worker.dto.JobExecutionResult;

/**
 * Service responsible for orchestrating the execution of a claimed build job:
 * setting up workspaces, unpacking archives, and invoking the appropriate JobHandler.
 */
public interface JobExecutionService {

    default JobExecutionResult executeJob(JobClaimDto job) {
        return executeJob(job, "worker");
    }

    JobExecutionResult executeJob(JobClaimDto job, String workerId);
}
