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
 * Handler for COMPILATION: compiles Java source code using Maven compiler.
 * Compilation errors are non-retryable USER_CODE_ERROR.
 */
@Component
public class CompilationHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(CompilationHandler.class);

    @Override
    public boolean supports(JobType type) {
        return type == JobType.COMPILATION;
    }

    @Override
    public JobExecutionResult execute(JobContext context) {
        log.info("Executing Maven compile for job {}", context.job().jobId());

        ProcessResult result = context.mavenRunner().runMavenCommand(
                context.job().pipelineId(),
                context.job().jobId(),
                context.projectRoot(),
                List.of("compile")
        );

        if (result.isSuccess()) {
            return JobExecutionResult.successful();
        }

        if (result.timedOut()) {
            return JobExecutionResult.failure(
                    result.exitCode(),
                    "Compilation process timed out",
                    FailureClassification.INFRASTRUCTURE_ERROR
            );
        }

        return JobExecutionResult.failure(
                result.exitCode(),
                "Compilation failed with exit code " + result.exitCode() + ". See build logs for details.",
                FailureClassification.USER_CODE_ERROR
        );
    }
}
