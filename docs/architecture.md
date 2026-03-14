# libspiffy4j — High-Level Architecture

## 1. Purpose

libspiffy4j is the **enterprise, server-side** Java equivalent of [libspiffy](../../libspiffy/) (Dart). While the Dart version targets mobile and desktop clients, libspiffy4j targets backend services, payment processors, and enterprise wallet infrastructure.

It builds on top of [bitcoin4j](../../../bitcoin4j/) which already supplies the low-level Bitcoin primitives (transactions, scripts, HD keys, signing). libspiffy4j adds the **wallet domain layer**: UTXO tracking, event-sourced state, address management, SPV validation, invoice system, and coordinated multi-wallet support — all designed for server-side deployment with PostgreSQL, horizontal scalability, and enterprise operational concerns.

---

## 2. What bitcoin4j Already Provides

bitcoin4j (v1.7.0) is a mature library covering:

| Capability | Key Classes |
|---|---|
| HD key derivation (BIP32/BIP39) | `DeterministicKey`, `HDKeyDerivation`, `MnemonicCode` |
| Transaction building & signing | `TransactionBuilder`, `TransactionSigner`, `SigHash` |
| Script system & interpreter | `Script`, `Interpreter`, `ScriptTemplateRegistry` |
| Address encoding | `Address`, `LegacyAddress` |
| P2PKH, P2PK, P2MS, OP_RETURN | Lock/Unlock builders for each |
| Monetary values | `Coin` |
| Network params | `NetworkParameters`, `NetworkType` |

**We do NOT need to reimplement any of the above.** libspiffy4j depends on bitcoin4j and builds the wallet domain on top of it.

---

## 3. Functionality to Replicate from libspiffy (Dart)

### 3.1 Wallet Management
- Create wallet from mnemonic, WIF, XPRIV, or XPUB (watch-only)
- Multi-wallet isolation
- Wallet configuration and metadata
- Encrypted private key storage (AES-256-GCM)

### 3.2 Address Management
- HD address derivation (BIP44 paths)
- Address labeling and metadata
- Script type identification
- Usage statistics tracking
- Change vs. receiving address separation

### 3.3 UTXO Lifecycle
- States: `PENDING → AVAILABLE → RESERVED → SPENT`
- Reservation with expiration (for in-flight transactions)
- Confirmation tracking
- Coin selection for transaction building
- Batch UTXO queries

### 3.4 Transaction Management
- Build transactions using available UTXOs
- Record outgoing and imported transactions
- Transaction-address link tracking
- Fee calculation

### 3.5 SPV Validation
- BEEF (Background Evaluation Extended Format) parsing
- BUMP (BSV Universal Merkle Path) merkle proof verification
- Block header sync and chain validation
- Merkle root computation with correct byte-order handling

### 3.6 Invoice System
- Invoice creation with expiration
- Multi-output support (P2PKH, multisig, custom scripts)
- Status lifecycle: `PENDING → PAID | EXPIRED | CANCELLED`
- Payment matching and validation

### 3.7 Payment Channels
- Unidirectional channels with nLockTime
- 2-of-2 multisig funding
- Off-chain payment accumulation
- Cooperative and timeout settlement

### 3.8 Network Integration
- ARC service client (transaction broadcast, status, fee policy)
- Block header CDN sync
- Merkle proof retrieval

---

## 4. Why Event Sourcing and CQRS

The Dart version of libspiffy uses event sourcing (ES) and CQRS. Before carrying these patterns into the Java port, we assessed whether they remain justified for an enterprise server-side wallet — or whether simpler alternatives (CRUD + audit log) would suffice.

### 4.1 Assessment: Event Sourcing

**The core argument: this is a financial system managing other people's money.**

An audit trail isn't a nice-to-have — it's a compliance and trust requirement. With event sourcing, the audit trail *is* the system of record. There is no secondary log that can drift from actual state, no trigger that was accidentally disabled, no audit table that records "what changed" but not "why." The events are authoritative.

**Why ES fits the wallet domain specifically:**

| Domain Property | Why ES is a Natural Fit |
|---|---|
| **UTXO lifecycle** | A UTXO is created, confirmed, reserved, spent — never mutated in place. These are discrete state transitions that map 1:1 to events. A CRUD model would update status columns on a row, then need a separate history table to track what changed when — rebuilding a poor man's event store. |
| **Financial auditability** | "Show me exactly what happened to wallet X between Tuesday and Thursday" is answered natively by reading the event stream. With CRUD, you're querying an audit log and hoping it captured sufficient context. |
| **Debugging state issues** | When a balance looks wrong, replay the events and see exactly which command produced which state transition. With CRUD, you read logs and hope they're complete. |
| **Projection rebuilds** | Enterprise deployments live for years. Reporting and query needs *will* change. New compliance views, new analytics, new integration requirements. With ES, replay events through a new projection. With CRUD, write data migration scripts that hope current state contains enough to backfill — it often doesn't. |
| **Multi-wallet isolation** | One event stream per wallet. Streams are independent, can be loaded/replayed/archived independently. Clean boundaries. |

**The alternative considered — CRUD + audit log:**

A `wallet_audit_log` table with before/after JSON, triggered by application code, combined with materialized views for read separation and PostgreSQL `temporal_tables` for point-in-time queries. This gets roughly 80% of the benefit with significantly less complexity.

We rejected this because:
- Audit triggers can be accidentally disabled or bypassed in emergency patches
- The audit log is a *secondary* artifact — it can drift from actual state
- Projection rebuilds from an audit log require the log to have captured every field that matters, which is only knowable in hindsight
- "80% of the benefit" is not acceptable for a financial system where the missing 20% is the scenario that gets audited

### 4.2 Assessment: CQRS

**The core argument: the read and write patterns are genuinely different shapes.**

