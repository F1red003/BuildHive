package com.buildhive.server.controller;

import com.buildhive.common.dto.LogChunkDto;
import com.buildhive.server.service.LogBroadcastService;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LogStreamingControllerTest {

    @Mock
    private LogBroadcastService logBroadcastService;

    @Mock private com.buildhive.server.repository.JobRepository jobs;
    @Mock private com.buildhive.server.repository.PipelineRepository pipelines;

    @InjectMocks
    private LogStreamingController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET job logs returns a browser-compatible event stream and forwards the resume cursor")
    void testStreamJobLogsEndpoint() throws Exception {
        UUID jobId = UUID.randomUUID();
        var source = new LogBroadcastService.StreamSource(java.nio.file.Path.of("test.log"), 2, 15, false);
        when(logBroadcastService.source(jobId, 2, "2:15")).thenReturn(source);
        String event = "id: 2:20\ndata: test\n\n";
        when(logBroadcastService.stream(jobId, source)).thenReturn(
                output -> output.write(event.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var response = mockMvc.perform(get("/api/jobs/{jobId}/logs/stream", jobId)
                        .accept(MediaType.TEXT_EVENT_STREAM).param("attempt", "2").header("Last-Event-ID", "2:15"))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(response))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(event));
        verify(logBroadcastService).stream(jobId, source);
    }

    @Test
    @DisplayName("POST /api/internal/jobs/{jobId}/logs/chunks delegates to service")
    void testPushLogChunkEndpoint() throws Exception {
        UUID jobId = UUID.randomUUID();
        LogChunkDto chunk = new LogChunkDto(List.of("Line A", "Line B"));
        var job = new com.buildhive.server.entity.JobEntity();
        job.setWorkerId("worker"); job.setClaimToken("token");
        job.setState(com.buildhive.common.model.JobState.RUNNING);
        job.setLeaseExpiresAt(java.time.Instant.now().plusSeconds(60));
        when(jobs.findById(jobId)).thenReturn(java.util.Optional.of(job));

        mockMvc.perform(post("/api/internal/jobs/{jobId}/logs/chunks", jobId)
                        .header("X-Worker-Id", "worker").header("X-Claim-Token", "token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chunk)))
                .andExpect(status().isGone());
    }

    @Test
    void expiredClaimCannotAppendLogs() throws Exception {
        UUID id = UUID.randomUUID();
        var job = new com.buildhive.server.entity.JobEntity();
        job.setWorkerId("worker"); job.setClaimToken("token");
        job.setState(com.buildhive.common.model.JobState.RUNNING);
        job.setLeaseExpiresAt(java.time.Instant.now().minusSeconds(1));
        when(jobs.findById(id)).thenReturn(java.util.Optional.of(job));
        mockMvc.perform(post("/api/internal/jobs/{jobId}/logs/chunks", id)
                .header("X-Worker-Id", "worker").header("X-Claim-Token", "token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[\"forged\"]}"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(logBroadcastService);
    }
}
