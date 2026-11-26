package com.buildhive.server.service;

import com.buildhive.server.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;

/** Cursor-based tailing of worker-owned attempt files; no replay/subscription handoff exists. */
@Service
public class LogBroadcastService {
    private final JobRepository jobs;
    private final StorageService storage;
    public LogBroadcastService(JobRepository jobs, StorageService storage) { this.jobs = jobs; this.storage = storage; }
    public record StreamSource(Path path, int attempt, long offset, boolean legacy) {}

    public StreamSource source(UUID jobId, Integer requestedAttempt, String cursor) {
        var job = jobs.findById(jobId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        int attempt = requestedAttempt == null ? Math.max(1, job.getRetryCount()) : requestedAttempt;
        if (attempt < 1 || attempt > Math.max(1, job.getRetryCount())) throw new IllegalArgumentException("Unknown attempt");
        long offset = 0;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = cursor.split(":", -1);
            try {
                if (parts.length != 2 || Integer.parseInt(parts[0]) != attempt) throw new IllegalArgumentException("Cursor attempt mismatch");
                offset = Long.parseLong(parts[1]);
            } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid log cursor", invalid); }
            if (offset < 0) throw new IllegalArgumentException("Negative log offset");
        }
        Path path = storage.getLogsPath().resolve(job.getPipeline().getId().toString())
                .resolve(jobId.toString()).resolve("attempt-" + attempt + ".log");
        boolean legacy = false;
        Path oldPath = storage.getJobLogPath(job.getPipeline().getId(), jobId);
        if (!Files.exists(path) && job.getRetryCount() <= 1 && Files.isRegularFile(oldPath)) { path = oldPath; legacy = true; }
        try {
            long size = Files.exists(path) ? Files.size(path) : 0;
            if (offset > size) throw new ResponseStatusException(HttpStatus.CONFLICT, "Log cursor is beyond available output");
            if (offset > 0) {
                try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
                    file.seek(offset - 1);
                    if (file.read() != '\n') throw new IllegalArgumentException("Cursor is not at a line boundary");
                }
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return new StreamSource(path, attempt, offset, legacy);
    }

    /** At most 64 KiB per event. New worker files split long lines at 8192 characters. */
    public byte[] readBatch(Path path, long offset) throws IOException {
        if (!Files.exists(path)) return new byte[0];
        try (RandomAccessFile input = new RandomAccessFile(path.toFile(), "r")) {
            input.seek(offset);
            byte[] buffer = new byte[65536];
            int count = input.read(buffer);
            if (count < 1) return new byte[0];
            for (int i = count - 1; i >= 0; i--) {
                if (buffer[i] == '\n') return java.util.Arrays.copyOf(buffer, i + 1);
            }
            // Old logs can contain a line longer than the new worker limit.
            if (count == buffer.length) throw new IOException("Legacy log line exceeds streaming limit; download the log instead");
            return new byte[0]; // A concurrent writer has not finished its line yet.
        }
    }

    public StreamingResponseBody stream(UUID jobId, StreamSource source) {
        return output -> {
            long offset = source.offset();
            long heartbeat = 0;
            long started = System.nanoTime();
            while (!Thread.currentThread().isInterrupted() && System.nanoTime() - started < 30L * 60 * 1_000_000_000) {
                // Read state before the file: completion is committed after the worker closes its log.
                var job = jobs.findLogProgress(jobId).orElseThrow();
                boolean terminal = job.getState().isTerminal() || job.getRetryCount() > source.attempt();
                byte[] batch = readBatch(source.path(), offset);
                if (batch.length > 0) {
                    offset += batch.length;
                    String text = new String(batch, StandardCharsets.UTF_8);
                    StringBuilder event = new StringBuilder("id: " + source.attempt() + ":" + offset + "\n");
                    for (String line : text.split("\n", -1)) event.append("data: ").append(line).append('\n');
                    event.append('\n');
                    output.write(event.toString().getBytes(StandardCharsets.UTF_8)); output.flush();
                    continue;
                }
                boolean closed = Files.exists(Path.of(source.path() + ".done")) || source.legacy()
                        || job.getRetryCount() == 0 || job.getRetryCount() > source.attempt()
                        || job.getLeaseExpiresAt() == null || job.getLeaseExpiresAt().plusSeconds(2).isBefore(Instant.now());
                if (terminal && closed) {
                    output.write("event: complete\ndata: STREAM_COMPLETED\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush(); return;
                }
                if (System.nanoTime() - heartbeat > 10_000_000_000L) {
                    output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8)); output.flush(); heartbeat = System.nanoTime();
                }
                try { Thread.sleep(250); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        };
    }

    /** Kept for lifecycle callers: streams now observe durable state instead of queued notifications. */
    public void completeStream(UUID jobId) { }
}
