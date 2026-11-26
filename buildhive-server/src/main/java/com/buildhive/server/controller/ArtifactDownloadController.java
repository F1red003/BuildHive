package com.buildhive.server.controller;

import com.buildhive.server.dto.ArtifactResponseDto;
import com.buildhive.server.entity.ArtifactEntity;
import com.buildhive.server.repository.ArtifactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Controller handling artifact metadata listing and binary downloads (JARs, reports).
 */
@RestController
@CrossOrigin
public class ArtifactDownloadController {

    private static final Logger log = LoggerFactory.getLogger(ArtifactDownloadController.class);

    private final ArtifactRepository artifactRepository;

    private final com.buildhive.server.service.ArtifactPublicationService publication;

    public ArtifactDownloadController(ArtifactRepository artifactRepository,
                                      com.buildhive.server.service.ArtifactPublicationService publication) {
        this.publication = publication;
        this.artifactRepository = artifactRepository;
    }

    /**
     * Streams the requested build artifact file (JAR, test report, HTML summary) as a downloadable attachment.
     */
    @GetMapping("/api/artifacts/{artifactId}/download")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Resource> downloadArtifact(@PathVariable("artifactId") UUID artifactId) {
        artifactRepository.lockById(artifactId);
        ArtifactEntity artifact = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found: " + artifactId));

        Path filePath;
        try {
            filePath = publication.downloadPath(artifact);
        } catch (IllegalArgumentException | java.io.UncheckedIOException unavailable) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact file unavailable or invalid");
        }

        MediaType mediaType = resolveMediaType(artifact.getFileName());
        Resource resource = new FileSystemResource(filePath);

        long contentLength = artifact.getFileSizeBytes();
        if (contentLength <= 0) {
            try {
                contentLength = Files.size(filePath);
            } catch (Exception ignored) {
            }
        }

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(artifact.getFileName())
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(disposition);
        headers.setContentType(mediaType);
        if (contentLength > 0) {
            headers.setContentLength(contentLength);
        }

        return ResponseEntity.ok()
                .headers(headers)
                .body(resource);
    }

    /**
     * Lists all artifacts generated across all stages of a pipeline.
     */
    @GetMapping("/api/pipelines/{pipelineId}/artifacts")
    public ResponseEntity<List<ArtifactResponseDto>> listPipelineArtifacts(@PathVariable("pipelineId") UUID pipelineId) {
        List<ArtifactEntity> artifacts = artifactRepository.findByPipelineId(pipelineId);

        List<ArtifactResponseDto> dtos = artifacts.stream()
                .map(a -> new ArtifactResponseDto(
                        a.getId(),
                        a.getJob() != null ? a.getJob().getId() : null,
                        a.getPipeline().getId(),
                        a.getArtifactType(),
                        a.getFileName(),
                        a.getFileSizeBytes(),
                        a.getSha256(),
                        "/api/artifacts/" + a.getId() + "/download",
                        a.getCreatedAt()
                ))
                .toList();

        return ResponseEntity.ok(dtos);
    }

    private MediaType resolveMediaType(String fileName) {
        if (fileName == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".jar")) {
            return MediaType.parseMediaType("application/java-archive");
        } else if (lower.endsWith(".xml")) {
            return MediaType.APPLICATION_XML;
        } else if (lower.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return MediaType.TEXT_HTML;
        } else if (lower.endsWith(".txt") || lower.endsWith(".log")) {
            return MediaType.TEXT_PLAIN;
        } else if (lower.endsWith(".zip")) {
            return MediaType.parseMediaType("application/zip");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
