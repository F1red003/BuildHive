package com.buildhive.worker.dto;

/**
 * Request sent to server to transition claimed job to RUNNING.
 */
public record JobStartRequest(
        String claimToken
) {}
