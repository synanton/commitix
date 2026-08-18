# Commitix - Durable Execution Intent

## A Component of the Synanton Business Logic Library

**Version 1.0**
**Date:** 2026-08-08
**Status:** Final

> **Once committed, work doesn't disappear.**

------

# 1. The Problem Commitix Solves

Enterprise operations frequently modify state and initiate additional work in the same logical business transaction.

Consider this common scenario:

text

```
Create Order
    │
    ├── save order to database
    ├── reserve inventory
    ├── update search index
    └── notify warehouse
```



Without appropriate coordination, the system can produce dangerous inconsistencies:

| Scenario                                  | Result                                          |
| ----------------------------------------- | ----------------------------------------------- |
| Order saved, notification lost            | Database = committed, Notification = never sent |
| Notification delivered, order rolled back | Notification = sent, Database = no order        |
| Process crashes between operations        | Order saved → CRASH → Inventory never reserved  |

The problem is **not** that systems fail. The problem is that **work disappears without a trace** when failures occur.

------

# 2. The Core Idea

**Durable Execution Intent.**

Commitix turns a business transaction's declared execution intent into **durable, recoverable work**. It guarantees that committed intent remains represented until it reaches an explicit terminal state; it does **not** guarantee that the external operation succeeds.

text

```
BEGIN
  │
  ├── business state changes
  │
  └── declare execution intent
  │
COMMIT
  │
  ▼
READY (durable)
  │
  ▼
Reliable Execution
```



If the transaction rolls back, the intent is never created.
If the transaction commits, the execution intent becomes durable and  remains recoverable until the system reaches an explicit terminal state.

------

# 3. The Core Guarantee

> **Commitix guarantees durable intent, not successful completion.**

## 3.1 What Commitix Guarantees

| Guarantee                    | Level   | Description                                         |
| ---------------------------- | ------- | --------------------------------------------------- |
| **Transactional durability** | Level 1 | If transaction commits, intent exists durably       |
| **Recoverable execution**    | Level 2 | Execution remains recoverable after process failure |
| **At‑least‑once attempts**   | Level 3 | Execution attempts continue according to policy     |

## 3.2 What Commitix Does Not Guarantee

| Non-Guarantee          | Why                                                          | Who Provides                             |
| ---------------------- | ------------------------------------------------------------ | ---------------------------------------- |
| Exactly‑once execution | Distributed systems cannot guarantee this without coordination overhead | Application idempotency                  |
| Successful execution   | The operation may fail permanently                           | Application logic, operator intervention |
| Resource availability  | Commitix does not schedule resources                         | Equilix                                  |
| Operation correctness  | Commitix executes what it is told                            | Application logic                        |

------

# 4. Commitix vs. Transactional Outbox

This distinction is fundamental.

| Concept             | Transactional Outbox              | Commitix                              |
| ------------------- | --------------------------------- | ------------------------------------- |
| **Primary concern** | Reliable event/message publishing | Durable execution obligation          |
| **Persists**        | Messages/events                   | Operation intents                     |
| **Semantics**       | "Something must be published"     | "Something must happen"               |
| **Lifecycle**       | One‑way send                      | Full lifecycle (retry, block, result) |
| **Retry**           | Typically retry on send failure   | Full retry policy with backoff        |
| **Recovery**        | Message re‑delivery               | Full state recovery                   |

A **transactional outbox can be one implementation technique** used by a Commitix adapter. But Commitix is not "another outbox library."

Commitix defines the business‑level semantics *above* whatever infrastructure provides durability.

------

# 5. Core Concepts

## 5.1 Intent

A declaration that an operation should be performed.

java

```
public interface Intent {
    /**
     * Stable, globally unique identifier.
     */
    UUID getId();

    /**
     * The operation to perform.
     */
    Operation getOperation();

    /**
     * Arguments for the operation.
     * Abstract payload; serialization is adapter concern.
     */
    Payload getPayload();

    /**
     * Execution policy.
     */
    ExecutionPolicy getPolicy();

    /**
     * Optional: deduplication key.
     * Identifies a logical execution occurrence.
     * See Section 5.6 for semantics.
     * 
     * Phase 1: deduplication key may be stored but uniqueness is not enforced.
     * Phase 2: UNIQUE constraint is added and deduplication is fully enforced.
     */
    Optional<String> getDeduplicationKey();
}
```



### Intent Properties

| Property              | Description                                          |
| --------------------- | ---------------------------------------------------- |
| **Identity**          | Stable ID for tracking and idempotency               |
| **Operation**         | What to execute (with version)                       |
| **Payload**           | Data for the operation                               |
| **Policy**            | Retry, timeout, deadline                             |
| **Deduplication Key** | Logical occurrence identity (Phase 2 fully enforced) |

**Immutability:** Once an intent becomes durable, it is immutable. Its fields cannot be  changed. To modify the operation, payload, or policy, a new intent with a new identity must be created.

## 5.2 Operation

java

```
public interface Operation {
    /**
     * Operation identifier.
     * Should include versioning for long‑lived intents.
     */
    String getId();

    /**
     * Operation version.
     * Enables evolution of operation semantics.
     */
    String getVersion();

    /**
     * Human‑readable name.
     */
    String getName();
}
```



**Why versioning matters:** An intent created today might execute three months later after the  application has changed. Operation versioning enables forward/backward  compatibility.