Writes are command-driven: create wallet, receive UTXO, reserve UTXO, build transaction, mark invoice paid. Each write goes through business rule validation and produces events.

Reads are query-driven: current balance, available UTXOs sorted by value, address transaction history, invoice status lookup, UTXO reservation report. These require denormalized data, different indexes, and different access patterns than the write model.

CQRS isn't adding artificial separation here — it's reflecting a real domain split. The wallet projection maintains denormalized tables (balance, UTXO counts, address statistics) that would be expensive to compute on every read if derived from normalized write-side tables.

### 4.3 Complexity We Must Get Right

ES+CQRS is justified, but the complexity tax is real. These items are not deferrable — they are foundational requirements, not Phase 2 niceties. By using **Pekko persistent actors** (Section 5) as the ES/CQRS implementation vehicle, each of these concerns is addressed by battle-tested framework primitives rather than custom code:

**Snapshots** — A wallet with 100K events cannot replay from event zero on every load. Pekko's `EventSourcedBehavior` provides built-in snapshot support via `snapshotWhen()` and `SnapshotStore`, with automatic recovery from the latest snapshot plus subsequent events.

**Event upcasting** — Event schemas will evolve. `UTXOReceived` v1 will eventually need fields that didn't exist at launch. Pekko's `EventAdapter` chain transforms old event shapes to current shapes during replay — a tested upcasting pipeline we don't need to build.

**Projection lag monitoring** — The read side is eventually consistent with the write side. In a financial system, operators need to know when projections fall behind. Pekko Projection provides built-in telemetry hooks that we bridge to Micrometer for alerting thresholds.

**Idempotent projections** — Projections must handle duplicate event delivery (e.g., after a crash and replay) without corrupting read models. Pekko Projection offers `ExactlyOnceProjection` (transactional) and `AtLeastOnceProjection` with configurable error handling strategies (`RetryAndFail`, `RetryAndSkip`).

**Projection error handling** — When a projection handler fails or encounters a poison event, Pekko Projection provides dead-letter handling, configurable retry strategies, and offset management that survives crashes. These are operational certainties in a financial system, not edge cases.

**Command idempotency** — If a network timeout causes a retry of "reserve UTXO X for invoice Y," the system must not process it twice. Pekko's actor-level deduplication patterns and at-least-once delivery provide infrastructure support, though domain-level idempotency design is still required.

### 4.4 How It Works

```
Command → ActorRef.ask() → EventSourcedBehavior
                                    │
                              commandHandler()  (validate, emit events)
                                    │
                              Effect.persist(events) → Pekko JDBC Journal
                                    │
                              eventHandler()  (update aggregate state)
                                    │
                              Pekko Projection  (consume from journal)
                                    │
                              ProjectionHandler  (update read models in PostgreSQL)
```

- **Write side**: Aggregates are Pekko `EventSourcedBehavior` actors. They process commands, validate business rules, and persist events to the JDBC journal. Aggregate state is reconstructed automatically by replaying events from the latest snapshot. The actor mailbox guarantees single-writer access per aggregate — no explicit locking needed.
- **Read side**: Pekko Projections consume events from the journal and build denormalized read models in PostgreSQL. Offset tracking, error handling, and exactly-once semantics are provided by the framework. Queries always go through read models, never through aggregates.
- **Coordination**: Pekko Cluster Sharding (in local mode) routes commands to the correct aggregate instance and manages lifecycle — passivation, recovery, and supervision.

### 4.5 Decision Summary

| Question | Answer |
|---|---|
| Is ES justified? | **Yes** — financial system, UTXO lifecycle is naturally event-shaped, audit trail is a compliance requirement, projection rebuilds are a certainty |
| Is CQRS justified? | **Yes** — read and write patterns are genuinely different shapes with different optimization needs |
| Could CRUD + audit work? | Partially — but the audit log is a secondary artifact that can drift, and projection rebuilds from it are lossy |
| What's the risk? | Complexity — mitigated by using Pekko's tested primitives for snapshots, event upcasting, projection management, and error handling rather than building them from scratch |

---

## 5. Pekko Persistent Actors — ES/CQRS Implementation

### 5.1 Why Pekko

The Dart version uses actors (Dactor) and a custom event store (Eventador). For Java, we initially considered a custom lightweight ES framework with an Aggregate Router for state isolation. An [architecture review](architecture-review.md) revealed that state isolation is the easy part — the hard parts are projection lifecycle, error handling, snapshot management, command deduplication, and cross-aggregate coordination. Building all of these from scratch amounts to building a bespoke framework.

**Apache Pekko** (Apache 2.0, forked from Akka pre-BSL) provides `EventSourcedBehavior` — a typed, event-sourced persistent actor that addresses these concerns with battle-tested primitives. The full assessment is documented in [pekko-reassessment.md](pekko-reassessment.md).

**Alternatives considered:**

| Framework | Verdict | Why |
|---|---|---|
| **Custom lightweight** | Rejected | Amounts to building a bespoke ES framework — snapshots, projections, error handling, sagas all need custom implementation. The complexity budget exceeds the scope of "lightweight." |
| **Axon Framework** | Rejected | Annotation-driven programming model (`@CommandHandler`, `@EventSourcingHandler`, `@SagaEventHandler`) is prescriptive and leaks into the host application's code. Coupling is too tight for a library that must embed into arbitrary host apps. |
| **Pekko** | **Selected** | Actor system is internal to the library (host never sees it). Provides persistence, projections, supervision, and clustering as composable primitives. Java DSL is idiomatic. Apache 2.0 license. |

### 5.2 What Pekko Provides

#### Core Event Sourcing

