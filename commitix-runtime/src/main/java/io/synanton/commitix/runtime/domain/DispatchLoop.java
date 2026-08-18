package io.synanton.commitix.runtime.domain;

import io.synanton.commitix.core.domain.model.ExecutionContext;
import io.synanton.commitix.core.domain.model.ExecutionPolicy;
import io.synanton.commitix.core.domain.model.ExecutionResult;
import io.synanton.commitix.core.domain.model.Intent;
import io.synanton.commitix.core.domain.model.StoredIntent;
import io.synanton.commitix.core.port.ExecutionAdapter;
import io.synanton.commitix.core.port.StorageAdapter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core dispatch logic: finds READY intents, claims them, and submits execution
 * to the provided {@link ExecutorService} (expected to be a virtual-thread pool).
 *
 * <p>Stateless and clock-injected for testability. Invoked periodically by
 * {@link io.synanton.commitix.runtime.adapter.in.schedule.DispatcherScheduler}.
 */
@Slf4j
@RequiredArgsConstructor
public final class DispatchLoop {

    private final StorageAdapter storage;
    private final ExecutionAdapter execution;
    private final ExecutorService executor;
    private final Clock clock;
    private final String workerId;
    private final int batchSize;
    private final Duration leaseDuration;

    /**
     * One dispatch tick: find, claim, and submit READY intents up to {@code batchSize}.
     */
    public void tick() {
        List<StoredIntent> candidates = storage.findReadyIntents(batchSize);
        for (StoredIntent stored : candidates) {
            Instant leaseUntil = Instant.now(clock).plus(leaseDuration);
            boolean claimed = storage.claim(
                stored.intent().id(),
                workerId,
                leaseUntil,
                stored.leaseGeneration(),
                stored.version()
            );
            if (!claimed) {
                log.debug("Lost claim race on intent id={}", stored.intent().id());
                continue;
            }
            // Capture values post-claim: generation and version each incremented by 1
            int claimedGeneration = stored.leaseGeneration() + 1;
            long claimedVersion = stored.version() + 1;
            int attemptNumber = stored.attemptCount() + 1;

            executor.submit(() -> executeAndRecord(stored, claimedGeneration, claimedVersion, attemptNumber));
        }
    }

    private void executeAndRecord(StoredIntent stored, int gen, long ver, int attemptNumber) {
        Intent intent = stored.intent();
        try {
            ExecutionContext ctx = new ExecutionContext(attemptNumber, clock);
            ExecutionResult result = execution.execute(intent, ctx);
            recordResult(intent, result, gen, ver, attemptNumber);
        } catch (Exception ex) {
            log.error("Unexpected execution failure for intent id={}", intent.id(), ex);
            recordFailure(intent, ex, gen, ver, attemptNumber);
        }
    }

    private void recordResult(Intent intent, ExecutionResult result, int gen, long ver, int attemptNumber) {
        switch (result) {
            case ExecutionResult.Success success -> {
                boolean recorded = storage.recordSuccess(intent.id(), success, gen, ver);
                if (!recorded) {
                    log.warn("Success fencing rejected for intent id={} gen={}", intent.id(), gen);
                }
            }
            case ExecutionResult.Failure failure -> handleFailure(intent, failure, gen, ver, attemptNumber);
        }
    }

    private void handleFailure(Intent intent, ExecutionResult.Failure failure, int gen, long ver, int attemptNumber) {
        switch (failure.action()) {
            case BLOCK -> {
                boolean blocked = storage.block(intent.id(), failure.cause(), gen, ver);
                if (!blocked) {
                    log.warn("Block fencing rejected for intent id={} gen={}", intent.id(), gen);
                }
            }
            case FAIL -> {
                boolean failed = storage.fail(intent.id(), failure.cause(), gen, ver);
                if (!failed) {
                    log.warn("Fail fencing rejected for intent id={} gen={}", intent.id(), gen);
                }
            }
            case RETRY -> scheduleRetryOrFail(intent, failure.cause(), gen, ver, attemptNumber);
        }
    }

    private void scheduleRetryOrFail(Intent intent, Throwable cause, int gen, long ver, int attemptNumber) {
        ExecutionPolicy policy = intent.policy();
        if (policy.isExhausted(attemptNumber)) {
            boolean failed = storage.fail(intent.id(), cause, gen, ver);
            if (!failed) {
                log.warn("Exhausted fail fencing rejected for intent id={} gen={}", intent.id(), gen);
            }
            return;
        }
        Duration delay = policy.retryDelay().nextDelay(attemptNumber);
        Instant nextAttemptAt = Instant.now(clock).plus(delay);
        boolean scheduled = storage.scheduleRetry(intent.id(), nextAttemptAt, gen, ver);
        if (!scheduled) {
            log.warn("ScheduleRetry fencing rejected for intent id={} gen={}", intent.id(), gen);
        }
    }

    private void recordFailure(Intent intent, Throwable cause, int gen, long ver, int attemptNumber) {
        ExecutionPolicy policy = intent.policy();
        if (policy.isExhausted(attemptNumber)) {
            storage.fail(intent.id(), cause, gen, ver);
        } else {
            Duration delay = policy.retryDelay().nextDelay(attemptNumber);
            storage.scheduleRetry(intent.id(), Instant.now(clock).plus(delay), gen, ver);
        }
    }
}
