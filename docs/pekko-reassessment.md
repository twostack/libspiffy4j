# Revised Technology Assessment: Pekko as ES/CQRS Foundation

## 1. Context

The [architecture review](architecture-review.md) identified ten issues in the original design. Six of them — lock/cache races, projection concurrency, error/compensation strategy, optimistic concurrency conflicts, command idempotency, and cross-aggregate coordination — are problems that the custom lightweight ES approach must solve from scratch. These are the same problems that actor-based persistence frameworks exist to solve.

This document reassesses whether Apache Pekko's persistent actors should replace the custom `eventsourcing` package and `AggregateRouter` as the ES/CQRS foundation for libspiffy4j.

---

## 2. What the Original Architecture Rejected — and Why It Was Wrong

### 2.1 Pekko Was Evaluated Only as an Actor Framework

The original assessment (Section 5.5) framed Pekko narrowly: actors vs. locks for state isolation. It concluded that `ReentrantLock` + Caffeine cache provides equivalent guarantees with less framework weight. This is true **if state isolation is the only concern**.

But the architecture review revealed that state isolation is the easy part. The hard parts are:

- Projection lifecycle, error handling, and offset tracking
- Snapshot management integrated with event replay
- Dead-letter handling for poison events
- Command deduplication
- Saga/process manager coordination across aggregates
- Supervision and restart strategies that don't lose state

These are not features you bolt on — they are foundational to an event-sourced system. The custom approach requires building all of them. Pekko provides all of them as tested, documented primitives.

### 2.2 Axon Was Rejected on Partially Outdated Grounds

The original rejection cited "Axon Server or a specific Spring+JPA wiring." Axon can run without Axon Server (using a JDBC event store), and without Spring (though Spring is the path of least resistance). However, the deeper concern remains valid: Axon's programming model (annotation-driven aggregates, `@CommandHandler`, `@EventSourcingHandler`, `@SagaEventHandler`) is prescriptive and leaks into the host application's code. For a library that host applications embed, this coupling is problematic.

**Axon remains the wrong choice for a library. Pekko is the stronger candidate.**

---

## 3. What Pekko Persistent Actors Provide

Pekko (Apache 2.0, forked from Akka pre-BSL) provides `EventSourcedBehavior` — a typed, event-sourced persistent actor with the following built-in capabilities:

### 3.1 Core Event Sourcing

| Capability | Pekko Primitive | Custom Equivalent We'd Build |
|---|---|---|
| Command → Event → State | `EventSourcedBehavior<Command, Event, State>` | `Aggregate<S, E>` base class |
| Event persistence | `pekko-persistence-jdbc` (PostgreSQL journal) | `EventStore` interface + PostgreSQL impl |
| State recovery on startup | Automatic replay from journal | `loadOrCreate()` + manual replay |
| Snapshots | `snapshotWhen()`, `SnapshotStore` | Custom snapshot scheduling + storage |
| Event versioning/upcasting | `EventAdapter` chain | `EventUpcaster` chain |

### 3.2 Projection / Read Side (pekko-projection)