| Capability | Pekko Primitive |
|---|---|
| Command → Event → State | `EventSourcedBehavior<Command, Event, State>` |
| Event persistence | `pekko-persistence-jdbc` (PostgreSQL journal) |
| State recovery on startup | Automatic replay from journal |
| Snapshots | `snapshotWhen()`, `SnapshotStore` |
| Event versioning/upcasting | `EventAdapter` chain |

#### Projections / Read Side

| Capability | Pekko Primitive |
|---|---|
| Event consumption | `EventSourcedProvider` + `ProjectionBehavior` |
| Offset tracking | Built-in offset store (JDBC) |
| At-least-once delivery | `AtLeastOnceProjection` |
| Exactly-once delivery | `ExactlyOnceProjection` (transactional) |
| Error handling | `RetryAndFail`, `RetryAndSkip` strategies |
| Parallelism | Grouped/partitioned projections |
| Lag monitoring | Built-in telemetry hooks |

#### Concurrency & Lifecycle

| Capability | Pekko Primitive |
|---|---|
| Single-writer guarantee | Actor mailbox (structural — no locks, no races) |
| Passivation | `EntityContext.setReceiveTimeout()` |
| Supervision | `SupervisorStrategy` (restart, stop, backoff) |
| Graceful shutdown | `CoordinatedShutdown` |
| Stashing during recovery | `Effect.stash()` |

#### Cross-Aggregate Coordination

| Capability | Pekko Primitive |
|---|---|
| Sagas / process managers | Persistent actors reacting to events via projections |
| Reliable inter-aggregate messaging | `pekko-projection` to command routing |

#### Clustering (Future)

| Capability | Pekko Primitive |
|---|---|
| Distributed aggregates | `pekko-cluster-sharding` |
| Shard rebalancing | Built-in |
| Location-transparent routing | `EntityRef` |
| Replicated event sourcing | `ReplicatedEventSourcing` |

Starting with Pekko in local mode means clustering is an incremental configuration change, not an architectural migration.

### 5.3 Aggregate Structure

Aggregates extend Pekko's `EventSourcedBehavior` using the typed Java DSL:

```java
public class WalletAggregate
    extends EventSourcedBehavior<WalletCommand, WalletEvent, WalletState> {

    @Override
    public WalletState emptyState() {
        return WalletState.empty();
    }

    @Override
    public CommandHandler<WalletCommand, WalletEvent, WalletState> commandHandler() {
        return newCommandHandlerBuilder()
            .forAnyState()
            .onCommand(CreateWallet.class, this::onCreateWallet)
            .onCommand(ReceiveUtxo.class, this::onReceiveUtxo)
            .onCommand(ReserveUtxo.class, this::onReserveUtxo)
            .build();
    }

    @Override
    public EventHandler<WalletState, WalletEvent> eventHandler() {
        return newEventHandlerBuilder()
            .forAnyState()
            .onEvent(WalletCreated.class, WalletState::onWalletCreated)
            .onEvent(UtxoReceived.class, WalletState::onUtxoReceived)
            .onEvent(UtxoReserved.class, WalletState::onUtxoReserved)
            .build();
    }
}
```

This is no more complex than a custom `Aggregate<S, E>` base class would be — and developers can find community documentation, examples, and help online.

### 5.4 Boundary Enforcement

The critical design rule: **nothing outside the library holds a reference to the ActorSystem, actor refs, or aggregate instances.**

```
                    ┌───────────────────────────────┐
                    │         LibSpiffy4j            │
 Command ─────────► │                               │ ─────► Result
                    │  ┌──────────────────────────┐ │
                    │  │   Pekko Cluster Sharding  │ │
                    │  │   (local mode)            │ │
                    │  │  ┌─────┐ ┌─────┐ ┌─────┐ │ │
                    │  │  │ W-1 │ │ W-2 │ │ W-N │ │ │  (internal, unreachable)
                    │  │  └─────┘ └─────┘ └─────┘ │ │
                    │  └──────────────────────────┘ │
                    └───────────────────────────────┘

 Query ───────────► ReadModelStorage ─────────► ReadModel (projection data)
```

Callers interact with two surfaces:
- **Commands** go through `LibSpiffy4j`'s typed command API and return results
- **Queries** go through `ReadModelStorage` and return projection data

The host never imports Pekko types, never configures the ActorSystem, and never holds an `ActorRef`. Pekko is an internal implementation detail — encapsulated behind the same builder API the host would see regardless of the underlying concurrency model.

### 5.5 Comparison: Dart Actor Model → Pekko

| Dart (Dactor + Eventador) | Java (Pekko) |
|---|---|
| Dactor actor with message handler | `EventSourcedBehavior` with `commandHandler()` |
| Eventador event store | `pekko-persistence-jdbc` (PostgreSQL journal) |
| Actor mailbox (single-writer) | Actor mailbox (single-writer) |
| Custom event replay | Automatic recovery from journal + snapshots |
| Custom projections | `pekko-projection` with offset tracking, error handling |
| Dart isolates | Pekko dispatcher threads (actor processing) + virtual threads (non-aggregate work) |

The conceptual model is the same — actors processing commands, emitting events, maintaining isolated state. Pekko provides the same guarantees as Dactor but with persistence, projections, supervision, and clustering built in.

### 5.6 Thread Pool Considerations

Pekko uses its own dispatcher threads for actor processing. On virtual-thread-friendly hosts (Quarkus, Micronaut), this means the library's internal threading model for aggregate processing differs from the host's.

This is manageable:
- Pekko's dispatchers are configured with a small fork-join pool since persistent actors spend most of their time idle (waiting for commands, not holding threads)
- Non-aggregate work (ARC HTTP client, SPV validation, batch operations) uses virtual threads independently
- The thread pool is internal — the host doesn't configure or interact with it

---

## 6. Technology Choices

