package com.buildhive.server.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Detailed information about a registered worker node, including current job execution.
 */
public record WorkerDetailDto(
        String workerId,
        String workerName,
        String hostname,
        String status,
        Instant lastHeartbeat,
        Instant registeredAt,
        UUID currentJobId,
        String currentJobType
) {}
