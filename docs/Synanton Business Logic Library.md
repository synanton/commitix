# Synanton Business Logic Library

## Resolutor, Equilix, and Commitix

### Proposal for a Reusable Processing and Scheduling Toolkit within the Synanton Enterprise Knowledge Platform

------

## 1. Executive Summary

**Synanton is an enterprise knowledge platform.**

Its purpose is to represent, process, connect, and operationalize enterprise knowledge: information, relationships, rules, processes, decisions, and actions.

A knowledge platform of this kind requires more than storage and retrieval. It must be able to reason about complex operations, determine how they may be executed, allocate limited resources, and guarantee that important business operations survive failures.

To support these requirements, Synanton will contain a reusable **Business Logic Library**: a set of independent but composable processing and scheduling tools.

The initial set consists of three sibling components:

| Component     | Responsibility                                    | Fundamental question             |
| ------------- | ------------------------------------------------- | -------------------------------- |
| **Resolutor** | Dependency, conflict, and relationship resolution | **What may happen?**             |
| **Equilix**   | Resource-aware scheduling and balancing           | **What can run, and how?**       |
| **Commitix**  | Transactional and durable execution               | **How do we ensure it happens?** |

Together they provide a foundation for reliable business processing inside Synanton.

The three components are intentionally designed as **abstractions rather than technology-specific implementations**.

The application describes its business intent.

The Business Logic Library determines the processing semantics.

Adapters determine how those semantics are implemented using databases, queues, executors, transports, or other infrastructure.

This principle is similar in spirit to the architecture of Apache Camel, where application-level integration concepts are separated from concrete connectivity implementations. Camel provides routes, processors, components, and endpoints as abstractions that allow implementations to change without changing the fundamental integration logic.

Synanton applies the same architectural principle to a different problem domain:

> **Separate business functionality from execution infrastructure.**

------

# 2. Synanton

## Enterprise Knowledge Platform

Synanton should be understood first and foremost as an **enterprise knowledge platform**.

The platform provides a foundation for representing and processing enterprise knowledge and turning that knowledge into useful business operations.

Conceptually:

```text
                         SYNANTON
              Enterprise Knowledge Platform
                         │
       ┌─────────────────┼─────────────────┐
       │                 │                 │
       ▼                 ▼                 ▼
 Knowledge Model    Business Logic     Applications
 & Semantics          Library           & Services
                         │
              ┌──────────┼──────────┐
              │          │          │
         Resolutor     Equilix    Commitix
```

The Business Logic Library is therefore **part of Synanton**, but it is not Synanton itself.

This distinction is important.

Synanton may eventually contain:

- enterprise knowledge models;
- semantic relationships;
- rules and policies;
- data processing;
- search and discovery;
- analytics;
- workflow and business operations;
- integrations;
- applications;
- user-facing services;
- automation.

Resolutor, Equilix, and Commitix are infrastructure for implementing the processing logic required by these capabilities.

------

# 3. Why a Business Logic Library?

Enterprise applications frequently mix business requirements with implementation mechanisms.

A business operation that conceptually means:

> "Execute this operation after the current business transaction succeeds."

may become:

```text
INSERT INTO outbox
SELECT ... FOR UPDATE SKIP LOCKED
publish to Kafka
submit to executor
retry after timeout
```

The business requirement has disappeared inside infrastructure code.

The same happens with scheduling.

A requirement such as:

> "Run these operations without exceeding available memory and database capacity."

can become:

```text
thread pool
semaphore
lock
queue
timeout
retry
```

Again, the implementation replaces the business concept.

Synanton should avoid this.

The Business Logic Library should provide a vocabulary in which the application can express:

```text
resolve
schedule
execute
require
depend
commit
retry
defer
balance
```

without exposing the underlying infrastructure.

------

# 4. Core Architectural Principle

The primary principle of the Synanton Business Logic Library is:

> **Functionality must be separated from implementation.**

