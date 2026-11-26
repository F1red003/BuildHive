package com.buildhive.worker.dto;

/**
 * Registration request sent to the server.
 */
public record WorkerRegisterRequest(
        String workerName,
        String hostname,
        String workerId
) {
    public WorkerRegisterRequest(String workerName, String hostname) {
        this(workerName, hostname, null);
    }
}
