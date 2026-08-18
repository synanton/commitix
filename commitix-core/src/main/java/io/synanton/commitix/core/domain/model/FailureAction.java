package io.synanton.commitix.core.domain.model;

/**
 * Dictates what Commitix does when an intent execution fails permanently
 * or exhausts all retry attempts.
 */
public enum FailureAction {

    /** Retry according to {@code maxAttempts}; if exhausted transition to BLOCKED. */
    RETRY,

    /** Mark as permanently FAILED - no operator intervention expected. */
    FAIL,

    /** Stop execution and require operator intervention before continuing. */
    BLOCK
}