The application declares **what it wants to accomplish**.

The library defines **what that means operationally**.

An adapter determines **how it is implemented**.

```text
                     BUSINESS INTENT
                           │
                           ▼
                 Business Logic API
                           │
             ┌─────────────┼─────────────┐
             ▼             ▼             ▼
         Resolutor       Equilix       Commitix
             │             │             │
             └─────────────┼─────────────┘
                           │
                     Adapter Layer
                           │
       ┌────────────┬──────┼──────┬────────────┐
       ▼            ▼      ▼      ▼            ▼
   PostgreSQL      Kafka   HTTP   gRPC      Executor
```

The core library should not depend on any particular infrastructure technology.

------

# 5. Architectural Inspiration: Apache Camel

Apache Camel provides a useful example of this architectural philosophy.

Camel separates integration behavior from connectivity. Routes and processors describe what should happen, while components and endpoints provide implementations for communication with external systems.

The important lesson is not to reproduce Camel's API.

The lesson is architectural:

> **A business-level abstraction should not be coupled to the technology used to implement it.**

Synanton applies this principle to enterprise processing.

For example, the application should express:

```text
execute this intent
after this transaction commits
```

rather than:

```text
write to PostgreSQL outbox
```

Likewise:

```text
execute these operations with no more than
the available memory and database capacity
```

rather than:

```text
submit to this executor with this semaphore
```

The infrastructure becomes an interchangeable implementation detail.

------

# 6. The Three Sibling Components

The Business Logic Library consists initially of three independent components.

## Resolutor

**Processing relationships and logical constraints**

> What may happen?

## Equilix

**Resource-aware scheduling and balancing**

> What can run, and how should it be arranged?

## Commitix

**Transactional and durable execution**

> How do we ensure that committed work is eventually executed?

They are deliberately separated because these are different engineering problems.

------

# 7. Resolutor

## Resolving Processing Relationships

Enterprise operations rarely exist independently.

They can have:

- dependencies;
- prerequisites;
- conflicts;
- ordering requirements;
- mutual exclusion;
- causality;
- priorities;
- compatibility constraints.

Resolutor determines the valid relationship between operations.

For example:

```text
Create Customer
      │
      ├── Create Account
      │       │
      │       └── Activate Account
      │
      └── Send Notification
```

The problem is not yet execution.

The problem is determining the structure:

```text
             REQUESTED OPERATIONS
                       │
                       ▼
                   RESOLUTOR
                       │
                       ▼
              VALID EXECUTION GRAPH
```

This allows the system to detect contradictions before execution.

Instead of:

```text
execute
  ↓
conflict
  ↓
lock
  ↓
wait
  ↓
timeout
  ↓
retry
```

Synanton prefers:

```text
declare
  ↓
resolve
  ↓
plan
  ↓
execute
```

This is a fundamental Synanton philosophy:

> **Move correctness from runtime synchronization toward pre-execution reasoning whenever possible.**

------

# 8. Equilix

## Resource-Aware Scheduling

Once operations have been resolved, the next question is resource allocation.

An operation may require:

- CPU;
- memory;
- database connections;
- network bandwidth;
- worker capacity;
- GPU;
- external API quota;
- tenant capacity;
- other constrained resources.

A conventional executor typically answers:

> How many tasks can run?

Equilix asks:

> **Which tasks can safely run given the resources currently available?**

Conceptually:

```text
              AVAILABLE RESOURCES

       CPU       MEMORY       DATABASE
        │           │             │
        └───────────┼─────────────┘
                    │
                 EQUILIX
                    │
          ┌─────────┼─────────┐
          ▼         ▼         ▼
        Task A    Task B    Task C
```

This makes resource requirements part of the execution model rather than an afterthought.

Equilix therefore provides a foundation for:

- resource balancing;
- admission control;
- backpressure;
- fairness;
- capacity management;
- concurrency control;
- scheduling;
- resource-aware prioritization.

------

# 9. Commitix

