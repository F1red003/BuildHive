package com.buildhive.worker.dto;

/**
 * Registration response received from the server.
 */
public record WorkerRegisterResponse(
        String workerId,
        String status
) {}
