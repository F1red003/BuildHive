package com.buildhive.worker.workspace;

import com.buildhive.common.security.ZipSecurityValidator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Manages isolated workspace directories per pipeline attempt on disk.
 * Safely extracts project archives and purges temporary files upon completion.
 */
@Component
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    private final Path workspacesRoot;
    private final ZipSecurityValidator zipSecurityValidator;

    @Autowired
    public WorkspaceManager(
            @Value("${buildhive.worker.workspaces-root:./buildhive-data/workspaces}") String workspacesRootString
    ) {
        this(Paths.get(workspacesRootString), new ZipSecurityValidator());
    }

    public WorkspaceManager(Path workspacesRoot, ZipSecurityValidator zipSecurityValidator) {
        this.workspacesRoot = workspacesRoot.toAbsolutePath().normalize();
        this.zipSecurityValidator = zipSecurityValidator;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(workspacesRoot);
    }

    /**
     * Creates and returns a unique temporary workspace directory for a job attempt:
     * {@code workspaces/{pipelineId}/{jobId}_{attempt}/}
     *
     * @param pipelineId pipeline identifier
     * @param jobId      job identifier
     * @param attempt    attempt number (e.g. 1, 2)
     * @return created workspace directory path
     * @throws IOException if workspace directory creation fails
     */
    public Path createWorkspace(UUID pipelineId, UUID jobId, int attempt) throws IOException {
        Path workspaceDir = getWorkspaceDir(pipelineId, jobId, attempt);
        Files.createDirectories(workspaceDir);
        log.debug("Created workspace directory: {}", workspaceDir);
        return workspaceDir;
    }

    /**
     * Safely extracts the project ZIP archive into the workspace directory and resolves the Maven project root.
     *
     * @param zipPath      path to project ZIP archive
     * @param workspaceDir target workspace directory
     * @return Path pointing to the directory containing pom.xml (either workspaceDir or a single root subdirectory)
     * @throws IOException if extraction fails or security violation occurs
     */
    public Path extractArchive(Path zipPath, Path workspaceDir) throws IOException {
        log.info("Extracting archive '{}' into workspace '{}'", zipPath.getFileName(), workspaceDir);
        zipSecurityValidator.safelyExtract(zipPath, workspaceDir);

        // Determine Maven root (root or single top-level folder containing pom.xml)
        if (Files.exists(workspaceDir.resolve("pom.xml"))) {
            return workspaceDir;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workspaceDir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child) && Files.exists(child.resolve("pom.xml"))) {
                    log.debug("Found nested project root containing pom.xml: {}", child);
                    return child;
                }
            }
        }

        return workspaceDir;
    }

    /**
     * Cleans up the specific job attempt workspace directory.
     *
     * @param pipelineId pipeline identifier
     * @param jobId      job identifier
     * @param attempt    attempt number
     * @return true if deleted or directory did not exist
     */
    public boolean cleanWorkspace(UUID pipelineId, UUID jobId, int attempt) {
        Path workspaceDir = getWorkspaceDir(pipelineId, jobId, attempt);
        try {
            if (Files.exists(workspaceDir)) {
                boolean deleted = FileSystemUtils.deleteRecursively(workspaceDir);
                log.info("Cleaned workspace directory: {} (success={})", workspaceDir, deleted);
                return deleted;
            }
            return true;
        } catch (IOException e) {
            log.warn("Failed to clean workspace directory {}: {}", workspaceDir, e.getMessage());
            return false;
        }
    }

    /**
     * Cleans up all workspaces belonging to a pipeline.
     *
     * @param pipelineId pipeline identifier
     * @return true if deleted or directory did not exist
     */
    public boolean cleanPipelineWorkspaces(UUID pipelineId) {
        Path pipelineDir = workspacesRoot.resolve(pipelineId.toString()).normalize();
        try {
            if (Files.exists(pipelineDir)) {
                return FileSystemUtils.deleteRecursively(pipelineDir);
            }
            return true;
        } catch (IOException e) {
            log.warn("Failed to clean pipeline workspaces {}: {}", pipelineDir, e.getMessage());
            return false;
        }
    }

    /**
     * Returns the workspace path for a job attempt.
     */
    public Path getWorkspaceDir(UUID pipelineId, UUID jobId, int attempt) {
        return workspacesRoot
                .resolve(pipelineId.toString())
                .resolve(jobId.toString() + "_" + attempt)
                .normalize();
    }

    public Path getWorkspacesRoot() {
        return workspacesRoot;
    }
}
