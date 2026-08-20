# Commitix - Durable Execution Intent

**Commitix** is implementation of the *Synanton Durable Execution Intent* white paper.
It guarantees **at-least-once execution** of declared operations by persisting intents inside
the application's own database transaction before returning control to the caller.

If the transaction rolls back, the intent disappears. If the process crashes after commit,
the intent survives and is retried by the next worker - with full fencing protection against
stale workers recording spurious results.

---

## Module Layout

```
commitix/                              parent POM (pom.xml, packaging=pom)
  ├── commitix-bom/                    Published BOM - import in consuming projects
  ├── commitix-core/                   Domain model + port SPIs  (zero infrastructure deps)
  ├── commitix-jdbc/                   JDBC storage adapter + manual transaction adapter
  ├── commitix-reference-postgres/     PostgreSQL Flyway migrations (no Java logic)
  ├── commitix-runtime/                Spring Boot autoconfiguration, dispatcher, recovery
  └── commitix-demo/                   Order-processing demo (Phase 1 acceptance tests)
```

**Dependency direction** (enforced by ArchUnit):

```
commitix-core           ←── no runtime deps beyond SLF4J, JSpecify, Lombok(provided)
     ▲
     │
commitix-jdbc           ←── commitix-core  (only java.sql, no Spring)
     ▲
     │
commitix-runtime        ←── commitix-core + commitix-jdbc + Spring Boot
     ▲
     │
commitix-demo           ←── commitix-runtime + commitix-reference-postgres + Spring Web
```

---

## Hexagonal Architecture

```
 ┌─────────────────────────────────────────────────────────────────┐
 │  commitix-core                                                  │
 │                                                                 │
 │   ┌─────────────┐    ┌──────────────────────────────────────┐   │
 │   │  domain/    │    │  port/ (SPI interfaces)              │   │
 │   │  model/     │    │  ┌────────────────────────────────┐  │   │
 │   │  Intent     │◄───┤  │ StorageAdapter                 │  │   │
 │   │  Operation  │    │  │ TransactionAdapter             │  │   │
 │   │  Payload    │    │  │ ExecutionAdapter               │  │   │
 │   │  ...        │    │  │ PayloadSerializer              │  │   │
 │   └─────────────┘    │  │ IntentHandler                  │  │   │
 │                      │  └────────────────────────────────┘  │   │
 │   ┌─────────────┐    └──────────────────────────────────────┘   │
 │   │  Commitix   │◄─── public API (declare)                      │
 │   │  interface  │                                               │
 │   └─────────────┘                                               │
 └──────────┬──────────────────────────────────────────────────────┘
            │ implemented by
            ▼
 ┌──────────────────────────────┐   ┌──────────────────────────────┐
 │  commitix-jdbc               │   │  commitix-runtime            │
 │                              │   │                              │
 │  JdbcStorageAdapter          │   │  SpringJdbcTransactionAdapter│
 │  ManualJdbcTransactionAdapter│   │  JvmExecutionAdapter         │
 │                              │   │  DispatchLoop                │
 │  (no Spring on classpath)    │   │  RecoveryLoop                │
 └──────────────────────────────┘   │  CommitixAutoConfiguration   │
                                    └──────────────────────────────┘
```

---

## Intent Lifecycle

```
                      declare(intent)
                      inside @Transactional
                            │
              commit ───────┴─────── rollback → (discarded)
                            │
                          READY ◄─────────────────────────────────┐
                            │                                     │
                    claim(gen, ver)                               │
                     + attempt++                                  │
                     + gen++                                      │
                            │                                     │
                         RUNNING ──── lease expires ─── recovery─┘
                         │    │       (gen preserved)
              result      │    │
              ┌───────────┤    ├─────────────────────────┐
              │           │    │                          │
           SUCCESS     RETRYING              BLOCKED / FAILED / EXPIRED
           (terminal)  next_attempt_at     (terminal)
              │           │
              │     promote when due ──► READY
              │
           CANCELLED ◄── cancel() from RUNNING (gen++)
           (terminal)     or READY (gen preserved)
```

**Fencing invariants** (see ADR-003):
- `lease_generation` increments **only** on claim (READY→RUNNING) and RUNNING→CANCELLED.
- Recovery sets RUNNING→READY **without** touching `lease_generation`.
- Every mutation guards on `lease_generation` + `version`; stale workers are rejected.

