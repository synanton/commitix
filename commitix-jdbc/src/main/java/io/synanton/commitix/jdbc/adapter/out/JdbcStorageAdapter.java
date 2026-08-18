package io.synanton.commitix.jdbc.adapter.out;

import io.synanton.commitix.core.domain.error.StorageException;
import io.synanton.commitix.core.domain.model.ExecutionResult;
import io.synanton.commitix.core.domain.model.Intent;
import io.synanton.commitix.core.domain.model.StoredIntent;
import io.synanton.commitix.core.port.PayloadSerializer;
import io.synanton.commitix.core.port.StorageAdapter;
import io.synanton.commitix.jdbc.adapter.out.sql.IntentSql;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * JDBC implementation of {@link StorageAdapter}.
 *
 * <p>Each method acquires a connection from the injected {@link DataSource}.
 * For transactional operations (e.g. {@link #persist}), use a
 * {@code SingleConnectionDataSource} wrapping the current transaction's connection
 * so the INSERT participates in the business transaction.
 */
@Slf4j
@RequiredArgsConstructor
public final class JdbcStorageAdapter implements StorageAdapter {

    private final DataSource dataSource;
    private final PayloadSerializer payloadSerializer;

    private IntentMapper mapper() {
        return new IntentMapper(payloadSerializer);
    }

    // --- Intent persistence ---

    @Override
    public void persist(Intent intent) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(IntentSql.INSERT)) {
            bindInsert(ps, intent);
            ps.executeUpdate();
            log.debug("Persisted intent id={}", intent.id());
        } catch (SQLException ex) {
            throw new StorageException("Failed to persist intent " + intent.id(), ex);
        }
    }

    private void bindInsert(PreparedStatement ps, Intent intent) throws SQLException {
        byte[] payloadBytes = payloadSerializer.serialize(intent.payload());
        ps.setString(1, intent.id().toString());
        setNullableString(ps, 2, intent.deduplicationKey());
        ps.setString(3, intent.operation().id());
        ps.setString(4, intent.operation().version());
        ps.setString(5, intent.operation().name());
        ps.setString(6, intent.payload().contentType());
        ps.setBytes(7, payloadBytes);
        ps.setInt(8, intent.policy().maxAttempts());
        ps.setLong(9, intent.policy().retryDelay().initial().toMillis());
        ps.setLong(10, intent.policy().retryDelay().max().toMillis());
        ps.setDouble(11, intent.policy().retryDelay().multiplier());
        ps.setString(12, intent.policy().failureAction().name());
        setNullableInstant(ps, 13, intent.policy().deadline());
    }

    // --- Ownership operations ---

    @Override
    public boolean claim(UUID id, String workerId, Instant leaseUntil,
                         int currentGeneration, long currentVersion) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(IntentSql.CLAIM)) {
            ps.setString(1, workerId);
            ps.setTimestamp(2, Timestamp.from(leaseUntil));
            ps.setString(3, id.toString());
            ps.setInt(4, currentGeneration);
            ps.setLong(5, currentVersion);
            int rows = ps.executeUpdate();
            return rows == 1;
        } catch (SQLException ex) {
            throw new StorageException("Failed to claim intent " + id, ex);
        }
    }

    @Override
    public boolean releaseLease(UUID id, int currentGeneration, long currentVersion) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(IntentSql.RELEASE_LEASE)) {
            ps.setString(1, id.toString());
            ps.setInt(2, currentGeneration);
            ps.setLong(3, currentVersion);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new StorageException("Failed to release lease on intent " + id, ex);
        }
    }

    // --- Result operations ---

    @Override
    public boolean recordSuccess(UUID id, ExecutionResult.Success result,
                                 int currentGeneration, long currentVersion) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(IntentSql.RECORD_SUCCESS)) {
            ps.setString(1, id.toString());
            ps.setInt(2, currentGeneration);
            ps.setLong(3, currentVersion);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new StorageException("Failed to record success for intent " + id, ex);
        }
    }

    @Override
    public boolean recordFailure(UUID id, Throwable error,
                                 int currentGeneration, long currentVersion) {
        return updateWithError(IntentSql.RECORD_FAILURE, id, error, currentGeneration, currentVersion);
    }

    // --- Retry operations ---

    @Override
    public boolean scheduleRetry(UUID id, Instant nextAttemptAt,
                                 int currentGeneration, long currentVersion) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(IntentSql.SCHEDULE_RETRY)) {
            ps.setTimestamp(1, Timestamp.from(nextAttemptAt));
            ps.setString(2, id.toString());
            ps.setInt(3, currentGeneration);
            ps.setLong(4, currentVersion);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new StorageException("Failed to schedule retry for intent " + id, ex);
        }
    }

    // --- Administrative operations ---

    @Override
    public boolean block(UUID id, Throwable error, int currentGeneration, long currentVersion) {
        return updateWithError(IntentSql.BLOCK, id, error, currentGeneration, currentVersion);
    }

    @Override
    public boolean fail(UUID id, Throwable error, int currentGeneration, long currentVersion) {
        return updateWithError(IntentSql.FAIL, id, error, currentGeneration, currentVersion);
    }

    @Override
    public boolean cancel(UUID id, int currentGeneration, long currentVersion) {
        // Try RUNNING → CANCELLED first (increments generation); fall back to READY → CANCELLED
        boolean cancelledFromRunning = cancelFromRunning(id, currentGeneration, currentVersion);
        if (cancelledFromRunning) {
            return true;
        }
        return cancelFromReady(id, currentGeneration, currentVersion);
    }

    private boolean cancelFromRunning(UUID id, int currentGeneration, long currentVersion) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(IntentSql.CANCEL_FROM_RUNNING)) {
            ps.setString(1, id.toString());
            ps.setInt(2, currentGeneration);
            ps.setLong(3, currentVersion);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new StorageException("Failed to cancel intent " + id + " from RUNNING", ex);
        }
    }

    private boolean cancelFromReady(UUID id, int currentGeneration, long currentVersion) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(IntentSql.CANCEL_FROM_READY)) {
            ps.setString(1, id.toString());
            ps.setInt(2, currentGeneration);
            ps.setLong(3, currentVersion);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new StorageException("Failed to cancel intent " + id + " from READY", ex);
        }
    }

    // --- Recovery queries ---

    @Override
    public List<StoredIntent> findReadyIntents(int limit) {
        return queryList(IntentSql.FIND_READY, limit);
    }

    @Override
    public List<StoredIntent> findExpiredLeases(int limit) {
        return queryList(IntentSql.FIND_EXPIRED_LEASES, limit);
    }

    @Override
    public List<StoredIntent> findReadyRetries(int limit) {
        return queryList(IntentSql.FIND_READY_RETRIES, limit);
    }

    @Override
    public int recoverExpiredLeases() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(IntentSql.RECOVER_EXPIRED_LEASES)) {
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("Recovered {} expired lease(s)", rows);
            }
            return rows;
        } catch (SQLException ex) {
            throw new StorageException("Failed to recover expired leases", ex);
        }
    }

    @Override
    public int promoteRetryingIntents() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(IntentSql.PROMOTE_RETRYING)) {
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.debug("Promoted {} retrying intent(s) to READY", rows);
            }
            return rows;
        } catch (SQLException ex) {
            throw new StorageException("Failed to promote retrying intents", ex);
        }
    }

    @Override
    public int expireOverdueIntents() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(IntentSql.EXPIRE_OVERDUE)) {
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("Expired {} overdue intent(s)", rows);
            }
            return rows;
        } catch (SQLException ex) {
            throw new StorageException("Failed to expire overdue intents", ex);
        }
    }

    @Override
    public @Nullable StoredIntent findById(UUID id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(IntentSql.FIND_BY_ID)) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapper().map(rs);
                }
                return null;
            }
        } catch (SQLException ex) {
            throw new StorageException("Failed to find intent " + id, ex);
        }
    }

    // --- Internal helpers ---

    private List<StoredIntent> queryList(String sql, int limit) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<StoredIntent> results = new ArrayList<>();
                IntentMapper intentMapper = mapper();
                while (rs.next()) {
                    results.add(intentMapper.map(rs));
                }
                return results;
            }
        } catch (SQLException ex) {
            throw new StorageException("Failed to query intents", ex);
        }
    }

    private boolean updateWithError(String sql, UUID id, Throwable error,
                                    int currentGeneration, long currentVersion) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String message = error.getMessage() != null ? error.getMessage() : error.getClass().getName();
            ps.setString(1, message);
            ps.setString(2, id.toString());
            ps.setInt(3, currentGeneration);
            ps.setLong(4, currentVersion);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new StorageException("Failed to update intent " + id, ex);
        }
    }

    private void setNullableString(PreparedStatement ps, int idx, @Nullable String value)
            throws SQLException {
        if (value != null) {
            ps.setString(idx, value);
        } else {
            ps.setNull(idx, Types.VARCHAR);
        }
    }

    private void setNullableInstant(PreparedStatement ps, int idx, @Nullable Instant value)
            throws SQLException {
        if (value != null) {
            ps.setTimestamp(idx, Timestamp.from(value));
        } else {
            ps.setNull(idx, Types.TIMESTAMP_WITH_TIMEZONE);
        }
    }
}
