package io.synanton.commitix.core.port;

import io.synanton.commitix.core.domain.model.ExecutionResult;
import io.synanton.commitix.core.domain.model.Intent;
import io.synanton.commitix.core.domain.model.StoredIntent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * SPI for persisting and transitioning intent state.
 *
 * <p>Boolean-returning methods return {@code true} when exactly one row was updated (CAS success).
 * {@code false} means the caller no longer owns the lease or another update raced ahead;
 * adapters translate {@code int rowsAffected} from their underlying store accordingly.
 *
 * <p>Fencing invariant: every mutation that operates on a RUNNING intent must pass both
 * {@code currentGeneration} and {@code currentVersion}. The underlying store rejects stale writes.
 */
public interface StorageAdapter {

    // --- Intent persistence ---

    /**
     * Persists a new intent with status {@code READY}.
     * Must be called within the active business transaction so that rollback discards the row.
     */
    void persist(Intent intent);

    // --- Ownership operations ---

    /**
     * Atomically transitions an intent from READY to RUNNING, incrementing
     * {@code lease_generation} and {@code attempt_count}.
     *
     * @return {@code true} if the claim succeeded; {@code false} if another worker won the race
     */
    boolean claim(UUID id, String workerId, Instant leaseUntil,
                  int currentGeneration, long currentVersion);

    /**
     * Releases ownership of a RUNNING intent back to READY (recovery path).
     * Preserves {@code lease_generation} - recovery does not increment the ownership epoch.
     *
     * @return {@code true} if the release succeeded
     */
    boolean releaseLease(UUID id, int currentGeneration, long currentVersion);

    // --- Result operations ---

    /**
     * Transitions a RUNNING intent to SUCCESS.
     *
     * @return {@code true} if the update succeeded (caller still owns the lease)
     */
    boolean recordSuccess(UUID id, ExecutionResult.Success result,
                          int currentGeneration, long currentVersion);

    /**
     * Transitions a RUNNING intent to FAILED (permanent failure, no further attempts).
     *
     * @return {@code true} if the update succeeded
     */
    boolean recordFailure(UUID id, Throwable error,
                          int currentGeneration, long currentVersion);

    // --- Retry operations ---

    /**
     * Transitions a RUNNING intent to RETRYING and sets the next attempt time.
     *
     * @return {@code true} if the update succeeded
     */
    boolean scheduleRetry(UUID id, Instant nextAttemptAt,
                          int currentGeneration, long currentVersion);

    // --- Administrative / policy-driven operations ---

    /**
     * Transitions a RUNNING intent to BLOCKED (permanent stop, operator intervention required).
     *
     * @return {@code true} if the update succeeded
     */
    boolean block(UUID id, Throwable error, int currentGeneration, long currentVersion);

    /**
     * Transitions a RUNNING intent to FAILED.
     *
     * @return {@code true} if the update succeeded
     */
    boolean fail(UUID id, Throwable error, int currentGeneration, long currentVersion);

    /**
     * Cancels an intent. If RUNNING, increments {@code lease_generation} to invalidate the
     * worker epoch - a stale worker's subsequent SUCCESS write will be rejected.
     *
     * @return {@code true} if the update succeeded
     */
    boolean cancel(UUID id, int currentGeneration, long currentVersion);

    // --- Recovery queries ---

    /** Returns up to {@code limit} READY intents ordered by creation time. */
    List<StoredIntent> findReadyIntents(int limit);

    /** Returns RUNNING intents whose lease has expired. */
    List<StoredIntent> findExpiredLeases(int limit);

    /** Returns RETRYING intents whose {@code next_attempt_at} is in the past. */
    List<StoredIntent> findReadyRetries(int limit);

    /**
     * Releases all expired leases (RUNNING with {@code lease_until < NOW()}) back to READY.
     * Preserves {@code lease_generation}.
     *
     * @return number of intents recovered
     */
    int recoverExpiredLeases();

    /**
     * Promotes RETRYING intents whose {@code next_attempt_at <= NOW()} back to READY.
     *
     * @return number of intents promoted
     */
    int promoteRetryingIntents();

    /**
     * Transitions READY or RETRYING intents with a past deadline to EXPIRED.
     *
     * @return number of intents expired
     */
    int expireOverdueIntents();

    /** Returns the stored intent with the given id, or {@code null} if not found. */
    @Nullable StoredIntent findById(UUID id);
}
