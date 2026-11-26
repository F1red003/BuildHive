package com.buildhive.common.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class JobStateTest {

    @Test
    @DisplayName("Verify terminal states identification")
    void testTerminalStates() {
        assertThat(JobState.SUCCEEDED.isTerminal()).isTrue();
        assertThat(JobState.FAILED.isTerminal()).isTrue();
        assertThat(JobState.SKIPPED.isTerminal()).isTrue();
        assertThat(JobState.CANCELLED.isTerminal()).isTrue();

        assertThat(JobState.BLOCKED.isTerminal()).isFalse();
        assertThat(JobState.READY.isTerminal()).isFalse();
        assertThat(JobState.CLAIMED.isTerminal()).isFalse();
        assertThat(JobState.RUNNING.isTerminal()).isFalse();
        assertThat(JobState.RETRY_WAIT.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("Verify claimable states identification")
    void testClaimableStates() {
        assertThat(JobState.READY.isClaimable()).isTrue();

        assertThat(JobState.BLOCKED.isClaimable()).isFalse();
        assertThat(JobState.CLAIMED.isClaimable()).isFalse();
        assertThat(JobState.RUNNING.isClaimable()).isFalse();
        assertThat(JobState.SUCCEEDED.isClaimable()).isFalse();
        assertThat(JobState.FAILED.isClaimable()).isFalse();
        assertThat(JobState.SKIPPED.isClaimable()).isFalse();
        assertThat(JobState.RETRY_WAIT.isClaimable()).isFalse();
        assertThat(JobState.CANCELLED.isClaimable()).isFalse();
    }

    @Test
    @DisplayName("Verify active states identification")
    void testActiveStates() {
        assertThat(JobState.CLAIMED.isActive()).isTrue();
        assertThat(JobState.RUNNING.isActive()).isTrue();

        assertThat(JobState.READY.isActive()).isFalse();
        assertThat(JobState.SUCCEEDED.isActive()).isFalse();
        assertThat(JobState.FAILED.isActive()).isFalse();
    }

    @Test
    @DisplayName("Verify valid state transitions")
    void testValidTransitions() {
        assertThat(JobState.BLOCKED.canTransitionTo(JobState.READY)).isTrue();
        assertThat(JobState.BLOCKED.canTransitionTo(JobState.SKIPPED)).isTrue();
        assertThat(JobState.BLOCKED.canTransitionTo(JobState.CANCELLED)).isTrue();

        assertThat(JobState.READY.canTransitionTo(JobState.CLAIMED)).isTrue();
        assertThat(JobState.READY.canTransitionTo(JobState.CANCELLED)).isTrue();

        assertThat(JobState.CLAIMED.canTransitionTo(JobState.RUNNING)).isTrue();
        assertThat(JobState.CLAIMED.canTransitionTo(JobState.READY)).isTrue(); // lease timeout / worker abandon
        assertThat(JobState.CLAIMED.canTransitionTo(JobState.FAILED)).isTrue();

        assertThat(JobState.RUNNING.canTransitionTo(JobState.SUCCEEDED)).isTrue();
        assertThat(JobState.RUNNING.canTransitionTo(JobState.FAILED)).isTrue();
        assertThat(JobState.RUNNING.canTransitionTo(JobState.RETRY_WAIT)).isTrue();

        assertThat(JobState.RETRY_WAIT.canTransitionTo(JobState.READY)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = JobState.class, names = {"SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED"})
    @DisplayName("Terminal states cannot transition to any other state")
    void testTerminalStatesCannotTransition(JobState terminalState) {
        for (JobState target : JobState.values()) {
            assertThat(terminalState.canTransitionTo(target))
                    .as("Terminal state %s should not transition to %s", terminalState, target)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Verify failure classification retryability")
    void testFailureClassification() {
        assertThat(FailureClassification.INFRASTRUCTURE_ERROR.isRetryable()).isTrue();
        assertThat(FailureClassification.USER_CODE_ERROR.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("Verify JobType defaults and priorities")
    void testJobTypeAttributes() {
        assertThat(JobType.PROJECT_VALIDATION.getPriority()).isGreaterThan(JobType.REPORT_GENERATION.getPriority());
        assertThat(JobType.COMPILATION.isFailureRetryableByDefault()).isFalse();
        assertThat(JobType.UNIT_TESTS.isFailureRetryableByDefault()).isFalse();
        assertThat(JobType.DEPENDENCY_RESOLUTION.isFailureRetryableByDefault()).isTrue();
    }

    @Test
    @DisplayName("Verify PipelineStatus terminal states")
    void testPipelineStatus() {
        assertThat(PipelineStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(PipelineStatus.FAILED.isTerminal()).isTrue();
        assertThat(PipelineStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(PipelineStatus.PENDING.isTerminal()).isFalse();
        assertThat(PipelineStatus.RUNNING.isTerminal()).isFalse();
    }
}
