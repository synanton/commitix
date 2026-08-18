package io.synanton.commitix.core.domain.model;

import java.time.Duration;
import java.time.Instant;
import lombok.With;
import org.jspecify.annotations.Nullable;

/**
 * Governs how Commitix retries and handles failures for an intent.
 * Does NOT contain scheduling, priority, or resource allocation concerns.
 */
@With
public record ExecutionPolicy(
    int maxAttempts,
    RetryDelay retryDelay,
    @Nullable Instant deadline,
    FailureAction failureAction
) {

    /** Sentinel value for unlimited retries. */
    public static final int UNLIMITED = -1;

    private static final RetryDelay DEFAULT_RETRY_DELAY =
        RetryDelay.exponential(Duration.ofSeconds(1), Duration.ofMinutes(1));

    public ExecutionPolicy {
        if (maxAttempts != UNLIMITED && maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be UNLIMITED or >= 1");
        }
    }

    /**
     * Default policy: 3 attempts, exponential backoff 1s→60s, RETRY failure action.
     */
    public static ExecutionPolicy defaultPolicy() {
        return new ExecutionPolicy(3, DEFAULT_RETRY_DELAY, null, FailureAction.RETRY);
    }

    /** Returns true when this intent should retry forever. */
    public boolean isUnlimited() {
        return maxAttempts == UNLIMITED;
    }

    /** Returns true when {@code attemptCount} has exhausted all allowed attempts. */
    public boolean isExhausted(int attemptCount) {
        return !isUnlimited() && attemptCount >= maxAttempts;
    }

    /** Returns true when the given instant is past the deadline (deadline is non-null). */
    public boolean isDeadlineExceeded(Instant now) {
        return deadline != null && now.isAfter(deadline);
    }
}
