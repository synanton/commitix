-- Commitix Phase 1 schema
-- Stores durable execution intents and individual execution attempts.

CREATE TABLE commitix_intents (
    id                UUID          NOT NULL,
    deduplication_key VARCHAR(255),                          -- Phase 2: UNIQUE constraint
    operation_id      VARCHAR(255)  NOT NULL,
    operation_version VARCHAR(50)   NOT NULL,
    operation_name    VARCHAR(255)  NOT NULL,
    payload_type      VARCHAR(100)  NOT NULL,
    payload_value     BYTEA         NOT NULL,
    status            VARCHAR(50)   NOT NULL,
    worker_id         VARCHAR(255),
    lease_until       TIMESTAMPTZ,
    lease_generation  INTEGER       NOT NULL DEFAULT 0,
    max_attempts      INTEGER       NOT NULL DEFAULT 3,
    attempt_count     INTEGER       NOT NULL DEFAULT 0,
    retry_delay_ms    BIGINT        NOT NULL DEFAULT 1000,
    retry_delay_max_ms BIGINT       NOT NULL DEFAULT 60000,
    retry_multiplier  DOUBLE PRECISION NOT NULL DEFAULT 2.0,
    failure_action    VARCHAR(50)   NOT NULL DEFAULT 'RETRY',
    next_attempt_at   TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL,
    last_modified_at  TIMESTAMPTZ   NOT NULL,
    error_message     TEXT,
    stack_trace       TEXT,
    version           BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_commitix_intents PRIMARY KEY (id),
    CONSTRAINT ck_commitix_status CHECK (
        status IN ('READY','RUNNING','RETRYING','SUCCESS','BLOCKED','FAILED','EXPIRED','CANCELLED')
    ),
    CONSTRAINT ck_commitix_failure_action CHECK (
        failure_action IN ('RETRY','FAIL','BLOCK')
    )
);

CREATE TABLE commitix_executions (
    id             UUID          NOT NULL,
    intent_id      UUID          NOT NULL,
    attempt_number INTEGER       NOT NULL DEFAULT 0,
    status         VARCHAR(50)   NOT NULL,
    started_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    error_message  TEXT,
    result_type    VARCHAR(100),
    result_value   BYTEA,

    CONSTRAINT pk_commitix_executions PRIMARY KEY (id),
    CONSTRAINT uq_commitix_executions_attempt UNIQUE (intent_id, attempt_number),
    CONSTRAINT fk_commitix_executions_intent
        FOREIGN KEY (intent_id) REFERENCES commitix_intents (id)
);
