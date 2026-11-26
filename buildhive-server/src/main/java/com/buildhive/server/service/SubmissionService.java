package com.buildhive.server.service;

import com.buildhive.common.security.MavenProjectMetadata;
import com.buildhive.common.security.ZipSecurityValidator;
import com.buildhive.server.dto.SubmissionResponseDto;
import com.buildhive.server.entity.PipelineEntity;
import com.buildhive.server.entity.ProjectEntity;
import com.buildhive.server.entity.SubmissionEntity;
import com.buildhive.server.repository.ProjectRepository;
import com.buildhive.server.repository.SubmissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Handles project archive upload processing, security inspection, project registration,
 * and pipeline instantiation.
 */
@Service
public class SubmissionService {

    private final StorageService storageService;
    private final ProjectRepository projectRepository;
    private final SubmissionRepository submissionRepository;
    private final PipelineService pipelineService;
    private final ZipSecurityValidator zipSecurityValidator;

    public SubmissionService(
            StorageService storageService,
            ProjectRepository projectRepository,
            SubmissionRepository submissionRepository,
            PipelineService pipelineService
    ) {
        this.storageService = storageService;
        this.projectRepository = projectRepository;
        this.submissionRepository = submissionRepository;
        this.pipelineService = pipelineService;
        this.zipSecurityValidator = new ZipSecurityValidator();
    }

    /**
     * Processes an uploaded Maven project ZIP file, validates security/structure,
     * persists project coordinates, and triggers pipeline creation.
     *
     * @param file                the uploaded ZIP archive
     * @param optionalProjectName custom project name (optional)
     * @return submission response details including submissionId and pipelineId
     * @throws IOException if a disk I/O error occurs
     */
    @Transactional
    public SubmissionResponseDto processSubmission(MultipartFile file, String optionalProjectName) throws IOException {
        // 1. Store uploaded file safely while calculating its SHA-256 hash
        StorageService.StoredFile storedFile = storageService.storeUpload(file);

        // 2. Validate ZIP security (Zip Slip, bomb, extensions) and parse Maven pom.xml metadata
        MavenProjectMetadata metadata = zipSecurityValidator.validateAndInspect(storedFile.path());

        // 3. Resolve project name: user provided or fallback to Maven artifactId
        String projectName;
        if (optionalProjectName != null && !optionalProjectName.isBlank()) {
            projectName = optionalProjectName.trim();
        } else {
            projectName = metadata.artifactId();
        }

        // 4. Find or create ProjectEntity
        ProjectEntity project = projectRepository.findByName(projectName)
                .orElseGet(() -> projectRepository.save(new ProjectEntity(projectName)));

        // 5. Create SubmissionEntity
        SubmissionEntity submission = new SubmissionEntity(
                project,
                storedFile.path().toString(),
                storedFile.sha256()
        );
        submission = submissionRepository.save(submission);

        // 6. Create and queue execution pipeline DAG
        PipelineEntity pipeline = pipelineService.createPipelineForSubmission(submission);

        return new SubmissionResponseDto(
                submission.getId(),
                pipeline.getId(),
                project.getId(),
                project.getName(),
                pipeline.getStatus(),
                submission.getChecksumSha256(),
                submission.getCreatedAt()
        );
    }
}