## Transactional and Durable Execution

The third problem is fundamentally different.

Enterprise operations often modify state and initiate additional work in the same logical business operation.

For example:

```text
Create Order
    │
    ├── save order
    ├── reserve inventory
    ├── update search index
    └── notify warehouse
```

Without appropriate coordination, the system can produce:

```text
Database = committed
Notification = lost
```

or:

```text
Notification = delivered
Database = rolled back
```

Transactional Outbox is a well-known solution to this class of problem.

Commitix takes the underlying **functional abstraction** further.

The fundamental concept is not "an outbox row."

It is:

> **Durable execution intent.**

The application declares that an operation must happen as part of a successful business transaction.

Commitix guarantees that the intent becomes durable only when the transaction commits and remains available for execution afterwards.

------

# 10. Commitix Is Not Another Outbox Library

Transactional Outbox is an implementation pattern.

Commitix should not be defined as:

> "A better PostgreSQL outbox."

Instead:

> **Commitix provides an implementation-independent abstraction for transactional and durable execution.**

The underlying implementation may use:

- PostgreSQL;
- another relational database;
- a durable queue;
- a distributed log;
- a local journal;
- cloud infrastructure;
- another persistence mechanism.

The application should not need to know.

```text
Business Intent
       │
       ▼
    Commitix
       │
       ▼
  Adapter Contract
       │
 ┌─────┼──────────────┐
 ▼     ▼              ▼
SQL   Queue       Distributed Log
```

This preserves the separation between functionality and implementation.

------

# 11. Commitix Execution Model

The central Commitix model is:

```text
BEGIN
  │
  ├── business state changes
  │
  └── execution intent
  │
COMMIT
  │
  ▼
Durable Intent
  │
  ▼
Execution
```

If the transaction rolls back:

```text
BEGIN
  │
  ├── business state changes
  └── execution intent
  │
ROLLBACK
  │
  ▼
No executable intent
```

This creates an important invariant:

> **Work cannot become independently executable when the business transaction that created it did not commit.**

------

# 12. Fast Path and Durable Path

Commitix should not necessarily depend on continuous polling as its normal execution mechanism.

A preferable architecture is:

```text
                       COMMIT
                         │
                   ┌─────┴─────┐
                   │           │
                   ▼           ▼
               FAST PATH   DURABLE PATH
                   │           │
                   ▼           ▼
               execute      recover
                   │           │
                   └─────┬─────┘
                         ▼
                      SUCCESS
```

The fast path minimizes latency.

The durable path provides recovery.

Therefore, polling or reconciliation becomes a **recovery mechanism**, rather than necessarily the primary execution path.

This distinction can provide both lower latency and stronger failure recovery.

------

# 13. Commitix Core Concepts

Commitix should remain centered around a small semantic model.

### Intent

A declaration that an operation should be performed.

```text
Intent
 ├── identity
 ├── operation
 ├── arguments
 ├── dependencies
 ├── execution policy
 └── durability policy
```

### Execution

An attempt to perform the intent.

```text
Intent
   │
   ├── Attempt 1
   ├── Attempt 2
   ├── Attempt 3
   └── Success
```

### Identity

A stable identity that supports deduplication and idempotency.

### Policy

Defines:

- retry;
- delay;
- deadline;
- priority;
- ordering;
- failure behavior.

### Adapter

Connects the semantic model to a concrete implementation.

------

# 14. Failure Is a First-Class State

Commitix should explicitly model execution failure.

```text
CREATED
   │
   ▼
READY
   │
   ▼
RUNNING
   │
 ┌─┴─────────────┐
 ▼               ▼
SUCCESS        FAILED
                   │
              retry policy
                   │
             ┌─────┴─────┐
             ▼           ▼
           RETRY       BLOCKED
```

This provides an operational model for:

- retryable failures;
- permanent failures;
- blocked work;
- manual intervention;
- recovery.

The objective is not to pretend that distributed execution can eliminate failures.

