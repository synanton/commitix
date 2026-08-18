# Commitix - Phase 1 Implementation Plan

**Scope:** Phase 1 of the Commitix white paper (v1.0), delivered as a multi-module
Maven library targeting Java 21 with a hexagonal (ports & adapters) architecture.

**Status:** Draft - matches white paper §12 (Phase 1 Implementation Plan) and
`.cursor/rules/java-rules.mdc` (Commitix uses Maven; Java 21; Lombok; JSpecify).

---

## 1. Guiding Principles

The design must literally realise the ten guiding principles of the white paper
(§14.3). Any deviation is a bug in this plan:

1. **Intent immutability.** Once persisted, Intent fields never change; only
   state transitions.
2. **Fencing is correctness.** `lease_generation` increments **only** on
   ownership acquisition (READY → RUNNING and RETRYING → RUNNING) and on
   `RUNNING → CANCELLED`. It does **not** increment during recovery.
3. **Optimistic locking is safety.** Every state transition writes with
   `version = version + 1` and matches on the observed `version`.
4. **Recovery releases ownership.** `worker_id = NULL`, `lease_until = NULL`;
   generation is preserved.
5. **Fast path is optimisation.** The durable state machine is authoritative;
   Phase 1 ships only the durable path.
6. **Deduplication key is stored, not enforced** (Phase 1).
7. **Expiration prevents new attempts** but does not terminate in-flight work.
8. **Cloaking.** `commitix-core` depends on **nothing** except JSpecify,
   Lombok (compile-only), and SLF4J API. No JDBC, no Spring, no serializer.
9. **Cancellation invalidates the worker epoch** (RUNNING → CANCELLED bumps
   generation).
10. **Fencing protects Commitix state, not external side effects.** Idempotency
    stays an application responsibility; documented in Javadoc.

Deferred to Phase 2+ (do not implement): DEDUPLICATION UNIQUE constraint,
fast-path dispatcher, Prometheus metrics, additional storage adapters, Web UI.

---

## 2. Module Layout (Maven multi-module)

```
commitix/                                     ← parent POM (pom.xml, packaging=pom)
  ├── commitix-bom/                           ← published BOM for dependents
  ├── commitix-core/                          ← domain + ports (no infra deps)
  ├── commitix-jdbc/                          ← JDBC transaction + storage adapter
  ├── commitix-reference-postgres/            ← PostgreSQL Flyway schema + SQL
  ├── commitix-runtime/                       ← dispatcher, recovery, JVM executor
  └── commitix-demo/                          ← Spring Boot order-processing demo
```

**Parent POM responsibilities:**

- Set `<java.version>21</java.version>`, `maven.compiler.release=21`.
- Enforcer plugin (Maven 3.9+, Java 21+, no duplicate/convergent version conflicts).
- Central `<dependencyManagement>` (imports `commitix-bom` self-reference for
  cross-module version alignment; imports `spring-boot-dependencies` only in
  `commitix-runtime` and `commitix-demo`, **never** in `commitix-core`).
- Common plugin config: `maven-compiler-plugin`, `maven-surefire-plugin`,
  `maven-failsafe-plugin` (integration tests), `jacoco-maven-plugin`,
  `maven-checkstyle-plugin` (backed by `/checkstyle.xml`, 120-char lines).
- Reproducible builds: `project.build.outputTimestamp` fixed.

**Dependency direction (enforced by module graph):**

```
commitix-core       ── has no runtime deps beyond SLF4J + JSpecify (+ Lombok provided)
commitix-jdbc       ── depends on commitix-core
commitix-reference-postgres ── depends on commitix-core (SQL + Flyway only)
commitix-runtime    ── depends on commitix-core (Spring Boot autoconfig lives here)
commitix-demo       ── depends on runtime + jdbc + reference-postgres
```

Any adapter accidentally importing from another adapter fails the build.

---

## 3. Hexagonal Package Layout

