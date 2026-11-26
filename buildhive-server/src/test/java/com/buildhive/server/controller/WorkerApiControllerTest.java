package com.buildhive.server.controller;

import com.buildhive.common.model.JobState;
import com.buildhive.common.model.JobType;
import com.buildhive.server.dto.*;
import com.buildhive.server.entity.*;
import com.buildhive.server.exception.GlobalExceptionHandler;
import com.buildhive.server.repository.JobAttemptRepository;
import com.buildhive.server.repository.JobRepository;
import com.buildhive.server.repository.WorkerRepository;
import com.buildhive.server.repository.PipelineRepository;
import com.buildhive.server.service.JobClaimingService;
import com.buildhive.server.service.JobStateTransitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class WorkerApiControllerTest {

    @Mock
    private WorkerRepository workerRepository;

    @Mock
    private JobClaimingService jobClaimingService;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobAttemptRepository jobAttemptRepository;

    @Mock
    private JobStateTransitionService jobStateTransitionService;

    @Mock
    private com.buildhive.server.service.ArtifactPublicationService publication;

    @Mock
    private PipelineRepository pipelineRepository;

    @InjectMocks
    private WorkerApiController workerApiController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(workerApiController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Register worker successfully creates or updates worker entity")
    void testRegisterWorkerSuccess() throws Exception {
        WorkerRegisterRequestDto request = new WorkerRegisterRequestDto("worker-alpha", "host-1", "w-101");
        WorkerEntity workerEntity = new WorkerEntity("w-101", "worker-alpha", "host-1");
        workerEntity.setStatus("ACTIVE");

        when(workerRepository.findById("w-101")).thenReturn(Optional.of(workerEntity));
        when(workerRepository.save(any(WorkerEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/workers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value("w-101"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(workerRepository).save(any(WorkerEntity.class));
    }

    @Test
    @DisplayName("Heartbeat updates last_heartbeat when worker exists")
    void testHeartbeatSuccess() throws Exception {
        WorkerEntity worker = new WorkerEntity("w-101", "worker-alpha", "host-1");
        when(workerRepository.findById("w-101")).thenReturn(Optional.of(worker));
        when(workerRepository.save(any(WorkerEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/workers/w-101/heartbeat"))
                .andExpect(status().isOk());

        verify(workerRepository).save(worker);
    }

    @Test
    @DisplayName("Heartbeat returns 404 when worker is not found")
    void testHeartbeatNotFound() throws Exception {
        when(workerRepository.findById("w-unknown")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/workers/w-unknown/heartbeat"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Claim job returns HTTP 200 with job details when available")
    void testClaimJobSuccess() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        String claimToken = UUID.randomUUID().toString();

        JobClaimResponseDto claimResponse = new JobClaimResponseDto(
                jobId,
                pipelineId,
                JobType.COMPILATION,
                claimToken,
                Instant.now().plusSeconds(300),
                1,
                "/data/uploads/sample.zip"
        );

        when(jobClaimingService.claimNextJob(eq("w-101"), any(Duration.class)))
                .thenReturn(Optional.of(claimResponse));

        mockMvc.perform(post("/api/workers/w-101/claim")
                        .param("leaseSeconds", "300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.pipelineId").value(pipelineId.toString()))
                .andExpect(jsonPath("$.jobType").value("COMPILATION"))
                .andExpect(jsonPath("$.claimToken").value(claimToken))
                .andExpect(jsonPath("$.archivePath").value("/data/uploads/sample.zip"))
                .andExpect(jsonPath("$.archiveDownloadUrl").value("/api/pipelines/" + pipelineId + "/archive"));
    }

    @Test
    @DisplayName("Claim job returns HTTP 204 No Content when no READY job is available")
    void testClaimJobNoContent() throws Exception {
        when(jobClaimingService.claimNextJob(eq("w-101"), any(Duration.class)))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/workers/w-101/claim"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("Start job transitions CLAIMED job to RUNNING")
    void testStartJobSuccess() throws Exception {
        UUID jobId = UUID.randomUUID();
        String claimToken = "token-xyz";

        JobEntity job = new JobEntity();
        job.setId(jobId);
        job.setState(JobState.CLAIMED);
        job.setWorkerId("w-101");
        job.setClaimToken(claimToken);
        job.setLeaseExpiresAt(Instant.now().plusSeconds(300));

        JobAttemptEntity attempt = new JobAttemptEntity();
        attempt.setState(JobState.CLAIMED);
        attempt.setClaimToken(claimToken);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobAttemptRepository.findByJobIdOrderByAttemptNumberDesc(jobId)).thenReturn(List.of(attempt));

        JobStartRequestDto request = new JobStartRequestDto(claimToken);

        mockMvc.perform(post("/api/workers/w-101/jobs/" + jobId + "/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(jobRepository).save(job);
        assert job.getState() == JobState.RUNNING;
        verify(jobAttemptRepository).save(attempt);
        assert attempt.getState() == JobState.RUNNING;
    }

    @Test
    @DisplayName("Start job rejects invalid claim token with 400 Security Violation")
    void testStartJobInvalidClaimToken() throws Exception {
        UUID jobId = UUID.randomUUID();

        JobEntity job = new JobEntity();
        job.setId(jobId);
        job.setState(JobState.CLAIMED);
        job.setWorkerId("w-101");
        job.setClaimToken("correct-token");
        job.setLeaseExpiresAt(Instant.now().plusSeconds(300));

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        JobStartRequestDto request = new JobStartRequestDto("wrong-token");

        mockMvc.perform(post("/api/workers/w-101/jobs/" + jobId + "/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Security Violation"));
    }

    @Test
    @DisplayName("Complete job delegates to JobStateTransitionService")
    void testCompleteJobSuccess() throws Exception {
        UUID jobId = UUID.randomUUID();
        String claimToken = "token-123";

        JobCompletionDto completionDto = new JobCompletionDto(
                claimToken,
                true,
                0,
                null,
                null
        );

        mockMvc.perform(post("/api/workers/w-101/jobs/" + jobId + "/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(completionDto)))
                .andExpect(status().isOk());

        verify(jobStateTransitionService).handleJobCompleted(
                eq(jobId),
                eq(claimToken),
                eq(true),
                isNull(),
                eq(0),
                isNull(),
                eq("w-101")
        );
    }

    @Test
    @DisplayName("Register artifact persists ArtifactEntity and returns HTTP 201")
    void testRegisterArtifactSuccess() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();

        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(pipelineId);

        JobEntity job = new JobEntity();
        job.setId(jobId);
        job.setPipeline(pipeline);
        job.setWorkerId("w-101");
        job.setState(JobState.RUNNING);
        job.setClaimToken("token-123");
        job.setLeaseExpiresAt(Instant.now().plusSeconds(300));

        ArtifactRegistrationDto registrationDto = new ArtifactRegistrationDto(
                "JAR",
                "app-1.0.jar",
                "/artifacts/app-1.0.jar",
                1024L,
                "a1b2c3d4e5",
                "token-123"
        );

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(publication.publish(eq(job), any(ArtifactRegistrationDto.class))).thenAnswer(inv -> {
            ArtifactEntity entity = new ArtifactEntity(pipeline, job, "JAR", "app-1.0.jar", "/managed/file", 1024L, "a1b2c3d4e5");
            entity.setId(artifactId);
            return entity;
        });

        mockMvc.perform(post("/api/workers/w-101/jobs/" + jobId + "/artifacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registrationDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(artifactId.toString()))
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.pipelineId").value(pipelineId.toString()))
                .andExpect(jsonPath("$.fileName").value("app-1.0.jar"))
                .andExpect(jsonPath("$.sha256").value("a1b2c3d4e5"));

        verify(publication).publish(eq(job), any(ArtifactRegistrationDto.class));
    }
}
