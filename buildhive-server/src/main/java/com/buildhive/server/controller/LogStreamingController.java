package com.buildhive.server.controller;

import com.buildhive.common.dto.LogChunkDto;
import com.buildhive.server.service.LogBroadcastService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Controller exposing real-time log streaming via Server-Sent Events (SSE)
 * and internal ingestion endpoints for worker nodes to push log batches.
 */
@RestController
@CrossOrigin
public class LogStreamingController {

    private final LogBroadcastService logBroadcastService;
    private final com.buildhive.server.repository.JobRepository jobs;
    private final com.buildhive.server.repository.PipelineRepository pipelines;

    public LogStreamingController(LogBroadcastService logBroadcastService, com.buildhive.server.repository.JobRepository jobs,
                                  com.buildhive.server.repository.PipelineRepository pipelines) {
        this.jobs = jobs;
        this.pipelines = pipelines;
        this.logBroadcastService = logBroadcastService;
    }

    /**
     * Subscribes to live Server-Sent Events for job execution logs.
     * Streams previous log history and pushes subsequent lines in real time until completion.
     */
    @GetMapping(value = "/api/jobs/{jobId}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> streamJobLogs(
            @PathVariable("jobId") UUID jobId,
            @RequestParam(value = "attempt", required = false) Integer attempt,
            @RequestHeader(value = "Last-Event-ID", required = false) String cursor) {
        var source = logBroadcastService.source(jobId, attempt, cursor);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache").header("X-Accel-Buffering", "no")
                .body(logBroadcastService.stream(jobId, source));
    }

    @GetMapping("/api/jobs/{jobId}/logs/download")
    public ResponseEntity<org.springframework.core.io.Resource> downloadLog(@PathVariable UUID jobId,
            @RequestParam(value = "attempt", required = false) Integer attempt) {
        var source = logBroadcastService.source(jobId, attempt, null);
        if (!java.nio.file.Files.isRegularFile(source.path())) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .header("Content-Disposition", "attachment; filename=\"attempt-" + source.attempt() + ".log\"")
                .body(new org.springframework.core.io.FileSystemResource(source.path()));
    }

    /**
     * Internal endpoint invoked by worker nodes to append and broadcast a chunk of log output.
     */
    @PostMapping(value = "/api/internal/jobs/{jobId}/logs/chunks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Void> pushLogChunk(
            @PathVariable("jobId") UUID jobId,
            @RequestBody LogChunkDto chunk,
            @RequestHeader("X-Worker-Id") String workerId,
            @RequestHeader("X-Claim-Token") String claimToken
    ) {
        pipelines.lockForJob(jobId);
        var job = jobs.findById(jobId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND));
        if (!workerId.equals(job.getWorkerId()) || !claimToken.equals(job.getClaimToken())
                || job.getState() != com.buildhive.common.model.JobState.RUNNING
                || job.getLeaseExpiresAt() == null || !job.getLeaseExpiresAt().isAfter(java.time.Instant.now())) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "Logs require an active worker claim");
        }
        // This deployment has one canonical writer: the worker's attempt log file.
        return ResponseEntity.status(org.springframework.http.HttpStatus.GONE).build();
    }
}