| Capability | Pekko Primitive | Custom Equivalent We'd Build |
|---|---|---|
| Event consumption | `EventSourcedProvider` + `ProjectionBehavior` | `ProjectionManager` (undesigned) |
| Offset tracking | Built-in offset store (JDBC) | Custom checkpoint table + queries |
| At-least-once delivery | `AtLeastOnceProjection` | Manual retry logic |
| Exactly-once delivery | `ExactlyOnceProjection` (transactional) | Manual idempotency checks |
| Error handling | `RetryAndFail`, `RetryAndSkip` strategies | Undesigned (review issue #4) |
| Parallelism | Grouped/partitioned projections | Undesigned (review issue #3) |
| Lag monitoring | Built-in telemetry hooks | Custom Micrometer wiring |

### 3.3 Concurrency & Lifecycle

| Capability | Pekko Primitive | Custom Equivalent We'd Build |
|---|---|---|
| Single-writer guarantee | Actor mailbox (structural) | Per-ID `ReentrantLock` (race-prone — review issue #1) |
| Passivation | `EntityContext.setReceiveTimeout()` | Caffeine cache eviction |
| Supervision | `SupervisorStrategy` (restart, stop, backoff) | try/catch + hope |
| Graceful shutdown | `CoordinatedShutdown` | Manual drain logic |
| Stashing | `Effect.stash()` during recovery | Not considered |

### 3.4 Cross-Aggregate Coordination

| Capability | Pekko Primitive | Custom Equivalent We'd Build |
|---|---|---|
| Sagas / process managers | Persistent actors reacting to events | Entire saga framework (review issue #10) |
| Reliable event delivery between aggregates | `pekko-projection` to command routing | Manual event-to-command wiring |

### 3.5 Clustering (Future)

| Capability | Pekko Primitive |
|---|---|
| Distributed aggregates | `pekko-cluster-sharding` |
| Shard rebalancing | Built-in |
| Location-transparent routing | `EntityRef` |
| Replicated event sourcing | `ReplicatedEventSourcing` |

The original architecture already identified this as the migration path (Section 5.6). Starting with Pekko means this is an increment, not a migration.

---

## 4. Addressing Original Concerns About Pekko

### 4.1 "Framework weight (~10-15MB JARs + Scala stdlib)"

**Reassessed**: For a server-side library targeting enterprise deployments with PostgreSQL, JVM footprint is measured in hundreds of MB. A 15MB dependency is noise. The Scala stdlib is a transitive dependency that requires no Scala knowledge to use — the Pekko Java DSL is fully idiomatic Java.

### 4.2 "Learning curve: actor model, Behavior<T>, message protocols"

**Reassessed**: The alternative is now "learn our bespoke ES framework" — which has no community, no documentation, no Stack Overflow answers, and no battle-testing. Pekko's `EventSourcedBehavior` API is well-documented and the typed Java DSL is straightforward:

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

This is no more complex than the custom `Aggregate<S, E>` base class would be — and developers can find help online.

### 4.3 "Too prescriptive for a library"

**Reassessed**: This was the strongest original argument and it deserves careful treatment.

Pekko's persistence model does impose structure: aggregates must extend `EventSourcedBehavior`, events must be serializable via Pekko's serialization system, and the actor system must be started and stopped. However:

- **The actor system is internal to the library.** The host application never interacts with it directly. `LibSpiffy4j.builder()` creates and manages the actor system internally, just as it would have managed the `AggregateRouter` internally. The host sees the same builder API, the same command/query separation, the same `close()` lifecycle.

- **Pekko's serialization accepts Jackson.** We can use Jackson CBOR as the event serializer within Pekko, preserving the CBOR decision and Dart parity. Pekko doesn't force Protobuf or its own format.

- **The transitive dependency is the real cost.** Host applications will have `pekko-actor`, `pekko-persistence`, `pekko-projection`, and `scala-library` on their classpath. For enterprise server-side apps, this is acceptable. For lightweight microservices trying to minimize footprint, it's a consideration — but those apps are unlikely to need an enterprise wallet library with ES/CQRS in the first place.

### 4.4 "No framework runtime to manage alongside the host application"

**Reassessed**: The Pekko `ActorSystem` is created by the library and invisible to the host. The host manages it the same way it would manage any library resource: through the `close()` method on the builder. The host does not configure dispatchers, mailboxes, or serialization — the library owns that configuration.

The one legitimate concern is **thread pool interaction**. Pekko uses its own dispatcher threads. On virtual-thread-friendly hosts (Quarkus, Micronaut), this means the library's internal threading model differs from the host's. This is manageable — Pekko's dispatchers can be configured to use a small pool since persistent actors spend most of their time idle — but it's a rougher edge than the virtual-thread-only approach.

---

## 5. What Changes in the Architecture

### 5.1 Sections That Are Replaced

| Original Section | Replacement |
|---|---|
| **5. Aggregate Router** (entire section) | Pekko `ClusterSharding` (local mode initially, cluster mode later). The per-ID lock, Caffeine cache, passivation, and lifecycle management are all replaced by Pekko primitives. |
| **6.3 Event Sourcing: Custom lightweight** | Pekko `EventSourcedBehavior` + `pekko-persistence-jdbc`. No custom `Aggregate`, `EventStore`, `Snapshot`, `EventUpcaster`, or `ProjectionManager` to build. |
| **6.4 Concurrency: Virtual Threads + Aggregate Router** | Pekko dispatchers for aggregate processing. Virtual threads can still be used for non-aggregate work (ARC client, SPV validation, batch operations). |
| **8. Package Structure: `eventsourcing/` and `router/`** | Replaced by Pekko configuration and the aggregate implementations in `aggregate/`. |

### 5.2 Sections That Are Preserved

| Section | Status |
|---|---|
| **1-3. Purpose, bitcoin4j, Dart functionality** | Unchanged |
| **4. Why ES and CQRS** | Unchanged — the justification holds; only the implementation vehicle changes |
| **6.1 Gradle, 6.2 Java 21+** | Unchanged |
| **6.5 PostgreSQL** | Unchanged — Pekko persistence uses the same PostgreSQL via host-provided DataSource |
| **6.6 Jackson CBOR** | Unchanged — configured as Pekko's event serializer |
| **6.7-6.10 HTTP, crypto, logging, testing** | Unchanged |
| **7. Framework Integration** | Mostly unchanged — builder pattern stays; builder now creates an ActorSystem internally |
| **Aggregate domain code** (commands, events, state) | Unchanged — the wallet domain logic is the same; only the base class changes |

### 5.3 What Gets Added

**Pekko configuration** — The library ships a `reference.conf` with sensible defaults for:
- JDBC journal and snapshot store (pointing at the host-provided DataSource)
- Jackson CBOR serialization binding for events
- Dispatcher tuning (small thread pool; aggregates are lightweight)
- Cluster sharding in local mode (single-node, no Artery/remoting)

**Projection wiring** — Pekko Projection replaces the undesigned `ProjectionManager`:
- `EventSourcedProvider` reads from the journal
- `JdbcProjection.exactlyOnce()` for transactional projection updates
- Built-in offset tracking and error handling
- Projection lag exposed via Pekko's telemetry → Micrometer bridge

### 5.4 Revised Builder API

```java
var spiffy = LibSpiffy4j.builder()
    .dataSource(appDataSource)          // required — used for Pekko journal + projections
    .objectMapper(appObjectMapper)      // optional — default CBOR mapper if absent
    .meterRegistry(appMeterRegistry)    // optional — no-op if absent
    .build();

// Host never sees or configures the ActorSystem
// Commands and queries go through the same surfaces as before

spiffy.wallets().send(new CreateWallet(...));
var balance = spiffy.walletQueries().getBalance(walletId);

spiffy.close();  // shuts down ActorSystem + projections gracefully
```

The host-facing API is unchanged. The actor system is an implementation detail.

---

## 6. Revised Dependency Graph

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
```

### Revised External Dependencies

| Dependency | Purpose | Scope | Notes |
|---|---|---|---|
| `org.apache.pekko:pekko-actor-typed_2.13` | Actor system runtime | compile | Required |
| `org.apache.pekko:pekko-persistence-typed_2.13` | Event-sourced persistent actors | compile | Required |
| `org.apache.pekko:pekko-persistence-jdbc_2.13` | PostgreSQL journal + snapshot store | compile | Required; uses host DataSource |
| `org.apache.pekko:pekko-projection-eventsourced_2.13` | Read-side projections from journal | compile | Required |
| `org.apache.pekko:pekko-projection-jdbc_2.13` | JDBC-backed projection offset store | compile | Required |
| `org.apache.pekko:pekko-serialization-jackson_2.13` | Jackson serialization integration | compile | Required; we configure CBOR format |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-cbor` | CBOR binary serialization | compile | Required |
| `io.micrometer:micrometer-core` | Metrics / observability | compile (optional) | No-op fallback if absent |
| `org.junit.jupiter:junit-jupiter` | Testing | test | |
| `org.assertj:assertj-core` | Fluent assertions | test | |
| `org.apache.pekko:pekko-persistence-testkit_2.13` | In-memory journal for tests | test | Replaces custom in-memory EventStore |
| `org.testcontainers:postgresql` | PostgreSQL integration tests | test | |

### Dependencies No Longer Needed

| Dependency | Why Removed |
|---|---|
| `com.github.ben-manes.caffeine:caffeine` | Pekko manages aggregate lifecycle (passivation, eviction) natively |

---

## 7. Revised Package Structure

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
├── reference.conf              # Pekko configuration defaults (journal, serialization, etc.)
└── db/
    └── libspiffy4j/            # SQL migration files (host runs via Flyway/Liquibase)
        ├── V001__create_journal.sql        # Pekko JDBC journal schema
        ├── V002__create_snapshot.sql       # Pekko JDBC snapshot schema
        ├── V003__create_projection_offset.sql  # Pekko projection offset store
        ├── V004__create_wallet_read_models.sql
        ├── V005__create_invoice_read_models.sql
        └── V006__create_secure_storage.sql
```

**Removed packages:**
- `eventsourcing/` — replaced by Pekko persistence primitives
- `router/` — replaced by Pekko sharding (local mode)

**Added packages:**
- `serialization/` — thin adapter wiring Jackson CBOR into Pekko's serialization
- `config/` — internal factory for the ActorSystem (never exposed to host)

---

## 8. How Review Issues Are Resolved

| Review Issue | Resolution with Pekko |
|---|---|
| **#1 Lock/cache race** | Eliminated. Actor mailbox provides structural single-writer guarantee. No lock map to race on. |
| **#2 CBOR tooling** | Unchanged — still needs a deserializer utility. Orthogonal to framework choice. |
| **#3 Projection concurrency** | Resolved. `pekko-projection` provides configurable parallelism, partitioned projections, and clear threading model. |
| **#4 Error/compensation** | Resolved. Pekko Projection offers `RetryAndFail` / `RetryAndSkip` strategies, offset management survives crashes, and dead-letter actors handle poison messages. |
| **#5 Optimistic concurrency** | Resolved. Pekko's JDBC journal handles sequence number conflicts. The persistence plugin rejects conflicting writes; the actor restarts and replays to resolve. |
| **#6 SecureStorage key management** | Unchanged — still needs explicit key management design. Orthogonal to framework choice. |
| **#7 Testability gap** | Improved. `pekko-persistence-testkit` provides an in-memory journal with the same semantics as the JDBC journal. Tests against testkit are more representative than tests against a custom in-memory store. PostgreSQL integration tests via Testcontainers remain in place. |
| **#8 Command idempotency** | Partially resolved. Pekko's at-least-once delivery and actor-level deduplication patterns are well-documented. Still requires domain-level idempotency design, but the infrastructure support exists. |
| **#9 Payment channels** | Unchanged — still needs domain design. Orthogonal to framework choice. |
| **#10 Multi-wallet coordination** | Resolved. Persistent actors can react to each other's events via projections, and Pekko provides the building blocks for saga/process manager patterns without a bespoke saga framework. |

**Score: 6 of 10 issues resolved or significantly improved by the framework switch. The remaining 4 are domain-level concerns orthogonal to infrastructure choice.**

---

## 9. Revised Implementation Priority

### Phase 1 — Foundation
1. Project scaffolding (Gradle, package structure, bitcoin4j + Pekko dependencies)
2. `ActorSystemFactory` with JDBC journal configured against host DataSource
3. Jackson CBOR serialization adapter for Pekko
4. Core domain models (`BitcoinUtxo`, `AddressMetadata`, `WalletConfig`)
5. `LibSpiffy4j` builder accepting DataSource, creating ActorSystem internally

### Phase 2 — Wallet Core
6. `WalletAggregate` (extends `EventSourcedBehavior`) with wallet creation commands
7. Address derivation (leveraging bitcoin4j HD keys)
8. UTXO lifecycle (receive, reserve, spend, release)
9. `WalletProjection` via `pekko-projection` with JDBC offset store
10. `CryptoService` (key derivation, encryption) + `SecureStorage` with explicit key management

### Phase 3 — Transactions & SPV
11. `TransactionBuildService` (coin selection + bitcoin4j `TransactionBuilder`)
12. BEEF parser and BUMP merkle proof validator
13. `SpvService` for transaction verification
14. Block header sync and chain validation

### Phase 4 — Invoices, Channels & Coordination
15. `InvoiceAggregate` with lifecycle
16. `PaymentChannelAggregate` with UTXO coordination design
17. Cross-aggregate coordination patterns (process managers via persistent actors)
18. Associated projections

### Phase 5 — Operations & Observability
19. Pekko telemetry → Micrometer bridge for projection lag, aggregate metrics
20. Health indicator implementation
21. Snapshot tuning (configure `snapshotWhen()` thresholds per aggregate)
22. CBOR event inspection utility (CLI tool for debugging)

### Phase 6 — Network Integration
23. `ArcService` HTTP client
24. Block header CDN sync
25. Transaction broadcast and status tracking

**Key change**: PostgreSQL persistence is no longer a separate phase. Pekko's JDBC journal means persistence is baked in from Phase 1. The testability gap (review issue #7) is eliminated — aggregates are tested against the same journal backend from day one, with `pekko-persistence-testkit` for fast unit tests and Testcontainers for integration tests.

---

## 10. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| **Scala stdlib on classpath** | Transitive only. No Scala code in libspiffy4j. Host apps don't interact with Scala types. |
| **Pekko thread pool vs host virtual threads** | Configure Pekko dispatchers with small fork-join pool. Non-aggregate work (ARC, SPV) can use virtual threads independently. |
| **Pekko version conflicts with host** | Host apps using Pekko directly could hit version conflicts. Mitigate with a BOM/platform dependency and clear version requirements in documentation. |
| **Pekko community longevity** | Apache Software Foundation project (graduated). Active development. Large installed base from Akka migration. Lower risk than a bespoke framework maintained by one team. |
| **DataSource integration with Pekko JDBC** | `pekko-persistence-jdbc` accepts a `SlickDatabase` configured with a DataSource. Requires a thin adapter to bridge the host's DataSource into Slick's connection provider. This is a known pattern with documented solutions. |

---

## 11. Decision Summary

| Question | Original Answer | Revised Answer |
|---|---|---|
| ES framework | Custom lightweight | **Pekko persistent actors** — the custom approach was building a framework; better to use a tested one |
| Concurrency / state isolation | AggregateRouter + virtual threads | **Pekko actors** — structural single-writer guarantee, no lock/cache races |
| Projection system | Custom ProjectionManager (undesigned) | **Pekko Projection** — offset tracking, error handling, parallelism built in |
| Saga / cross-aggregate | Not addressed | **Pekko persistent actors as process managers** — well-documented pattern |
| Clustering path | "Migrate to Pekko later" | **Start with Pekko in local mode** — clustering becomes an increment, not a migration |
| Caffeine cache | Required for aggregate lifecycle | **Removed** — Pekko manages lifecycle natively |

The "library, not framework" principle is preserved: the host application still interacts with libspiffy4j through a builder, commands, and queries. It never sees the ActorSystem. Pekko is an internal implementation detail — but one that solves six architectural problems that would otherwise require building a bespoke framework.
