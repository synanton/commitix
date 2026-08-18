package io.synanton.commitix.jdbc.adapter.out.sql;

/**
 * SQL statements for the commitix_intents table.
 * All column names and parameter positions match V1__init.sql exactly.
 */
public final class IntentSql {

    private IntentSql() {
    }

    public static final String INSERT = """
        INSERT INTO commitix_intents (
            id, deduplication_key,
            operation_id, operation_version, operation_name,
            payload_type, payload_value,
            status,
            worker_id, lease_until, lease_generation,
            max_attempts, attempt_count,
            retry_delay_ms, retry_delay_max_ms, retry_multiplier,
            failure_action,
            next_attempt_at, expires_at,
            created_at, last_modified_at,
            version
        ) VALUES (
            ?::uuid, ?,
            ?, ?, ?,
            ?, ?,
            'READY',
            NULL, NULL, 0,
            ?, 0,
            ?, ?, ?,
            ?,
            NULL, ?,
            NOW(), NOW(),
            0
        )
        """;

    /** Atomic claim: READY → RUNNING. Increments lease_generation, attempt_count, version. */
    public static final String CLAIM = """
        UPDATE commitix_intents
        SET status           = 'RUNNING',
            worker_id        = ?,
            lease_until      = ?,
            lease_generation = lease_generation + 1,
            attempt_count    = attempt_count + 1,
            version          = version + 1,
            last_modified_at = NOW()
        WHERE id              = ?::uuid
          AND status          = 'READY'
          AND (expires_at IS NULL OR expires_at > NOW())
          AND lease_generation = ?
          AND version          = ?
        """;

    /**
     * Transition for retry promotion: RETRYING → RUNNING.
     * Increments lease_generation, attempt_count, version.
     */
    public static final String CLAIM_FROM_RETRYING = """
        UPDATE commitix_intents
        SET status           = 'RUNNING',
            worker_id        = ?,
            lease_until      = ?,
            lease_generation = lease_generation + 1,
            attempt_count    = attempt_count + 1,
            version          = version + 1,
            last_modified_at = NOW()
        WHERE id              = ?::uuid
          AND status          = 'RETRYING'
          AND next_attempt_at <= NOW()
          AND (expires_at IS NULL OR expires_at > NOW())
          AND lease_generation = ?
          AND version          = ?
        """;

    /**
     * Recovery: RUNNING → READY for expired leases.
     * Preserves lease_generation - recovery does not increment the ownership epoch.
     */
    public static final String RECOVER_EXPIRED_LEASES = """
        UPDATE commitix_intents
        SET status           = 'READY',
            worker_id        = NULL,
            lease_until      = NULL,
            version          = version + 1,
            last_modified_at = NOW()
        WHERE status    = 'RUNNING'
          AND lease_until < NOW()
        """;

    /** Recovery: RETRYING → READY when next_attempt_at has passed. */
    public static final String PROMOTE_RETRYING = """
        UPDATE commitix_intents
        SET status           = 'READY',
            version          = version + 1,
            last_modified_at = NOW()
        WHERE status        = 'RETRYING'
          AND next_attempt_at <= NOW()
        """;

    /** Expiry sweep: READY or RETRYING → EXPIRED when deadline is past. */
    public static final String EXPIRE_OVERDUE = """
        UPDATE commitix_intents
        SET status           = 'EXPIRED',
            version          = version + 1,
            last_modified_at = NOW()
        WHERE status IN ('READY', 'RETRYING')
          AND expires_at IS NOT NULL
          AND expires_at < NOW()
        """;

    /**
     * RUNNING → SUCCESS. Guards on lease_generation, version, lease_until.
     * The lease_generation is the correctness fence (white paper §7.3); worker_id is
     * omitted here so the interface does not need to pass it separately.
     */
    public static final String RECORD_SUCCESS = """
        UPDATE commitix_intents
        SET status           = 'SUCCESS',
            worker_id        = NULL,
            lease_until      = NULL,
            version          = version + 1,
            last_modified_at = NOW()
        WHERE id              = ?::uuid
          AND status          = 'RUNNING'
          AND lease_generation = ?
          AND lease_until      > NOW()
          AND version          = ?
        """;

    /** RUNNING → FAILED (permanent failure, no further attempts). */
    public static final String RECORD_FAILURE = """
        UPDATE commitix_intents
        SET status           = 'FAILED',
            worker_id        = NULL,
            lease_until      = NULL,
            error_message    = ?,
            version          = version + 1,
            last_modified_at = NOW()
        WHERE id              = ?::uuid
          AND status          = 'RUNNING'
          AND lease_generation = ?
          AND lease_until      > NOW()
          AND version          = ?
        """;