### 6.1 Build System: **Gradle (Kotlin DSL)**
- Consistent with bitcoin4j
- `bitcoin4j` referenced as a project dependency or local maven artifact

### 6.2 Java Version: **21+**
- Virtual threads (Project Loom) for lightweight concurrency in non-aggregate code
- Pattern matching and sealed classes for event/command types
- Record classes for immutable value objects

### 6.3 Event Sourcing & CQRS: **Apache Pekko**

**Rationale**: An [architecture review](architecture-review.md) of the original custom-lightweight ES design revealed that six foundational concerns — projection lifecycle, error handling, concurrency races, snapshot management, command deduplication, and cross-aggregate coordination — would require building a bespoke framework. Pekko provides tested implementations of all six as composable primitives (see Section 5 for full assessment).

Axon Framework was also evaluated but rejected: its annotation-driven programming model (`@CommandHandler`, `@SagaEventHandler`) is prescriptive and leaks into the host application's code, making it too tightly coupled for a library.

**Pekko modules used:**

| Module | Purpose |
|---|---|
| `pekko-persistence-typed` | Event-sourced persistent actors (`EventSourcedBehavior`) |
| `pekko-persistence-jdbc` | PostgreSQL-backed journal and snapshot store |
| `pekko-projection-eventsourced` | Read-side projections consuming from journal |
| `pekko-projection-jdbc` | JDBC-backed projection offset store |
| `pekko-serialization-jackson` | Jackson CBOR integration for event serialization |
| `pekko-cluster-sharding-typed` | Aggregate routing, passivation, lifecycle (local mode initially) |

**The ActorSystem is internal to the library.** The host application never imports Pekko types or configures the actor system. `LibSpiffy4j.builder()` creates and manages it; `close()` shuts it down. See Section 7.

### 6.4 Concurrency: **Pekko Dispatchers + Virtual Threads**

Aggregate processing uses Pekko's dispatcher threads — the actor mailbox provides structural single-writer guarantees per aggregate with no explicit locking. The dispatcher is configured with a small fork-join pool since persistent actors are lightweight and mostly idle.

Non-aggregate work uses Java 21+ virtual threads:
- `java.net.http.HttpClient` for ARC service calls
- `StructuredTaskScope` for fan-out operations (e.g., batch UTXO checks, SPV validation)
- Any host-facing async operations

### 6.5 Storage: **PostgreSQL**

PostgreSQL is the sole storage backend. No SQLite, no embedded options — this is a server-side library.

| Layer | Technology |
|---|---|
| Event Journal | **PostgreSQL** — Pekko JDBC journal plugin with sequence-number-based optimistic concurrency |
| Snapshots | **PostgreSQL** — Pekko JDBC snapshot store |
| Projection Offsets | **PostgreSQL** — Pekko Projection JDBC offset store |
| Read Models | **PostgreSQL** — denormalized projection tables, indexed for query patterns |
| Secure Storage | **PostgreSQL + AES-256-GCM** — encrypted xpriv/key material at rest |

**Connection pooling**: The library accepts a `javax.sql.DataSource` from the host application (see Section 7). It does not create or manage its own connection pool. The host uses whatever pool fits its framework (Agroal, HikariCP, etc.). A thin adapter bridges the host's DataSource into Pekko's Slick-based connection provider.

**Schema migrations**: The library ships SQL migration files as classpath resources (see Section 7.3). These include Pekko's journal, snapshot, and offset store schemas alongside the application's read model schemas. The host application runs them through its own migration tool (Flyway, Liquibase, or manual DDL). The library has no Flyway dependency.

**Why not JOOQ / JPA / Hibernate?** Pekko's persistence plugins handle event store access. For read model projections, the query patterns are known at design time and hand-written SQL is more transparent than ORM-generated queries. This keeps the dependency footprint small and the SQL auditable.

### 6.6 Serialization: **Jackson CBOR**

Events are serialized using CBOR (Concise Binary Object Representation) via `jackson-dataformat-cbor`, integrated into Pekko's serialization system through `pekko-serialization-jackson`. This provides the same `ObjectMapper` API as Jackson JSON but produces compact binary output.

**Why CBOR over JSON:**

| Concern | CBOR | JSON |
|---|---|---|
| **Binary data** | Native byte arrays — txids, script bytes, merkle proofs stored as-is | Every binary blob requires hex/base64 encoding round-trip |
| **Size** | 30-40% smaller events — significant at enterprise scale (thousands of wallets, millions of events) | Larger payloads, more disk I/O, slower replays |
| **Dart parity** | Same encoding as libspiffy — enables cross-platform event compatibility and shared test vectors | Translation layer needed for any cross-platform scenarios |
| **Performance** | Faster serialization/deserialization for structured data with binary fields | Slower due to text encoding overhead |
| **Human readability** | Binary — requires tooling to inspect | Readable in psql / DB tools |
| **JSONB querying** | Not possible (stored in `BYTEA` column) | Can index into event payloads |

The human-readability tradeoff is acceptable because:
- In practice, raw events are inspected through admin tooling (CLI, dashboards) that deserializes and displays them, not by reading `BYTEA` columns in psql
- Querying into raw event payloads is an anti-pattern in event sourcing — if you need to query by address or txid, that belongs in a projection table with proper indexes, not ad-hoc queries into the event store
- A CBOR event inspection CLI tool is included in the implementation plan (Phase 5) to support debugging during development and operations

**Event store column type**: `BYTEA` in PostgreSQL (not `JSONB`). Events are opaque binary blobs to the database; all structure lives in the application layer.

The library accepts an `ObjectMapper` from the host (see Section 7). If not provided, it creates a default instance configured with the CBOR factory and modules needed for event serialization. The host can register custom serializers or modules on the shared mapper.

### 6.7 HTTP Client: **java.net.http.HttpClient** (JDK built-in)

