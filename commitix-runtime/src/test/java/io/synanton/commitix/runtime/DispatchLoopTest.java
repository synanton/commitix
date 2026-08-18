package io.synanton.commitix.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.synanton.commitix.core.domain.model.ExecutionContext;
import io.synanton.commitix.core.domain.model.ExecutionPolicy;
import io.synanton.commitix.core.domain.model.ExecutionResult;
import io.synanton.commitix.core.domain.model.FailureAction;
import io.synanton.commitix.core.domain.model.Intent;
import io.synanton.commitix.core.domain.model.IntentStatus;
import io.synanton.commitix.core.domain.model.Operation;
import io.synanton.commitix.core.domain.model.RetryDelay;
import io.synanton.commitix.core.domain.model.StoredIntent;
import io.synanton.commitix.core.port.ExecutionAdapter;
import io.synanton.commitix.core.port.StorageAdapter;
import io.synanton.commitix.core.testfixtures.ByteArrayPayload;
import io.synanton.commitix.runtime.domain.DispatchLoop;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DispatchLoopTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String WORKER_ID = "test-worker";
    private static final int BATCH_SIZE = 10;
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Mock
    private StorageAdapter storage;
    @Mock
    private ExecutionAdapter execution;

    private DispatchLoop loop;

    @BeforeEach
    void setUp() {
        loop = new DispatchLoop(storage, execution, Executors.newVirtualThreadPerTaskExecutor(),
            FIXED_CLOCK, WORKER_ID, BATCH_SIZE, LEASE);
    }

    @Test
    void shouldDoNothingWhenNoReadyIntents() {
        when(storage.findReadyIntents(BATCH_SIZE)).thenReturn(List.of());
        loop.tick();
        verify(storage, never()).claim(any(), anyString(), any(), anyInt(), anyLong());
    }

    @Test
    void shouldSkipIntentWhenClaimFails() throws Exception {
        StoredIntent stored = storedIntent();
        when(storage.findReadyIntents(BATCH_SIZE)).thenReturn(List.of(stored));
        when(storage.claim(any(), any(), any(), anyInt(), anyLong())).thenReturn(false);

        loop.tick();

        verify(execution, never()).execute(any(), any());
    }

    @Test
    void shouldRecordSuccessAfterSuccessfulExecution() throws Exception {
        StoredIntent stored = storedIntent();
        UUID id = stored.intent().id();
        int gen = stored.leaseGeneration();
        long ver = stored.version();

        when(storage.findReadyIntents(BATCH_SIZE)).thenReturn(List.of(stored));
        when(storage.claim(any(), any(), any(), eq(gen), eq(ver))).thenReturn(true);
        when(execution.execute(any(), any())).thenReturn(new ExecutionResult.Success(null));
        when(storage.recordSuccess(any(), any(), anyInt(), anyLong())).thenReturn(true);

        CountDownLatch latch = new CountDownLatch(1);
        when(storage.recordSuccess(eq(id), any(), eq(gen + 1), eq(ver + 1))).thenAnswer(inv -> {
            latch.countDown();
            return true;
        });

        loop.tick();
        latch.await(2, TimeUnit.SECONDS);

        verify(storage).recordSuccess(eq(id), any(), eq(gen + 1), eq(ver + 1));
    }

    @Test
    void shouldScheduleRetryOnTransientFailure() throws Exception {
        StoredIntent stored = storedIntent();
        UUID id = stored.intent().id();
        int gen = stored.leaseGeneration();
        long ver = stored.version();

        when(storage.findReadyIntents(BATCH_SIZE)).thenReturn(List.of(stored));
        when(storage.claim(any(), any(), any(), eq(gen), eq(ver))).thenReturn(true);
        when(execution.execute(any(), any()))
            .thenReturn(new ExecutionResult.Failure(new RuntimeException("transient"), FailureAction.RETRY));

        CountDownLatch latch = new CountDownLatch(1);
        when(storage.scheduleRetry(eq(id), any(), eq(gen + 1), eq(ver + 1))).thenAnswer(inv -> {
            latch.countDown();
            return true;
        });

        loop.tick();
        latch.await(2, TimeUnit.SECONDS);

        verify(storage).scheduleRetry(eq(id), any(Instant.class), eq(gen + 1), eq(ver + 1));
    }

    @Test
    void shouldFailWhenAttemptsExhausted() throws Exception {
        // Create a stored intent that has already attempted maxAttempts - 1 times
        StoredIntent stored = storedIntentWithAttemptCount(2); // policy has maxAttempts=3, so this is the last
        UUID id = stored.intent().id();
        int gen = stored.leaseGeneration();
        long ver = stored.version();

        when(storage.findReadyIntents(BATCH_SIZE)).thenReturn(List.of(stored));
        when(storage.claim(any(), any(), any(), eq(gen), eq(ver))).thenReturn(true);
        when(execution.execute(any(), any()))
            .thenReturn(new ExecutionResult.Failure(new RuntimeException("fail"), FailureAction.RETRY));

        CountDownLatch latch = new CountDownLatch(1);
        when(storage.fail(eq(id), any(), eq(gen + 1), eq(ver + 1))).thenAnswer(inv -> {
            latch.countDown();
            return true;
        });

        loop.tick();
        latch.await(2, TimeUnit.SECONDS);

        verify(storage).fail(eq(id), any(), eq(gen + 1), eq(ver + 1));
    }

    @Test
    void shouldBlockOnBlockFailureAction() throws Exception {
        StoredIntent stored = storedIntent();
        UUID id = stored.intent().id();
        int gen = stored.leaseGeneration();
        long ver = stored.version();

        when(storage.findReadyIntents(BATCH_SIZE)).thenReturn(List.of(stored));
        when(storage.claim(any(), any(), any(), eq(gen), eq(ver))).thenReturn(true);
        when(execution.execute(any(), any()))
            .thenReturn(new ExecutionResult.Failure(new RuntimeException("block"), FailureAction.BLOCK));

        CountDownLatch latch = new CountDownLatch(1);
        when(storage.block(eq(id), any(), eq(gen + 1), eq(ver + 1))).thenAnswer(inv -> {
            latch.countDown();
            return true;
        });

        loop.tick();
        latch.await(2, TimeUnit.SECONDS);

        verify(storage).block(eq(id), any(), eq(gen + 1), eq(ver + 1));
    }

    private StoredIntent storedIntent() {
        return storedIntentWithAttemptCount(0);
    }

    private StoredIntent storedIntentWithAttemptCount(int attemptCount) {
        Intent intent = Intent.builder()
            .id(UUID.randomUUID())
            .operation(new Operation("op-1", "v1", "TestOp"))
            .payload(ByteArrayPayload.ofString("test"))
            .policy(ExecutionPolicy.defaultPolicy())
            .build();

        return new StoredIntent(
            intent, IntentStatus.READY,
            0, 0L,
            attemptCount, null, null, null,
            Instant.now(FIXED_CLOCK), Instant.now(FIXED_CLOCK),
            null
        );
    }
}
