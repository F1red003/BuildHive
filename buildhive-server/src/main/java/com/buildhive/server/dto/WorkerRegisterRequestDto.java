package com.buildhive.server.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload sent by a worker to register itself with the server.
 */
public record WorkerRegisterRequestDto(
        @NotBlank(message = "workerName must not be blank")
        String workerName,

        @NotBlank(message = "hostname must not be blank")
        String hostname,

        String workerId
) {
    public WorkerRegisterRequestDto(String workerName, String hostname) {
        this(workerName, hostname, null);
    }
}
