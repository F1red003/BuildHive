package com.buildhive.server.service;
import com.buildhive.server.entity.*;
import com.buildhive.common.model.*;
import com.buildhive.server.repository.JobRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class LogBroadcastServiceTest {
    @TempDir Path directory;
    LogBroadcastService service; JobEntity job; Path file;
    @BeforeEach void setUp() throws Exception {
        var jobs = mock(JobRepository.class); var storage = mock(StorageService.class);
        var pipeline = new PipelineEntity(); pipeline.setId(UUID.randomUUID());
        job = new JobEntity(pipeline, JobType.COMPILATION, JobState.RUNNING, 1);
        job.setId(UUID.randomUUID()); job.setRetryCount(1);
        job.setLeaseExpiresAt(java.time.Instant.now().plusSeconds(60));
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        var progress = mock(JobRepository.LogProgress.class);
        when(progress.getState()).thenAnswer(invocation -> job.getState());
        when(progress.getRetryCount()).thenAnswer(invocation -> job.getRetryCount());
        when(progress.getLeaseExpiresAt()).thenAnswer(invocation -> job.getLeaseExpiresAt());
        when(jobs.findLogProgress(job.getId())).thenReturn(Optional.of(progress));
        when(storage.getLogsPath()).thenReturn(directory);
        when(storage.getJobLogPath(pipeline.getId(), job.getId())).thenReturn(directory.resolve("legacy.log"));
        file = directory.resolve(pipeline.getId().toString()).resolve(job.getId().toString()).resolve("attempt-1.log");
        Files.createDirectories(file.getParent()); service = new LogBroadcastService(jobs, storage);
    }
    @Test void appendDuringReplayIsNotLost() throws Exception {
        Files.writeString(file, "first\n");
        var output = new ByteArrayOutputStream() { boolean appended;
            @Override public void flush() throws IOException {
                if (!appended) { appended = true; Files.writeString(file, "second\n", StandardOpenOption.APPEND);
                    Files.writeString(Path.of(file + ".done"), "done"); job.setState(JobState.SUCCEEDED); }
            }
        };
        service.stream(job.getId(), service.source(job.getId(), 1, null)).writeTo(output);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("id: 1:6", "id: 1:13", "data: first", "data: second", "event: complete");
    }
    @Test void reconnectAtUtf8OffsetHasNoDuplicate() throws Exception {
        String first = "été\n"; Files.writeString(file, first + "second\n");
        Files.writeString(Path.of(file + ".done"), "done"); job.setState(JobState.SUCCEEDED);
        var output = new ByteArrayOutputStream();
        service.stream(job.getId(), service.source(job.getId(), 1, "1:" + first.getBytes(StandardCharsets.UTF_8).length)).writeTo(output);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("second", "event: complete").doesNotContain("été");
    }
    @Test void attemptsAndCursorsAreSeparate() throws Exception {
        Files.writeString(file, "old\n"); job.setRetryCount(2);
        Path second = file.resolveSibling("attempt-2.log"); Files.writeString(second, "new\n");
        assertThat(service.source(job.getId(), 1, null).path()).isEqualTo(file);
        assertThat(service.source(job.getId(), null, null).path()).isEqualTo(second);
        assertThatThrownBy(() -> service.source(job.getId(), 2, "1:4")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void replayIsBounded() throws Exception {
        Files.writeString(file, "line\n".repeat(50000));
        byte[] batch = service.readBatch(file, 0);
        assertThat(batch.length).isLessThanOrEqualTo(65536);
        assertThat(batch[batch.length - 1]).isEqualTo((byte)'\n');
    }
}