## 5.3 ExecutionPolicy

Defines how execution should behave. **It does not contain scheduling, priority, or resource allocation** – those belong to Equilix.

java

```
public interface ExecutionPolicy {
    /**
     * Maximum number of execution attempts.
     * UNLIMITED = retry forever.
     * Includes the initial execution attempt.
     * Only meaningful when FailureAction is RETRY.
     */
    int getMaxAttempts();

    /**
     * Retry delay configuration.
     * Default: exponential backoff from 1s to 60s
     */
    RetryDelay getRetryDelay();

    /**
     * Absolute deadline.
     * Prevents new execution attempts after this time.
     * Does not automatically terminate already-running operations.
     */
    Instant getDeadline();

    /**
     * Action on permanent failure.
     */
    FailureAction getFailureAction();
}
```



### Failure Actions

| Action    | Meaning                                                      |
| --------- | ------------------------------------------------------------ |
| **RETRY** | Retry according to `maxAttempts`; if exhausted → BLOCKED     |
| **FAIL**  | Mark as permanently FAILED (no intervention expected)        |
| **BLOCK** | Stop execution; require operator intervention (future action possible) |

### Operational Distinction: FAILED vs. BLOCKED

| State       | Meaning                                                      |
| ----------- | ------------------------------------------------------------ |
| **FAILED**  | The system has determined that execution cannot/should not continue. No human intervention is expected. This execution is over. |
| **BLOCKED** | Execution is stopped but a future continuation is possible through an  administrative decision. The execution is waiting for someone to decide  what happens next. |

### Retry Configuration

java

```
// Retry up to 3 times (initial + 2 retries) with exponential backoff
ExecutionPolicy.defaultPolicy()
    .withMaxAttempts(3)
    .withRetryDelay(RetryDelay.exponential(Duration.ofSeconds(1), Duration.ofMinutes(1)));

// Retry forever
ExecutionPolicy.defaultPolicy()
    .withMaxAttempts(UNLIMITED)
    .withRetryDelay(RetryDelay.constant(Duration.ofSeconds(5)));

// No retry: fail immediately
ExecutionPolicy.defaultPolicy()
    .withMaxAttempts(1)
    .withFailureAction(FAIL);
```



## 5.4 Payload

Abstract representation of operation arguments.

java

```
public interface Payload {
    /**
     * Content type hint for adapters.
     */
    String getContentType();
}
```



Serialization is handled by a separate adapter:

java

```
public interface PayloadSerializer {
    byte[] serialize(Payload payload);
    Payload deserialize(byte[] bytes, String contentType);
}
```



The core Commitix API does **not** depend on JSON, Java serialization, Protobuf, Avro, or any database‑specific format. Those are adapter concerns.

## 5.5 Attempt Counting

The `attempt_count` represents the number of execution attempts that have been **started** from the business perspective:

| Attempt           | Count | Description                                          |
| ----------------- | ----- | ---------------------------------------------------- |
| Initial execution | 1     | First attempt after becoming READY                   |
| First retry       | 2     | After transient failure, started by a worker         |
| Second retry      | 3     | After another transient failure, started by a worker |

`attempt_count` increments **whenever a worker starts execution** (transitions from READY to RUNNING).

It does **not** increment:

- During recovery (the state transition RUNNING → READY is not an execution start)
- During lease renewal
- During any state transition that does not begin a new execution attempt

**Important invariant:** `attempt_count` represents the number of execution *starts*, not the number of recovery events.

## 5.6 Identity, Deduplication, and Idempotency

Commitix distinguishes three related concepts:

| Concept               | Purpose                                   | Scope                                      | Responsibility |
| --------------------- | ----------------------------------------- | ------------------------------------------ | -------------- |
| **Intent ID**         | Unique identity of this physical intent   | One intent                                 | Commitix       |
| **Deduplication Key** | Identifies a logical execution occurrence | One occurrence across all states (Phase 2) | Commitix       |
| **Idempotency Key**   | Protects side effects from replay         | Application‑defined                        | Application    |

### Deduplication Semantics (Phase 2+)

A deduplication key identifies a **logical execution occurrence** – a unique business event. At most one intent with a given key may exist across **all states** unless an explicit replacement policy is configured.

| Scenario                                  | Behaviour                            |
| ----------------------------------------- | ------------------------------------ |
| Intent A = READY, Intent B = same key     | B is rejected (duplicate occurrence) |
| Intent A = RUNNING, Intent B = same key   | B is rejected                        |
| Intent A = SUCCESS, Intent B = same key   | B is rejected                        |
| Intent A = BLOCKED, Intent B = same key   | B is rejected                        |
| Intent A = FAILED, Intent B = same key    | B is rejected                        |
| Intent A = EXPIRED, Intent B = same key   | B is rejected                        |
| Intent A = CANCELLED, Intent B = same key | B is rejected                        |
| Two concurrent transactions with same key | First wins; second is rejected       |

**Phase 1:** Deduplication keys are stored but uniqueness is not enforced. This  allows the data model to evolve without blocking Phase 1 delivery.

**Replacement policy:** If a business process legitimately requires a new occurrence with the  same logical identity (e.g., a second payment for the same order),  generate a new deduplication key for the new occurrence. The old key  remains associated with the completed occurrence.

### Idempotency

