package com.buildhive.server.controller;

import com.buildhive.server.dto.JobDetailDto;
import com.buildhive.server.dto.PipelineDetailDto;
import com.buildhive.server.entity.JobEntity;
import com.buildhive.server.entity.PipelineEntity;
import com.buildhive.server.service.PipelineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/pipelines")
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @GetMapping
    public ResponseEntity<List<PipelineDetailDto>> getAllPipelines() {
        List<PipelineEntity> pipelines = pipelineService.getAllPipelines();
        List<PipelineDetailDto> dtos = pipelines.stream()
                .map(this::toPipelineDetailDto)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/metrics")
    public ResponseEntity<com.buildhive.server.dto.DashboardMetricsDto> getMetrics() {
        return ResponseEntity.ok(pipelineService.getMetrics());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PipelineDetailDto> getPipeline(@PathVariable("id") UUID id) {
        return pipelineService.getPipeline(id)
                .map(this::toPipelineDetailDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/jobs")
    public ResponseEntity<List<JobDetailDto>> getPipelineJobs(@PathVariable("id") UUID id) {
        List<JobEntity> jobs = pipelineService.getPipelineJobs(id);
        List<JobDetailDto> dtos = jobs.stream()
                .map(this::toJobDetailDto)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelPipeline(@PathVariable("id") UUID id) {
        pipelineService.cancelPipeline(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/{id}/archive", produces = org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<org.springframework.core.io.Resource> downloadPipelineArchive(@PathVariable("id") UUID id) {
        return pipelineService.getPipeline(id)
                .map(pipeline -> {
                    String archivePath = pipeline.getSubmission().getArchivePath();
                    java.nio.file.Path path = java.nio.file.Paths.get(archivePath);
                    if (!java.nio.file.Files.exists(path)) {
                        return org.springframework.http.ResponseEntity.notFound().<org.springframework.core.io.Resource>build();
                    }
                    org.springframework.core.io.Resource resource = new org.springframework.core.io.FileSystemResource(path);
                    return org.springframework.http.ResponseEntity.ok()
                            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + path.getFileName().toString() + "\"")
                            .body(resource);
                })
                .orElseGet(() -> org.springframework.http.ResponseEntity.notFound().build());
    }

    private PipelineDetailDto toPipelineDetailDto(PipelineEntity entity) {
        String projectName = entity.getSubmission().getProject() != null
                ? entity.getSubmission().getProject().getName()
                : "Unknown";

        return new PipelineDetailDto(
                entity.getId(),
                entity.getSubmission().getId(),
                projectName,
                entity.getStatus(),
                entity.isCancellationRequested(),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getCompletedAt()
        );
    }

    private JobDetailDto toJobDetailDto(JobEntity job) {
        return new JobDetailDto(
                job.getId(),
                job.getPipeline().getId(),
                job.getJobType(),
                job.getState(),
                job.getPriority(),
                job.getWorkerId(),
                job.getRetryCount(),
                job.getFailureClassification(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt()
        );
    }
}
