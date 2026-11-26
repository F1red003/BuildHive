package com.buildhive.server.integration;

import com.buildhive.common.model.JobState;
import com.buildhive.server.controller.WorkerApiController;
import com.buildhive.server.dto.JobClaimResponseDto;
import com.buildhive.server.entity.ProjectEntity;
import com.buildhive.server.entity.SubmissionEntity;
import com.buildhive.server.exception.GlobalExceptionHandler;
import com.buildhive.server.repository.*;
import com.buildhive.server.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LeaseRenewalIntegrationTest extends BasePostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkerApiController workerApi;
    @Autowired JobClaimingService claims;
    @Autowired PipelineService pipelines;
    @Autowired LeaseReaperService reaper;
    @Autowired JobRepository jobs;
    @Autowired ProjectRepository projects;
    @Autowired SubmissionRepository submissions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE projects, workers CASCADE");
        mvc = MockMvcBuilders.standaloneSetup(workerApi).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private JobClaimResponseDto claim() {
        var project = projects.save(new ProjectEntity("renewal"));
        var submission = submissions.save(new SubmissionEntity(project, "/test.zip", "a".repeat(64)));
        pipelines.createPipelineForSubmission(submission);
        return claims.claimNextJob("worker", Duration.ofSeconds(30)).orElseThrow();
    }

    @Test
    void renewalSurvivesTheOriginalLeaseAndRejectsAnotherWorker() throws Exception {
        var claim = claim();
        mvc.perform(post("/api/workers/other/jobs/" + claim.jobId() + "/renew")
                .contentType(MediaType.APPLICATION_JSON).content("{\"claimToken\":\"" + claim.claimToken() + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/workers/worker/jobs/" + claim.jobId() + "/renew?leaseSeconds=60")
                .contentType(MediaType.APPLICATION_JSON).content("{\"claimToken\":\"" + claim.claimToken() + "\"}"))
                .andExpect(status().isOk());
        reaper.reapExpiredLeasesAt(claim.leaseExpiresAt().plusSeconds(1));
        assertThat(jobs.findById(claim.jobId()).orElseThrow().getState()).isEqualTo(JobState.CLAIMED);
        assertThat(jobs.findById(claim.jobId()).orElseThrow().getLeaseExpiresAt()).isAfter(claim.leaseExpiresAt());
    }

    @Test
    void cancelledAndExpiredClaimsCannotRenew() throws Exception {
        var claim = claim();
        var job = jobs.findById(claim.jobId()).orElseThrow();
        job.setLeaseExpiresAt(java.time.Instant.now().minusSeconds(1));
        jobs.save(job);
        mvc.perform(post("/api/workers/worker/jobs/" + claim.jobId() + "/renew")
                .contentType(MediaType.APPLICATION_JSON).content("{\"claimToken\":\"" + claim.claimToken() + "\"}"))
                .andExpect(status().isConflict());
        pipelines.cancelPipeline(claim.pipelineId());
        mvc.perform(post("/api/workers/worker/jobs/" + claim.jobId() + "/renew")
                .contentType(MediaType.APPLICATION_JSON).content("{\"claimToken\":\"" + claim.claimToken() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void leaseDurationIsBoundedByTheServer() {
        assertThatThrownBy(() -> claims.claimNextJob("worker", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> claims.claimNextJob("worker", Duration.ofHours(1))).isInstanceOf(IllegalArgumentException.class);
    }
}
