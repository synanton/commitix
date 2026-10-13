# Commitix developer documentation

Commitix is a JVM library for **durable execution intents**: declare that an operation must be performed inside your database transaction, and the intent survives crashes, retries with fencing, and never executes twice by accident.

This book is built from the repository's `docs/` on every merge and published to [synanton.github.io/commitix](https://synanton.github.io/commitix/). Start with the section that matches your job:

- **Understanding the design** — the [white paper v1.0](Commitix-White-Paper-v1.0.md) (final) and the [Synanton Business Logic Library](Synanton%20Business%20Logic%20Library.md) proposal it belongs to.
- **Implementing and integrating** — the [Phase 1 implementation plan](implementation.md).
- **Decision rationale** — the three [ADRs](adr/ADR-001-maven-over-gradle.md): Maven over Gradle, JDBC adapter shape, fencing invariants.

The repository [README](https://github.com/synanton/commitix#readme) covers the module layout, getting started, and the current acceptance criteria.
