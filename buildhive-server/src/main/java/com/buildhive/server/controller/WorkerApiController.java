package com.buildhive.server.controller;

import com.buildhive.common.model.JobState;
import com.buildhive.common.dto.LeaseRenewalDto;
import com.buildhive.server.dto.*;
import com.buildhive.server.entity.ArtifactEntity;
import com.buildhive.server.entity.JobAttemptEntity;
import com.buildhive.server.entity.JobEntity;
import com.buildhive.server.entity.WorkerEntity;
import com.buildhive.server.repository.JobAttemptRepository;
import com.buildhive.server.repository.JobRepository;
import com.buildhive.server.repository.WorkerRepository;
import com.buildhive.server.repository.PipelineRepository;
import com.buildhive.server.service.JobClaimingService;
import com.buildhive.server.service.JobStateTransitionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller coordinating worker nodes: registration, heartbeats,
 * atomic job claiming, execution state transitions, and artifact reporting.
 */
@RestController
@RequestMapping("/api/workers")
public class WorkerApiController {

    private final WorkerRepository workerRepository;
    private final JobClaimingService jobClaimingService;
    private final JobRepository jobRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final JobStateTransitionService jobStateTransitionService;
    private final com.buildhive.server.service.ArtifactPublicationService publication;
    private final PipelineRepository pipelineRepository;

    public WorkerApiController(
            WorkerRepository workerRepository,
            JobClaimingService jobClaimingService,
            JobRepository jobRepository,
            JobAttemptRepository jobAttemptRepository,
            JobStateTransitionService jobStateTransitionService,
            com.buildhive.server.service.ArtifactPublicationService publication,
            PipelineRepository pipelineRepository
    ) {
        this.workerRepository = workerRepository;
        this.jobClaimingService = jobClaimingService;
        this.jobRepository = jobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.jobStateTransitionService = jobStateTransitionService;
        this.publication = publication;
        this.pipelineRepository = pipelineRepository;
    }

