package com.buildhive.worker.client;

import com.buildhive.common.dto.LogChunkDto;
import com.buildhive.common.dto.LeaseRenewalDto;
import com.buildhive.worker.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * HTTP REST client communicating with the BuildHive server for registration,
 * heartbeats, atomic job claiming, execution state reporting, and artifact uploads.
 */
@Component
public class WorkerClient {

    private static final Logger log = LoggerFactory.getLogger(WorkerClient.class);

    private final RestClient restClient;

    @Autowired
    public WorkerClient(@Value("${buildhive.worker.server-base-url:http://localhost:8080}") String serverBaseUrl,
                        @Value("${buildhive.worker.name:worker-1}") String workerName,
                        @Value("${buildhive.worker.api-key:}") String apiKey) {
        this(createRestClient(serverBaseUrl, workerName, apiKey));
    }

    private static RestClient createRestClient(String baseUrl, String workerName, String apiKey) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("X-Worker-Name", workerName).defaultHeader("X-Worker-Key", apiKey).build();
    }

    public LeaseRenewalDto renewLease(String workerId, UUID jobId, String claimToken, long leaseSeconds) {
        return restClient.post()
                .uri("/api/workers/{workerId}/jobs/{jobId}/renew?leaseSeconds={leaseSeconds}", workerId, jobId, leaseSeconds)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new JobStartRequest(claimToken))
                .retrieve()
                .body(LeaseRenewalDto.class);
    }

    public WorkerClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Registers worker node with the BuildHive server.
     */
    public WorkerRegisterResponse register(String workerName, String hostname, String workerId) {
        log.info("Registering worker '{}' on host '{}' with server", workerName, hostname);
        return restClient.post()
                .uri("/api/workers/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new WorkerRegisterRequest(workerName, hostname, workerId))
                .retrieve()
                .body(WorkerRegisterResponse.class);
    }

    /**
     * Sends heartbeat to refresh worker lease.
     */
    public boolean sendHeartbeat(String workerId) {
        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri("/api/workers/{workerId}/heartbeat", workerId)
                    .retrieve()
                    .toBodilessEntity();
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("Failed to send heartbeat for worker {}: {}", workerId, e.getMessage());
            return false;
        }
    }

    /**
     * Atomically claims the next READY job from the server.
     * Returns Optional with JobClaimDto if a job was claimed, or empty if no jobs ready (HTTP 204).
     */
    public Optional<JobClaimDto> claimJob(String workerId, long leaseSeconds) {
        try {
            ResponseEntity<JobClaimDto> response = restClient.post()
                    .uri("/api/workers/{workerId}/claim?leaseSeconds={leaseSeconds}", workerId, leaseSeconds)
                    .retrieve()
                    .toEntity(JobClaimDto.class);

            if (response.getStatusCode().value() == 204 || response.getBody() == null) {
                return Optional.empty();
            }
            return Optional.of(response.getBody());
        } catch (Exception e) {
            log.warn("Error claiming job for worker {}: {}", workerId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Notifies server that execution of the claimed job has started.
     */
    public void startJob(String workerId, UUID jobId, String claimToken) {
        log.info("Notifying server that job {} has started execution", jobId);
        restClient.post()
                .uri("/api/workers/{workerId}/jobs/{jobId}/start", workerId, jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new JobStartRequest(claimToken))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Reports final completion status and metrics of a job to the server.
     */
    public void completeJob(String workerId, UUID jobId, JobCompletionDto completion) {
        log.info("Reporting completion for job {}: success={}, exitCode={}",
                jobId, completion.success(), completion.exitCode());
        restClient.post()
                .uri("/api/workers/{workerId}/jobs/{jobId}/complete", workerId, jobId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(completion)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Registers a build artifact generated by a job.
     */
    public Optional<ArtifactResponseDto> registerArtifact(String workerId, UUID jobId, ArtifactRegistrationDto artifact) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                ArtifactResponseDto response = restClient.post()
                        .uri("/api/workers/{workerId}/jobs/{jobId}/artifacts", workerId, jobId)
                        .contentType(MediaType.APPLICATION_JSON).body(artifact).retrieve().body(ArtifactResponseDto.class);
                if (response != null) return Optional.of(response);
            } catch (org.springframework.web.client.HttpClientErrorException rejected) {
                log.warn("Artifact registration rejected for job {}: {}", jobId, rejected.getStatusCode());
                return Optional.empty();
            } catch (Exception unavailable) {
                log.warn("Artifact registration delivery {} failed for job {}", attempt + 1, jobId);
            }
            if (attempt < 2) {
                try { Thread.sleep(200L << attempt); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Streams a batch of log lines for an active job to the server.
     */
    public void pushLogChunk(String workerId, UUID jobId, String claimToken, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        try {
            restClient.post()
                    .uri("/api/internal/jobs/{jobId}/logs/chunks", jobId)
                    .header("X-Worker-Id", workerId).header("X-Claim-Token", claimToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new LogChunkDto(lines))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.debug("Failed to stream log chunk for job {}: {}", jobId, e.getMessage());
        }
    }
}