`.cursor/rules/java-rules.mdc` describes a service layout (`adapter/in`,
`adapter/out`, `domain`, `config`). Commitix is a **library**, so we apply the
same principle: the domain owns the ports; adapters live in their own modules.

```
commitix-core (io.synanton.commitix.core)
  ├── domain/
  │   ├── model/          Intent, Operation, ExecutionPolicy, Payload, ExecutionResult,
  │   │                   RetryDelay, FailureAction, IntentStatus, ExecutionContext
  │   ├── error/          CommitixException hierarchy (see §7)
  │   └── Commitix.java   Public API: declare(Intent)
  ├── port/               Ports = SPI implemented by adapters
  │   ├── TransactionAdapter
  │   ├── StorageAdapter
  │   ├── ExecutionAdapter
  │   ├── PayloadSerializer
  │   ├── Clock            (thin wrapper over java.time.Clock for testability)
  │   └── IntentHandler   Business handlers registered with runtime
  └── util/               Purely internal helpers (package-private)

commitix-jdbc (io.synanton.commitix.jdbc)
  └── adapter/out/
      ├── JdbcStorageAdapter
      ├── JdbcTransactionAdapter   (Spring TransactionSynchronizationManager
      │                             *and* plain DataSource variants - see §6)
      └── sql/                     SQL constants only, no logic

commitix-reference-postgres (io.synanton.commitix.postgres)
  └── db/migration/V1__init.sql
  └── db/migration/V2__indexes.sql
      (Flyway lives here; no Java code besides a Spring Boot autoconfig
       stub that registers Flyway locations if consumer opts in.)

commitix-runtime (io.synanton.commitix.runtime)
  ├── adapter/in/schedule/
  │   ├── DispatcherScheduler        polls READY intents
  │   └── RecoveryScheduler          releases expired leases, promotes RETRYING→READY
  ├── adapter/out/execution/
  │   └── JvmExecutionAdapter        virtual-thread dispatch to IntentHandler
  ├── domain/
  │   └── DispatchLoop, RecoveryLoop (pure logic used by schedulers)
  └── config/
      └── CommitixAutoConfiguration  wires the beans; opt-in via property

commitix-demo (io.synanton.commitix.demo)
  ├── adapter/in/rest/               POST /orders
  ├── adapter/out/                   InventoryClient, NotificationClient (stubs)
  ├── domain/
  │   ├── OrderService, CreateOrderUseCase
  │   └── handler/InventoryReserveHandler, WarehouseNotifyHandler
  └── config/                        application.yml, DemoConfig
```

**Non-negotiables:**

- `commitix-core` **must not** compile against JDBC, Spring, Jackson, or any
  serializer. A module-info-free but ArchUnit-enforced boundary test asserts
  this in CI.
- `domain` never imports `port` implementations; `port` never imports adapters.

---

## 4. Core Domain Model (`commitix-core`)

Follow `@122-java-type-design` and `@144-java-data-oriented-programming`:
records for immutable data, sealed hierarchies where the choice-space is closed,
`@Nullable` (JSpecify) on every nullable reference.

### 4.1 Records and enums

```java
public record Intent(
    UUID id,
    Operation operation,
    Payload payload,
    ExecutionPolicy policy,
    @Nullable String deduplicationKey,
    Instant createdAt
) { /* compact ctor: null checks on non-nullable fields */ }

public record Operation(String id, String version, String name) { }

public record ExecutionPolicy(
    int maxAttempts,                 // UNLIMITED = -1
    RetryDelay retryDelay,
    @Nullable Instant deadline,
    FailureAction failureAction
) {
    public static final int UNLIMITED = -1;
    public static ExecutionPolicy defaultPolicy() { /* 3 attempts, 1s→60s exp, RETRY */ }
}

public record RetryDelay(Duration initial, Duration max, double multiplier) {
    public Duration nextDelay(int attempt) { /* clamped exponential */ }
    public static RetryDelay exponential(Duration initial, Duration max) { … }
    public static RetryDelay constant(Duration d) { … }
}

public enum FailureAction { RETRY, FAIL, BLOCK }

public enum IntentStatus {
    DECLARED, READY, RUNNING, RETRYING, SUCCESS, BLOCKED, FAILED, EXPIRED, CANCELLED
}

public sealed interface ExecutionResult permits Success, Failure {
    record Success(@Nullable Payload result) implements ExecutionResult { }
    record Failure(Throwable cause, FailureAction action) implements ExecutionResult { }
}

public interface Payload { String contentType(); }   // marker
```

