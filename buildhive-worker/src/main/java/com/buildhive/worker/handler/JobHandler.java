package com.buildhive.worker.handler;

import com.buildhive.common.model.JobType;
import com.buildhive.worker.dto.JobExecutionResult;

/**
 * Strategy interface implemented by handlers for specific job stages.
 */
public interface JobHandler {

    /**
     * Checks if this handler supports the given job type.
     */
    boolean supports(JobType type);

    /**
     * Executes the job stage within the provided workspace context.
     */
    JobExecutionResult execute(JobContext context);
}
