package com.buildhive.common.model;

import java.util.Set;

/**
 * Represents the overall lifecycle status of an end-to-end pipeline execution.
 */
public enum PipelineStatus {

    /**
     * Pipeline has been instantiated but has not yet started executing jobs.
     */
    PENDING,

    /**
     * One or more jobs in the pipeline are currently active.
     */
    RUNNING,

    /**
     * All jobs completed successfully and final report and artifacts are ready.
     */
    SUCCEEDED,

    /**
     * One or more critical jobs failed, causing pipeline execution to abort.
     */
    FAILED,

    /**
     * Pipeline execution was cancelled manually by the user or an administrative action.
     */
    CANCELLED;

    private static final Set<PipelineStatus> TERMINAL_STATUSES = Set.of(
            SUCCEEDED, FAILED, CANCELLED
    );

    /**
     * Returns true if this pipeline status is terminal.
     */
    public boolean isTerminal() {
        return TERMINAL_STATUSES.contains(this);
    }
}
