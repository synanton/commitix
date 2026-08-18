# ADR-002 - JDBC Adapter Shape

**Status:** Accepted  
**Date:** 2026-08-09

---

## Context

`commitix-jdbc` must persist intents and transition their state via SQL without pulling in
Spring, JPA, or any ORM. Three shapes were considered for the adapter:

1. **Spring JdbcTemplate** - thin wrapper over JDBC, commonly used in Synanton.
2. **Plain `java.sql` via `DataSource`** - zero additional dependencies.
3. **JOOQ / QueryDSL** - type-safe query builders.

The `commitix-core` cloaking principle (white paper §14.8) requires that the core has no
infrastructure dependencies. By extension, `commitix-jdbc` must also remain Spring-free so
it can be used by non-Spring consumers.

The SQL set is small and fixed (≈ 15 named statements). None involve dynamic query construction.

---

## Decision

Use **plain `java.sql` API** (`DataSource`, `PreparedStatement`, `ResultSet`) with SQL constants
in a single `IntentSql` class. No Spring, no ORM, no query builder.

---

## Rationale

| Criterion | Plain java.sql | Spring JdbcTemplate | JOOQ/QueryDSL |
|-----------|---------------|---------------------|---------------|
| Spring dependency | None | `spring-jdbc` required | None (JOOQ) |
| Verbosity | Higher (try-with-resources) | Lower | Low (compile-time safe) |
| SQL portability | Explicit; easy to audit | Same | Schema-dependent codegen |
| Testability | Mock `DataSource` or real DB | Same | Same |
| Classpath footprint | Minimal | +spring-jdbc | +jooq or querydsl |
| Build complexity | None | None | Codegen step required |

The adapter has a fixed set of ≈ 15 prepared statements. The verbosity of plain JDBC is
acceptable and is contained entirely in `JdbcStorageAdapter` and helper `IntentMapper`.
`try-with-resources` ensures connections and statements are always closed.

**Connection strategy:** Each method opens its own connection from the injected `DataSource`.
For transactional `persist` calls the caller wraps the transaction connection in a
`SingleConnectionDataSource` so the INSERT participates in the business transaction.
This keeps the adapter API simple (no `Connection` parameter threading) and lets the caller
choose the connection-pooling strategy.

---

## Consequences

- `commitix-jdbc` compiles with `java.sql` only; the ArchUnit boundary test enforces this.
- Spring users wire the adapter by passing a `DataSource` from their pool (HikariCP, etc.).
- Non-Spring users use `ManualJdbcTransactionAdapter` to drive the connection lifecycle.
- If a second non-PostgreSQL database needs supporting in Phase 2, the SQL constants can be
  extracted into a strategy or replaced with database-specific subclasses without changing
  the adapter API.
- SQL statements live in `IntentSql` (constants only); logic lives in `JdbcStorageAdapter`.
  This separation makes SQL audits straightforward.
