package com.buildhive.server.integration;

import com.buildhive.server.BuildHiveServerApplication;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BuildHiveServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "buildhive.security.worker-credentials={\"alpha\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"beta\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"}")
@AutoConfigureMockMvc
class WorkerAccessIntegrationTest extends BasePostgresIntegrationTest {
    @Autowired MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"/api/workers/register", "/api/workers/intruder/claim",
            "/api/internal/jobs/00000000-0000-0000-0000-000000000001/logs/chunks"})
    void anonymousWorkerMutationIsRejected(String path) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"workerName\":\"intruder\",\"hostname\":\"host\",\"lines\":[\"forged\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @org.junit.jupiter.api.Test
    void credentialsCannotImpersonateAnotherWorker() throws Exception {
        String response = mvc.perform(post("/api/workers/register")
                .header("X-Worker-Name", "alpha").header("X-Worker-Key", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .contentType(MediaType.APPLICATION_JSON).content("{\"workerName\":\"alpha\",\"hostname\":\"host\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("workerId").asText();
        mvc.perform(post("/api/workers/" + id + "/heartbeat")
                .header("X-Worker-Name", "alpha").header("X-Worker-Key", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/workers/" + id + "/claim")
                .header("X-Worker-Name", "beta").header("X-Worker-Key", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/workers/register")
                .header("X-Worker-Name", "alpha").header("X-Worker-Key", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .contentType(MediaType.APPLICATION_JSON).content("{\"workerName\":\"beta\",\"hostname\":\"host\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/workers/unknown/claim")
                .header("X-Worker-Name", "alpha").header("X-Worker-Key", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/workers/" + id + "/heartbeat")
                .header("X-Worker-Name", "alpha").header("X-Worker-Key", "wrong"))
                .andExpect(status().isUnauthorized());
    }
}
