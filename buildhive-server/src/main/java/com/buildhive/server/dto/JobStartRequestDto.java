package com.buildhive.server.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload sent by a worker to indicate that it is starting execution of a claimed job.
 */
public record JobStartRequestDto(
        @NotBlank(message = "claimToken must not be blank")
        String claimToken
) {}
