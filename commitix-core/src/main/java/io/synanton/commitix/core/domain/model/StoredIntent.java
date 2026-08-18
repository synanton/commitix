package io.synanton.commitix.core.domain.model;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * An intent as persisted in storage, including all database-managed state fields.
 * The dispatcher uses {@code leaseGeneration} and {@code version} to drive the atomic claim.
 */
public record StoredIntent(
    Intent intent,
    IntentStatus status,
    int leaseGeneration,
    long version,
    int attemptCount,
    @Nullable String workerId,
    @Nullable Instant leaseUntil,
    @Nullable Instant nextAttemptAt,
    Instant createdAt,
    Instant lastModifiedAt,
    @Nullable String errorMessage
) {
}