### 4.2 Public API - `Commitix`

```java
public interface Commitix {
    /** Declares an intent within the current transaction. Persists on commit;
     *  disappears on rollback. */
    void declare(Intent intent);
}
```

The default `TransactionalCommitix` implementation delegates persistence to
`StorageAdapter.persist` and registers an `afterCommit` hook via
`TransactionAdapter` for post-commit dispatch notification. Persistence itself
happens **inside** the transaction so rollback is automatic - the after-commit
hook only signals the dispatcher.

### 4.3 Attempt counting invariant (§5.5)

`attempt_count` increments **only** on the atomic claim SQL. Any other place
that increments it is a bug. Enforced by:

- SQL: only the claim statement writes `attempt_count = attempt_count + 1`.
- Contract test: `StorageAdapterContractTest#recoveryDoesNotIncrementAttemptCount`.

---

## 5. Ports (`commitix-core/port`)

Match the white paper §9.2 exactly. Ports are the SPI adapters implement.

```java
public interface TransactionAdapter {
    boolean isTransactionActive();
    void afterCommit(Runnable action);
}

public interface StorageAdapter {
    void persist(Intent intent);

    boolean claim(UUID id, String workerId, Instant leaseUntil,
                  int currentGeneration, long currentVersion);
    boolean releaseLease(UUID id, int currentGeneration, long currentVersion);

    boolean recordSuccess(UUID id, ExecutionResult.Success r, int gen, long ver);
    boolean recordFailure(UUID id, Throwable err, int gen, long ver);

    boolean scheduleRetry(UUID id, Instant nextAttemptAt, int gen, long ver);
    boolean block(UUID id, Throwable err, int gen, long ver);
    boolean fail (UUID id, Throwable err, int gen, long ver);
    boolean cancel(UUID id, int gen, long ver);

    List<Intent> findReadyIntents(int limit);
    List<Intent> findExpiredLeases(int limit);
    List<Intent> findReadyRetries(int limit);
    int recoverExpiredLeases();

    @Nullable Intent findById(UUID id);
}

public interface ExecutionAdapter {
    ExecutionResult execute(Intent intent, ExecutionContext ctx) throws ExecutionException;
    void cancel(UUID id);   // best-effort
}

public interface PayloadSerializer {
    byte[] serialize(Payload payload);
    Payload deserialize(byte[] bytes, String contentType);
}
```

**Boolean returns:** `true` iff exactly one row was updated. `false` means the
worker no longer owns the lease, the version drifted, or another transition won
the race. Adapters translate `int rowsAffected` from JDBC accordingly.

---

## 6. `commitix-jdbc` - JDBC Adapters

### 6.1 `JdbcStorageAdapter`

Uses plain `DataSource` + prepared statements. **Not** JdbcTemplate - the core
must stay Spring-free; the JDBC adapter needs only the standard `java.sql` API.

Every state transition executes exactly the SQL from §7.2, §7.3, §7.4, §7.5:

- Claim (§7.2): guards on `status='READY'`, deadline, `lease_generation`,
  `version`. Increments `lease_generation`, `attempt_count`, `version`.
- Success/Failure/Retry/Block/Fail: guards on `worker_id`, `lease_generation`,
  `lease_until > NOW()`, `version`. Increments `version` only.
- Cancel from RUNNING: increments `version` **and** `lease_generation`
  (invalidates the stale worker epoch - §7.3.1).
- Cancel from READY: increments `version` only (nothing to invalidate).
- Recover expired leases (§7.4): sets status=READY, clears worker_id/lease_until,
  preserves `lease_generation`, increments `version`.