---

## Getting Started

### 1. Add the BOM

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.synanton.commitix</groupId>
            <artifactId>commitix-bom</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 2. Add runtime dependency

```xml
<dependency>
    <groupId>io.synanton.commitix</groupId>
    <artifactId>commitix-runtime</artifactId>
</dependency>
<dependency>
    <groupId>io.synanton.commitix</groupId>
    <artifactId>commitix-reference-postgres</artifactId>
</dependency>
```

### 3. Configure Flyway

```yaml
spring:
  flyway:
    locations: classpath:db/migration,classpath:db/migration/commitix
```

### 4. Declare an intent

```java
@Transactional
public Order createOrder(CreateOrderRequest req) {
    Order order = orderRepository.save(Order.create(req));

    commitix.declare(Intent.builder()
        .id(UUID.randomUUID())
        .operation(new Operation("INVENTORY_RESERVE", "v1", "Reserve inventory"))
        .payload(new InventoryReservePayload(order.id(), req.productId(), req.quantity()))
        .policy(ExecutionPolicy.defaultPolicy())
        .deduplicationKey(order.id() + "-inventory")
        .build());

    return order;
}
```

### 5. Register a handler

```java
@Component
public class InventoryReserveHandler
        implements IntentHandler, CommitixAutoConfiguration.OperationKeyProvider {

    @Override
    public String operationKey() { return "INVENTORY_RESERVE@v1"; }

    @Override
    public ExecutionResult execute(Intent intent, ExecutionContext ctx) {
        InventoryReservePayload p = (InventoryReservePayload) intent.payload();
        // ... idempotent business logic ...
        return new ExecutionResult.Success(null);
    }
}
```

---

## Configuration Reference

```yaml
commitix:
  enabled: true                      # disable entirely if needed
  worker-id-prefix: ${HOSTNAME:local} # node identifier prefix

  dispatcher:
    interval: 200ms                  # polling interval
    batch-size: 32                   # max intents claimed per tick
    lease-duration: 30s              # how long a worker holds a lease

  recovery:
    interval: 1s                     # recovery sweep interval
    batch-size: 128                  # max intents recovered per tick
```

---

## Design Decisions

See `docs/adr/` for the rationale behind key architectural choices:

| ADR | Title |
|-----|-------|
| [ADR-001](docs/adr/ADR-001-maven-over-gradle.md) | Maven over Gradle |
| [ADR-002](docs/adr/ADR-002-jdbc-adapter-shape.md) | JDBC adapter shape |
| [ADR-003](docs/adr/ADR-003-fencing-invariants.md) | Fencing invariants |

---

## Phase 1 Acceptance Criteria

| # | Deliverable | Status |
|---|-------------|--------|
| 1 | Declare intent inside transaction | ✓ `TransactionalCommitix` |
| 2 | Commit → intent persists as READY | ✓ `JdbcStorageAdapter.persist` |
| 3 | Rollback → intent discarded | ✓ (same connection, rolled back) |
| 4 | Claim with lease + generation + version | ✓ `CLAIM` SQL + `DispatchLoop` |
| 5 | Record SUCCESS/FAILURE with gen + version | ✓ fencing SQL guards |
| 6 | Crash → lease expires → recovery releases | ✓ `RECOVER_EXPIRED_LEASES` |
| 7 | Next worker claims with gen++ | ✓ `lease_generation` incremented on claim |
| 8 | Stale worker SUCCESS rejected (gen mismatch) | ✓ `RECORD_SUCCESS` guards on gen |
| 9 | Retry with backoff and `next_attempt_at` | ✓ `SCHEDULE_RETRY` + `PROMOTE_RETRYING` |
| 10 | Blocked intents observable | ✓ `BLOCK` SQL transition |
| 11 | Cancellation with generation invalidation | ✓ `CANCEL_FROM_RUNNING` bumps gen |

**Phase 2+ (not in scope):** deduplication UNIQUE constraint, fast-path dispatcher,
Prometheus metrics, additional storage adapters, Web UI.

## License

Apache 2.0 License – see [LICENSE](https://LICENSE).