An idempotency key must be **stable across retry attempts**:

text

```
Attempt 1: key = "order-42-reservation"
Attempt 2: key = "order-42-reservation"  ← same key
Attempt 3: key = "order-42-reservation"  ← same key
```



The key should **not** include the attempt number. That defeats the purpose of idempotency.

------

# 6. Lifecycle

## 6.1 Commitix Lifecycle States

| State         | Meaning                                                      |
| ------------- | ------------------------------------------------------------ |
| **DECLARED**  | Intent declared within transaction, not yet durable (application context only) |
| **READY**     | Durable, eligible for execution                              |
| **RUNNING**   | Currently executing (with lease and generation)              |
| **SUCCESS**   | Completed successfully                                       |
| **RETRYING**  | Failed, will retry after delay (contains `next_attempt_at`)  |
| **BLOCKED**   | Failed permanently, needs intervention                       |
| **FAILED**    | Completed with permanent failure (no intervention expected)  |
| **EXPIRED**   | Deadline passed; no new execution attempts allowed           |
| **CANCELLED** | Explicitly cancelled; terminal                               |

**Important:** `DECLARED` exists only within the application transaction context. Commitix's durable state machine begins at `READY`. `RESOLVED` and `ADMITTED` are not Commitix states – they belong to Resolutor and Equilix respectively.

## 6.2 Lifecycle Diagram

text

```
                Application Transaction
                ┌─────────────────────┐
                │      DECLARED       │
                └──────────┬──────────┘
                           │
                       (commit)
                           │
                           ▼
                ┌─────────────────────┐
                │       READY         │  ← durable, recoverable
                └──────────┬──────────┘
                           │
                    (atomic claim)
                    (attempt_count++)
                    (generation++)
                           │
                           ▼
                ┌─────────────────────┐
                │      RUNNING        │  ← with lease + generation
                └──────────┬──────────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
         ┌──────────┐ ┌──────────┐ ┌──────────┐
         │ SUCCESS  │ │ RETRYING │ │ EXPIRED  │
         └──────────┘ └────┬─────┘ └────┬─────┘
                    (after delay)        │
                           │             │
                           ▼             ▼
                      ┌──────────┐ ┌──────────┐
                      │ RUNNING  │ │ BLOCKED  │
                      └──────────┘ └──────────┘

              (max attempts exceeded or permanent failure)
                                    │
                                    ▼
                               ┌──────────┐
                               │ BLOCKED  │
                               └──────────┘
```



## 6.3 State Transitions

| From     | To        | Trigger                                | Generation Effect                     |
| -------- | --------- | -------------------------------------- | ------------------------------------- |
| DECLARED | READY     | Transaction commit                     | N/A                                   |
| DECLARED | (none)    | Transaction rollback                   | N/A                                   |
| READY    | RUNNING   | Atomic claim succeeds                  | generation++                          |
| RUNNING  | SUCCESS   | Execution completes successfully       | generation preserved                  |
| RUNNING  | RETRYING  | Execution fails, retry allowed         | generation preserved                  |
| RUNNING  | EXPIRED   | Deadline passes; no new attempts       | generation preserved                  |
| RUNNING  | BLOCKED   | Execution fails permanently            | generation preserved                  |
| RUNNING  | CANCELLED | Explicit cancellation                  | generation++ (validates worker epoch) |
| RETRYING | RUNNING   | Retry delay expires                    | generation++                          |
| READY    | CANCELLED | Explicit cancellation (if not claimed) | N/A                                   |
| EXPIRED  | BLOCKED   | Operator intervention                  | Admin API                             |
| BLOCKED  | READY     | Operator‑initiated retry               | Admin API                             |

**Important:** `RUNNING → CANCELLED` increments `lease_generation`, invalidating the worker's ownership epoch. A stale worker attempting to record SUCCESS after cancellation will be rejected.

------

# 7. Execution Model

## 7.1 The Transaction Boundary

The intent becomes durable **only when the business transaction commits**.

text

```
┌─────────────────────────────────────────────────────────────┐
│                    BUSINESS TRANSACTION                     │
│                                                             │
│  BEGIN                                                      │
│    │                                                        │
│    ├── business state changes                               │
│    │                                                        │
│    └── Commitix.declare(intent)                             │
│    │         │                                              │
│    │         └── INSERT INTO commitix_intents               │
│    │                                                        │
│  COMMIT                                                     │
│                                                             │
│  ┌─────────────────────────────────────────────────────────┐│
│  │  AFTER COMMIT                                          ││
│  │  Commitix enqueues the intent for dispatch             ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```



**Important:** Commitix participates in the existing transaction. It does not own the  transaction. The transaction integration is provided by an adapter.

## 7.2 The Atomic Claim

Workers **claim** intents atomically. The claim is the ownership transition; `lease_generation` increments on every successful claim.

sql

```
UPDATE commitix_intents
SET status = 'RUNNING',
    worker_id = :workerId,
    lease_until = :leaseUntil,
    lease_generation = lease_generation + 1,
    attempt_count = attempt_count + 1,
    version = version + 1,
    last_modified_at = NOW()
WHERE id = :intentId
  AND status = 'READY'
  AND (expires_at IS NULL OR expires_at > NOW())
  AND lease_generation = :currentGeneration
  AND version = :currentVersion;
```



