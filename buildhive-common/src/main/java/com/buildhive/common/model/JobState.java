package com.buildhive.common.model;

import java.util.Set;

/**
 * Represents the lifecycle state of an individual job in a build pipeline.
 */
public enum JobState {

    /**
     * Job is waiting for its prerequisite dependencies to complete successfully.
     */
    BLOCKED,

    /**
     * All prerequisite dependencies have succeeded. Job is available to be claimed by a worker.
     */
    READY,

    /**
     * Job has been claimed transactionally by a worker, but execution has not yet started.
     */
    CLAIMED,

    /**
     * Job is currently actively executing on a worker node.
     */
    RUNNING,

    /**
     * Job completed successfully.
     */
    SUCCEEDED,

    /**
     * Job failed (either due to compilation/test failures or unrecoverable infrastructure error).
     */
    FAILED,

    /**
     * Job was skipped because an upstream prerequisite failed or was skipped.
     */
    SKIPPED,

    /**
     * Job encountered a transient infrastructure error and is waiting for retry backoff before returning to READY.
     */
    RETRY_WAIT,

    /**
     * Job or entire pipeline was cancelled by user request.
     */
    CANCELLED;

    private static final Set<JobState> TERMINAL_STATES = Set.of(
            SUCCEEDED, FAILED, SKIPPED, CANCELLED
    );

    private static final Set<JobState> ACTIVE_STATES = Set.of(
            CLAIMED, RUNNING
    );

    /**
     * Returns true if this state represents a final state with no further transitions.
     */
    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }

    /**
     * Returns true if this job is eligible to be claimed by an available worker.
     */
    public boolean isClaimable() {
        return this == READY;
    }

    /**
     * Returns true if a worker is currently assigned or actively processing this job.
     */
    public boolean isActive() {
        return ACTIVE_STATES.contains(this);
    }

    /**
     * Validates whether a state transition from this state to next is permissible.
     */
    public boolean canTransitionTo(JobState next) {
        if (next == null || this == next) {
            return false;
        }
        if (this.isTerminal()) {
            return false;
        }

        return switch (this) {
            case BLOCKED -> next == READY || next == SKIPPED || next == CANCELLED;
            case READY -> next == CLAIMED || next == CANCELLED;
            case CLAIMED -> next == RUNNING || next == READY || next == FAILED || next == CANCELLED;
            case RUNNING -> next == SUCCEEDED || next == FAILED || next == RETRY_WAIT || next == READY || next == CANCELLED;
            case RETRY_WAIT -> next == READY || next == CANCELLED;
            default -> false;
        };
    }
}
