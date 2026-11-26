package com.buildhive.server.service;

import com.buildhive.server.entity.*;
import com.buildhive.server.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Emits a pipeline snapshot only after all jobs have reached terminal states. */
@Service
public class PipelineReportService {
    private final JobRepository jobs;
    private final JobAttemptRepository attempts;
    private final ArtifactRepository artifacts;
    private final ArtifactPublicationService publication;
    private final ObjectMapper mapper;
    public PipelineReportService(JobRepository jobs, JobAttemptRepository attempts,
            ArtifactRepository artifacts, ArtifactPublicationService publication, ObjectMapper mapper) {
        this.jobs = jobs; this.attempts = attempts; this.artifacts = artifacts;
        this.publication = publication; this.mapper = mapper;
    }
    public void publish(PipelineEntity pipeline) {
        if (!pipeline.getStatus().isTerminal()) return;
        List<JobEntity> all = jobs.findByPipelineId(pipeline.getId());
        if (all.isEmpty() || all.stream().anyMatch(j -> !j.getState().isTerminal())) return;
        List<ArtifactEntity> publishedArtifacts = artifacts.findByPipelineId(pipeline.getId());
        List<Map<String, Object>> stages = new ArrayList<>();
        for (JobEntity job : all) {
            List<Map<String, Object>> history = new ArrayList<>();
            for (JobAttemptEntity attempt : attempts.findByJobIdOrderByAttemptNumberDesc(job.getId()).reversed()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("number", attempt.getAttemptNumber()); row.put("workerId", attempt.getWorkerId());
                row.put("state", attempt.getState().name()); row.put("startedAt", attempt.getStartedAt());
                row.put("completedAt", attempt.getCompletedAt());
                row.put("durationMs", duration(attempt.getStartedAt(), attempt.getCompletedAt()));
                row.put("exitCode", attempt.getExitCode()); row.put("failureReason", attempt.getFailureReason());
                row.put("classification", attempt.getFailureClassification()); history.add(row);
            }
            Map<String, Object> stage = new LinkedHashMap<>();
            stage.put("jobId", job.getId()); stage.put("type", job.getJobType().name());
            stage.put("state", job.getState().name()); stage.put("startedAt", job.getStartedAt());
            stage.put("completedAt", job.getCompletedAt());
            stage.put("durationMs", duration(job.getStartedAt(), job.getCompletedAt()));
            if (job.getJobType() == com.buildhive.common.model.JobType.STATIC_ANALYSIS
                    && publishedArtifacts.stream().anyMatch(a -> "CHECKSTYLE_REPORT".equals(a.getArtifactType())
                            && a.getJob() != null && job.getId().equals(a.getJob().getId()))) {
                stage.put("analyzer", "Checkstyle");
            }
            stage.put("attempts", history); stages.add(stage);
        }
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (ArtifactEntity artifact : publishedArtifacts) {
            if (!artifact.getArtifactType().startsWith("BUILD_REPORT")) outputs.add(Map.of(
                "type", artifact.getArtifactType(), "fileName", artifact.getFileName(),
                "url", "/api/artifacts/" + artifact.getId() + "/download"));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("pipelineId", pipeline.getId()); report.put("status", pipeline.getStatus().name());
        report.put("startedAt", pipeline.getStartedAt()); report.put("completedAt", pipeline.getCompletedAt());
        report.put("durationMs", duration(pipeline.getStartedAt(), pipeline.getCompletedAt()));
        report.put("stages", stages); report.put("outputs", outputs);
        try {
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
            StringBuilder html = new StringBuilder("<!doctype html><html><meta charset=\"utf-8\"><title>BuildHive Report</title><body>");
            html.append("<h1>Pipeline ").append(pipeline.getId()).append("</h1><p>Status: ")
                .append(pipeline.getStatus()).append("</p><table><tr><th>Stage</th><th>Outcome</th><th>Duration (ms)</th><th>Attempts</th></tr>");
            for (Map<String, Object> stage : stages) {
                html.append("<tr><td>").append(stage.get("type"));
                if (stage.containsKey("analyzer")) html.append(" (").append(stage.get("analyzer")).append(")");
                html.append("</td><td>").append(stage.get("state"))
                    .append("</td><td>").append(stage.get("durationMs") == null ? "—" : stage.get("durationMs"))
                    .append("</td><td>").append(((List<?>)stage.get("attempts")).size()).append("</td></tr>");
                for (Object raw : (List<?>)stage.get("attempts")) {
                    Map<?, ?> attempt = (Map<?, ?>) raw;
                    if (attempt.get("failureReason") != null) html.append("<tr><td colspan=\"4\">Attempt ")
                        .append(attempt.get("number")).append(" on ").append(escape(String.valueOf(attempt.get("workerId"))))
                        .append(" (").append(attempt.get("classification")).append("): ")
                        .append(escape(attempt.get("failureReason").toString()))
                        .append("</td></tr>");
                }
            }
            html.append("</table><h2>Outputs</h2><ul>");
            for (Map<String, Object> output : outputs) html.append("<li><a href=\"").append(output.get("url"))
                .append("\">").append(escape(output.get("fileName").toString())).append("</a></li>");
            html.append("</ul></body></html>");
            JobEntity anchor = all.getFirst();
            publication.publishGenerated(anchor, "BUILD_REPORT_JSON", "build-report.json", json);
            publication.publishGenerated(anchor, "BUILD_REPORT_HTML", "build-report.html", html.toString().getBytes(StandardCharsets.UTF_8));
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("Could not serialize pipeline report", impossible);
        }
    }
    private static Long duration(Instant start, Instant end) {
        return start == null || end == null ? null : Duration.between(start, end).toMillis();
    }
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
