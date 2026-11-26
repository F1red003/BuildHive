package com.buildhive.worker.executor;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Executes Maven build commands inside isolated workspaces, manages environment variables,
 * enforces timeouts, and asynchronously streams output to persistent log files and live buffers.
 */
@Component
public class MavenProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(MavenProcessRunner.class);

    private final Path logsRoot;
    private final ThreadLocal<Integer> activeAttempt = ThreadLocal.withInitial(() -> 1);
    private final String configuredMavenExecutable;
    private final String configuredJavaHome;
    private final String configuredM2Home;
    private final String configuredMavenOpts;
    private final Duration defaultTimeout;
    private final ExecutorService logDrainExecutor;

    @Autowired
    public MavenProcessRunner(
            @Value("${buildhive.worker.logs-root:./buildhive-data/logs}") String logsRootString,
            @Value("${buildhive.worker.maven.executable:}") String configuredMavenExecutable,
            @Value("${buildhive.worker.maven.java-home:}") String configuredJavaHome,
            @Value("${buildhive.worker.maven.m2-home:}") String configuredM2Home,
            @Value("${buildhive.worker.maven.opts:}") String configuredMavenOpts,
            @Value("${buildhive.worker.maven.default-timeout-seconds:300}") long defaultTimeoutSeconds
    ) {
        this(
                Paths.get(logsRootString),
                configuredMavenExecutable,
                configuredJavaHome,
                configuredM2Home,
                configuredMavenOpts,
                Duration.ofSeconds(defaultTimeoutSeconds),
                Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "maven-log-drainer");
                    t.setDaemon(true);
                    return t;
                })
        );
    }

    public MavenProcessRunner(
            Path logsRoot,
            String configuredMavenExecutable,
            String configuredJavaHome,
            String configuredM2Home,
            String configuredMavenOpts,
            Duration defaultTimeout,
            ExecutorService logDrainExecutor
    ) {
        this.logsRoot = logsRoot.toAbsolutePath().normalize();
        this.configuredMavenExecutable = configuredMavenExecutable;
        this.configuredJavaHome = configuredJavaHome;
        this.configuredM2Home = configuredM2Home;
        this.configuredMavenOpts = configuredMavenOpts;
        this.defaultTimeout = defaultTimeout != null ? defaultTimeout : Duration.ofMinutes(5);
        this.logDrainExecutor = logDrainExecutor;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(logsRoot);
    }

    /**
     * Executes a Maven command using the default timeout and without custom log line consumer.
     */
    public ProcessResult runMavenCommand(
            UUID pipelineId,
            UUID jobId,
            Path workingDirectory,
            List<String> goalsAndArgs
    ) {
        return runMavenCommand(pipelineId, jobId, workingDirectory, goalsAndArgs, defaultTimeout, null);
    }

    /**
     * Executes a Maven command in the specified workspace with goals, arguments, timeout, and log consumer.
     *
     * @param pipelineId       pipeline identifier for log directory
     * @param jobId            job identifier for log filename
     * @param workingDirectory workspace project root containing pom.xml
     * @param goalsAndArgs     Maven goals, phases, and flags (e.g. ["compile"], ["test"])
     * @param timeout          maximum execution duration before forced termination
     * @param logLineConsumer  optional consumer for live line forwarding
     * @return ProcessResult with exitCode, duration, timedOut flag, and outputLines
     */
    public ProcessResult runMavenCommand(
            UUID pipelineId,
            UUID jobId,
            Path workingDirectory,
            List<String> goalsAndArgs,
            Duration timeout,
            Consumer<String> logLineConsumer
    ) {
        String mavenExe = resolveMavenExecutable();
        List<String> command = new ArrayList<>();
        command.add(mavenExe);
        command.add("-B"); // Batch mode (no interactive questions)
        command.add("-e"); // Produce execution error messages

        if (goalsAndArgs != null) {
            command.addAll(goalsAndArgs);
        }

        return runProcess(pipelineId, jobId, workingDirectory, command, timeout, logLineConsumer);
    }

    /**
     * General process runner executing an arbitrary command line list, redirecting output to logs.
     */
    public ProcessResult runProcess(
            UUID pipelineId,
            UUID jobId,
            Path workingDirectory,
            List<String> command,
            Duration timeout,
            Consumer<String> logLineConsumer
    ) {
        Path logFilePath = getLogFilePath(pipelineId, jobId);
        if (Thread.currentThread().isInterrupted()) {
            return new ProcessResult(130, 0, true, logFilePath);
        }

        try {
            Files.createDirectories(logFilePath.getParent());
        } catch (IOException e) {
            log.error("Failed to create log directory for job {}: {}", jobId, e.getMessage());
        }

        log.info("Executing command in '{}': {}", workingDirectory, String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());
        pb.redirectErrorStream(true); // Merge stderr into stdout

        configureEnvironment(pb);

        long startTime = System.currentTimeMillis();
        boolean timedOut = false;
        int exitCode = -1;
        java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
        Process process = null;
        Future<?> drainFuture = null;
        try (BufferedWriter writer = Files.newBufferedWriter(logFilePath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            Consumer<String> append = line -> {
                synchronized (writer) {
                    try {
                        writer.write(line); writer.newLine(); writer.flush();
                        if (tail.size() == 200) tail.removeFirst();
                        tail.addLast(line);
                        if (logLineConsumer != null) logLineConsumer.accept(line);
                    } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                }
            };
            try {
                process = pb.start();
                Process started = process;
                drainFuture = logDrainExecutor.submit(() -> {
                    try (var reader = new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8)) {
                        StringBuilder line = new StringBuilder(8192);
                        char[] buffer = new char[4096];
                        int count;
                        while ((count = reader.read(buffer)) != -1) {
                            for (int i = 0; i < count; i++) {
                                char c = buffer[i];
                                if (c == '\n') {
                                    append.accept(line.toString().replaceFirst("\\r$", "")); line.setLength(0);
                                } else {
                                    line.append(c);
                                    if (line.length() >= 8192 && !Character.isHighSurrogate(c)) {
                                        append.accept(line.toString()); line.setLength(0);
                                    }
                                }
                            }
                        }
                        if (!line.isEmpty()) append.accept(line.toString());
                    } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                });
                Duration effectiveTimeout = timeout != null ? timeout : defaultTimeout;
                if (!process.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    terminateProcessTree(process); timedOut = true; exitCode = 124;
                } else { exitCode = process.exitValue(); }
                drainFuture.get(5, TimeUnit.SECONDS);
                if (timedOut) append.accept("[ERROR] Build execution timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); timedOut = true; exitCode = 130;
            } catch (Exception failure) {
                timedOut = true; exitCode = 125;
                append.accept("[ERROR] Process or log capture failed: " + failure.getClass().getSimpleName());
            } finally {
                if (process != null && process.isAlive()) terminateProcessTree(process);
                boolean interrupted = Thread.interrupted();
                try {
                    if (drainFuture != null) drainFuture.get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    if (drainFuture != null) drainFuture.cancel(true);
                } finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        } catch (IOException storageFailure) {
            timedOut = true; exitCode = 125;
        }
        long durationMs = System.currentTimeMillis() - startTime;
        return new ProcessResult(exitCode, durationMs, timedOut, logFilePath, List.copyOf(tail));
    }

    /** One worker owns each attempt file. The done marker is written only after process drains close. */
    public AutoCloseable openAttempt(UUID pipelineId, UUID jobId, int attemptNumber) throws IOException {
        activeAttempt.set(attemptNumber);
        Path path = getLogFilePath(pipelineId, jobId);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "[INFO] Starting attempt " + attemptNumber + "\n", StandardCharsets.UTF_8);
        Files.deleteIfExists(Path.of(path + ".done"));
        return () -> {
            try {
                Files.writeString(path, "[INFO] Attempt execution ended\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                Files.writeString(Path.of(path + ".done"), "done", StandardCharsets.UTF_8);
            } finally { activeAttempt.remove(); }
        };
    }

    private void terminateProcessTree(Process process) {
        boolean interrupted = Thread.interrupted();
        List<ProcessHandle> descendants = process.descendants().toList();
        try {
            for (ProcessHandle child : descendants.reversed()) {
                child.destroyForcibly();
            }
            // Give the parent a chance to reap its children before terminating it.
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            interrupted = true;
            process.destroyForcibly();
        } finally {
            for (ProcessHandle child : descendants) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void configureEnvironment(ProcessBuilder pb) {
        // JAVA_HOME
        if (configuredJavaHome != null && !configuredJavaHome.isBlank()) {
            pb.environment().put("JAVA_HOME", configuredJavaHome);
        } else if (System.getenv("JAVA_HOME") != null) {
            pb.environment().put("JAVA_HOME", System.getenv("JAVA_HOME"));
        }

        // M2_HOME / MAVEN_HOME
        if (configuredM2Home != null && !configuredM2Home.isBlank()) {
            pb.environment().put("M2_HOME", configuredM2Home);
            pb.environment().put("MAVEN_HOME", configuredM2Home);
        } else if (System.getenv("M2_HOME") != null) {
            pb.environment().put("M2_HOME", System.getenv("M2_HOME"));
        }

        // MAVEN_OPTS
        if (configuredMavenOpts != null && !configuredMavenOpts.isBlank()) {
            pb.environment().put("MAVEN_OPTS", configuredMavenOpts);
        } else if (System.getenv("MAVEN_OPTS") != null) {
            pb.environment().put("MAVEN_OPTS", System.getenv("MAVEN_OPTS"));
        }
    }

    /**
     * Resolves the Maven executable command path based on OS and environment configuration.
     */
    public String resolveMavenExecutable() {
        if (configuredMavenExecutable != null && !configuredMavenExecutable.isBlank()) {
            return configuredMavenExecutable;
        }

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String m2Home = configuredM2Home != null && !configuredM2Home.isBlank()
                ? configuredM2Home
                : System.getenv("M2_HOME");

        if (m2Home == null || m2Home.isBlank()) {
            m2Home = System.getenv("MAVEN_HOME");
        }

        if (m2Home != null && !m2Home.isBlank()) {
            Path binMvn = Paths.get(m2Home, "bin", isWindows ? "mvn.cmd" : "mvn");
            if (Files.exists(binMvn)) {
                return binMvn.toString();
            }
        }

        return isWindows ? "mvn.cmd" : "mvn";
    }

    public Path getLogFilePath(UUID pipelineId, UUID jobId) {
        return logsRoot
                .resolve(pipelineId.toString())
                .resolve(jobId.toString())
                .resolve("attempt-" + activeAttempt.get() + ".log")
                .normalize();
    }

    public Path getLogsRoot() {
        return logsRoot;
    }
}
