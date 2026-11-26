package com.buildhive.worker.handler;

import com.buildhive.common.model.JobType;
import com.buildhive.worker.dto.JobExecutionResult;
import org.springframework.stereotype.Component;

/** This DAG stage completes; the server publishes the final report from committed results. */
@Component
public class ReportGenerationHandler implements JobHandler {
    @Override public boolean supports(JobType type) { return type == JobType.REPORT_GENERATION; }
    @Override public JobExecutionResult execute(JobContext context) { return JobExecutionResult.successful(); }
}
