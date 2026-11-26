package com.buildhive.worker.handler;

import com.buildhive.common.model.FailureClassification;
import com.buildhive.common.model.JobType;
import com.buildhive.worker.dto.JobExecutionResult;
import com.buildhive.worker.executor.ProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Handler for DEPENDENCY_RESOLUTION: downloads and caches declared project dependencies.
 * Failures at this stage are classified as retryable INFRASTRUCTURE_ERROR.
 */
@Component
public class DependencyResolutionHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(DependencyResolutionHandler.class);

    @Override
    public boolean supports(JobType type) {
        return type == JobType.DEPENDENCY_RESOLUTION;
    }

    @Override
    public JobExecutionResult execute(JobContext context) {
        log.info("Resolving Maven dependencies for job {}", context.job().jobId());

        ProcessResult result = context.mavenRunner().runMavenCommand(
                context.job().pipelineId(),
                context.job().jobId(),
                context.projectRoot(),
                List.of("dependency:resolve", "-DincludeScope=test")
        );

        if (result.isSuccess()) {
            return JobExecutionResult.successful();
        }

        if (result.timedOut()) {
            return JobExecutionResult.failure(
                    result.exitCode(),
                    "Dependency resolution timed out",
                    FailureClassification.INFRASTRUCTURE_ERROR
            );
        }

        return JobExecutionResult.failure(
                result.exitCode(),
                "Dependency resolution failed with exit code " + result.exitCode(),
                FailureClassification.INFRASTRUCTURE_ERROR
        );
    }
}