- Promote RETRYING → READY when `next_attempt_at <= NOW()`.

**PayloadSerializer wiring:** The storage adapter accepts a `PayloadSerializer`
in its constructor. `payload_type` stores `payload.contentType()`,
`payload_value` stores the serialized bytes.

**Query API:** `findReadyIntents` orders by `created_at ASC` and uses
`LIMIT ? FOR UPDATE SKIP LOCKED` on PostgreSQL to prevent multiple dispatchers
picking the same row before claim. This is a preselect only - the claim is the
authoritative ownership transition.

### 6.2 `JdbcTransactionAdapter`

Two implementations behind a common `JdbcTransactionAdapter` type:

- `SpringJdbcTransactionAdapter` (in `commitix-runtime`, not this module) -
  hooks into `TransactionSynchronizationManager` to detect active tx and
  register an `afterCommit` callback via `TransactionSynchronization`.
- `ManualJdbcTransactionAdapter` (in this module) - for consumers not on Spring;
  the caller passes in a running `Connection` and drives commit themselves. The
  adapter tracks callbacks in a `ThreadLocal<Deque<Runnable>>` flushed on
  explicit `commit()`.

Keeping the Spring variant out of `commitix-jdbc` preserves cloaking: this
module has zero Spring on the classpath.

---

## 7. Errors and Exceptions (`@123-java-exception-handling`)

Sealed hierarchy in `commitix-core/domain/error`:

```java
public sealed abstract class CommitixException extends RuntimeException
    permits IntentAlreadyExistsException,       // future dedup enforcement
             ConcurrentModificationException,   // version/lease-generation drift
             SerializationException,
             StorageException,
             ExecutionException,
             NoActiveTransactionException;
```

- `Commitix.declare` throws `NoActiveTransactionException` if
  `TransactionAdapter.isTransactionActive()` is false.
- `ConcurrentModificationException` is thrown by the runtime **only** if a
  state transition unexpectedly returns `false` when the caller believed it
  still owned the lease - never leaked to `declare` callers.
- Handler-side exceptions from `IntentHandler.execute` are wrapped into
  `ExecutionResult.Failure`; `ExecutionAdapter.execute` never throws for
  business failures - only for infrastructure failures.

Try-with-resources on `Connection`/`PreparedStatement`. Never swallow
`InterruptedException` in the runtime loops (§`@125-java-concurrency`).

---

## 8. `commitix-reference-postgres` - Schema and Migrations

Ships the SQL from white paper §9.3 as Flyway migrations. Ships **no Java
logic**. Optional Spring Boot autoconfig class that declares Flyway locations
so a consumer only writes:

```yaml
spring.flyway.locations: classpath:db/migration,classpath:db/migration/commitix
```

### 8.1 `V1__init.sql`

```sql
CREATE TABLE commitix_intents (
    id UUID PRIMARY KEY,
    deduplication_key VARCHAR(255),            -- Phase 1: no UNIQUE constraint
    operation_id VARCHAR(255) NOT NULL,
    operation_version VARCHAR(50)  NOT NULL,
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
    version BIGINT DEFAULT 0
);

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
```

### 8.2 `V2__indexes.sql`

Partial indexes from §9.3 (`idx_commitix_status_ready`,
`idx_commitix_status_retrying`, `idx_commitix_lease`,
`idx_commitix_deduplication`).

Phase 2 (**not now**): `V3__deduplication_unique.sql` adding the UNIQUE
constraint on `deduplication_key`.

---

## 9. `commitix-runtime` - Dispatcher, Recovery, Execution

### 9.1 `JvmExecutionAdapter`

- Uses a virtual-thread executor
  (`Executors.newVirtualThreadPerTaskExecutor()`), per `@125-java-concurrency`.
- Resolves the right `IntentHandler` from a registry keyed by
  `Operation.id + Operation.version`.
- Wraps the handler call in a `try/catch (Throwable)` - checked exceptions
  become `ExecutionResult.Failure(cause, FailureAction.RETRY)` by default;
  handlers may throw a `PermanentException` (in demo) that maps to `BLOCK`.
