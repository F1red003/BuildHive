package com.buildhive.server.controller;

import com.buildhive.common.model.JobState;
import com.buildhive.common.model.JobType;
import com.buildhive.server.entity.ArtifactEntity;
import com.buildhive.server.entity.JobEntity;
import com.buildhive.server.entity.PipelineEntity;
import com.buildhive.server.repository.ArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ArtifactDownloadControllerTest {

    @Mock
    private ArtifactRepository artifactRepository;

    @Mock private com.buildhive.server.service.ArtifactPublicationService publication;

    @InjectMocks
    private ArtifactDownloadController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/artifacts/{id}/download returns file content with attachment headers")
    void testDownloadArtifactSuccess(@TempDir Path tempDir) throws Exception {
        Path jarFile = tempDir.resolve("my-app-1.0.jar");
        byte[] content = "JAR_BINARY_BYTES".getBytes();
        Files.write(jarFile, content);

        UUID artifactId = UUID.randomUUID();
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(UUID.randomUUID());
        JobEntity job = new JobEntity(pipeline, JobType.PACKAGING, JobState.SUCCEEDED, 60);
        job.setId(UUID.randomUUID());

        ArtifactEntity artifact = new ArtifactEntity(
                pipeline,
                job,
                "PACKAGE",
                "my-app-1.0.jar",
                jarFile.toString(),
                content.length,
                "mock-sha256"
        );
        artifact.setId(artifactId);

        when(artifactRepository.findById(artifactId)).thenReturn(Optional.of(artifact));
        when(publication.downloadPath(artifact)).thenReturn(jarFile);

        mockMvc.perform(get("/api/artifacts/{artifactId}/download", artifactId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"my-app-1.0.jar\""))
                .andExpect(content().contentType("application/java-archive"))
                .andExpect(content().bytes(content));
    }

    @Test
    @DisplayName("GET /api/artifacts/{id}/download returns 404 when artifact is not in database")
    void testDownloadArtifactNotFoundInDb() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        when(artifactRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/artifacts/{artifactId}/download", nonExistentId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/artifacts/{id}/download returns 404 when file is missing on storage disk")
    void testDownloadArtifactMissingOnDisk() throws Exception {
        UUID artifactId = UUID.randomUUID();
        PipelineEntity pipeline = new PipelineEntity();
        JobEntity job = new JobEntity();

        ArtifactEntity artifact = new ArtifactEntity(
                pipeline,
                job,
                "PACKAGE",
                "missing.jar",
                "/non/existent/path/missing.jar",
                100,
                "sha256"
        );
        artifact.setId(artifactId);

        when(artifactRepository.findById(artifactId)).thenReturn(Optional.of(artifact));
        when(publication.downloadPath(artifact)).thenThrow(new IllegalArgumentException("Unavailable"));

        mockMvc.perform(get("/api/artifacts/{artifactId}/download", artifactId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/pipelines/{pipelineId}/artifacts lists all artifacts with download URLs")
    void testListPipelineArtifacts() throws Exception {
        UUID pipelineId = UUID.randomUUID();
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId(pipelineId);

        JobEntity job = new JobEntity();
        job.setId(UUID.randomUUID());

        UUID artId1 = UUID.randomUUID();
        ArtifactEntity art1 = new ArtifactEntity(pipeline, job, "PACKAGE", "app.jar", "/path/app.jar", 1024, "sha-1");
        art1.setId(artId1);

        UUID artId2 = UUID.randomUUID();
        ArtifactEntity art2 = new ArtifactEntity(pipeline, job, "REPORT", "build-report.html", "/path/report.html", 2048, "sha-2");
        art2.setId(artId2);

        when(artifactRepository.findByPipelineId(pipelineId)).thenReturn(List.of(art1, art2));

        mockMvc.perform(get("/api/pipelines/{pipelineId}/artifacts", pipelineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(artId1.toString()))
                .andExpect(jsonPath("$[0].fileName").value("app.jar"))
                .andExpect(jsonPath("$[0].downloadUrl").value("/api/artifacts/" + artId1 + "/download"))
                .andExpect(jsonPath("$[1].id").value(artId2.toString()))
                .andExpect(jsonPath("$[1].fileName").value("build-report.html"))
                .andExpect(jsonPath("$[1].downloadUrl").value("/api/artifacts/" + artId2 + "/download"));
    }
}
