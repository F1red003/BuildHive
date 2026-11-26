package com.buildhive.server.controller;

import com.buildhive.server.dto.SubmissionResponseDto;
import com.buildhive.server.service.SubmissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * REST controller for Maven project submission and archive uploads.
 */
@RestController
@RequestMapping("/api/submissions")
public class SubmissionController {

    private final SubmissionService submissionService;

    public SubmissionController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    /**
     * Uploads a Maven project ZIP archive, validates its structure, and queues a build pipeline.
     *
     * @param file        the ZIP archive containing pom.xml and source code
     * @param projectName optional custom display name for the project
     * @return HTTP 201 with submission and created pipeline metadata
     * @throws IOException on file I/O errors
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SubmissionResponseDto> submitProject(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "projectName", required = false) String projectName
    ) throws IOException {
        SubmissionResponseDto response = submissionService.processSubmission(file, projectName);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