- `cancel(UUID)` is best-effort: sets a volatile flag on the
  `ExecutionContext` that handlers may cooperatively read; the white paper
  §9.2 explicitly permits no-op.

### 9.2 `DispatchLoop` (invoked by `DispatcherScheduler`)

Per tick:

1. `storage.findReadyIntents(batchSize)` (uses `FOR UPDATE SKIP LOCKED`).
2. For each intent, attempt `storage.claim(id, workerId, leaseUntil, gen, ver)`.
   Skip on `false` (lost race).
3. On success, submit to `JvmExecutionAdapter`.
4. Executor callback records terminal transition via storage. All transitions
   pass the `lease_generation` and `version` observed at claim time.

`workerId` is `hostname + "-" + processId + "-" + randomUUID().slice(8)`
computed once at startup.

### 9.3 `RecoveryLoop` (invoked by `RecoveryScheduler`)

Per tick (in one transaction each):

1. `storage.recoverExpiredLeases()` - RUNNING with `lease_until < NOW()` → READY,
   generation preserved.
2. Promote RETRYING → READY where `next_attempt_at <= NOW()`.
3. EXPIRED sweep: READY/RETRYING intents with `expires_at < NOW()` → EXPIRED.
   (RUNNING intents past deadline are **not** touched - §7.6.)

Both loops honour cancellation flags on their executor and never swallow
`InterruptedException`.

### 9.4 Spring Boot autoconfiguration

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
registers `CommitixAutoConfiguration` which:

- Creates `Commitix` bean iff `commitix.enabled=true` (default `true`).
- Wires `StorageAdapter` (Jdbc), `TransactionAdapter` (Spring variant),
  `ExecutionAdapter` (JvmExecutionAdapter), `PayloadSerializer` (Jackson-backed
  default; overridable), `Clock` (system UTC).
- Registers `@Scheduled` triggers with cron/interval bound to
  `commitix.dispatcher.interval` and `commitix.recovery.interval` from
  `application.yml`. **No literal defaults in Java** (`@Value("${key}")` only)
  - defaults live in the module's bundled `application.yml`.

Configuration properties (bound via `@ConfigurationProperties("commitix")`):

```yaml
commitix:
  enabled: true
  worker-id-prefix: ${HOSTNAME:local}
  dispatcher:
    interval: 200ms
    batch-size: 32
    lease-duration: 30s
  recovery:
    interval: 1s
    batch-size: 128
```

---

## 10. `commitix-demo` - Order Processing Example

Realises white paper §13 in a runnable Spring Boot app.

- `POST /orders` → `CreateOrderUseCase` runs `@Transactional`, saves the Order,
  then `commitix.declare(...)` for both `InventoryReserve` and `WarehouseNotify`.