    /**
     * Registers a new worker or updates existing worker heartbeat and status.
     */
    @PostMapping("/register")
    @Transactional
    public ResponseEntity<WorkerRegisterResponseDto> registerWorker(@Valid @RequestBody WorkerRegisterRequestDto request,
            @RequestHeader(value = "X-Worker-Name", required = false) String authenticatedName) {
        if (authenticatedName != null && !authenticatedName.equals(request.workerName())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "Worker name mismatch");
        }
        if (request.workerId() != null) {
            workerRepository.findById(request.workerId()).ifPresent(existing -> {
                if (!existing.getWorkerName().equals(request.workerName())) {
                    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "Worker identity mismatch");
                }
            });
        }
        String workerId = (request.workerId() != null && !request.workerId().isBlank())
                ? request.workerId()
                : ("worker-" + UUID.randomUUID().toString().substring(0, 8));

        WorkerEntity worker = workerRepository.findById(workerId)
                .orElse(new WorkerEntity(workerId, request.workerName(), request.hostname()));

        worker.setWorkerName(request.workerName());
        worker.setHostname(request.hostname());
        worker.setStatus("ACTIVE");
        worker.setLastHeartbeat(Instant.now());

        WorkerEntity saved = workerRepository.save(worker);
        return ResponseEntity.ok(new WorkerRegisterResponseDto(saved.getId(), saved.getStatus()));
    }

    /**
     * Updates worker heartbeat.
     */
    @PostMapping("/{workerId}/heartbeat")
    @Transactional
    public ResponseEntity<Void> heartbeat(@PathVariable("workerId") String workerId) {
        Optional<WorkerEntity> workerOpt = workerRepository.findById(workerId);
        if (workerOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        WorkerEntity worker = workerOpt.get();
        worker.setLastHeartbeat(Instant.now());
        worker.setStatus("ACTIVE");
        workerRepository.save(worker);
        return ResponseEntity.ok().build();
    }

    /**
     * Atomically claims the next READY job for the worker.
     * Returns HTTP 200 with job details or HTTP 204 if no job is available.
     */
    @PostMapping("/{workerId}/claim")
    public ResponseEntity<JobClaimResponseDto> claimJob(
            @PathVariable("workerId") String workerId,
            @RequestParam(value = "leaseSeconds", defaultValue = "300") long leaseSeconds
    ) {
        Optional<JobClaimResponseDto> claimOpt = jobClaimingService.claimNextJob(
                workerId,
                Duration.ofSeconds(leaseSeconds)
        );

        return claimOpt
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Transitions a claimed job into RUNNING state upon worker starting execution.
     */
    @PostMapping("/{workerId}/jobs/{jobId}/start")
    @Transactional
    public ResponseEntity<Void> startJob(
            @PathVariable("workerId") String workerId,
            @PathVariable("jobId") UUID jobId,
            @Valid @RequestBody JobStartRequestDto request
    ) {
        pipelineRepository.lockForJob(jobId);
        JobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        if (job.getClaimToken() == null || !job.getClaimToken().equals(request.claimToken())) {
            throw new SecurityException("Invalid claim token for job " + jobId);
        }

        if (!workerId.equals(job.getWorkerId())) {
            throw new SecurityException("Worker ID mismatch for claimed job " + jobId);
        }

        if (job.getState() != JobState.CLAIMED && job.getState() != JobState.RUNNING) {
            throw new IllegalStateException("Cannot start job " + jobId + " from state " + job.getState());
        }

        if (job.getLeaseExpiresAt() == null || !job.getLeaseExpiresAt().isAfter(Instant.now())) {
            throw new SecurityException("Job lease expired");
        }

        if (job.getState() == JobState.CLAIMED) {
            job.setState(JobState.RUNNING);
            job.setStartedAt(Instant.now());
            jobRepository.save(job);

            List<JobAttemptEntity> attempts = jobAttemptRepository.findByJobIdOrderByAttemptNumberDesc(jobId);
            if (!attempts.isEmpty()) {
                JobAttemptEntity latest = attempts.getFirst();
                latest.setState(JobState.RUNNING);
                jobAttemptRepository.save(latest);
            }
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/{workerId}/jobs/{jobId}/renew")
    @Transactional
    public ResponseEntity<LeaseRenewalDto> renewLease(
            @PathVariable("workerId") String workerId,
            @PathVariable("jobId") UUID jobId,
            @Valid @RequestBody JobStartRequestDto request,
            @RequestParam(value = "leaseSeconds", defaultValue = "300") long leaseSeconds
    ) {
        Duration duration = Duration.ofSeconds(leaseSeconds);
        JobClaimingService.validateLeaseDuration(duration);
        pipelineRepository.lockForJob(jobId);
        JobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        if (!workerId.equals(job.getWorkerId()) || job.getClaimToken() == null
                || !job.getClaimToken().equals(request.claimToken())) {
            throw new SecurityException("Invalid worker claim for renewal");
        }
        Instant now = Instant.now();
        if ((job.getState() != JobState.CLAIMED && job.getState() != JobState.RUNNING)
                || job.getLeaseExpiresAt() == null || !job.getLeaseExpiresAt().isAfter(now)) {
            throw new IllegalStateException("Only an active unexpired attempt can renew its lease");
        }
        job.setLeaseExpiresAt(now.plus(duration));
        jobRepository.save(job);
        return ResponseEntity.ok(new LeaseRenewalDto(job.getLeaseExpiresAt()));
    }

    /**
     * Reports job completion and triggers downstream DAG state transitions.
     */
    @PostMapping("/{workerId}/jobs/{jobId}/complete")
    public ResponseEntity<Void> completeJob(
            @PathVariable("workerId") String workerId,
            @PathVariable("jobId") UUID jobId,
            @Valid @RequestBody JobCompletionDto request
    ) {
        jobStateTransitionService.handleJobCompleted(
                jobId,
                request.claimToken(),
                request.success(),
                request.failureReason(),
                request.exitCode(),
                request.failureClassification(),
                workerId
        );
        return ResponseEntity.ok().build();
    }

    /**
     * Registers a build artifact generated by a job.
     */
    @PostMapping("/{workerId}/jobs/{jobId}/artifacts")
    @Transactional
    public ResponseEntity<ArtifactResponseDto> registerArtifact(
            @PathVariable("workerId") String workerId,
            @PathVariable("jobId") UUID jobId,
            @Valid @RequestBody ArtifactRegistrationDto request
    ) {
        pipelineRepository.lockForJob(jobId);
        JobEntity job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        if (job.getClaimToken() == null || !job.getClaimToken().equals(request.claimToken())
                || !workerId.equals(job.getWorkerId())) {
            throw new SecurityException("Invalid worker claim for artifact");
        }
        if (job.getState() != JobState.RUNNING || job.getLeaseExpiresAt() == null
                || !job.getLeaseExpiresAt().isAfter(Instant.now())) {
            throw new IllegalStateException("Artifact requires an active running attempt");
        }

        ArtifactEntity saved = publication.publish(job, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(new ArtifactResponseDto(
                saved.getId(),
                job.getId(),
                job.getPipeline().getId(),
                saved.getFileName(),
                saved.getSha256(),
                saved.getCreatedAt()
        ));
    }

    /**
     * Retrieves all registered workers with heartbeat and active job details.
     */
    @GetMapping
    public ResponseEntity<List<WorkerDetailDto>> getAllWorkers() {
        List<WorkerEntity> workers = workerRepository.findAll();
        List<WorkerDetailDto> dtos = workers.stream().map(worker -> {
            List<JobEntity> runningJobs = jobRepository.findByWorkerIdAndState(worker.getId(), JobState.RUNNING);
            JobEntity currentJob = runningJobs.isEmpty() ? null : runningJobs.getFirst();
            return new WorkerDetailDto(
                    worker.getId(),
                    worker.getWorkerName(),
                    worker.getHostname(),
                    worker.getStatus(),
                    worker.getLastHeartbeat(),
                    worker.getRegisteredAt(),
                    currentJob != null ? currentJob.getId() : null,
                    currentJob != null ? currentJob.getJobType().name() : null
            );
        }).toList();
        return ResponseEntity.ok(dtos);
    }
}
