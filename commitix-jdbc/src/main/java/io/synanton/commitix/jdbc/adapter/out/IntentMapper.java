package io.synanton.commitix.jdbc.adapter.out;

import io.synanton.commitix.core.domain.model.ExecutionPolicy;
import io.synanton.commitix.core.domain.model.FailureAction;
import io.synanton.commitix.core.domain.model.Intent;
import io.synanton.commitix.core.domain.model.IntentStatus;
import io.synanton.commitix.core.domain.model.Operation;
import io.synanton.commitix.core.domain.model.RetryDelay;
import io.synanton.commitix.core.domain.model.StoredIntent;
import io.synanton.commitix.core.port.PayloadSerializer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/** Maps a JDBC {@link ResultSet} row to a {@link StoredIntent}. */
@RequiredArgsConstructor
final class IntentMapper {

    private final PayloadSerializer payloadSerializer;

    StoredIntent map(ResultSet rs) throws SQLException {
        UUID id = UUID.fromString(rs.getString("id"));
        String deduplicationKey = rs.getString("deduplication_key");

        Operation operation = new Operation(
            rs.getString("operation_id"),
            rs.getString("operation_version"),
            rs.getString("operation_name")
        );

        byte[] payloadBytes = rs.getBytes("payload_value");
        String payloadType = rs.getString("payload_type");

        RetryDelay retryDelay = new RetryDelay(
            Duration.ofMillis(rs.getLong("retry_delay_ms")),
            Duration.ofMillis(rs.getLong("retry_delay_max_ms")),
            rs.getDouble("retry_multiplier")
        );

        Timestamp expiresAtTs = rs.getTimestamp("expires_at");
        @Nullable Instant expiresAt = expiresAtTs != null ? expiresAtTs.toInstant() : null;

        ExecutionPolicy policy = new ExecutionPolicy(
            rs.getInt("max_attempts"),
            retryDelay,
            expiresAt,
            FailureAction.valueOf(rs.getString("failure_action"))
        );

        Intent intent = Intent.builder()
            .id(id)
            .operation(operation)
            .payload(payloadSerializer.deserialize(payloadBytes, payloadType))
            .policy(policy)
            .deduplicationKey(deduplicationKey)
            .build();

        IntentStatus status = IntentStatus.valueOf(rs.getString("status"));

        Timestamp leaseUntilTs = rs.getTimestamp("lease_until");
        @Nullable Instant leaseUntil = leaseUntilTs != null ? leaseUntilTs.toInstant() : null;

        Timestamp nextAttemptAtTs = rs.getTimestamp("next_attempt_at");
        @Nullable Instant nextAttemptAt = nextAttemptAtTs != null ? nextAttemptAtTs.toInstant() : null;

        return new StoredIntent(
            intent,
            status,
            rs.getInt("lease_generation"),
            rs.getLong("version"),
            rs.getInt("attempt_count"),
            rs.getString("worker_id"),
            leaseUntil,
            nextAttemptAt,
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("last_modified_at").toInstant(),
            rs.getString("error_message")
        );
    }
}