If the update affects exactly one row, the claim succeeded. If zero rows,  another worker claimed it first (or the intent was already taken).

**Invariant:** Every `RUNNING` intent has exactly one `lease_generation` representing its owner epoch.

## 7.3 Fencing (Lease Generation)

**This is a core correctness mechanism, not an optional feature.**

A stale worker must not be able to write SUCCESS after its lease has expired and another worker has taken over. The `lease_generation` token ensures this:

text

```
Worker A claims intent
    generation = 1, lease = 30s

Worker A starts executing
Worker A crashes (lease still valid)

30s pass, lease expires

Recovery:
    RUNNING → READY
    generation remains 1 (ownership released, but epoch number does not advance)

Worker B claims:
    READY → RUNNING
    generation = 2 (ownership acquired, epoch advances)

Worker B executes successfully
Worker B records SUCCESS (generation = 2)

Worker A recovers and attempts to record SUCCESS (generation = 1)
Database rejects the update (lease_generation mismatch)
```



**Result:** Only the current lease holder can record the authoritative execution  result. External side effects remain at‑least‑once and must therefore be idempotent or independently deduplicated.

All state updates from a worker must include the current `lease_generation` and `version`:

sql

```
UPDATE commitix_intents
SET status = :newStatus,
    version = version + 1,
    last_modified_at = NOW()
WHERE id = :intentId
  AND worker_id = :workerId
  AND lease_generation = :currentGeneration
  AND lease_until > NOW()
  AND version = :currentVersion;
```



If the update affects zero rows, the worker no longer owns the lease or another update occurred concurrently.

### 7.3.1 Fencing Protects Commitix State, Not External Operations

> **Commitix fencing protects Commitix state, not arbitrary external side effects.**

Consider this scenario:

text

```
Worker A
  │
  ├── calls payment provider
  ├── payment succeeds
  └── network dies

Worker B
  │
  └── retries payment
```



The database fencing prevents Worker A from subsequently writing an authoritative SUCCESS. It cannot undo the payment.

Therefore:

> **External operations must provide idempotency or an independent deduplication mechanism when duplicate execution is unacceptable.**

## 7.4 Leases

Each running intent has a lease.

sql

```
worker_id VARCHAR(255),
lease_until TIMESTAMPTZ,
lease_generation INTEGER DEFAULT 0,
```



If a worker crashes, its leases expire. Recovery releases ownership:

sql

```
UPDATE commitix_intents
SET status = 'READY',
    worker_id = NULL,
    lease_until = NULL,
    version = version + 1,
    last_modified_at = NOW()
WHERE status = 'RUNNING'
  AND lease_until < NOW();
```



**Invariants:**

| State    | worker_id | lease_until      |
| -------- | --------- | ---------------- |
| READY    | NULL      | NULL             |
| RUNNING  | non‑NULL  | future timestamp |
| Terminal | NULL      | NULL             |

## 7.5 RETRYING with Next Attempt Time

The `RETRYING` state includes a `next_attempt_at` timestamp.

sql

```
next_attempt_at TIMESTAMPTZ,
```



When an execution fails with `FailureAction.RETRY`:

sql

```
UPDATE commitix_intents
SET status = 'RETRYING',
    next_attempt_at = NOW() + :retryDelay,
    worker_id = NULL,
    lease_until = NULL,
    version = version + 1,
    last_modified_at = NOW()
WHERE id = :intentId
  AND lease_generation = :currentGeneration
  AND version = :currentVersion;
```



Recovery makes retry‑ready intents eligible:

sql

```
UPDATE commitix_intents
SET status = 'READY',
    version = version + 1,
    last_modified_at = NOW()
WHERE status = 'RETRYING'
  AND next_attempt_at <= NOW();
```



This provides a durable, observable representation of retry eligibility.

## 7.6 Deadline Semantics

**Deadline prevents new execution attempts. It does not automatically terminate already‑running operations.**

| Scenario                   | Behaviour                                            |
| -------------------------- | ---------------------------------------------------- |
| READY + deadline passed    | → EXPIRED                                            |
| RETRYING + deadline passed | → EXPIRED                                            |
| RUNNING + deadline passed  | Continues until completion, failure, or cancellation |

**Rationale:** Commitix cannot safely terminate arbitrary external operations (HTTP  requests, database transactions, payment calls). Deadline therefore  controls eligibility to start new attempts, not to kill in‑flight work.

## 7.7 Fast Path vs. Durable Path

text

```
                       COMMIT
                          │
                          ▼
                       READY
                          │
              ┌───────────┴───────────┐
              ▼                       ▼
      Fast Dispatcher           Recovery Worker
      (optimization)            (guaranteed)
              │                       │
              └───────────┬───────────┘
                          ▼
                   atomic claim
                          │
                          ▼
                       RUNNING
```



**Principle:** The fast path is an **optimization** of dispatch latency. It is **not** an alternative to durable persistence. The durable state is always the authority.

> **Optimization must never become a correctness mechanism.**

## 7.8 At‑Least‑Once Semantics

Commitix provides **at‑least‑once** execution attempts.

Consider this scenario:

text

```
RUNNING (generation = 1)
   │
   ├── external side effect succeeds
   │
   └── process crashes before recording SUCCESS
   │
   ▼
lease expires; recovery releases ownership
   │
   ▼
READY (generation still 1, ownership released)
   │
   ▼
Worker B claims (generation = 2)
   │
   ▼
RUNNING (again, with new generation)
   │
   ▼
SUCCESS
```



