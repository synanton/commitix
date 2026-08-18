-- Demo application schema

CREATE TABLE demo_orders (
    id          VARCHAR(36)  PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    product_id  VARCHAR(255) NOT NULL,
    quantity    INTEGER      NOT NULL CHECK (quantity > 0),
    status      VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMPTZ  NOT NULL,
    failure_reason TEXT
);

CREATE TABLE idempotency_keys (
    key          VARCHAR(512) PRIMARY KEY,
    intent_id    VARCHAR(36)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