    /** RUNNING → RETRYING. Sets next_attempt_at for delayed retry. */
    public static final String SCHEDULE_RETRY = """
        UPDATE commitix_intents
        SET status           = 'RETRYING',
            worker_id        = NULL,
            lease_until      = NULL,
            next_attempt_at  = ?,
            version          = version + 1,
            last_modified_at = NOW()
        WHERE id              = ?::uuid
          AND status          = 'RUNNING'
          AND lease_generation = ?
          AND lease_until      > NOW()
          AND version          = ?
        """;

    /** RUNNING → BLOCKED (operator intervention required). */
    public static final String BLOCK = """
        UPDATE commitix_intents
        SET status           = 'BLOCKED',
            worker_id        = NULL,
            lease_until      = NULL,
            error_message    = ?,
            version          = version + 1,
            last_modified_at = NOW()
        WHERE id              = ?::uuid
          AND status          = 'RUNNING'
          AND lease_generation = ?
          AND lease_until      > NOW()
          AND version          = ?
        """;

    /** RUNNING → FAILED (explicit fail action). */
    public static final String FAIL = """
        UPDATE commitix_intents
        SET status           = 'FAILED',
            worker_id        = NULL,
            lease_until      = NULL,
            error_message    = ?,
            version          = version + 1,
            last_modified_at = NOW()
        WHERE id              = ?::uuid
          AND status          = 'RUNNING'
          AND lease_generation = ?
          AND lease_until      > NOW()
          AND version          = ?
        """;

    /**
     * RUNNING → CANCELLED. Increments lease_generation to invalidate the worker epoch,
     * preventing a stale worker from recording a late SUCCESS.
     */
    public static final String CANCEL_FROM_RUNNING = """
        UPDATE commitix_intents
        SET status           = 'CANCELLED',
            worker_id        = NULL,
            lease_until      = NULL,
            lease_generation = lease_generation + 1,
            version          = version + 1,
            last_modified_at = NOW()
        WHERE id              = ?::uuid
          AND status          = 'RUNNING'
          AND lease_generation = ?
          AND version          = ?
        """;

    /** READY → CANCELLED (before claim). Does not touch lease_generation. */
    public static final String CANCEL_FROM_READY = """
        UPDATE commitix_intents
        SET status           = 'CANCELLED',
            version          = version + 1,
            last_modified_at = NOW()
        WHERE id              = ?::uuid
          AND status          = 'READY'
          AND lease_generation = ?
          AND version          = ?
        """;

    /** Release ownership without any state change (internal recovery helper). */
    public static final String RELEASE_LEASE = """
        UPDATE commitix_intents
        SET status           = 'READY',
            worker_id        = NULL,
            lease_until      = NULL,
            version          = version + 1,
            last_modified_at = NOW()
        WHERE id              = ?::uuid
          AND status          = 'RUNNING'
          AND lease_generation = ?
          AND version          = ?
        """;

    public static final String FIND_BY_ID = """
        SELECT id, deduplication_key,
               operation_id, operation_version, operation_name,
               payload_type, payload_value,
               status, worker_id, lease_until, lease_generation,
               max_attempts, attempt_count,
               retry_delay_ms, retry_delay_max_ms, retry_multiplier,
               failure_action,
               next_attempt_at, expires_at,
               created_at, last_modified_at,
               error_message, version
        FROM commitix_intents
        WHERE id = ?::uuid
        """;

    public static final String FIND_READY = """
        SELECT id, deduplication_key,
               operation_id, operation_version, operation_name,
               payload_type, payload_value,
               status, worker_id, lease_until, lease_generation,
               max_attempts, attempt_count,
               retry_delay_ms, retry_delay_max_ms, retry_multiplier,
               failure_action,
               next_attempt_at, expires_at,
               created_at, last_modified_at,
               error_message, version
        FROM commitix_intents
        WHERE status = 'READY'
          AND (expires_at IS NULL OR expires_at > NOW())
        ORDER BY created_at ASC
        LIMIT ?
        """;

    public static final String FIND_EXPIRED_LEASES = """
        SELECT id, deduplication_key,
               operation_id, operation_version, operation_name,
               payload_type, payload_value,
               status, worker_id, lease_until, lease_generation,
               max_attempts, attempt_count,
               retry_delay_ms, retry_delay_max_ms, retry_multiplier,
               failure_action,
               next_attempt_at, expires_at,
               created_at, last_modified_at,
               error_message, version
        FROM commitix_intents
        WHERE status = 'RUNNING'
          AND lease_until < NOW()
        LIMIT ?
        """;

    public static final String FIND_READY_RETRIES = """
        SELECT id, deduplication_key,
               operation_id, operation_version, operation_name,
               payload_type, payload_value,
               status, worker_id, lease_until, lease_generation,
               max_attempts, attempt_count,
               retry_delay_ms, retry_delay_max_ms, retry_multiplier,
               failure_action,
               next_attempt_at, expires_at,
               created_at, last_modified_at,
               error_message, version
        FROM commitix_intents
        WHERE status = 'RETRYING'
          AND next_attempt_at <= NOW()
        LIMIT ?
        """;
}
