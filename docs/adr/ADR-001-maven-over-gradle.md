# ADR-001 - Maven over Gradle

**Status:** Accepted  
**Date:** 2026-08-09

---

## Context

Commitix is a multi-module library published as a BOM and a set of JARs to an internal Maven
repository. The build toolchain must handle:

- Multi-module reactor with inter-module test-JAR dependencies (`commitix-core:tests` classifier).
- Checkstyle, JaCoCo, and Enforcer plugins integrated globally from the parent POM.
- Reproducible builds (`project.build.outputTimestamp`).
- Familiar CI/CD pipelines already using Maven in the broader Synanton platform.

Two candidates were evaluated: **Maven 3.9+** and **Gradle 8+ (Kotlin DSL)**.

---

## Decision

Use **Maven 3.9+** with the Maven Wrapper (`mvnw`).

---

## Rationale

| Criterion | Maven | Gradle |
|-----------|-------|--------|
| Platform consistency | Already used across Synanton | Would require a second toolchain |
| Test-JAR sharing | Native `classifier=tests` + `test-jar` goal | Requires custom configuration |
| Plugin ecosystem | Checkstyle, JaCoCo, Enforcer are first-class | Plugins exist but configuration differs |
| BOM publishing | `import` scope in `dependencyManagement` is well-understood | Requires Gradle platform plugin |
| Learning curve | Familiar to all current contributors | Would require Gradle expertise |
| Incremental builds | Slower for large repos | Faster incremental builds |
| Flexibility | Convention-based; verbose for custom logic | More flexible but steeper learning curve |

The incremental-build advantage of Gradle is not material for a small library (< 6 modules,
compile time < 30 s). The consistency and familiarity benefits of Maven outweigh it.

---

## Consequences

- All contributors must have Maven 3.9+ or use the committed wrapper (`./mvnw`).
- Gradle is not introduced in any submodule; any future multi-repo BOM consumers may use either.
- The enforcer plugin (`requireMavenVersion >= 3.9`) documents the minimum version at build time.
- If incremental-build performance becomes a pain point (e.g. after adding many more modules),
  revisit this decision and consider migrating to Gradle with the Develocity build cache.
