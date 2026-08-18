package io.synanton.commitix.core.domain.model;

import java.time.Duration;
import lombok.With;

/**
 * Configures delay between execution retry attempts.
 *
 * <p>The {@code retryNumber} parameter for {@link #nextDelay(int)} is zero-indexed:
 * 0 for the first retry, 1 for the second, and so on.
 */
@With
public record RetryDelay(Duration initial, Duration max, double multiplier) {

    public RetryDelay {
        if (initial.isNegative() || initial.isZero()) {
            throw new IllegalArgumentException("initial delay must be positive");
        }
        if (max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("max delay must be >= initial delay");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0");
        }
    }

    /**
     * Computes the delay for the given (zero-indexed) retry number.
     * The result is clamped to {@code max}.
     */
    public Duration nextDelay(int retryNumber) {
        long delayMs = initial.toMillis();
        for (int i = 0; i < retryNumber; i++) {
            delayMs = (long) (delayMs * multiplier);
        }
        return Duration.ofMillis(Math.min(delayMs, max.toMillis()));
    }

    /** Exponential backoff from {@code initial} up to {@code max} with multiplier 2.0. */
    public static RetryDelay exponential(Duration initial, Duration max) {
        return new RetryDelay(initial, max, 2.0);
    }

    /** Constant delay - every retry waits the same duration. */
    public static RetryDelay constant(Duration delay) {
        return new RetryDelay(delay, delay, 1.0);
    }
}
