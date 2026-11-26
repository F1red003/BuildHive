package com.buildhive.server.service;

import com.buildhive.common.dag.DagGraph;
import com.buildhive.common.dag.PipelineBlueprint;
import com.buildhive.common.model.JobState;
import com.buildhive.common.model.JobType;
import com.buildhive.common.model.PipelineStatus;
import com.buildhive.server.dto.DashboardMetricsDto;
import com.buildhive.server.entity.JobDependencyEntity;
import com.buildhive.server.entity.JobEntity;
import com.buildhive.server.entity.PipelineEntity;
import com.buildhive.server.entity.SubmissionEntity;
import com.buildhive.server.repository.JobDependencyRepository;
import com.buildhive.server.repository.JobRepository;
import com.buildhive.server.repository.PipelineRepository;
import com.buildhive.server.repository.WorkerRepository;
import com.buildhive.server.repository.JobAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class PipelineServiceImpl implements PipelineService {

    private final PipelineRepository pipelineRepository;
    private final JobRepository jobRepository;
    private final JobDependencyRepository jobDependencyRepository;
    private final WorkerRepository workerRepository;
    private final JobAttemptRepository jobAttemptRepository;

    public PipelineServiceImpl(
            PipelineRepository pipelineRepository,
            JobRepository jobRepository,
            JobDependencyRepository jobDependencyRepository,
            WorkerRepository workerRepository,
            JobAttemptRepository jobAttemptRepository
    ) {
        this.pipelineRepository = pipelineRepository;
        this.jobRepository = jobRepository;
        this.jobDependencyRepository = jobDependencyRepository;
        this.workerRepository = workerRepository;
        this.jobAttemptRepository = jobAttemptRepository;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PipelineReportService reports;

    @Override
    @Transactional(readOnly = true)
    public List<PipelineEntity> getAllPipelines() {
        return pipelineRepository.findAllByOrderByCreatedAtDesc();
    }

    @Override
    @Transactional(readOnly = true)
    public DashboardMetricsDto getMetrics() {
        long totalPipelines = pipelineRepository.count();
        long runningJobs = jobRepository.countByState(JobState.RUNNING);
        long readyJobs = jobRepository.countByState(JobState.READY);
        long onlineWorkers = workerRepository.countByStatus("ACTIVE");
        return new DashboardMetricsDto(totalPipelines, runningJobs, readyJobs, onlineWorkers);
    }

    @Override
    @Transactional
    public PipelineEntity createPipelineForSubmission(SubmissionEntity submission) {
        // 1. Create pipeline entity in PENDING state
        PipelineEntity pipeline = new PipelineEntity(submission);
        pipeline.setStatus(PipelineStatus.RUNNING);
        pipeline.setStartedAt(Instant.now());
        pipeline = pipelineRepository.save(pipeline);

        // 2. Generate standard 7-stage DAG blueprint
        DagGraph<JobType> graph = PipelineBlueprint.createDefaultMavenPipeline();

        // 3. Persist all 7 jobs: root (PROJECT_VALIDATION) is READY, all others are BLOCKED
        Map<JobType, JobEntity> jobEntities = new EnumMap<>(JobType.class);
        for (JobType type : graph.getNodes()) {
            JobState initialState = (type == JobType.PROJECT_VALIDATION) ? JobState.READY : JobState.BLOCKED;
            JobEntity job = new JobEntity(pipeline, type, initialState, type.getPriority());
            job = jobRepository.save(job);
            jobEntities.put(type, job);
        }

        // 4. Persist dependency links (parent -> child)
        for (JobType parentType : graph.getNodes()) {
            JobEntity parentJob = jobEntities.get(parentType);
            for (JobType childType : graph.getChildren(parentType)) {
                JobEntity childJob = jobEntities.get(childType);
                JobDependencyEntity dependency = new JobDependencyEntity(parentJob, childJob);
                jobDependencyRepository.save(dependency);
            }
        }

        return pipeline;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PipelineEntity> getPipeline(UUID pipelineId) {
        return pipelineRepository.findById(pipelineId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobEntity> getPipelineJobs(UUID pipelineId) {
        return jobRepository.findByPipelineId(pipelineId);
    }

    @Override
    @Transactional
    public void cancelPipeline(UUID pipelineId) {
        pipelineRepository.lockById(pipelineId);
        PipelineEntity pipeline = pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new IllegalArgumentException("Pipeline not found: " + pipelineId));

        if (pipeline.getStatus().isTerminal()) {
            return;
        }

        pipeline.setCancellationRequested(true);
        pipeline.setStatus(PipelineStatus.CANCELLED);
        pipeline.setCompletedAt(Instant.now());
        pipelineRepository.save(pipeline);

        // Cancel any non-terminal jobs
        List<JobEntity> jobs = jobRepository.findByPipelineId(pipelineId);
        for (JobEntity job : jobs) {
            if (!job.getState().isTerminal()) {
                job.setState(JobState.CANCELLED);
                job.setCompletedAt(Instant.now());
                jobRepository.save(job);
                for (var attempt : jobAttemptRepository.findByJobIdOrderByAttemptNumberDesc(job.getId())) {
                    if (attempt.getCompletedAt() == null) {
                        attempt.setState(JobState.CANCELLED);
                        attempt.setCompletedAt(job.getCompletedAt());
                        attempt.setFailureReason("Pipeline cancelled");
                        jobAttemptRepository.save(attempt);
                    }
                }
            }
        }
        if (reports != null) reports.publish(pipeline);
    }
}