The objective is to make failures **explicit, durable, observable, and controllable**.

------

# 15. At-Least-Once Execution and Idempotency

Commitix should not claim magical "exactly once" execution.

In distributed systems, a worker may successfully perform an operation and fail before recording that success.

The system can subsequently execute the operation again.

Therefore:

> **Durable intent does not imply exactly-once side effects.**

Commitix should instead provide mechanisms for:

- stable execution identity;
- deduplication;
- retry policy;
- idempotency;
- compensation;
- explicit delivery semantics.

The operation itself must define whether repeated execution is safe.

------

# 16. Ordering as a Semantic Policy

Ordering should also be represented as functionality rather than encoded into a particular database implementation.

For example:

```text
A → B → C
```

may represent a strict dependency.

While:

```text
A ─────┐
B ─────┼── parallel
C ─────┘
```

represents independent work.

Commitix should express the requirement:

```text
ordering(key)
```

rather than exposing:

```text
SELECT ...
FOR UPDATE SKIP LOCKED
```

The latter is implementation.

The former is semantics.

------

# 17. Relationship Between the Three Components

The three siblings should remain independent.

A complete operation can nevertheless pass through all three.

```text
                 BUSINESS INTENT
                       │
                       ▼
                  RESOLUTOR
                       │
                logical plan
                       │
                       ▼
                   EQUILIX
                       │
                resource plan
                       │
                       ▼
                  COMMITIX
                       │
              durable execution
                       │
                       ▼
                    ADAPTER
                       │
                       ▼
                 IMPLEMENTATION
```

Each component answers a different question.

### Resolutor

**Is the operation logically valid?**

### Equilix

**Can the operation be executed with the available resources?**

### Commitix

**Will the operation survive transaction and process failure?**

------

# 18. Separation of Responsibilities

The boundaries should remain strict.

| Concern               | Owner                                |
| --------------------- | ------------------------------------ |
| Dependency resolution | Resolutor                            |
| Conflict resolution   | Resolutor                            |
| Execution graph       | Resolutor                            |
| Resource requirements | Equilix                              |
| Capacity              | Equilix                              |
| Scheduling            | Equilix                              |
| Fairness              | Equilix                              |
| Transactional intent  | Commitix                             |
| Durable execution     | Commitix                             |
| Retry                 | Commitix                             |
| Execution identity    | Commitix                             |
| Ordering              | Commitix / shared execution contract |
| Infrastructure        | Adapters                             |

This prevents the Business Logic Library from becoming a monolithic distributed runtime.

------

# 19. Shared Execution Contract

The components can communicate through a common conceptual model.

```text
ExecutionIntent
 ├── id
 ├── operation
 ├── arguments
 ├── dependencies
 ├── resource requirements
 ├── priority
 ├── deadline
 ├── ordering key
 ├── retry policy
 ├── idempotency policy
 └── durability policy
```

Each component sees only what it needs.

```text
              ExecutionIntent
                    │
       ┌────────────┼────────────┐
       ▼            ▼            ▼
   Resolutor      Equilix      Commitix
       │            │            │
   relationships  resources   durability
```

This keeps the components loosely coupled while allowing them to compose.

------

# 20. Adapter Architecture

Adapters are fundamental to the design.

The Business Logic Library should expose interfaces such as:

```text
TransactionAdapter
StorageAdapter
ExecutionAdapter
QueueAdapter
ClockAdapter
```

Concrete implementations can then provide:

```text
PostgreSQLAdapter
KafkaAdapter
RabbitMQAdapter
HttpAdapter
GrpcAdapter
JvmExecutorAdapter
CloudQueueAdapter
```

The core library should not know which one is being used.

This makes it possible to change infrastructure without changing the business-level processing model.

------

# 21. Why This Matters for Enterprise Systems

Enterprise infrastructure changes over time.

A system may start with:

```text
PostgreSQL + JVM Executor
```

