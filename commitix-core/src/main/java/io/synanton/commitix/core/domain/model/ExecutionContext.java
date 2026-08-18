package io.synanton.commitix.core.domain.model;

import java.time.Clock;

/**
 * Runtime context supplied to an intent handler during execution.
 *
 * @param attemptNumber current attempt number (1-indexed, matches {@code attempt_count})
 * @param clock         fixed clock for deterministic time operations in handlers
 * @param cancelled     cooperative cancellation signal; handlers should check periodically
 */
public record ExecutionContext(int attemptNumber, Clock clock, boolean cancelled) {

    public ExecutionContext(int attemptNumber, Clock clock) {
        this(attemptNumber, clock, false);
    }
}
