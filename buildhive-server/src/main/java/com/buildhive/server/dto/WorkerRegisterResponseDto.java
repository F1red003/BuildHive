package com.buildhive.server.dto;

/**
 * Payload returned to a worker after successful registration.
 */
public record WorkerRegisterResponseDto(
        String workerId,
        String status
) {}
