package com.buildhive.worker.executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class MavenProcessRunnerTest {

    @TempDir
    Path tempDir;

    private MavenProcessRunner processRunner;
    private Path logsRoot;

    @BeforeEach
    void setUp() {
        logsRoot = tempDir.resolve("logs");
        processRunner = new MavenProcessRunner(
                logsRoot,
                null,
                null,
                null,
                null,
                Duration.ofMinutes(5),
                Executors.newCachedThreadPool()
        );
    }

    @Test
    @DisplayName("runProcess captures output and writes log file for successful command")
    void testRunProcessSuccess() throws IOException {
        UUID pipelineId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        List<String> liveLines = new ArrayList<>();
        ProcessResult result = processRunner.runProcess(
                pipelineId,
                jobId,
                tempDir,
                List.of("java", "--version"),
                Duration.ofSeconds(10),
                liveLines::add
        );

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);

        // Verify log file was written
        assertThat(Files.exists(result.logFilePath())).isTrue();
        String logContent = Files.readString(result.logFilePath());
        assertThat(logContent.toLowerCase()).contains("openjdk");

        // Verify live consumer received lines
        assertThat(liveLines).isNotEmpty();
    }

    @Test
    @DisplayName("runProcess terminates process and sets timedOut when timeout expires")
    void testRunProcessTimeout() throws IOException {
        UUID pipelineId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> sleepCommand = isWindows
                ? List.of("powershell", "-Command", "Start-Sleep -Seconds 5")
                : List.of("sleep", "5");

        ProcessResult result = processRunner.runProcess(
                pipelineId,
                jobId,
                tempDir,
                sleepCommand,
                Duration.ofMillis(200),
                null
        );

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(124);
        assertThat(result.isSuccess()).isFalse();

        assertThat(Files.exists(result.logFilePath())).isTrue();
        String logContent = Files.readString(result.logFilePath());
        assertThat(logContent).contains("[ERROR] Build execution timed out");
    }

    @Test
    @DisplayName("resolveMavenExecutable resolves mvn.cmd on Windows or mvn on Unix")
    void testResolveMavenExecutable() {
        String exe = processRunner.resolveMavenExecutable();
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        if (isWindows) {
            assertThat(exe).endsWith("mvn.cmd");
        } else {
            assertThat(exe).endsWith("mvn");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void interruptOrTimeoutStopsTheProcessAndItsChild(boolean interrupt) throws Exception {
        Path parentPidFile = tempDir.resolve("parent.pid");
        Path childPidFile = tempDir.resolve("child.pid");
        var result = new java.util.concurrent.atomic.AtomicReference<ProcessResult>();
        Thread execution = new Thread(() -> result.set(processRunner.runProcess(
                UUID.randomUUID(), UUID.randomUUID(), tempDir,
                List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                        ProcessTree.class.getName(), parentPidFile.toString(), childPidFile.toString()),
                Duration.ofSeconds(interrupt ? 30 : 3), null)));
        execution.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while ((!Files.exists(childPidFile) || Files.size(childPidFile) == 0) && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertThat(Files.exists(childPidFile)).isTrue();
            long parentPid = Long.parseLong(Files.readString(parentPidFile));
            long childPid = Long.parseLong(Files.readString(childPidFile));
            if (interrupt) {
                execution.interrupt();
            }
            execution.join(10000);
            assertThat(execution.isAlive()).isFalse();
            assertThat(result.get().exitCode()).isEqualTo(interrupt ? 130 : 124);
            assertThat(ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        } finally {
            for (Path file : List.of(childPidFile, parentPidFile)) {
                if (Files.exists(file)) {
                    ProcessHandle.of(Long.parseLong(Files.readString(file))).ifPresent(ProcessHandle::destroyForcibly);
                }
            }
            execution.interrupt();
            execution.join(5000);
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }

    public static class ProcessTree {
        public static void main(String[] args) throws Exception {
            if (args.length == 1) {
                Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(30000);
                return;
            }
            Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
            Process child = new ProcessBuilder(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                    ProcessTree.class.getName(), args[1]).inheritIO().start();
            child.waitFor();
        }
    }

    @Test
    void largeOutputKeepsOnlyABoundedDiagnosticTail() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!Files.exists(Path.of(java))) java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var result = processRunner.runProcess(UUID.randomUUID(), UUID.randomUUID(), tempDir,
                List.of(java, "-cp", System.getProperty("java.class.path"), LoudProcess.class.getName()),
                Duration.ofSeconds(20), null);
        assertThat(result.exitCode()).isZero();
        assertThat(result.outputLines()).hasSizeLessThanOrEqualTo(200);
        assertThat(Files.readString(result.logFilePath())).contains("line-0", "line-4999");
    }

    public static class LoudProcess {
        public static void main(String[] args) {
            for (int i = 0; i < 5000; i++) System.out.println("line-" + i);
            System.out.println("x".repeat(100000));
        }
    }
}
