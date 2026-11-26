package com.buildhive.common.dto;

import java.util.List;

/**
 * Carries a batch of log output lines emitted by a worker for a job execution.
 */
public record LogChunkDto(List<String> lines) {
    public LogChunkDto {
        if (lines == null) {
            lines = List.of();
        }
    }
}
