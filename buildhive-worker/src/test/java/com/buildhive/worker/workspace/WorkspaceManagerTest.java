package com.buildhive.worker.workspace;

import com.buildhive.common.security.ZipSecurityValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

    private WorkspaceManager workspaceManager;

    @BeforeEach
    void setUp() {
        Path workspacesRoot = tempDir.resolve("workspaces");
        workspaceManager = new WorkspaceManager(workspacesRoot, new ZipSecurityValidator());
    }

    @Test
    @DisplayName("createWorkspace creates expected directory hierarchy")
    void testCreateWorkspace() throws IOException {
        UUID pipelineId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        int attempt = 1;

        Path workspace = workspaceManager.createWorkspace(pipelineId, jobId, attempt);

        assertThat(Files.exists(workspace)).isTrue();
        assertThat(workspace.toString()).contains(pipelineId.toString());
        assertThat(workspace.toString()).contains(jobId.toString() + "_1");
    }

    @Test
    @DisplayName("extractArchive extracts ZIP with root pom.xml and resolves project root")
    void testExtractArchiveWithRootPom() throws IOException {
        UUID pipelineId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Path workspace = workspaceManager.createWorkspace(pipelineId, jobId, 1);

        Path zipFile = createSampleZip(false);

        Path projectRoot = workspaceManager.extractArchive(zipFile, workspace);

        assertThat(projectRoot).isEqualTo(workspace);
        assertThat(Files.exists(projectRoot.resolve("pom.xml"))).isTrue();
        assertThat(Files.exists(projectRoot.resolve("src/main/java/App.java"))).isTrue();
    }

    @Test
    @DisplayName("extractArchive extracts ZIP with nested folder pom.xml and resolves nested project root")
    void testExtractArchiveWithNestedPom() throws IOException {
        UUID pipelineId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Path workspace = workspaceManager.createWorkspace(pipelineId, jobId, 1);

        Path zipFile = createSampleZip(true);

        Path projectRoot = workspaceManager.extractArchive(zipFile, workspace);

        assertThat(projectRoot).isEqualTo(workspace.resolve("my-app"));
        assertThat(Files.exists(projectRoot.resolve("pom.xml"))).isTrue();
        assertThat(Files.exists(projectRoot.resolve("src/main/java/App.java"))).isTrue();
    }

    @Test
    @DisplayName("cleanWorkspace deletes attempt directory")
    void testCleanWorkspace() throws IOException {
        UUID pipelineId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Path workspace = workspaceManager.createWorkspace(pipelineId, jobId, 1);

        assertThat(Files.exists(workspace)).isTrue();

        boolean cleaned = workspaceManager.cleanWorkspace(pipelineId, jobId, 1);

        assertThat(cleaned).isTrue();
        assertThat(Files.exists(workspace)).isFalse();
    }

    @Test
    @DisplayName("cleanPipelineWorkspaces deletes entire pipeline folder")
    void testCleanPipelineWorkspaces() throws IOException {
        UUID pipelineId = UUID.randomUUID();
        UUID job1 = UUID.randomUUID();
        UUID job2 = UUID.randomUUID();

        workspaceManager.createWorkspace(pipelineId, job1, 1);
        workspaceManager.createWorkspace(pipelineId, job2, 1);

        Path pipelineDir = workspaceManager.getWorkspacesRoot().resolve(pipelineId.toString());
        assertThat(Files.exists(pipelineDir)).isTrue();

        boolean cleaned = workspaceManager.cleanPipelineWorkspaces(pipelineId);

        assertThat(cleaned).isTrue();
        assertThat(Files.exists(pipelineDir)).isFalse();
    }

    private Path createSampleZip(boolean nested) throws IOException {
        Path zipPath = tempDir.resolve("test-project.zip");
        String prefix = nested ? "my-app/" : "";

        String pomXml = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                </project>
                """;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(prefix + "pom.xml"));
            zos.write(pomXml.getBytes());
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(prefix + "src/main/java/App.java"));
            zos.write("public class App {}".getBytes());
            zos.closeEntry();
        }

        Files.write(zipPath, baos.toByteArray());
        return zipPath;
    }
}
