package com.buildhive.server.service;

import com.buildhive.server.dto.DashboardMetricsDto;
import com.buildhive.server.entity.JobEntity;
import com.buildhive.server.entity.PipelineEntity;
import com.buildhive.server.entity.SubmissionEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service responsible for instantiating and orchestrating build pipeline execution DAGs.
 */
public interface PipelineService {

    /**
     * Creates and initializes a new build pipeline execution graph for a validated project submission.
     * Persists all 7 jobs and their dependency relationships according to the default Maven pipeline blueprint.
     *
     * @param submission the persisted project submission
     * @return the created PipelineEntity in RUNNING state
     */
    PipelineEntity createPipelineForSubmission(SubmissionEntity submission);

    /**
     * Retrieves all pipelines ordered by creation date descending.
     */
    List<PipelineEntity> getAllPipelines();

    /**
     * Aggregates platform dashboard metrics.
     */
    DashboardMetricsDto getMetrics();

    /**
     * Retrieves a pipeline by ID.
     */
    Optional<PipelineEntity> getPipeline(UUID pipelineId);

    /**
     * Retrieves all jobs belonging to a pipeline.
     */
    List<JobEntity> getPipelineJobs(UUID pipelineId);

    /**
     * Requests cancellation of a pipeline and cancels any non-terminal jobs.
     */
    void cancelPipeline(UUID pipelineId);
}
