package com.buildhive.server.service;

import com.buildhive.server.dto.ArtifactRegistrationDto;
import com.buildhive.server.entity.ArtifactEntity;
import com.buildhive.server.entity.JobEntity;
import com.buildhive.server.repository.ArtifactRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Publishes verified immutable copies; worker paths never become download locations. */
@Service
public class ArtifactPublicationService {
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private final StorageService storage;
    private final ArtifactRepository artifacts;

    public ArtifactPublicationService(StorageService storage, ArtifactRepository artifacts) {
        this.storage = storage;
        this.artifacts = artifacts;
    }

    public ArtifactEntity publish(JobEntity job, ArtifactRegistrationDto request) {
        validateMetadata(request.fileName(), request.fileSizeBytes(), request.sha256());
        if (!request.artifactType().matches("[A-Z_]{1,50}")) throw new IllegalArgumentException("Invalid artifact type");
        String key = hex(digest().digest((job.getId() + "\0" + job.getClaimToken() + "\0" +
                request.artifactType() + "\0" + request.fileName()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var previous = artifacts.findByPublicationKey(key);
        if (previous.isPresent()) {
            var artifact = previous.get();
            if (!artifact.getSha256().equalsIgnoreCase(request.sha256()) || artifact.getFileSizeBytes() != request.fileSizeBytes()) {
                throw new IllegalStateException("Conflicting artifact publication for this attempt");
            }
            verifyStored(artifact);
            return artifact;
        }
        Path workspace = storage.getWorkspacesPath().resolve(job.getPipeline().getId().toString())
                .resolve(job.getId() + "_" + job.getRetryCount());
        Path source = safeFile(workspace, Path.of(request.filePath()));
        Path target = copyVerified(source, request.fileSizeBytes(), request.sha256());
        var artifact = new ArtifactEntity(job.getPipeline(), job, request.artifactType(), request.fileName(),
                target.toString(), request.fileSizeBytes(), request.sha256().toLowerCase(Locale.ROOT));
        artifact.setPublicationKey(key);
        return artifacts.saveAndFlush(artifact);
    }

    /** Server-generated reports use the same private, verified publication path as worker artifacts. */
    public ArtifactEntity publishGenerated(JobEntity job, String type, String name, byte[] bytes) {
        String key = hex(digest().digest((job.getPipeline().getId() + ":" + type)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var previous = artifacts.findByPublicationKey(key);
        if (previous.isPresent()) return previous.get();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(storage.getArtifactsPath(), ".report-", ".tmp");
            Files.write(temporary, bytes);
            String checksum = hex(digest().digest(bytes));
            Path target = copyVerified(temporary, bytes.length, checksum);
            var artifact = new ArtifactEntity(job.getPipeline(), job, type, name, target.toString(), bytes.length, checksum);
            artifact.setPublicationKey(key);
            return artifacts.saveAndFlush(artifact);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }

    /** Called within the download transaction; legacy workspace records are promoted on first read. */
    public Path downloadPath(ArtifactEntity artifact) {
        Path file = Path.of(artifact.getFilePath()).toAbsolutePath().normalize();
        if (!file.startsWith(storage.getArtifactsPath().toAbsolutePath().normalize())) {
            Path pipelineRoot = storage.getWorkspacesPath().resolve(artifact.getPipeline().getId().toString());
            Path relative;
            try { relative = pipelineRoot.toAbsolutePath().normalize().relativize(file); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid legacy artifact path"); }
            if (relative.getNameCount() < 2 || !relative.getName(0).toString().matches(
                    java.util.regex.Pattern.quote(artifact.getJob().getId().toString()) + "_[1-9][0-9]*")) {
                throw new IllegalArgumentException("Artifact is outside managed storage");
            }
            validateMetadata(artifact.getFileName(), artifact.getFileSizeBytes(), artifact.getSha256());
            Path source = safeFile(pipelineRoot, Path.of(artifact.getFilePath()));
            file = copyVerified(source, artifact.getFileSizeBytes(), artifact.getSha256());
            artifact.setFilePath(file.toString());
            artifacts.saveAndFlush(artifact);
        }
        return verifyStored(artifact);
    }

    private Path verifyStored(ArtifactEntity artifact) {
        Path path = safeFile(storage.getArtifactsPath(), Path.of(artifact.getFilePath()));
        verifyBytes(path, artifact.getFileSizeBytes(), artifact.getSha256(), null);
        return path;
    }

    static Path safeFile(Path root, Path candidate) {
        for (Path component : candidate) {
            if (component.toString().equals("..")) throw new IllegalArgumentException("Path traversal is forbidden");
        }
        Path base = root.toAbsolutePath().normalize();
        Path file = candidate.toAbsolutePath().normalize();
        if (!file.startsWith(base) || file.equals(base)) throw new IllegalArgumentException("File is outside its managed directory");
        // Check every ancestor, including the attempt directory itself, before opening the source.
        for (Path current = file; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Symbolic links are forbidden");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Artifact file is unavailable");
        try {
            // Reject directory junctions/reparse redirects too (not all are reported as symbolic links).
            if (!file.toRealPath().equals(file)) throw new IllegalArgumentException("Redirected artifact paths are forbidden");
        } catch (IOException unavailable) {
            throw new IllegalArgumentException("Artifact file is unavailable", unavailable);
        }
        return file;
    }

    private Path copyVerified(Path source, long size, String checksum) {
        Path temporary = null;
        try {
            Path directory = storage.getArtifactsPath();
            if (Files.isSymbolicLink(directory)) throw new IllegalArgumentException("Invalid artifact storage directory");
            temporary = Files.createTempFile(directory, ".publishing-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.WRITE)) {
                verifyBytes(source, size, checksum, output);
            }
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Path target = directory.resolve(UUID.randomUUID() + ".bin");
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            try { Files.deleteIfExists(target); } catch (IOException ignored) { /* retention cleanup */ }
                        }
                    }
                });
            }
            return target;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Artifact publication failed", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* retention cleanup */ }
            }
        }
    }

    private void verifyBytes(Path path, long expectedSize, String checksum, OutputStream output) {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            long size = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > MAX_BYTES || size > expectedSize) throw new IllegalArgumentException("Artifact size mismatch or limit exceeded");
                digest.update(buffer, 0, count);
                if (output != null) output.write(buffer, 0, count);
            }
            if (size != expectedSize || !hex(digest.digest()).equalsIgnoreCase(checksum)) {
                throw new IllegalArgumentException("Artifact size or SHA-256 mismatch");
            }
        } catch (IOException e) { throw new UncheckedIOException("Artifact file is unavailable", e); }
    }

    private static void validateMetadata(String name, long size, String checksum) {
        if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}") || name.contains("..")) {
            throw new IllegalArgumentException("Invalid artifact filename");
        }
        if (size < 1 || size > MAX_BYTES || checksum == null || !checksum.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("Invalid artifact size or SHA-256");
        }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
}
