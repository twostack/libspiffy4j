# Architecture Review — libspiffy4j

Critical assessment of [architecture.md](architecture.md), evaluating coherence, architectural smells, and areas for improvement.

---

## What Works Well

The document is remarkably thorough and well-reasoned. The ES+CQRS justification (Section 4) is particularly strong — it honestly evaluates the alternative and gives concrete reasons for rejection. The framework integration design (Section 7) is excellent: "accept infrastructure, don't create it" is the right philosophy for a library. The Aggregate Router (Section 5) is a clean, pragmatic alternative to a full actor framework.

---

## Architectural Smells & Improvement Areas

### 1. Snapshot + Lock Cleanup Race (Section 5.3)

The Caffeine removal listener does `locks.remove(id)` on eviction, but there's a race: a new command could arrive between cache eviction and lock removal. The new command would `computeIfAbsent` a fresh lock, then the removal listener fires and deletes it. This needs a CAS-style cleanup or the lock map should be decoupled from the cache lifecycle.

### 2. CBOR Decision Has a Hidden Cost (Section 6.6)

The document dismisses human readability as solvable by "admin tooling" — but that tooling doesn't exist yet and isn't in any implementation phase. During development and early operations, engineers will be debugging with `psql`. Consider shipping a CLI tool or at minimum a deserializer utility in Phase 1, not as an afterthought. Also, CBOR makes the event store opaque to PostgreSQL's `pg_dump --inserts` for readable backups.

### 3. No Concurrency Model for Projections (Section 4 vs Section 5)

The write side has a clear concurrency story (Aggregate Router, per-ID locks). The read side has nothing specified. How does `ProjectionManager` consume events? Polling? Change-data-capture? In-process event bus? What happens when two projections process the same event stream — are they sequential or parallel? This is a significant gap given that projection lag monitoring is listed as a day-one requirement.

### 4. Missing Error/Compensation Strategy

The document describes the happy path (`Command → Events → Projection`) but never addresses:

- What happens when `EventStore.save()` succeeds but the projection handler fails?
- Is there a dead-letter mechanism for failed projection events?
- How are poison events (events that consistently crash a projection) handled?

For a financial system, these aren't edge cases — they're operational certainties.

### 5. Optimistic Concurrency Is Mentioned but Not Designed (Section 6.5)

The event store uses "optimistic concurrency (version column)" but there's no discussion of what happens on conflict. Does the Aggregate Router's per-ID lock make optimistic concurrency redundant for single-node? If so, why have it? If it's there for future distributed deployment, the retry/conflict-resolution strategy should be sketched now since it affects the command handler return type.

### 6. `SecureStorage` Scope Is Unclear (Section 8)

`SecureStorage` encrypts xpriv/key material with AES-256-GCM, but: who manages the encryption key? If it's in the database alongside the encrypted data, that's security theater. If the host provides it, it should be a builder parameter. If it's derived from a passphrase, that flow needs to be defined. This is a critical security boundary left as an implementation detail.

### 7. Phase Ordering Creates a Testability Gap

Phase 1 builds the ES framework + in-memory store. Phase 2 builds the wallet aggregate. Phase 4 builds PostgreSQL storage. This means the wallet aggregate is developed and tested for two full phases against only in-memory storage. When PostgreSQL lands, you may discover that assumptions baked into the aggregate (event sizes, query patterns, serialization edge cases) don't hold. Consider pulling a minimal PostgreSQL event store into Phase 2 alongside Testcontainers.

### 8. No Idempotency Key on Commands

The document requires idempotent projections (Section 4.3) but says nothing about idempotent command handling. If a network timeout causes a retry of "reserve UTXO X for invoice Y," the aggregate will process it twice and emit duplicate events. Financial systems need command deduplication — typically via an idempotency key checked before processing.

### 9. Payment Channels Section Is Thin (Section 3.7)

Every other domain area has clear lifecycle states and design rationale. Payment channels get four bullet points and no discussion of: how channel state interacts with the UTXO lifecycle, whether the channel aggregate owns its funding UTXO or coordinates with the wallet aggregate, or how disputes/timeouts are monitored (who watches nLockTime expiry?).

### 10. Missing: Multi-Wallet Coordination

Section 3.1 mentions "multi-wallet isolation" and Section 4.1 says "one event stream per wallet." But there's no design for cross-wallet operations: sweeping funds between wallets, consolidated balance reporting, or atomic operations spanning two wallets. If these are out of scope, say so explicitly. If not, the single-aggregate-per-stream model needs a saga or process manager pattern.

---

## Summary

The document is strong on the "what" and "why" for the core patterns. The gaps are mostly at boundaries — between write and read sides, between aggregates, between the library and operational reality (debugging, error recovery, key management). These are worth addressing before implementation begins, since they affect foundational interfaces.
