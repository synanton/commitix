package io.synanton.commitix.core.domain.model;

/**
 * Lifecycle states of a Commitix intent.
 *
 * <p>{@code DECLARED} exists only inside the application transaction context.
 * The durable state machine starts at {@code READY} after transaction commit.
 */
public enum IntentStatus {

    /** Intent declared within a transaction; not yet durable (in-memory only). */
    DECLARED,

    /** Durable and eligible for execution. */
    READY,

    /** Currently executing (holds a lease and a generation token). */
    RUNNING,

    /** Execution completed successfully. Terminal. */
    SUCCESS,

    /** Execution failed transiently; scheduled for retry at {@code next_attempt_at}. */
    RETRYING,

    /** Execution stopped permanently; operator intervention required. */
    BLOCKED,

    /** Permanent failure; no further attempts expected. Terminal. */
    FAILED,

    /** Deadline passed; no new execution attempts are allowed. */
    EXPIRED,

    /** Explicitly cancelled. Terminal. */
    CANCELLED
}
