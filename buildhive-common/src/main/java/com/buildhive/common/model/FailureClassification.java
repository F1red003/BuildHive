package com.buildhive.common.model;

/**
 * Classifies the origin of a job failure to determine whether retrying is appropriate.
 */
public enum FailureClassification {

    /**
     * Infrastructure failure: network disconnect, disk full, container crash, lease timeout.
     * Temporary and eligible for retry within configured attempt limits.
     */
    INFRASTRUCTURE_ERROR,

    /**
     * User code failure: compile error, broken unit test, syntax mistake, invalid pom.xml.
     * Deterministic failure that will not resolve on retry. Permanent failure.
     */
    USER_CODE_ERROR;

    /**
     * Returns true if errors under this classification should be retried automatically.
     */
    public boolean isRetryable() {
        return this == INFRASTRUCTURE_ERROR;
    }
}