and later evolve to:

```text
PostgreSQL + Kafka + distributed workers
```

or:

```text
Cloud Queue + containerized workers
```

If application logic directly depends on those technologies, migration becomes a business-logic rewrite.

If the application depends on Synanton abstractions, the implementation can change behind the adapter boundary.

Therefore:

> **The abstraction becomes the stable architectural boundary.**

------

# 22. Synanton Business Logic Library Is Not a Message Broker

The library should not be positioned as a replacement for Kafka, RabbitMQ, or other messaging infrastructure.

A queue or log can be an implementation mechanism.

Synanton defines the processing semantics above that mechanism.

The central questions are:

```text
What should happen?
What may happen?
What can run?
When should it run?
What must survive failure?
```

Transport is secondary.

------

# 23. Synanton Business Logic Library Is Not a Workflow Engine

Likewise, the library should not initially attempt to become a complete workflow platform.

A workflow engine may provide:

- long-running business processes;
- human tasks;
- workflow visualization;
- business state machines;
- compensation workflows.

The Business Logic Library should remain a lower-level execution foundation.

Higher-level Synanton services can use these primitives to build such functionality.

------

# 24. Preflight and Execution Planning

One of the most important opportunities for the library is moving decisions before runtime.

A complete Synanton processing flow can eventually become:

```text
Business Intent
      │
      ▼
   PREFLIGHT
      │
      ├── dependencies valid?
      ├── conflicts resolved?
      ├── resources sufficient?
      ├── memory sufficient?
      ├── capacity available?
      ├── deadline achievable?
      └── policy valid?
             │
             ▼
          COMMIT
             │
             ▼
         EXECUTION
```

This reflects a central Synanton engineering philosophy:

> **The earlier an architectural or operational problem can be identified, the cheaper it is to fix.**

------

# 25. Observability

The processing lifecycle should be visible.

A user or operator should be able to determine whether an operation is:

```text
DECLARED
RESOLVED
SCHEDULED
WAITING
ADMITTED
DURABLE
RUNNING
RETRYING
BLOCKED
COMPLETED
FAILED
```

Long-running operations should never degrade into:

```text
Please wait...
```

without meaningful information about what is happening.

Progress, state, dependencies, resource constraints, and failures should be available to the platform.

Observability is therefore part of the processing architecture, not merely a UI concern.

------

# 26. Phase 1 Implementation

The first implementation of the Business Logic Library should prove the abstractions rather than attempt to implement every possible infrastructure integration.

## Resolutor

Initial demonstrator:

- dependency graph;
- conflict detection;
- deterministic resolution;
- execution plan output.

## Equilix

Initial demonstrator:

- resource requirements;
- capacity constraints;
- admission control;
- balanced execution;
- memory-aware scheduling.

## Commitix

Initial demonstrator:

```text
commitix-core
commitix-jdbc
commitix-postgres
commitix-runtime
commitix-demo
```

The demonstration should show:

```text
BEGIN
   │
   ├── create business object
   ├── declare execution intent
   │
COMMIT
   │
   ▼
application failure
   │
   ▼
application restart
   │
   ▼
Commitix recovers intent
   │
   ▼
execution continues
```

This provides a compact demonstration of the fundamental guarantee.

------

# 27. What Phase 1 Should Not Do

The first version should avoid unnecessary infrastructure complexity.

Do not initially attempt to implement:

- every message broker;
- Kubernetes orchestration;
- complex workflow DSLs;
- distributed consensus;
- exactly-once semantics;
- dozens of database dialects;
- framework-specific magic;
- a complete workflow engine.

The first objective is to prove:

> **Can a clean, implementation-independent abstraction describe reliable business execution?**

If the answer is yes, adapters and integrations can be added incrementally.

------

# 28. The Business Logic Library as a Platform Layer

The intended architecture is:

