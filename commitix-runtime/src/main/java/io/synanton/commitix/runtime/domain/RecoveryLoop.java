package io.synanton.commitix.runtime.domain;

import io.synanton.commitix.core.port.StorageAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core recovery logic executed on a periodic schedule by
 * {@link io.synanton.commitix.runtime.adapter.in.schedule.RecoveryScheduler}.
 *
 * <p>Each tick performs three operations in order:
 * <ol>
 *   <li>Release expired leases: RUNNING with {@code lease_until < NOW()} → READY.
 *       {@code lease_generation} is preserved (recovery does not invalidate the epoch).
 *   <li>Promote retrying intents: RETRYING with {@code next_attempt_at <= NOW()} → READY.
 *   <li>Expire overdue intents: READY or RETRYING with {@code expires_at < NOW()} → EXPIRED.
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public final class RecoveryLoop {

    private final StorageAdapter storage;

    public void tick() {
        int recovered = storage.recoverExpiredLeases();
        if (recovered > 0) {
            log.info("Recovery: released {} expired lease(s)", recovered);
        }

        int promoted = storage.promoteRetryingIntents();
        if (promoted > 0) {
            log.debug("Recovery: promoted {} retrying intent(s) to READY", promoted);
        }

        int expired = storage.expireOverdueIntents();
        if (expired > 0) {
            log.info("Recovery: expired {} overdue intent(s)", expired);
        }
    }
}