For ARC service integration. No external dependency needed with Java 21+. For enterprise resilience (retry, circuit breaking), we can add Resilience4j decorators around the client without replacing it.

### 6.8 Cryptographic Utilities

- **Bouncy Castle** (already a bitcoin4j dependency) for AES-256-GCM encryption of private keys
- bitcoin4j's own crypto for all Bitcoin operations

### 6.9 Logging & Observability

- **SLF4J** API (already a bitcoin4j dependency) — host application chooses the backend (Logback, Log4j2)
- **Micrometer** metrics bridged from Pekko's built-in telemetry — projection lag, aggregate event counts, journal throughput, snapshot frequency. The library accepts a `MeterRegistry` from the host (see Section 7). If not provided, metrics are silently disabled via a no-op registry. Micrometer is an **optional** compile dependency.
- Structured logging with correlation IDs for multi-wallet request tracing

### 6.10 Testing

- **JUnit 5** for unit and integration tests
- **AssertJ** for fluent assertions
- **`pekko-persistence-testkit`** — in-memory journal with the same semantics as the JDBC journal, enabling fast unit tests that are representative of production behavior
- **Testcontainers** for PostgreSQL integration tests (not optional — this is the real storage backend)

---

## 7. Framework Integration Design

libspiffy4j is a **library**, not a standalone application. It must integrate cleanly into host applications built with Quarkus, Micronaut, Spring Boot, or plain Java. This section defines the integration contract.

### 7.1 Core Principle: Accept Infrastructure, Don't Create It

A library that creates and manages its own infrastructure (connection pools, migration runners, metrics registries, thread executors) will fight the host framework. The host already owns these resources — duplicating them causes operational blind spots, configuration fragmentation, and resource waste.

**libspiffy4j defines what it needs (interfaces) and accepts what the host provides (implementations).**

The entry point is a builder that accepts host-managed resources:

```java
var spiffy = LibSpiffy4j.builder()
    .dataSource(appDataSource)          // required — host's connection pool
    .objectMapper(appObjectMapper)      // optional — default CBOR mapper if absent
    .meterRegistry(appMeterRegistry)    // optional — no-op if absent
    .build();
```

The builder internally creates and configures the Pekko `ActorSystem`, JDBC journal, projection infrastructure, and cluster sharding (local mode). The host never sees or interacts with these internals.

### 7.2 What the Host Provides vs. What the Library Owns

