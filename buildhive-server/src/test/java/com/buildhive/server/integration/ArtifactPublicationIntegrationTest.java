package com.buildhive.server.integration;

import com.buildhive.server.controller.*;
import com.buildhive.server.dto.*;
import com.buildhive.server.entity.*;
import com.buildhive.server.repository.*;
import com.buildhive.server.service.*;
import com.buildhive.server.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ArtifactPublicationIntegrationTest extends BasePostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkerApiController workerApi;
    @Autowired ArtifactDownloadController downloads;
    @Autowired JobClaimingService claims;
    @Autowired PipelineService pipelines;
    @Autowired ProjectRepository projects;
    @Autowired SubmissionRepository submissions;
    @Autowired ArtifactRepository artifacts;
    @Autowired StorageService storage;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    JobClaimResponseDto claim;
    Path source;
    MockMvc mvc;
    String digest;
    byte[] bytes = "artifact contents".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE projects, workers CASCADE");
        mvc = MockMvcBuilders.standaloneSetup(workerApi, downloads)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var project = projects.save(new ProjectEntity("publication"));
        var submission = submissions.save(new SubmissionEntity(project, "/test.zip", "a".repeat(64)));
        pipelines.createPipelineForSubmission(submission);
        claim = claims.claimNextJob("worker", Duration.ofSeconds(300)).orElseThrow();
        workerApi.startJob("worker", claim.jobId(), new JobStartRequestDto(claim.claimToken()));
        Path workspace = storage.getWorkspacesPath().resolve(claim.pipelineId().toString())
                .resolve(claim.jobId() + "_" + claim.attemptNumber());
        Files.createDirectories(workspace);
        source = Files.write(workspace.resolve("app.jar"), bytes);
        digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private org.springframework.test.web.servlet.ResultActions publish(Path file, String checksum) throws Exception {
        var dto = new ArtifactRegistrationDto("JAR", "app.jar", file.toString(), bytes.length, checksum, claim.claimToken());
        return mvc.perform(post("/api/workers/worker/jobs/" + claim.jobId() + "/artifacts")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(dto)));
    }

    @Test
    void publicationSurvivesWorkspaceRemovalAndRepeatedAcknowledgements() throws Exception {
        String first = publish(source, digest).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(first).get("id").asText();
        Files.delete(source);
        publish(source, digest).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        assertThat(artifacts.findByJobId(claim.jobId())).hasSize(1);
        mvc.perform(get("/api/artifacts/" + id + "/download")).andExpect(status().isOk())
                .andExpect(content().bytes(bytes));
    }

    @Test
    void externalSourceIsRejected() throws Exception {
        Path external = Files.createTempFile("buildhive-outside-", ".jar");
        try {
            Files.write(external, bytes);
            publish(external, digest).andExpect(status().isBadRequest());
            assertThat(artifacts.findByJobId(claim.jobId())).isEmpty();
        } finally { Files.deleteIfExists(external); }
    }

    @Test
    void checksumMismatchIsRejected() throws Exception {
        publish(source, "f".repeat(64)).andExpect(status().isBadRequest());
        assertThat(artifacts.findByJobId(claim.jobId())).isEmpty();
    }

    @Test
    void metadataCannotExposeArbitraryServerFiles() throws Exception {
        Path external = Files.createTempFile("buildhive-private-", ".txt");
        try {
            Files.write(external, bytes);
            var job = new JobEntity(); job.setId(claim.jobId());
            var pipeline = new PipelineEntity(); pipeline.setId(claim.pipelineId());
            var artifact = artifacts.save(new ArtifactEntity(pipeline, job, "JAR", "private.jar",
                    external.toString(), bytes.length, digest));
            mvc.perform(get("/api/artifacts/" + artifact.getId() + "/download")).andExpect(status().isNotFound());
        } finally { Files.deleteIfExists(external); }
    }

    @Test
    void conflictingRepeatAndTraversalAreRejected() throws Exception {
        publish(source, digest).andExpect(status().isCreated());
        publish(source, "b".repeat(64)).andExpect(status().isConflict());
        // A different filename gives a new publication key, so path validation must run.
        var dto = new ArtifactRegistrationDto("JAR", "other.jar", source.getParent().resolve("nested/../app.jar").toString(),
                bytes.length, digest, claim.claimToken());
        mvc.perform(post("/api/workers/worker/jobs/" + claim.jobId() + "/artifacts")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest());
        assertThat(artifacts.findByJobId(claim.jobId())).hasSize(1);
    }

    @Test
    void anotherAttemptWorkspaceCannotBePublished() throws Exception {
        Path other = source.getParent().resolveSibling(claim.jobId() + "_2");
        Files.createDirectories(other);
        publish(Files.write(other.resolve("app.jar"), bytes), digest).andExpect(status().isBadRequest());
    }

    @Test
    void concurrentDuplicatePublicationsReturnOneArtifact() throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> publish(source, digest).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString());
            var second = pool.submit(() -> publish(source, digest).andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString());
            assertThat(mapper.readTree(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).get("id"))
                    .isEqualTo(mapper.readTree(second.get(10, java.util.concurrent.TimeUnit.SECONDS)).get("id"));
        }
        assertThat(artifacts.findByJobId(claim.jobId())).hasSize(1);
    }

    @Test
    void rollbackRemovesThePublishedCopy() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Path> published = new java.util.concurrent.atomic.AtomicReference<>();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var dto = new ArtifactRegistrationDto("JAR", "app.jar", source.toString(), bytes.length, digest, claim.claimToken());
            workerApi.registerArtifact("worker", claim.jobId(), dto);
            published.set(Path.of(artifacts.findByJobId(claim.jobId()).getFirst().getFilePath()));
            assertThat(published.get()).exists();
            tx.setRollbackOnly();
        });
        assertThat(published.get()).doesNotExist();
        assertThat(artifacts.findByJobId(claim.jobId())).isEmpty();
    }

    @Test
    void legacyWorkspaceArtifactIsPromotedBeforeDownload() throws Exception {
        var job = new JobEntity(); job.setId(claim.jobId());
        var pipeline = new PipelineEntity(); pipeline.setId(claim.pipelineId());
        var artifact = artifacts.save(new ArtifactEntity(pipeline, job, "JAR", "app.jar", source.toString(), bytes.length, digest));
        mvc.perform(get("/api/artifacts/" + artifact.getId() + "/download"))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes));
        Files.delete(source);
        mvc.perform(get("/api/artifacts/" + artifact.getId() + "/download"))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes));
        assertThat(Path.of(artifacts.findById(artifact.getId()).orElseThrow().getFilePath()))
                .startsWith(storage.getArtifactsPath());
    }
}
