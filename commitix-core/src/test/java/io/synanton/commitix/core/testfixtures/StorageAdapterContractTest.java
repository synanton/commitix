package io.synanton.commitix.core.testfixtures;

import static org.assertj.core.api.Assertions.assertThat;

import io.synanton.commitix.core.domain.model.ExecutionPolicy;
import io.synanton.commitix.core.domain.model.ExecutionResult;
import io.synanton.commitix.core.domain.model.FailureAction;
import io.synanton.commitix.core.domain.model.Intent;
import io.synanton.commitix.core.domain.model.IntentStatus;
import io.synanton.commitix.core.domain.model.Operation;
import io.synanton.commitix.core.domain.model.StoredIntent;
import io.synanton.commitix.core.port.PayloadSerializer;
import io.synanton.commitix.core.port.StorageAdapter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link StorageAdapter} implementations.
 * Extend this class and implement {@link #realDataSource()} and {@link #serializer()}.
 *
 * <p>All 10 correctness scenarios from the implementation plan are covered here.
 */
public abstract class StorageAdapterContractTest {

    private static final String WORKER_ID = "test-worker-1";
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

    protected abstract DataSource realDataSource();

    protected abstract PayloadSerializer serializer();

    protected abstract StorageAdapter storageFor(DataSource dataSource);

    private DataSource txDataSource;
    private Connection txConnection;
    private StorageAdapter storage;

    @BeforeEach
    void setUpConnection() throws SQLException {
        txConnection = realDataSource().getConnection();
        txConnection.setAutoCommit(false);
        cleanTables(txConnection);
        txConnection.commit();
        txConnection.setAutoCommit(false);
        txDataSource = new SingleConnectionDataSource(txConnection);
        storage = storageFor(txDataSource);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDownConnection() throws SQLException {
        txConnection.rollback();
        txConnection.close();
    }

    // --- Scenario 1 & 2: persist + commit → row present ---

    @Test
    void shouldPersistIntentOnCommit() throws SQLException {
        Intent intent = newIntent();

        storage.persist(intent);
        txConnection.commit();

        StoredIntent found = storageFor(realDataSource()).findById(intent.id());
        assertThat(found).isNotNull();
        assertThat(found.status()).isEqualTo(IntentStatus.READY);
        assertThat(found.leaseGeneration()).isEqualTo(0);
        assertThat(found.attemptCount()).isEqualTo(0);
        assertThat(found.version()).isEqualTo(0L);
    }

    // --- Scenario 3: persist + rollback → row absent ---

    @Test
    void shouldNotPersistIntentOnRollback() throws SQLException {
        Intent intent = newIntent();

        storage.persist(intent);
        txConnection.rollback();

        StoredIntent found = storageFor(realDataSource()).findById(intent.id());
        assertThat(found).isNull();
    }

    // --- Scenario 4: claim increments attempt_count, lease_generation, version ---

    @Test
    void shouldIncrementCountersOnClaim() throws SQLException {
        Intent intent = newIntent();
        storage.persist(intent);
        txConnection.commit();

        StorageAdapter poolStorage = storageFor(realDataSource());
        Instant leaseUntil = Instant.now().plus(LEASE_DURATION);
        boolean claimed = poolStorage.claim(intent.id(), WORKER_ID, leaseUntil, 0, 0L);

        assertThat(claimed).isTrue();
        StoredIntent after = poolStorage.findById(intent.id());
        assertThat(after.status()).isEqualTo(IntentStatus.RUNNING);
        assertThat(after.leaseGeneration()).isEqualTo(1);
        assertThat(after.attemptCount()).isEqualTo(1);
        assertThat(after.version()).isEqualTo(1L);
        assertThat(after.workerId()).isEqualTo(WORKER_ID);
    }

    // --- Scenario 4b: concurrent claim - exactly one succeeds ---

    @Test
    void shouldAllowOnlyOneWorkerToClaim() throws SQLException {
        Intent intent = newIntent();
        storage.persist(intent);
        txConnection.commit();

        StorageAdapter adapter1 = storageFor(realDataSource());
        StorageAdapter adapter2 = storageFor(realDataSource());
        Instant leaseUntil = Instant.now().plus(LEASE_DURATION);

        boolean first = adapter1.claim(intent.id(), "worker-a", leaseUntil, 0, 0L);
        boolean second = adapter2.claim(intent.id(), "worker-b", leaseUntil, 0, 0L);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    // --- Scenario 5: recordSuccess transitions to SUCCESS ---

    @Test
    void shouldTransitionToSuccessOnRecordSuccess() throws SQLException {
        Intent intent = newIntent();
        storage.persist(intent);
        txConnection.commit();

        StorageAdapter poolStorage = storageFor(realDataSource());
        Instant leaseUntil = Instant.now().plus(LEASE_DURATION);
        poolStorage.claim(intent.id(), WORKER_ID, leaseUntil, 0, 0L);

        StoredIntent afterClaim = poolStorage.findById(intent.id());
        boolean succeeded = poolStorage.recordSuccess(
            intent.id(), ExecutionResult.Success.empty(),
            afterClaim.leaseGeneration(), afterClaim.version());

        assertThat(succeeded).isTrue();
        StoredIntent after = poolStorage.findById(intent.id());
        assertThat(after.status()).isEqualTo(IntentStatus.SUCCESS);
    }

    // --- Scenario 6: lease expires → recovery releases ownership (generation preserved) ---

    @Test
    void shouldReleaseExpiredLeaseWithoutIncrementingGeneration() throws SQLException {
        Intent intent = newIntent();
        storage.persist(intent);
        txConnection.commit();

        StorageAdapter poolStorage = storageFor(realDataSource());
        Instant expiredLease = Instant.now().minus(Duration.ofSeconds(1));
        poolStorage.claim(intent.id(), WORKER_ID, expiredLease, 0, 0L);

        StoredIntent afterClaim = poolStorage.findById(intent.id());
        int generationAfterClaim = afterClaim.leaseGeneration();

        int recovered = poolStorage.recoverExpiredLeases();

        assertThat(recovered).isGreaterThanOrEqualTo(1);
        StoredIntent afterRecovery = poolStorage.findById(intent.id());
        assertThat(afterRecovery.status()).isEqualTo(IntentStatus.READY);
        assertThat(afterRecovery.workerId()).isNull();
        assertThat(afterRecovery.leaseUntil()).isNull();
        // generation must NOT increment during recovery (white paper §14.3 rule 4)
        assertThat(afterRecovery.leaseGeneration()).isEqualTo(generationAfterClaim);
        // attempt_count must NOT increment during recovery (white paper §5.5)
        assertThat(afterRecovery.attemptCount()).isEqualTo(afterClaim.attemptCount());
    }

    // --- Scenario 7: next worker claims with generation++ ---

    @Test
    void shouldIncrementGenerationOnNextClaim() throws SQLException {
        Intent intent = newIntent();
        storage.persist(intent);
        txConnection.commit();

        StorageAdapter poolStorage = storageFor(realDataSource());
        Instant expiredLease = Instant.now().minus(Duration.ofSeconds(1));
        poolStorage.claim(intent.id(), "worker-a", expiredLease, 0, 0L);
        poolStorage.recoverExpiredLeases();

        StoredIntent afterRecovery = poolStorage.findById(intent.id());
        Instant freshLease = Instant.now().plus(LEASE_DURATION);
        boolean claimed = poolStorage.claim(
            intent.id(), "worker-b", freshLease,
            afterRecovery.leaseGeneration(), afterRecovery.version());

        assertThat(claimed).isTrue();
        StoredIntent after = poolStorage.findById(intent.id());
        assertThat(after.leaseGeneration()).isEqualTo(2);
        assertThat(after.workerId()).isEqualTo("worker-b");
    }

    // --- Scenario 8: stale worker cannot write SUCCESS (generation fencing) ---

    @Test
    void shouldRejectStaleWorkerSuccessAfterReassignment() throws SQLException {
        Intent intent = newIntent();
        storage.persist(intent);
        txConnection.commit();

        StorageAdapter poolStorage = storageFor(realDataSource());
        Instant expiredLease = Instant.now().minus(Duration.ofSeconds(1));
        poolStorage.claim(intent.id(), "worker-a", expiredLease, 0, 0L);

        // Worker A holds gen=1 but lease has expired - recover
        StoredIntent afterClaim = poolStorage.findById(intent.id());
        int staleGeneration = afterClaim.leaseGeneration();
        long staleVersion = afterClaim.version();

        poolStorage.recoverExpiredLeases();

        // Worker B claims - gen becomes 2
        StoredIntent afterRecovery = poolStorage.findById(intent.id());
        poolStorage.claim(intent.id(), "worker-b", Instant.now().plus(LEASE_DURATION),
            afterRecovery.leaseGeneration(), afterRecovery.version());

        // Worker A (stale) attempts to record success with stale generation/version
        boolean staleSuccess = poolStorage.recordSuccess(
            intent.id(), ExecutionResult.Success.empty(), staleGeneration, staleVersion);

        assertThat(staleSuccess).isFalse();
        assertThat(poolStorage.findById(intent.id()).status()).isEqualTo(IntentStatus.RUNNING);
    }

    // --- Scenario 9: retry with next_attempt_at ---

    @Test
    void shouldTransitionToRetryingWithNextAttemptTime() throws SQLException {
        Intent intent = newIntent();
        storage.persist(intent);
        txConnection.commit();

        StorageAdapter poolStorage = storageFor(realDataSource());
        Instant leaseUntil = Instant.now().plus(LEASE_DURATION);
        poolStorage.claim(intent.id(), WORKER_ID, leaseUntil, 0, 0L);

        StoredIntent afterClaim = poolStorage.findById(intent.id());
        Instant nextAttemptAt = Instant.now().plus(Duration.ofSeconds(5));
        boolean scheduled = poolStorage.scheduleRetry(
            intent.id(), nextAttemptAt,
            afterClaim.leaseGeneration(), afterClaim.version());

        assertThat(scheduled).isTrue();
        StoredIntent after = poolStorage.findById(intent.id());
        assertThat(after.status()).isEqualTo(IntentStatus.RETRYING);
        assertThat(after.nextAttemptAt()).isNotNull();
    }

    @Test
    void shouldPromoteRetryingIntentWhenDue() throws SQLException {
        Intent intent = newIntent();
        storage.persist(intent);
        txConnection.commit();

        StorageAdapter poolStorage = storageFor(realDataSource());
        Instant leaseUntil = Instant.now().plus(LEASE_DURATION);
        poolStorage.claim(intent.id(), WORKER_ID, leaseUntil, 0, 0L);
        StoredIntent afterClaim = poolStorage.findById(intent.id());

        // schedule retry in the past so it's immediately due
        Instant pastDue = Instant.now().minus(Duration.ofSeconds(1));
        poolStorage.scheduleRetry(intent.id(), pastDue,
            afterClaim.leaseGeneration(), afterClaim.version());

        int promoted = poolStorage.promoteRetryingIntents();

        assertThat(promoted).isGreaterThanOrEqualTo(1);
        assertThat(poolStorage.findById(intent.id()).status()).isEqualTo(IntentStatus.READY);
    }

    // --- Scenario 10: blocked intents are observable ---

    @Test
    void shouldTransitionToBlockedAndBeObservable() throws SQLException {
        Intent intent = newIntent();
        storage.persist(intent);
        txConnection.commit();

        StorageAdapter poolStorage = storageFor(realDataSource());
        Instant leaseUntil = Instant.now().plus(LEASE_DURATION);
        poolStorage.claim(intent.id(), WORKER_ID, leaseUntil, 0, 0L);
        StoredIntent afterClaim = poolStorage.findById(intent.id());

        poolStorage.block(intent.id(), new RuntimeException("permanent failure"),
            afterClaim.leaseGeneration(), afterClaim.version());

        StoredIntent after = poolStorage.findById(intent.id());
        assertThat(after.status()).isEqualTo(IntentStatus.BLOCKED);
        assertThat(after.errorMessage()).isNotBlank();
    }

    // --- Scenario 11: cancellation bumps generation ---

    @Test
    void shouldIncrementGenerationOnCancelFromRunning() throws SQLException {
        Intent intent = newIntent();
        storage.persist(intent);
        txConnection.commit();

        StorageAdapter poolStorage = storageFor(realDataSource());
        Instant leaseUntil = Instant.now().plus(LEASE_DURATION);
        poolStorage.claim(intent.id(), WORKER_ID, leaseUntil, 0, 0L);
        StoredIntent afterClaim = poolStorage.findById(intent.id());
        int generationBeforeCancel = afterClaim.leaseGeneration();

        poolStorage.cancel(intent.id(), afterClaim.leaseGeneration(), afterClaim.version());

        StoredIntent afterCancel = poolStorage.findById(intent.id());
        assertThat(afterCancel.status()).isEqualTo(IntentStatus.CANCELLED);
        assertThat(afterCancel.leaseGeneration()).isEqualTo(generationBeforeCancel + 1);
    }

    @Test
    void shouldRejectStaleSuccessAfterCancellation() throws SQLException {
        Intent intent = newIntent();
        storage.persist(intent);
        txConnection.commit();

        StorageAdapter poolStorage = storageFor(realDataSource());
        Instant leaseUntil = Instant.now().plus(LEASE_DURATION);
        poolStorage.claim(intent.id(), WORKER_ID, leaseUntil, 0, 0L);
        StoredIntent afterClaim = poolStorage.findById(intent.id());

        poolStorage.cancel(intent.id(), afterClaim.leaseGeneration(), afterClaim.version());

        // Worker attempts SUCCESS with pre-cancel generation
        boolean staleSuccess = poolStorage.recordSuccess(
            intent.id(), ExecutionResult.Success.empty(),
            afterClaim.leaseGeneration(), afterClaim.version());

        assertThat(staleSuccess).isFalse();
        assertThat(poolStorage.findById(intent.id()).status()).isEqualTo(IntentStatus.CANCELLED);
    }

    // --- Helpers ---

    protected Intent newIntent() {
        return Intent.builder()
            .id(UUID.randomUUID())
            .operation(Operation.of("TestOperation", "v1"))
            .payload(ByteArrayPayload.ofString("test-payload"))
            .policy(ExecutionPolicy.defaultPolicy()
                .withFailureAction(FailureAction.RETRY))
            .build();
    }

    private void cleanTables(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM commitix_executions");
            stmt.execute("DELETE FROM commitix_intents");
        }
    }

    private List<StoredIntent> findAll(StorageAdapter adapter) {
        return adapter.findReadyIntents(Integer.MAX_VALUE);
    }
}
