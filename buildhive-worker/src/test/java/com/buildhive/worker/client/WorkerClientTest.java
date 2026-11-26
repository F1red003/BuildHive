package com.buildhive.worker.client;

import com.buildhive.common.model.JobType;
import com.buildhive.worker.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WorkerClientTest {

    private MockRestServiceServer mockServer;
    private WorkerClient workerClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8080");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        workerClient = new WorkerClient(builder.build());
    }

    @Test
    @DisplayName("register sends POST /api/workers/register and returns response")
    void testRegister() {
        mockServer.expect(requestTo("http://localhost:8080/api/workers/register"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.workerName").value("worker-1"))
                .andExpect(jsonPath("$.hostname").value("host-alpha"))
                .andRespond(withSuccess("""
                        {"workerId":"w-123","status":"ACTIVE"}
                        """, MediaType.APPLICATION_JSON));

        WorkerRegisterResponse response = workerClient.register("worker-1", "host-alpha", null);

        mockServer.verify();
        assertThat(response).isNotNull();
        assertThat(response.workerId()).isEqualTo("w-123");
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("sendHeartbeat sends POST /api/workers/{id}/heartbeat and returns true on 200")
    void testSendHeartbeatSuccess() {
        mockServer.expect(requestTo("http://localhost:8080/api/workers/w-123/heartbeat"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        boolean ok = workerClient.sendHeartbeat("w-123");

        mockServer.verify();
        assertThat(ok).isTrue();
    }

    @Test
    @DisplayName("sendHeartbeat returns false on error response")
    void testSendHeartbeatFailure() {
        mockServer.expect(requestTo("http://localhost:8080/api/workers/w-123/heartbeat"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        boolean ok = workerClient.sendHeartbeat("w-123");

        mockServer.verify();
        assertThat(ok).isFalse();
    }

    @Test
    @DisplayName("claimJob returns JobClaimDto on HTTP 200")
    void testClaimJobSuccess() {
        UUID jobId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();

        mockServer.expect(requestTo("http://localhost:8080/api/workers/w-123/claim?leaseSeconds=300"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                            "jobId": "%s",
                            "pipelineId": "%s",
                            "jobType": "COMPILATION",
                            "claimToken": "token-abc",
                            "leaseExpiresAt": "2026-09-16T22:00:00Z",
                            "attemptNumber": 1,
                            "archivePath": "/data/sample.zip"
                        }
                        """.formatted(jobId, pipelineId), MediaType.APPLICATION_JSON));

        Optional<JobClaimDto> claim = workerClient.claimJob("w-123", 300);

        mockServer.verify();
        assertThat(claim).isPresent();
        assertThat(claim.get().jobId()).isEqualTo(jobId);
        assertThat(claim.get().pipelineId()).isEqualTo(pipelineId);
        assertThat(claim.get().jobType()).isEqualTo(JobType.COMPILATION);
        assertThat(claim.get().claimToken()).isEqualTo("token-abc");
        assertThat(claim.get().archivePath()).isEqualTo("/data/sample.zip");
    }

    @Test
    @DisplayName("claimJob returns empty Optional on HTTP 204 No Content")
    void testClaimJobNoContent() {
        mockServer.expect(requestTo("http://localhost:8080/api/workers/w-123/claim?leaseSeconds=300"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withNoContent());

        Optional<JobClaimDto> claim = workerClient.claimJob("w-123", 300);

        mockServer.verify();
        assertThat(claim).isEmpty();
    }

    @Test
    @DisplayName("startJob sends POST /api/workers/{id}/jobs/{jobId}/start")
    void testStartJob() {
        UUID jobId = UUID.randomUUID();

        mockServer.expect(requestTo("http://localhost:8080/api/workers/w-123/jobs/" + jobId + "/start"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.claimToken").value("token-abc"))
                .andRespond(withSuccess());

        workerClient.startJob("w-123", jobId, "token-abc");

        mockServer.verify();
    }

    @Test
    @DisplayName("completeJob sends POST /api/workers/{id}/jobs/{jobId}/complete")
    void testCompleteJob() {
        UUID jobId = UUID.randomUUID();
        JobCompletionDto completion = new JobCompletionDto(
                "token-abc",
                true,
                0,
                null,
                null
        );

        mockServer.expect(requestTo("http://localhost:8080/api/workers/w-123/jobs/" + jobId + "/complete"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.claimToken").value("token-abc"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.exitCode").value(0))
                .andRespond(withSuccess());

        workerClient.completeJob("w-123", jobId, completion);

        mockServer.verify();
    }

    @Test
    @DisplayName("registerArtifact sends POST /api/workers/{id}/jobs/{jobId}/artifacts and returns response")
    void testRegisterArtifact() {
        UUID jobId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();

        ArtifactRegistrationDto dto = new ArtifactRegistrationDto(
                "JAR",
                "app.jar",
                "/artifacts/app.jar",
                2048L,
                "sha-hash-123",
                "token-abc"
        );

        mockServer.expect(requestTo("http://localhost:8080/api/workers/w-123/jobs/" + jobId + "/artifacts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.artifactType").value("JAR"))
                .andExpect(jsonPath("$.fileName").value("app.jar"))
                .andRespond(withSuccess("""
                        {
                            "id": "%s",
                            "jobId": "%s",
                            "pipelineId": "%s",
                            "fileName": "app.jar",
                            "sha256": "sha-hash-123",
                            "createdAt": "2026-09-16T22:00:00Z"
                        }
                        """.formatted(artifactId, jobId, pipelineId), MediaType.APPLICATION_JSON));

        Optional<ArtifactResponseDto> response = workerClient.registerArtifact("w-123", jobId, dto);

        mockServer.verify();
        assertThat(response).isPresent();
        assertThat(response.get().id()).isEqualTo(artifactId);
        assertThat(response.get().fileName()).isEqualTo("app.jar");
    }

    @Test
    @DisplayName("pushLogChunk sends POST /api/internal/jobs/{id}/logs/chunks with lines")
    void testPushLogChunk() {
        UUID jobId = UUID.randomUUID();

        mockServer.expect(requestTo("http://localhost:8080/api/internal/jobs/" + jobId + "/logs/chunks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.lines[0]").value("Line 1"))
                .andExpect(jsonPath("$.lines[1]").value("Line 2"))
                .andRespond(withSuccess());

        workerClient.pushLogChunk("worker", jobId, "token", List.of("Line 1", "Line 2"));

        mockServer.verify();
    }

    @Test
    void renewalSendsClaimAndReadsExpiry() {
        UUID jobId = UUID.randomUUID();
        mockServer.expect(requestTo("http://localhost:8080/api/workers/w-123/jobs/" + jobId + "/renew?leaseSeconds=60"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.claimToken").value("token"))
                .andRespond(withSuccess("{\"leaseExpiresAt\":\"2026-11-07T12:00:00Z\"}", MediaType.APPLICATION_JSON));
        var response = workerClient.renewLease("w-123", jobId, "token", 60);
        assertThat(response.leaseExpiresAt()).isEqualTo(Instant.parse("2026-11-07T12:00:00Z"));
        mockServer.verify();
    }

    @Test
    void artifactRegistrationRetriesTheIdenticalPayloadAfterLostResponse() {
        UUID jobId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var dto = new ArtifactRegistrationDto("JAR", "app.jar", "/workspace/app.jar", 20, "a".repeat(64), "claim");
        String url = "http://localhost:8080/api/workers/worker/jobs/" + jobId + "/artifacts";
        mockServer.expect(requestTo(url)).andExpect(jsonPath("$.claimToken").value("claim"))
                .andExpect(jsonPath("$.sha256").value("a".repeat(64)))
                .andRespond(withException(new java.io.IOException("response lost")));
        mockServer.expect(requestTo(url)).andExpect(jsonPath("$.claimToken").value("claim"))
                .andExpect(jsonPath("$.sha256").value("a".repeat(64)))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"fileName\":\"app.jar\"}", MediaType.APPLICATION_JSON));
        assertThat(workerClient.registerArtifact("worker", jobId, dto).orElseThrow().id()).isEqualTo(id);
        mockServer.verify();
    }
}