- `InventoryReserveHandler` implements `IntentHandler`. Idempotency
  demonstrated via an application-owned `idempotency_keys` table
  (`orderId + "-reservation"`) - reinforces §5.6 (idempotency is the
  application's job).
- Recovery demo (§13.3): a REST endpoint `POST /demo/crash-during/{intentId}`
  toggles a fault so the handler sleeps past its lease, verifying that a
  second worker takes over with generation++ and the stale worker's SUCCESS
  is rejected.
- Cancellation demo (§13.4): endpoint `POST /intents/{id}/cancel`.

The demo is **the acceptance test** for the eleven Phase 1 deliverables in §12.1.

---

## 11. Testing Strategy

Rules from `java-rules.mdc` and `@131`/`@132`:

### 11.1 Unit tests (`src/test/java`)

- `{ClassName}Test.java`, JUnit 5 + AssertJ + Mockito, no Spring.
- `@InjectMocks` where feasible; static import `org.mockito.Mockito.*`.
- Whole-object assertions only (`isEqualTo`, `containsExactly*`).
- Names start with `should`.
- `Clock.fixed(...)` injected - never `Instant.now()`.
- Coverage targets: `RetryDelay`, `ExecutionPolicy`, `IntentStatus` transitions
  as data-oriented tests, `DispatchLoop`, `RecoveryLoop`, autoconfig conditions.

### 11.2 Storage adapter contract tests

A shared abstract `StorageAdapterContractTest` in `commitix-core/test-fixtures`
(installed via a `-test-jar`) that concrete adapters extend. Verifies:

1. `persist` inside tx + rollback → row absent.
2. `persist` inside tx + commit → row present with `status='READY'`.
3. Concurrent `claim` on same intent: exactly one returns `true`.
4. `claim` bumps `attempt_count`, `lease_generation`, `version`.
5. Stale worker (`gen=1`) cannot write SUCCESS after new claim (`gen=2`) -
   §7.3 fencing.
6. `recoverExpiredLeases` does **not** increment `attempt_count` or generation.
7. `RUNNING → CANCELLED` increments `lease_generation`; stale SUCCESS rejected.
8. `scheduleRetry` sets `next_attempt_at`; recovery promotes to READY when due.
9. `deadline` past + status READY/RETRYING → EXPIRED; RUNNING past deadline
   untouched.
10. `optimistic concurrency`: two transitions racing on same `version` - one
    wins, one returns `false`.

### 11.3 Integration tests (Testcontainers, `@132`)

- Live in `src/integrationTest/java` (Maven Failsafe, `**/*IT.java`).
- `@SpringBootTest` + `PostgreSQLContainer` (real Postgres 16).
- Interact via REST/API only - no repository beans (rule: repos only for
  `deleteAll()` in the base class).
- Random per-test operation IDs / dedup keys avoid collisions.
- End-to-end demo scenarios cover the eleven Phase 1 deliverables.

### 11.4 Boundary tests

- ArchUnit test in `commitix-core` asserts no imports of `java.sql`,
  `org.springframework`, `com.fasterxml.jackson`, or JDBC adapters.
- ArchUnit in `commitix-jdbc` forbids `org.springframework.*`.
- ArchUnit checks: `domain` never depends on `adapter`, `adapter/in` never
  depends on `adapter/out`.

### 11.5 Concurrency tests

- `JCStress`-style test on the atomic claim: 8 virtual threads race to claim
  100 intents - assert every intent claimed exactly once, and observed
  `attempt_count` equals number of successful claims.

---

## 12. Build, Style, and Quality Gates

- **Maven 3.9+**, wrapper committed (`mvnw`).
- `maven-compiler-plugin`: `--release 21`, `-parameters`, `-Werror` (except
  `-Xlint:processing`), `-Xlint:all,-serial,-processing`.
- **Lombok** provided-scope; `lombok-mapstruct-binding` not needed.
- **JSpecify** `@Nullable` required on every nullable reference (rule from
  `java-rules.mdc`). NullAway plugin runs on `commitix-core` and `commitix-jdbc`.
- **Checkstyle** from `/checkstyle.xml` at project root - 120-char lines,
  fails build on violations.
- **JaCoCo**: instruction coverage ≥ 85% for `commitix-core`, ≥ 75% for
  adapters.
- **OWASP dependency-check** in a profile (`-Psecurity`) so it doesn't block
  fast builds.
- **`mvn verify`** runs unit + integration tests + coverage + checkstyle.
- No `@Value("${…:DEFAULT}")` - all defaults in YAML (rule from java-rules.mdc).
- Multi-line builder pattern; `rows.getFirst()`; `@RequiredArgsConstructor`.

---

## 13. Delivery Sequencing (Milestones)

Each milestone ends with `mvn verify` green and a documented demo scenario.

**M1 - Skeleton (day 1–2)**
- Parent POM, five submodules, checkstyle, JaCoCo, ArchUnit boundary tests
  wired. All modules compile empty; CI runs.

**M2 - Core domain (day 3–4)**
- Records, enums, sealed hierarchies, `Commitix` interface, port SPIs, error
  hierarchy. Unit tests for `RetryDelay`, `ExecutionPolicy`.

**M3 - Storage adapter + schema (day 5–8)**
- Flyway migrations in `commitix-reference-postgres`.
- `JdbcStorageAdapter` with every SQL transition from §7.
- Shared `StorageAdapterContractTest` (10 scenarios above) passing against
  Testcontainers Postgres.

**M4 - Transaction adapter (day 9)**
- `ManualJdbcTransactionAdapter` unit-tested.
- `SpringJdbcTransactionAdapter` (in runtime) with `@Transactional` integration
  test asserting persist-inside-tx and rollback semantics.

**M5 - Runtime (day 10–12)**
- `JvmExecutionAdapter`, `DispatchLoop`, `RecoveryLoop`, autoconfig, YAML
  defaults.
- Concurrency test on atomic claim.

**M6 - Demo & Phase 1 acceptance (day 13–15)**
- Demo app with order flow, inventory reserve handler, warehouse notify handler.
- Integration tests covering the eleven Phase 1 deliverables end-to-end
  (§12.1).
- Fencing (recovery, cancellation) demonstrated as integration tests.

**M7 - Polish (day 16)**
- Javadoc on public API (`@170-java-documentation`).
- Architecture diagrams (`@172-java-diagrams`) embedded in `README.md`.
- ADR for the Maven-vs-Gradle choice, JDBC-adapter shape, and fencing
  invariants (`@171-java-adr`).

---

## 14. Definition of Done - Phase 1 Acceptance

The eleven deliverables from white paper §12.1 must each map to a **passing
integration test** in `commitix-demo`:

| # | Deliverable | Test |
|---|-------------|------|
| 1 | Declare intent inside transaction | `DeclareInsideTxIT#persistsOnlyOnCommit` |
| 2 | Transaction commit → intent persists | same |
| 3 | Transaction rollback → intent not persisted | `DeclareInsideTxIT#rollbackDropsIntent` |
| 4 | Worker claims with lease + generation + version | `ClaimIT#claimIsExclusive` |
| 5 | Worker records SUCCESS/FAILURE with gen + version | `ExecutionResultIT` |
| 6 | Worker crash → lease expires → recovery releases ownership | `RecoveryIT#expiredLeaseReturnsToReady` |
| 7 | New worker claims with generation increment | `RecoveryIT#nextClaimIncrementsGeneration` |
| 8 | Stale worker cannot write SUCCESS (generation mismatch) | `FencingIT#staleSuccessRejected` |
| 9 | Retry with backoff and `next_attempt_at` | `RetryIT#backoffRespected` |
| 10 | Blocked intents observable | `BlockedIT#exposesBlockedStatus` |
| 11 | Cancellation with generation invalidation | `CancellationIT#cancelBumpsGeneration` |

**Explicitly not done in Phase 1:**

- Deduplication UNIQUE constraint (Phase 2).
- Fast path dispatcher (Phase 2).
- Prometheus metrics (Phase 2).
- Additional storage adapters (Redis, Kafka, log-based) (Phase 2).
- Web UI / GraphQL API (Phase 3).

---

## 15. Open Questions to Resolve Before Coding

1. **Payload default serializer.** Jackson is the pragmatic choice for the
   demo, but `commitix-core` must not depend on it. Confirm we ship a
   Jackson-backed serializer in `commitix-runtime` only, with a plain
   `Serializable`-based fallback in `commitix-jdbc` for consumers without
   Spring Boot on the classpath.
2. **Batch claim.** White paper does not mandate per-row claim. For Phase 1,
   proceed with per-row claim inside the dispatcher's read loop (simpler,
   correctness-first); revisit batched claim in Phase 2.
3. **Handler registry lookup key.** Confirm `operation.id + "@" + operation.version`
   is the registry key. Enables per-version routing per §5.2.
4. **`ManualJdbcTransactionAdapter` connection lifecycle.** Confirm the caller
   supplies a `Connection` per transaction via a `ConnectionHolder` interface
   rather than the adapter opening one.

None of these block M1–M2; resolve before M3 kicks off.
