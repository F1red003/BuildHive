package com.buildhive.worker.daemon;

import com.buildhive.common.model.FailureClassification;
import com.buildhive.worker.client.WorkerClient;
import com.buildhive.worker.dto.*;
import com.buildhive.worker.service.JobExecutionService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import org.springframework.web.client.HttpClientErrorException;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker daemon that registers with the server, emits periodic heartbeats,
 * polls for ready jobs with exponential backoff, and delegates execution.
 */
@Component
public class WorkerDaemon implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkerDaemon.class);

    private final WorkerClient workerClient;
    private final JobExecutionService jobExecutionService;

    private final String configuredWorkerName;
    private final String configuredHostname;
    private final long leaseSeconds;
    private final long heartbeatIntervalMs;
    private final long minPollIntervalMs;
    private final long maxPollIntervalMs;
    private final boolean pollingEnabled;

    private String workerId;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService heartbeatExecutor;
    private Thread pollingThread;
    private volatile ActiveAttempt activeAttempt;
    private volatile boolean stopping;

    public WorkerDaemon(
            WorkerClient workerClient,
            JobExecutionService jobExecutionService,
            @Value("${buildhive.worker.name:worker-1}") String configuredWorkerName,
            @Value("${buildhive.worker.hostname:}") String configuredHostname,
            @Value("${buildhive.worker.lease-seconds:300}") long leaseSeconds,
            @Value("${buildhive.worker.heartbeat-interval-ms:10000}") long heartbeatIntervalMs,
            @Value("${buildhive.worker.min-poll-interval-ms:1000}") long minPollIntervalMs,
            @Value("${buildhive.worker.max-poll-interval-ms:5000}") long maxPollIntervalMs,
            @Value("${buildhive.worker.polling-enabled:true}") boolean pollingEnabled
    ) {
        if (leaseSeconds < 1 || leaseSeconds > 300 || heartbeatIntervalMs < 1) {
            throw new IllegalArgumentException("Lease must be 1..300 seconds and heartbeat interval must be positive");
        }
        this.workerClient = workerClient;
        this.jobExecutionService = jobExecutionService;
        this.configuredWorkerName = configuredWorkerName;
        this.configuredHostname = configuredHostname;
        this.leaseSeconds = leaseSeconds;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.minPollIntervalMs = minPollIntervalMs;
        this.maxPollIntervalMs = maxPollIntervalMs;
        this.pollingEnabled = pollingEnabled;
    }

    @Override
    public void run(String... args) {
        register();
        startHeartbeat();

        if (pollingEnabled) {
            running.set(true);
            pollingThread = new Thread(this::startPollingLoop, "worker-polling-thread");
            pollingThread.setDaemon(true);
            pollingThread.start();
            log.info("Worker polling loop started with backoff [{}ms - {}ms]", minPollIntervalMs, maxPollIntervalMs);
        }
    }

    /**
     * Registers this worker with the server.
     */
    public void register() {
        String hostname = resolveHostname();
        WorkerRegisterResponse response = workerClient.register(configuredWorkerName, hostname, null);
        if (response != null && response.workerId() != null) {
            this.workerId = response.workerId();
            log.info("Successfully registered worker '{}' (ID: {}, Status: {})",
                    configuredWorkerName, workerId, response.status());
        } else {
            this.workerId = configuredWorkerName;
            log.warn("No workerId returned from server registration; defaulting to '{}'", workerId);
        }
    }

    /**
     * Starts background heartbeat loop running every heartbeatIntervalMs.
     */
    public void startHeartbeat() {
        if (heartbeatExecutor == null || heartbeatExecutor.isShutdown()) {
            heartbeatExecutor = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "worker-heartbeat-thread");
                t.setDaemon(true);
                return t;
            });
        }

        long renewalIntervalMs = Math.min(heartbeatIntervalMs, Math.max(100, leaseSeconds * 1000 / 3));
        heartbeatExecutor.scheduleWithFixedDelay(this::refreshLeaseAndHeartbeat,
                renewalIntervalMs, renewalIntervalMs, TimeUnit.MILLISECONDS);
        // Separate from HTTP calls so a stalled connection cannot extend local ownership.
        heartbeatExecutor.scheduleWithFixedDelay(this::expireLocalLease, 100, 100, TimeUnit.MILLISECONDS);
        log.info("Heartbeat and lease renewal scheduled every {}ms", renewalIntervalMs);
    }

    void refreshLeaseAndHeartbeat() {
        ActiveAttempt attempt = activeAttempt;
        if (attempt != null && !attempt.isCancelled()) {
            try {
                var renewed = workerClient.renewLease(workerId, attempt.job.jobId(),
                        attempt.job.claimToken(), leaseSeconds);
                if (renewed != null && renewed.leaseExpiresAt() != null) {
                    synchronized (attempt) {
                        if (!attempt.closed && !attempt.cancelled) {
                            attempt.expiresAt = renewed.leaseExpiresAt();
                        }
                    }
                }
            } catch (HttpClientErrorException rejected) {
                attempt.cancel();
                log.info("Stopping job {} because its lease was rejected", attempt.job.jobId());
            } catch (Exception unavailable) {
                log.warn("Lease renewal unavailable for job {}: {}", attempt.job.jobId(), unavailable.getMessage());
            }
        }
        if (workerId != null) {
            workerClient.sendHeartbeat(workerId);
        }
    }

    void expireLocalLease() {
        ActiveAttempt attempt = activeAttempt;
        if (attempt != null) {
            synchronized (attempt) {
                if (!attempt.closed && !attempt.expiresAt.isAfter(Instant.now())) {
                    attempt.cancel();
                }
            }
        }
    }

    /**
     * Continuous polling loop with exponential backoff on HTTP 204.
     */
    public void startPollingLoop() {
        long currentBackoff = minPollIntervalMs;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                boolean jobProcessed = pollOnce();

                if (jobProcessed) {
                    currentBackoff = minPollIntervalMs;
                } else {
                    Thread.sleep(currentBackoff);
                    currentBackoff = Math.min(currentBackoff * 2, maxPollIntervalMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in polling loop: {}", e.getMessage(), e);
                try {
                    Thread.sleep(currentBackoff);
                    currentBackoff = Math.min(currentBackoff * 2, maxPollIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Performs a single poll cycle: claims, starts, executes, and reports a job if available.
     *
     * @return true if a job was claimed and executed, false if no job was available
     */
    public boolean pollOnce() {
        if (workerId == null) {
            register();
        }

        Optional<JobClaimDto> claimOpt = workerClient.claimJob(workerId, leaseSeconds);
        if (claimOpt.isEmpty()) {
            return false;
        }

        JobClaimDto job = claimOpt.get();
        ActiveAttempt attempt = new ActiveAttempt(job, Thread.currentThread());
        this.activeAttempt = attempt;
        log.info("Claimed job {} (type={}, attempt={})", job.jobId(), job.jobType(), job.attemptNumber());

        try {
            JobExecutionResult result;
            try {
                workerClient.startJob(workerId, job.jobId(), job.claimToken());
                if (attempt.isCancelled()) {
                    return true;
                }
                result = jobExecutionService.executeJob(job, workerId);
            } catch (Exception executionFailure) {
                result = JobExecutionResult.failure(
                        "Execution threw exception: " + executionFailure.getMessage(),
                        FailureClassification.INFRASTRUCTURE_ERROR);
            }
            reportCompletion(attempt, result);
        } finally {
            synchronized (attempt) {
                attempt.closed = true;
                this.activeAttempt = null;
            }
            if (attempt.isCancelled() && !stopping) {
                Thread.interrupted();
            }
        }

        return true;
    }

    private void reportCompletion(ActiveAttempt attempt, JobExecutionResult result) {
        JobCompletionDto completion = new JobCompletionDto(attempt.job.claimToken(), result.success(),
                result.exitCode(), result.failureReason(), result.failureClassification());
        synchronized (attempt) {
            if (attempt.cancelled) {
                return;
            }
            attempt.completion = completion;
        }
        // Retry transport/server failures with the identical payload. Never reinterpret delivery as execution.
        for (int deliveryAttempt = 0; deliveryAttempt < 3 && !attempt.isCancelled(); deliveryAttempt++) {
            try {
                workerClient.completeJob(workerId, attempt.job.jobId(), completion);
                return;
            } catch (HttpClientErrorException rejected) {
                log.warn("Completion rejected for job {}: {}", attempt.job.jobId(), rejected.getMessage());
                return;
            } catch (Exception unavailable) {
                log.warn("Completion delivery {} failed for job {}: {}",
                        deliveryAttempt + 1, attempt.job.jobId(), unavailable.getMessage());
                if (deliveryAttempt < 2) {
                    try {
                        Thread.sleep(200L << deliveryAttempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /**
     * Graceful shutdown hook: cancels active job if running and terminates timers.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Initiating graceful shutdown for worker '{}'", workerId);
        stopping = true;
        running.set(false);

        if (pollingThread != null) {
            pollingThread.interrupt();
        }

        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }

        ActiveAttempt attempt = this.activeAttempt;
        if (attempt != null) {
            attempt.cancel();
            JobClaimDto current = attempt.job;
            log.warn("Active job {} detected during shutdown; reporting failure to release lease", current.jobId());
            try {
                JobCompletionDto completion;
                synchronized (attempt) {
                    completion = attempt.completion != null ? attempt.completion : new JobCompletionDto(
                            current.claimToken(), false, 130, "Worker terminated during execution",
                            FailureClassification.INFRASTRUCTURE_ERROR);
                }
                workerClient.completeJob(workerId, current.jobId(), completion);
            } catch (Exception e) {
                log.error("Failed to report job cancellation during shutdown: {}", e.getMessage());
            }
        }
    }

    private String resolveHostname() {
        if (configuredHostname != null && !configuredHostname.isBlank()) {
            return configuredHostname.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

    public String getWorkerId() {
        return workerId;
    }

    public JobClaimDto getActiveJob() {
        ActiveAttempt attempt = activeAttempt;
        return attempt == null ? null : attempt.job;
    }

    public boolean isRunning() {
        return running.get();
    }

    private static final class ActiveAttempt {
        final JobClaimDto job;
        final Thread executionThread;
        Instant expiresAt;
        JobCompletionDto completion;
        boolean cancelled;
        boolean closed;

        ActiveAttempt(JobClaimDto job, Thread executionThread) {
            this.job = job;
            this.executionThread = executionThread;
            this.expiresAt = job.leaseExpiresAt();
        }

        synchronized boolean isCancelled() {
            return cancelled;
        }

        synchronized void cancel() {
            if (!closed && !cancelled) {
                cancelled = true;
                executionThread.interrupt();
            }
        }
    }
}
