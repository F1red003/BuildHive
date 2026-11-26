package com.buildhive.server.dto;

/**
 * Aggregated dashboard metrics for the platform.
 */
public record DashboardMetricsDto(
        long totalPipelines,
        long runningJobs,
        long readyJobs,
        long onlineWorkers
) {}
