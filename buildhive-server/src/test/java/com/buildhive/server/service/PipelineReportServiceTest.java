package com.buildhive.server.service;
import com.buildhive.server.entity.*;
import com.buildhive.server.repository.*;
import com.buildhive.common.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PipelineReportServiceTest {
    @Test void failureAndCancellationReportsShowActualAttemptsAndEscapeHtml() throws Exception {
        var jobs = mock(JobRepository.class); var attempts = mock(JobAttemptRepository.class);
        var artifacts = mock(ArtifactRepository.class); var publication = mock(ArtifactPublicationService.class);
        var service = new PipelineReportService(jobs, attempts, artifacts, publication, new ObjectMapper().findAndRegisterModules());
        for (PipelineStatus status : List.of(PipelineStatus.SUCCEEDED, PipelineStatus.FAILED, PipelineStatus.CANCELLED)) {
            var pipeline = new PipelineEntity(); pipeline.setId(UUID.randomUUID()); pipeline.setStatus(status);
            var job = new JobEntity(pipeline, JobType.COMPILATION,
                    status == PipelineStatus.SUCCEEDED ? JobState.SUCCEEDED : status == PipelineStatus.CANCELLED ? JobState.CANCELLED : JobState.FAILED, 1);
            job.setId(UUID.randomUUID());
            var old = new JobAttemptEntity(); old.setAttemptNumber(1); old.setWorkerId("first");
            old.setState(JobState.FAILED); old.setStartedAt(Instant.now().minusSeconds(4)); old.setCompletedAt(Instant.now().minusSeconds(3));
            old.setFailureReason("<script>alert('x')</script>");
            var newer = new JobAttemptEntity(); newer.setAttemptNumber(2); newer.setState(job.getState());
            when(jobs.findByPipelineId(pipeline.getId())).thenReturn(List.of(job));
            when(attempts.findByJobIdOrderByAttemptNumberDesc(job.getId())).thenReturn(List.of(newer, old));
            when(artifacts.findByPipelineId(pipeline.getId())).thenReturn(List.of());
            service.publish(pipeline);
            var json = org.mockito.ArgumentCaptor.forClass(byte[].class);
            verify(publication).publishGenerated(eq(job), eq("BUILD_REPORT_JSON"), eq("build-report.json"), json.capture());
            var report = new ObjectMapper().readTree(json.getValue());
            assertThat(report.get("status").asText()).isEqualTo(status.name());
            assertThat(report.get("stages").get(0).get("attempts")).hasSize(2);
            assertThat(report.get("stages").get(0).get("attempts").get(0).get("number").asInt()).isEqualTo(1);
            var html = org.mockito.ArgumentCaptor.forClass(byte[].class);
            verify(publication).publishGenerated(eq(job), eq("BUILD_REPORT_HTML"), eq("build-report.html"), html.capture());
            assertThat(new String(html.getValue())).contains("&lt;script&gt;").doesNotContain("<script>");
            clearInvocations(publication);
        }
    }
}