| Resource | Who Provides | Why |
|---|---|---|
| **DataSource** (connection pool) | Host application | The host already manages a pool (Agroal in Quarkus, HikariCP in Micronaut, etc.). A second pool means doubled connections, split monitoring, and connection starvation under load. |
| **Schema migrations** | Host application | The host runs Flyway/Liquibase on startup through its own extension. libspiffy4j ships SQL migration files at a known classpath location; the host includes them in its migration set. |
| **MeterRegistry** (metrics) | Host application (optional) | The host's Micrometer registry is wired to its export pipeline (Prometheus, Datadog, etc.). A separate registry means invisible metrics. If not provided, the library uses a no-op registry. |
| **ObjectMapper** (CBOR) | Host application (optional) | The host may have custom serializers or module registrations. If not provided, the library creates a default CBOR-configured instance. |
| **ActorSystem** (Pekko runtime) | Library (internal) | Created and managed by the builder. The host never imports Pekko types. Encapsulated behind the command/query API. |
| **Aggregates, events, projections** | Library | Core domain logic — the library's reason for existing. |
| **Event journal, snapshot store** | Library (via Pekko JDBC plugins using host's DataSource) | Pekko persistence plugins execute against the host's connection pool. |
| **Read model storage** | Library (using host's DataSource) | SQL queries and schema are the library's concern, executed against the host's connection pool. |
| **Lifecycle (shutdown)** | Shared | Library exposes `close()`. Host calls it from its shutdown hook. Internally triggers Pekko's `CoordinatedShutdown`. |

### 7.3 Migration File Shipping

libspiffy4j ships its PostgreSQL migration scripts as classpath resources:

```
src/main/resources/
└── db/
    └── libspiffy4j/
        ├── V001__create_journal.sql              # Pekko JDBC journal schema
        ├── V002__create_snapshot.sql             # Pekko JDBC snapshot schema
        ├── V003__create_projection_offset.sql    # Pekko projection offset store
        ├── V004__create_wallet_read_models.sql
        ├── V005__create_invoice_read_models.sql
        └── V006__create_secure_storage.sql
```

The host application configures its migration tool to include this location:

**Quarkus** (`application.properties`):
```properties
quarkus.flyway.locations=db/migration,db/libspiffy4j
```

**Micronaut** (`application.yml`):
```yaml
flyway:
  datasources:
    default:
      locations:
        - classpath:db/migration
        - classpath:db/libspiffy4j
```

**Plain Java** (manual Flyway):
```java
Flyway.configure()
    .dataSource(dataSource)
    .locations("db/migration", "db/libspiffy4j")
    .load()
    .migrate();
```

This approach means:
- No Flyway dependency in the library itself
- The host's existing migration pipeline handles ordering and execution
- DBAs can review the SQL files before deployment (including Pekko's journal schema)
- The host can override or customize migrations if needed

### 7.4 Framework Integration Examples

**Quarkus:**
```java
@ApplicationScoped
public class SpiffyProducer {

    @Inject
    DataSource dataSource;

    @Inject
    MeterRegistry meterRegistry;

    @Produces @ApplicationScoped
    public LibSpiffy4j spiffy() {
        return LibSpiffy4j.builder()
            .dataSource(dataSource)
            .meterRegistry(meterRegistry)
            .build();
    }

    void onShutdown(@Observes ShutdownEvent ev, LibSpiffy4j spiffy) {
        spiffy.close();
    }
}
```

**Micronaut:**
```java
@Factory
public class SpiffyFactory {

    @Singleton
    public LibSpiffy4j spiffy(DataSource dataSource, MeterRegistry meterRegistry) {
        return LibSpiffy4j.builder()
            .dataSource(dataSource)
            .meterRegistry(meterRegistry)
            .build();
    }

    @PreDestroy
    void close(LibSpiffy4j spiffy) {
        spiffy.close();
    }
}
```

**Plain Java (no framework):**
```java
// Host creates and owns the DataSource
var ds = new HikariDataSource();
ds.setJdbcUrl("jdbc:postgresql://localhost/wallets");

// Host runs migrations (includes Pekko journal + app read model schemas)
Flyway.configure().dataSource(ds)
    .locations("db/libspiffy4j").load().migrate();

// Library accepts host's DataSource — creates ActorSystem internally
var spiffy = LibSpiffy4j.builder()
    .dataSource(ds)
    .build();

// Commands and queries — host never sees Pekko types
spiffy.wallets().send(new CreateWallet(...));
var balance = spiffy.walletQueries().getBalance(walletId);

spiffy.close();
ds.close();
```

In the plain Java case, the developer does more manual wiring — but the library's behavior is identical. It never creates its own pool, never runs its own migrations, never exposes Pekko internals.

### 7.5 Health and Readiness

The library exposes health information through a simple interface that any framework can adapt:

```java
public interface SpiffyHealthIndicator {
    boolean isEventStoreReachable();
    long projectionLagEvents();       // events behind the write side
    Duration projectionLagDuration(); // time since last projected event
    int activeAggregates();           // entities currently in memory
}
```

The host wraps this in its framework's health check API (MicroProfile Health for Quarkus, `HealthIndicator` for Micronaut, Spring Actuator for Spring Boot). The library does not depend on any health check framework. Internally, these metrics are derived from Pekko's telemetry and cluster sharding statistics.

### 7.6 Design Implications

This integration model constrains our implementation:

- **No static singletons.** Multiple `LibSpiffy4j` instances must coexist in the same JVM (e.g., integration tests, multi-tenant deployments). All state is instance-scoped, including the ActorSystem.
- **No classpath scanning or reflection-based discovery.** Quarkus and Micronaut use build-time compilation; runtime classpath scanning breaks their optimization model.
- **No `ServiceLoader` or SPI for core wiring.** Explicit builder configuration is more predictable and debuggable.
- **No `Thread.currentThread().getContextClassLoader()` assumptions.** Modular deployments and Quarkus dev mode use non-standard classloader hierarchies.
- **All SQL in resource files, not generated at runtime.** Allows static analysis, review, and framework-specific migration tooling to inspect them.
- **Pekko configuration via `reference.conf`.** The library ships sensible defaults; host-level overrides are possible via standard Pekko/Typesafe Config mechanisms but not required.

---

## 8. Package Structure

```
org.twostack.libspiffy4j
├── LibSpiffy4j.java            # Entry point — builder creates ActorSystem internally
├── LibSpiffy4jBuilder.java     # Builder for framework-agnostic configuration
├── SpiffyHealthIndicator.java  # Health/readiness interface for host adaptation
│
├── aggregate/                  # Event-sourced aggregates (Pekko EventSourcedBehavior)
│   ├── wallet/                 #   WalletAggregate, commands, events, state
│   ├── invoice/                #   InvoiceAggregate, commands, events, state
│   └── channel/                #   PaymentChannelAggregate, commands, events, state
│
├── model/                      # Domain value objects (records)
│   ├── BitcoinUtxo.java
│   ├── AddressMetadata.java
│   ├── WalletConfig.java
│   ├── Invoice.java
│   ├── PaymentChannel.java
│   └── TransactionRecord.java
│
├── projection/                 # CQRS read-side projections (Pekko Projection handlers)
│   ├── WalletProjection.java
│   ├── InvoiceProjection.java
│   └── ChannelProjection.java
│
├── service/                    # Business services
│   ├── CryptoService.java      #   Key derivation, encryption
│   ├── TransactionBuildService.java  # UTXO selection + bitcoin4j builder
│   ├── SpvService.java         #   BEEF/BUMP validation
│   └── ArcService.java         #   ARC HTTP client
│
├── spv/                        # SPV validation internals
│   ├── Beef.java               #   BEEF parser
│   ├── Bump.java               #   BUMP merkle proof
│   └── BlockHeaderChain.java   #   Header sync and validation
│
├── storage/                    # Read model storage (projections write here)
│   ├── ReadModelStorage.java   #   Read model query interface
│   ├── SecureStorage.java      #   Encrypted key storage interface
│   ├── postgres/               #   PostgreSQL implementations
│   └── memory/                 #   In-memory implementations (testing)
│
├── serialization/              # Pekko serialization configuration
│   └── CborEventSerializer.java  # Jackson CBOR adapter for Pekko serialization
│
├── config/                     # Internal Pekko configuration
│   └── ActorSystemFactory.java #   Creates and configures the ActorSystem
│
└── util/                       # Utilities
    ├── HexUtils.java
    └── ByteOrderUtils.java     #   Display vs internal byte format

src/main/resources/
├── reference.conf              # Pekko configuration defaults (journal, serialization, dispatchers)
└── db/
    └── libspiffy4j/            # SQL migration files (host runs via Flyway/Liquibase)
        ├── V001__create_journal.sql
        ├── V002__create_snapshot.sql
        ├── V003__create_projection_offset.sql
        ├── V004__create_wallet_read_models.sql
        ├── V005__create_invoice_read_models.sql
        └── V006__create_secure_storage.sql
```

---

## 9. Dependency Graph

```
┌──────────────────────────────────────────────────────────┐
│ Host Application (Quarkus, Micronaut, Spring, plain)     │
│                                                          │
│  Provides: DataSource, MeterRegistry*, ObjectMapper*     │
│                                    (* = optional)        │
└──────────────────────┬───────────────────────────────────┘
                       │
         ┌─────────────▼─────────────┐
         │       LibSpiffy4j         │
         │  builder accepts host     │
         │  infrastructure, owns     │
         │  domain logic + actor     │
         │  system (internal)        │
         └─────────────┬─────────────┘
                       │
       ┌───────────────┼───────────────┐
       ▼               ▼               ▼
 ┌────────────┐ ┌─────────────┐ ┌──────────────┐
 │ bitcoin4j  │ │ Pekko       │ │ Jackson CBOR │
 │ tx/script/ │ │ persistence │ │ serialization│
 │ keys/addr  │ │ projection  │ │              │
 └─────┬──────┘ │ (cluster*)  │ └──────────────┘
       ▼        └──────┬──────┘
 ┌──────────┐          ▼
 │ Bouncy   │   ┌──────────────┐
 │ Castle   │   │ Scala stdlib │
 └──────────┘   │ (transitive) │
                └──────────────┘

  (* cluster modules added later, only when needed)

  Host provides at runtime:
  ┌────────────┐ ┌──────────────┐ ┌────────────┐
  │ DataSource │ │ MeterRegistry│ │  Flyway /   │
  │ (Agroal,   │ │ (optional)   │ │ Liquibase  │
  │  HikariCP, │ │              │ │ (host-run) │
  │  etc.)     │ │              │ │            │
  └────────────┘ └──────────────┘ └────────────┘
```

### External Dependencies (new, beyond bitcoin4j's transitive deps)

| Dependency | Purpose | Scope | Notes |
|---|---|---|---|
| `org.apache.pekko:pekko-actor-typed_2.13` | Actor system runtime | compile | Required |
| `org.apache.pekko:pekko-persistence-typed_2.13` | Event-sourced persistent actors | compile | Required |
| `org.apache.pekko:pekko-persistence-jdbc_2.13` | PostgreSQL journal + snapshot store | compile | Required; uses host DataSource via Slick adapter |
| `org.apache.pekko:pekko-projection-eventsourced_2.13` | Read-side projections from journal | compile | Required |
| `org.apache.pekko:pekko-projection-jdbc_2.13` | JDBC-backed projection offset store | compile | Required |
| `org.apache.pekko:pekko-cluster-sharding-typed_2.13` | Aggregate routing, passivation, lifecycle | compile | Required; local mode initially |
| `org.apache.pekko:pekko-serialization-jackson_2.13` | Jackson serialization integration | compile | Required; configured for CBOR format |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-cbor` | CBOR binary serialization | compile | Required |
| `io.micrometer:micrometer-core` | Metrics / observability | compile (optional) | No-op fallback if absent at runtime |
| `org.junit.jupiter:junit-jupiter` | Testing | test | |
| `org.assertj:assertj-core` | Fluent assertions | test | |
| `org.apache.pekko:pekko-persistence-testkit_2.13` | In-memory journal for fast unit tests | test | Same semantics as JDBC journal |
| `org.testcontainers:postgresql` | PostgreSQL integration tests | test | |

**Not needed (provided by the host or replaced by Pekko):**

| Dependency | Why Not Needed |
|---|---|
| `org.postgresql:postgresql` | Host provides the DataSource; JDBC driver is the host's concern |
| `com.zaxxer:HikariCP` | Host manages its own connection pool |
| `org.flywaydb:flyway-core` | Host runs migrations; library ships SQL files only |
| `com.github.ben-manes.caffeine:caffeine` | Pekko manages aggregate lifecycle (passivation, eviction) natively |

---

## 10. Implementation Priority

### Phase 1 — Foundation
1. Project scaffolding (Gradle, package structure, bitcoin4j + Pekko dependencies)
2. `ActorSystemFactory` with JDBC journal configured against host DataSource
3. Jackson CBOR serialization adapter for Pekko (`CborEventSerializer`)
4. Core domain models (`BitcoinUtxo`, `AddressMetadata`, `WalletConfig`)
5. `LibSpiffy4j` builder accepting DataSource, creating ActorSystem internally
6. SQL migration files for Pekko journal, snapshot, and offset store schemas

### Phase 2 — Wallet Core
7. `WalletAggregate` (extends `EventSourcedBehavior`) with wallet creation commands
8. Address derivation (leveraging bitcoin4j HD keys)
9. UTXO lifecycle (receive, reserve, spend, release)
10. `WalletProjection` via `pekko-projection` with JDBC offset store
11. `CryptoService` (key derivation, encryption) + `SecureStorage` with explicit key management

### Phase 3 — Transactions & SPV
12. `TransactionBuildService` (coin selection + bitcoin4j `TransactionBuilder`)
13. BEEF parser and BUMP merkle proof validator
14. `SpvService` for transaction verification
15. Block header sync and chain validation

### Phase 4 — Invoices, Channels & Coordination
16. `InvoiceAggregate` with lifecycle
17. `PaymentChannelAggregate` with UTXO coordination design
18. Cross-aggregate coordination patterns (process managers via persistent actors)
19. Associated projections

### Phase 5 — Operations & Observability
20. Pekko telemetry → Micrometer bridge for projection lag, aggregate metrics
21. Health indicator implementation
22. Snapshot tuning (configure `snapshotWhen()` thresholds per aggregate)
23. CBOR event inspection utility (CLI tool for debugging)

### Phase 6 — Network Integration
24. `ArcService` HTTP client
25. Block header CDN sync
26. Transaction broadcast and status tracking

**Key structural change from earlier versions of this plan**: PostgreSQL persistence is no longer a separate late phase. Pekko's JDBC journal means persistence is baked in from Phase 1. Aggregates are tested against the same journal backend from day one — `pekko-persistence-testkit` for fast unit tests, Testcontainers for integration tests.

---

## 11. Key Design Decisions

| Decision | Choice | Alternative Considered | Rationale |
|---|---|---|---|
| Event sourcing + CQRS | Yes | CRUD + audit log | Financial system, UTXO lifecycle is naturally event-shaped, audit trail is a compliance requirement, projection rebuilds are a certainty (see Section 4) |
| ES/CQRS framework | Apache Pekko persistent actors | Custom lightweight; Axon Framework | Custom approach amounts to building a bespoke framework for 6+ foundational concerns; Axon's programming model is too prescriptive for a library. Pekko provides tested primitives with ActorSystem encapsulated internally (see Section 5) |
| Concurrency / state isolation | Pekko actor mailbox | AggregateRouter + ReentrantLock | Actor mailbox provides structural single-writer guarantee — no lock/cache races, no custom lifecycle code. Pekko dispatchers handle threading internally |
| Projections | Pekko Projection (JDBC) | Custom ProjectionManager | Built-in offset tracking, error handling (RetryAndFail/RetryAndSkip), exactly-once delivery, and parallelism. The custom approach left these undesigned |
| Cross-aggregate coordination | Persistent actors as process managers | Not addressed in original | Pekko provides saga/process manager patterns via persistent actors reacting to events through projections |
| Clustering path | Start with Pekko local mode | "Build custom first, migrate to Pekko later" | Starting with Pekko means clustering is an incremental config change, not a migration. Avoids building throwaway infrastructure |
| Integration model | Accept host infrastructure via builder | Library creates its own resources | Avoids two-pool problem, config fragmentation, orphaned metrics; integrates cleanly with any framework (see Section 7) |
| Database | PostgreSQL only | PostgreSQL + SQLite | Server-side only — one backend to test, optimize, and operate |
| Connection pool | Host-provided DataSource | Library-owned HikariCP | Host already manages a pool; a second pool wastes connections and splits monitoring |
| Migrations | Ship SQL files; host runs them | Library runs Flyway internally | No Flyway dependency; works with host's migration tool; DBAs can review SQL (including Pekko journal schema) |
| Serialization | Jackson CBOR (via Pekko serialization) | Jackson JSON, Protobuf | Dart parity (same encoding), native binary data (txids, scripts, merkle proofs), 30-40% smaller events, faster ser/deser; human-readable debugging solved by CLI tooling (Phase 5) |
| ORM | None (JDBC for read models) | Hibernate, JOOQ | Pekko handles journal access; read model projections use hand-written SQL for transparency |
| Java version | 21+ | 17, 11 | Virtual threads (non-aggregate work), records, sealed classes |
| HTTP client | JDK HttpClient | OkHttp, Apache HC | Built-in, adequate for ARC; add Resilience4j for retries |
| Observability | Pekko telemetry → Micrometer bridge + SLF4J | Custom metrics | Built-in telemetry from Pekko persistence and projections; bridged to host's MeterRegistry |

---

## 12. Mapping: libspiffy (Dart) → libspiffy4j (Java)

| Dart Component | Java Equivalent |
|---|---|
| Eventador (event store) | Pekko `pekko-persistence-jdbc` (PostgreSQL journal) |
| Dactor (actors) | Pekko `EventSourcedBehavior` + Cluster Sharding (local mode) |
| Dactor mailbox (single-writer) | Pekko actor mailbox (single-writer) |
| Custom event replay | Pekko automatic recovery from journal + snapshots |
| Custom projections | Pekko Projection with offset tracking, error handling |
| Isar (NoSQL) | PostgreSQL via host-provided `DataSource` (Section 7) |
| dartsv | bitcoin4j |
| `dart:io` HttpClient | `java.net.http.HttpClient` |
| CBOR serialization | Jackson CBOR via `pekko-serialization-jackson` — same encoding |
| Isar schemas (codegen) | SQL migration files shipped as classpath resources (Section 7.3) |
| `pubspec.yaml` | `build.gradle.kts` |
| Dart isolates | Pekko dispatcher threads (aggregates) + virtual threads (non-aggregate work) |
| `synchronized` package | Actor mailbox (structural, no explicit locks) |
| — (no equivalent) | Pekko Projection error handling (RetryAndFail, dead letters) |
| — (no equivalent) | Pekko supervision strategies (restart, backoff) |
| — (no equivalent) | Micrometer metrics (via Pekko telemetry bridge) |
| — (no equivalent) | Structured logging with correlation IDs |
| — (no equivalent) | `LibSpiffy4j.builder()` — framework-agnostic entry point (Section 7) |

---

## 13. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| **Scala stdlib on classpath** | Transitive only. No Scala code in libspiffy4j. Host apps don't interact with Scala types. |
| **Pekko thread pool vs host virtual threads** | Configure Pekko dispatchers with small fork-join pool. Non-aggregate work (ARC, SPV) uses virtual threads independently. Thread pool is internal to the library. |
| **Pekko version conflicts with host** | Host apps using Pekko directly could hit version conflicts. Mitigate with a BOM/platform dependency and clear version requirements in documentation. |
| **Pekko community longevity** | Apache Software Foundation project (graduated). Active development. Large installed base from Akka migration. Lower risk than a bespoke framework maintained by one team. |
| **DataSource integration with Pekko JDBC** | `pekko-persistence-jdbc` uses Slick internally. A thin adapter bridges the host's DataSource into Slick's connection provider. This is a known pattern with documented solutions. |
| **SecureStorage key management** | The encryption key for xpriv/key material must be provided by the host via the builder. It is never stored in the database alongside encrypted data. Derivation from passphrase is the host's concern. |
