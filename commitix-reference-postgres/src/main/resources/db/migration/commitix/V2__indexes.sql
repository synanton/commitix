-- Partial indexes for Commitix state-machine queries.
-- Phase 2 migration V3 will add the UNIQUE constraint on deduplication_key.

-- Dispatcher: find READY intents eligible for execution
CREATE INDEX idx_commitix_status_ready
    ON commitix_intents (created_at ASC)
    WHERE status = 'READY';

-- Recovery: promote RETRYING intents whose delay has elapsed
CREATE INDEX idx_commitix_status_retrying
    ON commitix_intents (next_attempt_at ASC)
    WHERE status = 'RETRYING';

-- Recovery: find RUNNING intents with expired leases
CREATE INDEX idx_commitix_lease_expired
    ON commitix_intents (lease_until ASC)
    WHERE status = 'RUNNING';

-- Expiry sweep: find READY/RETRYING intents past their deadline
CREATE INDEX idx_commitix_expires_at
    ON commitix_intents (expires_at ASC)
    WHERE status IN ('READY', 'RETRYING') AND expires_at IS NOT NULL;

-- Deduplication lookups (Phase 1: lookup only, no uniqueness enforced)
CREATE INDEX idx_commitix_deduplication
    ON commitix_intents (deduplication_key)
    WHERE deduplication_key IS NOT NULL;

-- execution history
CREATE INDEX idx_commitix_executions_intent
    ON commitix_executions (intent_id);
