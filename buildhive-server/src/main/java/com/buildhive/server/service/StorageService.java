package com.buildhive.server.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Manages physical disk storage directories and secure file persistence for uploads, workspaces, logs, and artifacts.
 */
@Service
public class StorageService {

    public static final long MAX_UPLOAD_BYTES = 50L * 1024 * 1024; // 50 MB

    @Value("${buildhive.storage.root-path:./buildhive-data}")
    private String rootStoragePathString;

    private Path rootPath;
    private Path uploadsPath;
    private Path workspacesPath;
    private Path logsPath;
    private Path artifactsPath;

    public record StoredFile(Path path, String sha256, long sizeBytes) {}

    @PostConstruct
    public void init() throws IOException {
        this.rootPath = Paths.get(rootStoragePathString).toAbsolutePath().normalize();
        this.uploadsPath = rootPath.resolve("uploads");
        this.workspacesPath = rootPath.resolve("workspaces");
        this.logsPath = rootPath.resolve("logs");
        this.artifactsPath = rootPath.resolve("artifacts");

        Files.createDirectories(uploadsPath);
        Files.createDirectories(workspacesPath);
        Files.createDirectories(logsPath);
        Files.createDirectories(artifactsPath);
    }

    /**
     * Streams an uploaded multipart file to disk while validating size and computing its SHA-256 hash.
     *
     * @param file uploaded multipart file
     * @return StoredFile record containing file path, SHA-256 hash, and size in bytes
     */
    public StoredFile storeUpload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file cannot be empty");
        }

        long size = file.getSize();
        if (size > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("File exceeds maximum upload limit of 50 MB");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("Uploaded file must be a .zip archive");
        }

        // Clean filename to prevent filesystem traversal
        String sanitizedName = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
        String uniqueName = UUID.randomUUID() + "_" + sanitizedName;
        Path targetPath = uploadsPath.resolve(uniqueName).normalize();

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }

        long bytesWritten = 0;
        byte[] buffer = new byte[8192];

        try (InputStream is = file.getInputStream();
             DigestInputStream dis = new DigestInputStream(is, digest);
             OutputStream os = Files.newOutputStream(targetPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            int n;
            while ((n = dis.read(buffer)) != -1) {
                bytesWritten += n;
                if (bytesWritten > MAX_UPLOAD_BYTES) {
                    Files.deleteIfExists(targetPath);
                    throw new IllegalArgumentException("File exceeds maximum upload limit of 50 MB");
                }
                os.write(buffer, 0, n);
            }
        }

        String sha256 = HexFormat.of().formatHex(digest.digest());
        return new StoredFile(targetPath, sha256, bytesWritten);
    }

    public Path getUploadsPath() {
        return uploadsPath;
    }

    public Path getWorkspacesPath() {
        return workspacesPath;
    }

    public Path getLogsPath() {
        return logsPath;
    }

    public Path getArtifactsPath() {
        return artifactsPath;
    }

    /**
     * Resolves the log file path for a specific job within a pipeline.
     */
    public Path getJobLogPath(UUID pipelineId, UUID jobId) {
        if (pipelineId != null) {
            return logsPath.resolve(pipelineId.toString()).resolve(jobId.toString() + ".log");
        }
        return logsPath.resolve(jobId.toString() + ".log");
    }

    /**
     * Appends a batch of log lines to disk.
     */
    public synchronized void appendJobLogs(UUID pipelineId, UUID jobId, List<String> lines) throws IOException {
        Path logFile = getJobLogPath(pipelineId, jobId);
        if (logFile.getParent() != null && !Files.exists(logFile.getParent())) {
            Files.createDirectories(logFile.getParent());
        }
        Files.write(logFile, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Reads all recorded log lines for a specific job.
     */
    public List<String> readJobLogs(UUID pipelineId, UUID jobId) {
        Path logFile = getJobLogPath(pipelineId, jobId);
        if (Files.exists(logFile)) {
            try {
                return Files.readAllLines(logFile);
            } catch (IOException ignored) {
                return List.of();
            }
        }
        // Fallback: check flat logs directory
        Path flatFile = logsPath.resolve(jobId.toString() + ".log");
        if (Files.exists(flatFile)) {
            try {
                return Files.readAllLines(flatFile);
            } catch (IOException ignored) {
                return List.of();
            }
        }
        return List.of();
    }
}
