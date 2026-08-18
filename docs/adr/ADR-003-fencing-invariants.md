# ADR-003 - Fencing Invariants

**Status:** Accepted  
**Date:** 2026-08-09

---

## Context

Distributed workers claim intents from a shared database and race to execute them. Two
correctness hazards must be prevented:

1. **Stale worker writes** - a worker whose lease expired records a SUCCESS for an intent
   that has since been reclaimed by another worker or cancelled by an operator.
2. **Double-execution visible to Commitix state** - two workers both believe they hold the
   lease and both transition the intent to SUCCESS.

Two mechanisms are common in the literature:

- **Epoch / generation counter** - a monotonically increasing integer incremented each time
  ownership transfers. A write is accepted only when the writer's epoch matches the stored epoch.
- **Optimistic version counter** - a monotonically increasing integer incremented on every
  mutation. A write is accepted only when the writer's version matches the stored version.

---

## Decision

Use **both** counters with different semantics:

| Counter | Column | When incremented | Purpose |
|---------|--------|-----------------|---------|
| `lease_generation` | INTEGER | Only on ownership acquisition (READY→RUNNING, RETRYING→RUNNING) and RUNNING→CANCELLED | Fencing token: identifies the worker epoch |
| `version` | BIGINT | Every state mutation | Optimistic concurrency: prevents lost updates |

**Key invariants:**

1. `lease_generation` is incremented **only** when a new worker acquires ownership
   (claim) or when a cancellation invalidates the current worker (RUNNING→CANCELLED).
2. Recovery (expired lease → READY) does **not** increment `lease_generation`. Recovery
   releases the lease without changing the epoch so the recovered generation cannot be
   confused with a fresh claim.
3. Every mutation that operates on a RUNNING intent guards on **both** `lease_generation`
   and `version`. The query looks like:
   ```sql
   WHERE id = ?
     AND status = 'RUNNING'
     AND lease_generation = ?
     AND lease_until > NOW()
     AND version = ?
   ```
4. CANCEL from RUNNING increments `lease_generation` (same as claim) so that the worker
   already holding the lease cannot record a late SUCCESS - its stored generation no
   longer matches.

---

## Rationale

### Why two counters?

`version` alone prevents concurrent mutations but does not isolate across claim epochs.
If worker A holds `gen=1, ver=5` and crashes, recovery sets `ver=6`. Worker B claims at
`gen=2, ver=7`. Worker A wakes up and tries `WHERE gen=1 AND ver=5` - rejected, even
without a `lease_until` check.

`lease_generation` alone is sufficient for fencing but does not prevent two racing claims
from the same generation colliding. `version` provides the tighter optimistic-concurrency
guard.

### Why not `worker_id` as the fence?

An earlier design guarded result-recording with `AND worker_id = ?`. This requires passing
`workerId` through every `StorageAdapter` method, polluting the port SPI with an
implementation detail. Replacing it with `lease_generation + version` is equivalent
(generation uniquely identifies the worker epoch) and keeps the port clean.

### Why is `lease_until > NOW()` in the guard?

A live fence plus a time fence: the database rejects a write from a worker whose lease has
technically expired, even if the generation matches. This prevents an edge case where the
clock skew allows a very late write from a worker that believes its lease is still valid.

---

## Consequences

- Workers must capture `lease_generation` and `version` from the `claim` return
  (`StoredIntent`) and pass them into every subsequent state mutation.
- Recovery (expired-lease sweep) must not increment `lease_generation`; code review and
  the contract test `recoveryDoesNotIncrementAttemptCount` enforce this.
- CANCEL must increment `lease_generation`; the `CANCEL_FROM_RUNNING` SQL and a dedicated
  contract test `cancelBumpsLeaseGeneration` enforce this.
- Phase 2 deduplication (UNIQUE constraint on `deduplication_key`) does not affect fencing -
  deduplication is a separate concern.
- Application-level idempotency remains the handler's responsibility; Commitix fencing
  protects only Commitix's own state machine, not external side effects (white paper §14.10).