This is a fundamental distributed systems property. A worker may  successfully execute the external operation, crash before recording  SUCCESS, and then have the intent retried. **This is why idempotency is the application's responsibility.**

**Guarantee:** At‑least‑once intent execution attempts.

**Application responsibility:** Idempotent or deduplicatable side effects.

------

# 8. Failure Model

## 8.1 Failure Types

| Failure Type             | Behaviour                                 | Recovery                                          |
| ------------------------ | ----------------------------------------- | ------------------------------------------------- |
| **Transient**            | Network timeout, temporary unavailability | RETRY after delay                                 |
| **Permanent**            | Invalid data, authorisation failure       | FAILED or BLOCKED                                 |
| **Timeout (deadline)**   | Deadline exceeded                         | EXPIRED → terminal; operator intervention         |
| **Process crash**        | Worker dies during execution              | Lease recovery → READY → claim → RETRY            |
| **Database unavailable** | Cannot persist intent                     | Transaction rollback                              |
| **Cancel while RUNNING** | Operator cancels                          | CANCELLED (generation++ invalidates worker epoch) |

## 8.2 Retry Logic

java

```
public class RetryDelay {
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double backoffMultiplier;

    public Duration nextDelay(int attempt) {
        long delay = initialDelay.toMillis();
        for (int i = 0; i < attempt; i++) {
            delay = (long) (delay * backoffMultiplier);
        }
        return Duration.ofMillis(Math.min(delay, maxDelay.toMillis()));
    }
}
```



**Default retry policy:**

| Parameter          | Value      |
| ------------------ | ---------- |
| Max attempts       | 3          |
| Initial delay      | 1 second   |
| Max delay          | 60 seconds |
| Backoff multiplier | 2.0        |

## 8.3 Blocked Intents

Intents that exceed the retry limit or fail with permanent errors go to **BLOCKED** state.

Commitix exposes:

- `BLOCKED` status
- `IntentBlocked` event
- Error details

The application/platform layer decides what to do:

- Alert operator
- Store in dead letter queue
- Display in UI
- Manual retry
- Compensation

Commitix does **not** implement alerting, dead letter queues, or UI. That is application/platform responsibility.

------

# 9. Adapter Architecture

## 9.1 Cloaking

Commitix embodies the Synanton **cloaking** principle: business semantics are deliberately separated from infrastructure.

text

```
                 BUSINESS MEANING
                       │
                       ▼
                  Commitix Intent
                       │
              ┌────────┴────────┐
              │      CLOAK       │
              │                  │
              │ infrastructure  │
              │ implementation  │
              └────────┬─────────┘
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
    PostgreSQL       Kafka           HTTP
```



**Why cloaking matters:** The business layer should not know what is underneath the cloak.  Cloaking does not hide implementation because implementation is  unimportant. It hides implementation because implementation is **replaceable**.

## 9.2 Three Adapter Families

text

```
                 COMMITIX CORE
                      │
        ┌─────────────┼─────────────┐
        │             │             │
        ▼             ▼             ▼
 Transaction       Storage       Execution
   Adapter         Adapter        Adapter
        │             │             │
    JDBC/JTA       PostgreSQL     JVM
    Spring         Kafka          HTTP
    etc.           Redis?         gRPC
```



### Transaction Adapter

Discovers and participates in the ambient transaction.

java

```
public interface TransactionAdapter {
    /**
     * Returns true if the adapter can enlist in the current transaction.
     */
    boolean isTransactionActive();

    /**
     * Register an action to run after successful commit.
     */
    void afterCommit(Runnable action);
}
```



### Storage Adapter

Persists and recovers intents with explicit transition operations.

java

```
public interface StorageAdapter {
    // Intent persistence
    void persist(Intent intent);

    // Ownership operations
    boolean claim(UUID id, String workerId, Instant leaseUntil, int currentGeneration, long currentVersion);
    boolean releaseLease(UUID id, int currentGeneration, long currentVersion);

    // Result operations
    boolean recordSuccess(UUID id, ExecutionResult result, int generation, long version);
    boolean recordFailure(UUID id, Throwable error, int generation, long version);

    // Retry operations
    boolean scheduleRetry(UUID id, Instant nextAttemptAt, int generation, long version);

    // Administrative operations
    boolean block(UUID id, Throwable error, int generation, long version);
    boolean fail(UUID id, Throwable error, int generation, long version);
    boolean cancel(UUID id, int generation, long version);

    // Recovery operations
    List<Intent> findReadyIntents(int limit);
    List<Intent> findExpiredLeases(int limit);
    List<Intent> findReadyRetries(int limit);
    int recoverExpiredLeases();

    // Query
    Intent findById(UUID id);
}
```



### Execution Adapter

Invokes the business operation.

java

```
public interface ExecutionAdapter {
    /**
     * Execute the intent synchronously.
     * Returns result or throws.
     */
    ExecutionResult execute(Intent intent, ExecutionContext context)
        throws ExecutionException;

    /**
     * Best-effort request to stop currently running work.
     * Application/adapter-specific; may be a no-op.
     * Does not affect Commitix state; cancellation must be recorded
     * through the StorageAdapter independently.
     */
    void cancel(UUID id);
}
```