```text
                         SYNANTON
              Enterprise Knowledge Platform
                              │
       ┌──────────────────────┼──────────────────────┐
       │                      │                      │
       ▼                      ▼                      ▼
 Knowledge &             Business Logic         Platform
 Semantics                  Library             Services
                              │
                 ┌────────────┼────────────┐
                 │            │            │
                 ▼            ▼            ▼
             Resolutor      Equilix      Commitix
                 │            │            │
                 └────────────┼────────────┘
                              │
                       Adapter Layer
                              │
                 ┌────────────┼────────────┐
                 ▼            ▼            ▼
             Database       Queue       Runtime
```

The Business Logic Library is therefore a **foundation layer inside Synanton**.

It is reusable across multiple Synanton services.

It can also potentially be used independently of Synanton, provided the abstractions are useful outside the platform.

------

# 29. The Three Dimensions

The three components can be understood as three complementary dimensions of execution.

### Resolutor - Relationships

It determines how operations relate to each other.

### Equilix - Resources

It determines how limited resources can be allocated.

### Commitix - Time and Durability

It determines how execution survives transaction boundaries and failures.

Together:

```text
                 EXECUTION
                    │
       ┌────────────┼────────────┐
       │            │            │
       ▼            ▼            ▼
  RELATIONSHIPS   RESOURCES      TIME
       │            │            │
   Resolutor      Equilix      Commitix
```

This is the conceptual foundation of the sibling set.

------

# 30. The Common Philosophy

The three components share a common philosophy:

> **Reason before execution.**

Instead of relying on runtime mechanisms to discover every problem:

```text
execute
 → collide
 → block
 → timeout
 → retry
 → recover
```

Synanton seeks to perform as much reasoning as possible beforehand:

```text
declare
 → analyze
 → resolve
 → estimate
 → schedule
 → commit
 → execute
```

Runtime remains necessary.

But runtime should execute a plan whenever the problem can be solved before execution.

------

# 31. The Role of Abstraction

The purpose of abstraction is not abstraction for its own sake.

It has a practical goal:

> **Preserve business logic while allowing infrastructure to evolve.**

The same business intent should be executable using different technologies.

For example:

```text
Intent
  │
  ├── PostgreSQL implementation
  ├── Kafka implementation
  ├── HTTP implementation
  ├── gRPC implementation
  └── local implementation
```

The intent remains the same.

Only the adapter changes.

This allows Synanton to remain technologically flexible while keeping business semantics stable.

------

# 32. Final Architectural Statement

Synanton is an **enterprise knowledge platform**.

Its Business Logic Library provides reusable abstractions for processing complex enterprise operations.

The initial three sibling tools are:

### Resolutor

> **Resolve what may happen.**

### Equilix

> **Determine what can run and how resources should be allocated.**

### Commitix

> **Guarantee that committed execution intent survives failure and reaches execution.**

They form a coherent processing foundation:

```text
                      SYNANTON
              Enterprise Knowledge Platform
                         │
                         ▼
                Business Logic Library
                         │
          ┌──────────────┼──────────────┐
          │              │              │
          ▼              ▼              ▼
      RESOLUTOR        EQUILIX       COMMITIX
          │              │              │
      relationships    resources      durability
          │              │              │
          └──────────────┼──────────────┘
                         │
                         ▼
                  Execution Contract
                         │
                         ▼
                    Adapters
                         │
                         ▼
                  Infrastructure
```

The central principle is:

> **Synanton business logic describes functionality. Adapters provide implementation.**

This separation makes the platform easier to evolve, test, reason about, and adapt to different enterprise environments.

The objective is not to build another queue, scheduler, lock manager, or outbox implementation.

The objective is to establish a **clean semantic layer above those mechanisms**.

That layer allows Synanton to reason about enterprise operations before committing them to infrastructure.

And that is the purpose of the three siblings:

> **Resolutor decides.
> Equilix balances.
> Commitix guarantees.**

Together they provide the processing and scheduling foundation on which higher-level Synanton enterprise knowledge capabilities can be built.