The core library does not depend on:

- JDBC
- Kafka
- Spring
- Any specific serialization
- Any specific transaction manager

## 9.3 Reference PostgreSQL Adapter (Schema)

sql

```
-- Commitix intents table (Phase 1)
CREATE TABLE commitix_intents (
    id UUID PRIMARY KEY,
    deduplication_key VARCHAR(255),       -- Phase 2: UNIQUE constraint added
    operation_id VARCHAR(255) NOT NULL,
    operation_version VARCHAR(50) NOT NULL,
    payload_type VARCHAR(100) NOT NULL,
    payload_value BYTEA NOT NULL,
    status VARCHAR(50) NOT NULL,
    worker_id VARCHAR(255),
    lease_until TIMESTAMPTZ,
    lease_generation INTEGER DEFAULT 0,
    max_attempts INTEGER DEFAULT 3,
    attempt_count INTEGER DEFAULT 0,
    retry_delay_ms BIGINT DEFAULT 1000,
    next_attempt_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    last_modified_at TIMESTAMPTZ NOT NULL,
    error_message TEXT,
    stack_trace TEXT,
    version BIGINT DEFAULT 0   -- optimistic concurrency for all transitions
);

-- Phase 1 indexes
CREATE INDEX idx_commitix_status_ready
    ON commitix_intents (status, created_at)
    WHERE status = 'READY';

CREATE INDEX idx_commitix_status_retrying
    ON commitix_intents (status, next_attempt_at)
    WHERE status = 'RETRYING';

CREATE INDEX idx_commitix_lease
    ON commitix_intents (status, lease_until)
    WHERE status = 'RUNNING';

-- Index for deduplication lookups (Phase 1)
CREATE INDEX idx_commitix_deduplication
    ON commitix_intents (deduplication_key);

-- Phase 2: UNIQUE constraint on deduplication_key
-- ALTER TABLE commitix_intents ADD CONSTRAINT uk_commitix_deduplication UNIQUE (deduplication_key);

-- Commitix executions table
CREATE TABLE commitix_executions (
    id UUID PRIMARY KEY,
    intent_id UUID NOT NULL,
    attempt_number INTEGER DEFAULT 0,
    status VARCHAR(50) NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_message TEXT,
    result_type VARCHAR(100),
    result_value BYTEA,
    FOREIGN KEY (intent_id) REFERENCES commitix_intents(id),
    UNIQUE(intent_id, attempt_number)
);

CREATE INDEX idx_executions_intent_id
    ON commitix_executions (intent_id);
```



**Note on `version`:** The `version` column serves as an optimistic concurrency control token for all state transitions. It is independent of `lease_generation`; `lease_generation` handles worker ownership, while `version` prevents lost updates between different processes. Both are necessary for correctness.

------

# 10. Relationship with Resolutor and Equilix

Commitix is one of three siblings in the Synanton Business Logic Library.

## 10.1 Orthogonal Capabilities

text

```
                  BUSINESS INTENT
                        │
        ┌───────────────┼───────────────┐
        │               │               │
        ▼               ▼               ▼
   RESOLUTOR         EQUILIX         COMMITIX
        │               │               │
   logical         resource         durability
   reasoning       reasoning        semantics
        │               │               │
   What may         When and        How is intent
   happen?        with what?        preserved?
```



These are **not** a linear pipeline. They are independent abstractions that can be used alone or together.

| Component     | Fundamental Question                                | Provides                                                     |
| ------------- | --------------------------------------------------- | ------------------------------------------------------------ |
| **Resolutor** | What logically follows?                             | Relationships, dependencies, constraints, execution plans    |
| **Equilix**   | What can run, when, and with what resources?        | Admission, resource balancing, scheduling, priority          |
| **Commitix**  | How do we ensure committed work does not disappear? | Durable intent, recovery, execution lifecycle, observability |

## 10.2 Component Boundaries

| Concern               | Owner       | Reason                   |
| --------------------- | ----------- | ------------------------ |
| Intent definition     | Commitix    | Core abstraction         |
| Dependency validation | Resolutor   | Relationship reasoning   |
| Execution plan        | Resolutor   | Logical structure        |
| Resource allocation   | Equilix     | Resource awareness       |
| Admission control     | Equilix     | Capacity management      |
| Durable persistence   | Commitix    | Transactional durability |
| Execution tracking    | Commitix    | Lifecycle management     |
| Idempotency           | Application | Business semantics       |
| Retry                 | Commitix    | Execution policy         |

## 10.3 Usage Scenarios

### Scenario A: Standalone Transactional Intent

Commitix can operate independently, without Resolutor or Equilix.

text

```
Business Transaction
        │
        ├── business state changes
        │
        └── Commitix.declare(intent)
                │
             COMMIT
                │
                ▼
             READY
                │
             Execution
```



### Scenario B: Full Synanton Pipeline

Commitix serves as the durability layer for execution plans produced by Resolutor and Equilix.

text

```
Business Intent
        │
        ▼
    Resolutor
        │
    ExecutionPlan
        │
        ▼
     Equilix
        │
    ResourceSchedule
        │
        ▼
    Commitix
        │
    Durable Execution
```



------

# 11. Observability

## 11.1 Execution States (Visible)

An operator can see for each intent:

| Field           | Description                     |
| --------------- | ------------------------------- |
| ID              | Stable identifier               |
| Operation       | What it does                    |
| Status          | Current state                   |
| Attempt count   | How many attempts started       |
| Created at      | When declared                   |
| Started at      | When last executed              |
| Lease until     | When lease expires              |
| Next attempt at | When retry is due               |
| Generation      | Current lease generation        |
| Version         | Current optimistic lock version |
| Error           | If failed                       |
| Result          | If succeeded                    |

## 11.2 Metrics

text

```
commitix.intents.total{status}
commitix.execution.duration
commitix.retries.total{operation}
commitix.errors.total{operation,type}
commitix.queue.depth
commitix.blocked.total
commitix.lease.expired.total
```



## 11.3 Health Check

text

```
GET /actuator/health/commitix
{
    "status": "UP",
    "pendingIntents": 42,
    "blockedIntents": 3,
    "expiredLeases": 0,
    "lastRecovery": "2026-08-08T10:00:00Z"
}
```



------

# 12. Phase 1 Implementation Plan

## 12.1 Phase 1 Focus

The first Commitix proof must demonstrate the **complete correctness model**, including fencing, to prove the architecture. The implementation should be minimal but not unsafe.

**Phase 1 deliverables:**

1. Declare intent inside transaction
2. Transaction commit → intent persists
3. Transaction rollback → intent not persists
4. Worker claims with lease and generation (with version)
5. Worker executes and records SUCCESS/FAILURE (with generation + version)
6. Worker crash → lease expires → recovery releases ownership
7. New worker claims with generation increment
8. Stale worker cannot write SUCCESS (generation mismatch)
9. Retry with backoff and `next_attempt_at`
10. Blocked intents are observable
11. Cancellation with generation invalidation

## 12.2 Modules

text

```
commitix-core/
    ├── Intent, Operation, ExecutionPolicy, Payload
    ├── Commitix API
    └── Adapter Contracts

commitix-jdbc/
    ├── TransactionAdapter (JDBC)
    └── StorageAdapter (JDBC)

commitix-reference-postgres/
    └── PostgreSQL schema and queries

commitix-runtime/
    ├── Dispatcher
    ├── RecoveryProcessor (handles expired leases and ready retries)
    └── ExecutionAdapter (JVM)

commitix-demo/
    ├── Demo application
    └── Example: order processing with reliable notifications
```



## 12.3 Phase 1 Features

| Feature                                   | Included |
| ----------------------------------------- | -------- |
| Declare intent within transaction         | ✅        |
| Transaction-aware persistence             | ✅        |
| Atomic claim with lease and generation    | ✅        |
| Stale worker detection via generation     | ✅        |
| Optimistic locking via version            | ✅        |
| Recovery with ownership release           | ✅        |
| RETRYING with `next_attempt_at`           | ✅        |
| Cancellation with generation invalidation | ✅        |
| Status tracking                           | ✅        |
| PostgreSQL reference adapter              | ✅        |
| Demo application                          | ✅        |

## 12.4 Phase 2 and Beyond

| Feature                           | Phase   |
| --------------------------------- | ------- |
| Deduplication (UNIQUE constraint) | Phase 2 |
| Fast path optimization            | Phase 2 |
| Prometheus metrics                | Phase 2 |
| Multiple adapter implementations  | Phase 2 |
| Web UI for monitoring             | Phase 3 |
| GraphQL API                       | Phase 3 |

------

# 13. Usage Example

## 13.1 Declaring an Intent

java

```
@Service
public class OrderService {
    private final Commitix commitix;
    private final OrderRepository orderRepository;

    @Transactional
    public Order createOrder(CreateOrderRequest request) {
        // 1. Business state
        Order order = orderRepository.save(
            Order.create(request)
        );

        // 2. Declare intents
        Intent intent = Intent.builder()
            .id(UUID.randomUUID())
            .operation(Operation.of("InventoryReserve", "v1"))
            .payload(JsonPayload.of(
                "orderId", order.getId(),
                "items", request.getItems()
            ))
            .policy(ExecutionPolicy.defaultPolicy()
                .withMaxAttempts(5)
                .withDeadline(Instant.now().plus(Duration.ofMinutes(10))))
            .build();

        // 3. Persist intent (transaction-aware)
        commitix.declare(intent);

        return order;
    }
}
```



## 13.2 Executing an Intent

java

```
@CommitixHandler(operation = "InventoryReserve", version = "v1")
public class InventoryReservationHandler implements IntentHandler {

    private final InventoryService inventoryService;

    @Override
    public ExecutionResult execute(Intent intent, ExecutionContext context) {
        UUID orderId = intent.getPayload().get("orderId");
        List<Item> items = intent.getPayload().get("items");

        try {
            inventoryService.reserve(orderId, items);
            return ExecutionResult.success();
        } catch (InsufficientInventoryException e) {
            return ExecutionResult.failure(e, FailureAction.BLOCK);
        } catch (TransientException e) {
            return ExecutionResult.failure(e, FailureAction.RETRY);
        }
    }
}
```



## 13.3 Recovery Demo (with Fencing)

text

```
Worker A: claims Intent-1
    generation = 1, lease = 30s

Worker A: starts executing
Worker A: crashes (lease still valid)

30s pass

Recovery: releases ownership
    status = READY
    worker_id = NULL
    lease_until = NULL
    generation remains 1

Worker B: claims Intent-1
    generation = 2, lease = 30s

Worker B: executes successfully
Worker B: records SUCCESS (generation = 2, accepted)

Worker A: recovers, attempts to record SUCCESS (generation = 1)
Database: rejects update (lease_generation mismatch)

Result: Only the current lease holder can record the authoritative execution result.
External side effects remain at‑least‑once and must therefore be idempotent or
independently deduplicated.
```



## 13.4 Cancellation with Fencing

text

```
Worker A: claims Intent-1
    generation = 1, lease = 30s

Worker A: starts executing

Operator: cancels Intent-1
    status = CANCELLED
    generation = 2 (invalidates worker epoch)

Worker A: completes successfully
Worker A: attempts to record SUCCESS (generation = 1)
Database: rejects update (lease_generation mismatch)

Result: Cancellation is durable and cannot be overridden by a stale worker.
```



------

# 14. Architectural Summary

## 14.1 Commitix in One Slide

text

```
┌─────────────────────────────────────────────────────────────┐
│                        COMMITIX                            │
│                                                             │
│  Problem: Work disappears when failures occur              │
│                                                             │
│  Solution: Durable Execution Intent                         │
│                                                             │
│  Core Guarantee:                                            │
│    Committed intent is durable and recoverable             │
│    Not: successful completion of the operation             │
│                                                             │
│  Guarantee Levels:                                          │
│    1. Transactional durability                             │
│    2. Recoverable execution                                │
│    3. At‑least‑once attempts                               │
│                                                             │
│  Lifecycle:                                                 │
│    DECLARED → READY → RUNNING → SUCCESS / RETRYING / EXPIRED│
│              RETRYING → RUNNING                            │
│              EXPIRED → BLOCKED                             │
│                                                             │
│  Failure Handling:                                          │
│    • Retry with backoff (next_attempt_at)                  │
│    • Block on permanent failure                            │
│    • Lease + generation fencing                            │
│    • Optimistic locking (version)                          │
│    • Observable state                                      │
│                                                             │
│  Adapters:                                                  │
│    • Transaction                                            │
│    • Storage                                                │
│    • Execution                                              │
└─────────────────────────────────────────────────────────────┘
```



## 14.2 The Three Siblings

text

```
                    EXECUTION GUARANTEES
                            │
        ┌───────────────────┼───────────────────┐
        │                   │                   │
        ▼                   ▼                   ▼
   RESOLUTOR            EQUILIX             COMMITIX
        │                   │                   │
   logical plan         resources           durability
   dependencies         admission           persistence
   constraints          scheduling          recovery
        │                   │                   │
        └───────────────────┼───────────────────┘
                            │
                     Execution Intent
                            │
                            ▼
                     Intent Handler
                            │
                            ▼
                    Business Operation
```



## 14.3 The Guiding Principles

1. **Intent immutability** – once durable, an intent cannot be changed. Only its state transitions.
2. **Fencing is correctness** – `lease_generation` is not optional. It prevents stale workers from corrupting state. Generation increments **only on ownership acquisition**.
3. **Optimistic locking is safety** – `version` prevents lost updates between concurrent processes.
4. **Recovery releases ownership** – `worker_id` and `lease_until` are cleared when an intent becomes READY. Recovery does not increment generation.
5. **Fast path is an optimization** – immediate execution after commit is desirable, but not required for correctness. The durable state is always authoritative.
6. **Deduplication Key** identifies a logical execution occurrence. Phase 2 fully enforces uniqueness; Phase 1 stores but does not enforce.
7. **Expiration prevents new attempts** – an expired intent cannot become READY again automatically. Deadline does not terminate running work.
8. **Cloaking** – the business layer is deliberately separated from infrastructure. Implementation is replaceable.
9. **Cancellation invalidates the worker epoch** – `RUNNING → CANCELLED` increments generation, making stale SUCCESS writes impossible.
10. **External fencing protects Commitix state** – not arbitrary side effects. Idempotency is the application's responsibility.

------

# 15. Conclusion

Commitix addresses a fundamental enterprise problem: **preserving the execution intent of committed business transactions and providing  reliable, recoverable execution according to policy.**

It does this by:

1. **Durable Execution Intent** – The application declares what should happen; Commitix ensures it survives transaction boundaries.
2. **Transaction-aware persistence** – Intents are durable only when the business transaction commits.
3. **Lease + generation fencing** – Workers claim intents with leases and generations; stale workers are  prevented from corrupting state. Generation increments on ownership  acquisition.
4. **Optimistic locking** – Versioning prevents lost updates between concurrent state transitions.
5. **Explicit failure handling** – Retry with `next_attempt_at`, block, or fail as policy dictates.
6. **Adapter‑based architecture** – Implementation can change without changing business logic.

**Commitix is not** another outbox library. It is a business execution abstraction.

**Commitix is not** a message broker. It is a semantic layer above infrastructure.

**Commitix is not** a workflow engine. It is the reliable execution foundation for higher‑level enterprise knowledge capabilities.

Together with Resolutor (relationships) and Equilix (resources), Commitix forms the processing foundation of Synanton:

text

```
Resolutor decides.
Equilix balances.
Commitix guarantees.
```



------

*Commitix - Once committed, work doesn't disappear.*