# libspiffy4j — Implementation Roadmap

Feature-complete port of [libspiffy](../../libspiffy/) (Dart) to Java, using Apache Pekko persistent actors as the ES/CQRS foundation. See [architecture.md](architecture.md) for design rationale.

---

## Scope

This roadmap covers every feature present in the Dart libspiffy codebase. Each phase lists concrete deliverables — types, classes, commands, events, tests — so progress is unambiguous. A deliverable is done when its tests pass against both `pekko-persistence-testkit` (unit) and Testcontainers PostgreSQL (integration).

### What bitcoin4j Already Provides (Not In Scope)

HD key derivation, transaction building/signing, script system, address encoding, P2PKH/P2PK/P2MS/OP_RETURN lock/unlock builders, `Coin`, `NetworkParameters`. These are used as-is.

---

## Phase 1 — Project Scaffolding & Pekko Foundation **COMPLETE** (2026-03-14)

**Goal**: Gradle project builds, Pekko ActorSystem starts against PostgreSQL, a trivial persistent actor round-trips events through the JDBC journal.

### 1.1 Project Structure

| Deliverable | Status | Detail |
|---|---|---|
| `build.gradle.kts` | DONE | `java-library` plugin, Java 21 toolchain, `-parameters` flag. Pekko 1.1.3, pekko-persistence-jdbc 1.1.0, Jackson CBOR 2.17.3, Micrometer compileOnly, JUnit 5, AssertJ, pekko-persistence-testkit, Testcontainers PostgreSQL |
| Package layout | DONE | 13 packages with `package-info.java`: `aggregate/`, `aggregate/wallet/`, `model/`, `projection/`, `service/`, `spv/`, `storage/`, `storage/postgres/`, `storage/memory/`, `serialization/`, `config/`, `util/` |
| `reference.conf` | DONE | JDBC journal + snapshot via `use-shared-db`, `HostDataSourceProvider` as custom `SlickDatabaseProvider`, Jackson CBOR serialization binding for `SpiffyEvent`, cluster provider with artery localhost:0, sharding (30 shards) |

### 1.2 Pekko Infrastructure

| Deliverable | Status | Detail |
|---|---|---|
| `ActorSystemFactory` | DONE | Creates typed `ActorSystem<Void>`, registers DataSource in `DataSourceRegistry` before system start, programmatic cluster self-join via `JoinSeedNodes` |
| `SpiffyEvent` marker interface | DONE | Replaces planned `CborEventSerializer` — Pekko's built-in `JacksonCborSerializer` is bound to `SpiffyEvent` in `reference.conf`, no custom serializer needed |
| `LibSpiffy4j` + `LibSpiffy4jBuilder` | DONE | Builder accepts `DataSource` (required), `ObjectMapper` (optional), `MeterRegistry` (optional), `configOverride` (for testing). `LibSpiffy4j` implements `AutoCloseable`, system naming via `"libspiffy4j-" + AtomicLong` |
| `HostDataSourceProvider` | DONE | Implements `SlickDatabaseProvider`, wraps host `DataSource` in Slick `EagerSlickDatabase` via `JdbcBackend$.forDataSource()`. `DataSourceRegistry` (static `ConcurrentHashMap`) keyed by system name for multi-instance support |

### 1.3 SQL Migrations

| File | Status | Content |
|---|---|---|
| `V001__create_journal.sql` | DONE | Pekko JDBC journal schema (event_journal, event_tag) |
| `V002__create_snapshot.sql` | DONE | Pekko JDBC snapshot schema |
| `V003__create_projection_offset.sql` | DONE | Pekko Projection offset + management tables |

### 1.4 Smoke Test

| Test | Status | Validates |
|---|---|---|
| `ActorSystemStartupTest` (1 test) | DONE | ClusterSharding + persistence-testkit. `SmokeAggregate` Ping → Pong:1, Ping → Pong:2 |
| `JdbcJournalIntegrationTest` (1 test) | DONE | Testcontainers PostgreSQL. Persist → shutdown → new instance → recover → assert pingCount=2 |
| `CborSerializationTest` (2 tests) | DONE | Jackson CBOR round-trip with `byte[]` fields. Pekko `SerializationExtension` resolves `SpiffyEvent` → `JacksonCborSerializer` |
| `BuilderLifecycleTest` (4 tests) | DONE | No DataSource → throws. With DataSource → builds. `close()` completes. `close()` twice idempotent |

### 1.5 Exit Criteria

- [x] `./gradlew test` green — 8 tests, 0 failures
- [x] Pekko journal round-trips events through PostgreSQL
- [x] CBOR serialization handles `byte[]` fields without encoding overhead
- [x] Builder creates and shuts down ActorSystem cleanly

### 1.6 Implementation Notes

- **No custom `CborEventSerializer`**: Pekko's built-in `JacksonCborSerializer` handles everything via `serialization-bindings` in `reference.conf`. The `SpiffyEvent` marker interface is the binding point.
- **Scala interop**: `HostDataSourceProvider` calls `slick.jdbc.JdbcBackend$.MODULE$.Database().forDataSource()` and wraps the result in `EagerSlickDatabase(db, PostgresProfile$.MODULE$)`.
- **Testkit config**: Tests must include `PersistenceTestKitPlugin$.MODULE$.config()` programmatically — the testkit plugin config is not in its `reference.conf`.
- **bitcoin4j dependency**: Commented out pending Maven coordinate verification.

---

## Phase 2 — Domain Models & Value Objects **COMPLETE** (2026-03-14)

**Goal**: All domain value types exist as Java records with serialization, validation, and equality semantics.

### 2.1 Core Models

| Record | Status | Key Fields | Notes |
|---|---|---|---|
| `BitcoinUtxo` | DONE | txid, vout, valueSats, scriptPubKey, address, status, blockHeight, confirmations, reservedByTxId, reservationExpiresAt, reservationPriority, reservationReason, derivationIndex, createdAt, updatedAt | Status enum: `PENDING`, `AVAILABLE`, `RESERVED`, `SPENT`. Methods: `isReservationExpired()`, `isEffectivelyAvailable()`, `reservationTimeRemaining()`, `reserve()`, `markSpent()`, `releaseReservation()`, `updateConfirmations()`, `renewReservation()` |
| `AddressMetadata` | DONE | address, scriptType, derivationPath, derivationIndex, isChange, label, purpose, firstUsedAt, lastUsedAt, usageCount, balanceSats, createdAt, isWatched | |
| `BitcoinTransaction` | DONE | walletId, txid, rawHex, status, direction, blockHeight, confirmations, inputValueSats, outputValueSats, feeSats, netAmountSats, sendingAddresses, receivingAddresses, createdAt, updatedAt, memo, lockTime, version | Immutable lists via `List.copyOf()` in compact constructor |
| `InvoiceOutputSpec` | DONE | Sealed interface with: `P2PKHOutputSpec(address, amountSats, label)`, `P2MSOutputSpec(publicKeys, threshold, amountSats, label)`, `OPReturnOutputSpec(dataChunks)` | Validation: P2MS threshold ≤ totalKeys ≤ 16; OP_RETURN totalSize ≤ 99KB |
| `PaymentChannel` | DONE | channelId, walletId, role, state, clientPeerId, serverPeerId, clientPubKeyHex, serverPubKeyHex, clientAddressB58, serverAddressB58, fundingAmountSats, lockTimeUnix, clientBalanceSats, serverBalanceSats, fundingTxId, latestSequenceNumber, createdAt, closedAt, errorMessage | State enum: `NEGOTIATING`, `FUNDING`, `OPENING`, `OPEN`, `CLOSING`, `CLOSED`, `EXPIRED`, `FAILED`. Role enum: `CLIENT`, `SERVER` |

### 2.2 Enums

| Enum | Status | Values |
|---|---|---|
| `UtxoStatus` | DONE | `PENDING`, `AVAILABLE`, `RESERVED`, `SPENT` |
| `TransactionStatus` | DONE | `CREATED`, `SIGNED`, `BROADCAST`, `PENDING`, `CONFIRMED`, `FAILED` |
| `TransactionDirection` | DONE | `INCOMING`, `OUTGOING`, `SELF`, `UNKNOWN` |
| `BitcoinScriptType` | DONE | `P2PKH`, `P2PK`, `P2MS`, `OP_RETURN`, `P2SH`, `CUSTOM`, `UNKNOWN` |
| `NetworkType` | DONE | `MAINNET`, `TESTNET`, `REGTEST` |
| `WalletType` | DONE | `HD`, `WIF`, `XPRIV`, `XPUB` |

### 2.3 Configuration Models

| Record | Status | Key Fields | Notes |
|---|---|---|---|
| `ArcServiceConfig` | DONE | baseUrl, apiKey, defaultCallbackUrl | Factory methods: `taalTestnet(apiKey)`, `taalMainnet()`, `custom(baseUrl)` |
| `TransactionBuildConfig` | DONE | feePerKb, selectionStrategy, minChangeAmount, forceChange, enableRBF, performSanityChecks | Strategy enum: `SMALLEST_FIRST`, `LARGEST_FIRST`, `RANDOM`, `OPTIMAL_CHANGE`. Presets: `standard()`, `partial()` |
| `CdnHeaderSyncConfig` | DONE | manifestUrl, cacheDirectory, chunkDownloadTimeout, validationEnabled | |

### 2.4 Tests

| Test | Status | Validates |
|---|---|---|
| `ModelCborRoundTripTest` | DONE | CBOR round-trip for domain records |
| `BitcoinUtxoTest` | DONE | State transition methods (reservation expiry, effective availability) |
| `InvoiceOutputSpecTest` | DONE | P2MS threshold validation, OP_RETURN size limits, sealed interface exhaustiveness |
| `PaymentChannelTest` | DONE | Record construction and field access |
| `ConfigModelTest` | DONE | Configuration record construction and factory methods |

### 2.5 Exit Criteria

- [x] All domain types compile, serialize/deserialize via CBOR, pass validation tests
- [x] Records are immutable, use `equals`/`hashCode` correctly

---

## Phase 3 — Wallet Aggregate **COMPLETE** (2026-03-14)

**Goal**: Create wallets, generate addresses, full UTXO lifecycle, transaction recording. The core wallet is functional end-to-end.

### 3.1 Commands

| Command | Status | Key Fields | Validation |
|---|---|---|---|
| `CreateWalletCommand` | DONE | walletId, name, walletType, networkType, rootAddress, metadata, replyTo | Rejects if wallet already exists |
| `RecordAddressCommand` | DONE | walletId, addressMetadata, replyTo | Rejects duplicate address |
| `RecordUtxoCommand` | DONE | walletId, utxo (BitcoinUtxo), replyTo | Rejects duplicate UTXO (by txid:vout key) |
| `RecordTransactionCommand` | DONE | walletId, transaction (BitcoinTransaction), replyTo | Rejects duplicate txid |
| `ReserveUtxoCommand` | DONE | walletId, utxoKey, reservingTxId, expiresAt, priority, reason, replyTo | UTXO must be effectively available |
| `ReleaseUtxoCommand` | DONE | walletId, utxoKey, replyTo | UTXO must be RESERVED |
| `MarkUtxoSpentCommand` | DONE | walletId, utxoKey, replyTo | UTXO must not already be SPENT |
| `UpdateConfirmationCommand` | DONE | walletId, txid, confirmations, blockHeight, replyTo | Emits both UtxoConfirmationUpdated and TransactionConfirmed if txid known |
| `CleanupExpiredReservationsCommand` | DONE | walletId, replyTo | Releases all expired reservations |

### 3.2 Events

| Event | Status | Triggered By |
|---|---|---|
| `WalletCreatedEvent` | DONE | CreateWalletCommand |
| `WalletConfigurationUpdatedEvent` | DONE | (placeholder for future config updates) |
| `AddressRecordedEvent` | DONE | RecordAddressCommand |
| `UtxoReceivedEvent` | DONE | RecordUtxoCommand |
| `UtxoSpentEvent` | DONE | MarkUtxoSpentCommand |
| `UtxoReservedEvent` | DONE | ReserveUtxoCommand |
| `UtxoReleasedEvent` | DONE | ReleaseUtxoCommand / CleanupExpiredReservationsCommand |
| `UtxoConfirmationUpdatedEvent` | DONE | UpdateConfirmationCommand |
| `TransactionRecordedEvent` | DONE | RecordTransactionCommand |
| `TransactionConfirmedEvent` | DONE | UpdateConfirmationCommand (when txid is known) |

### 3.3 Aggregate State: `WalletState`

| Field | Type | Notes |
|---|---|---|
| walletId | String | |
| name | String | |
| rootAddress | String | |
| created | boolean | Guards pre-creation command rejection |
| networkType | NetworkType | |
| walletType | WalletType | |
| utxoEntries | `Map<String, UtxoEntry>` (key: `txid:vout`) | Minimal record: status, valueSats, reservationExpiresAt, txid (refactored in Phase 4) |
| knownAddresses | `Set<String>` | Dedup only (refactored in Phase 4) |
| knownTxids | `Set<String>` | Dedup only (refactored in Phase 4) |
| nextDerivationIndex | int | Auto-incremented on address recording |
| metadata | `Map<String, Object>` | |
| version | long | Incremented on every event |
| lastUpdatedAt | Instant | |

### 3.4 Aggregate Behavior

| Deliverable | Status | Detail |
|---|---|---|
| `WalletAggregate` | DONE | Extends `EventSourcedBehavior<WalletCommand, WalletEvent, WalletState>`. Dual command handler states: "not created" (only CreateWallet accepted, all others reply Failure) and "created" (full command set). `ENTITY_TYPE_KEY` for cluster sharding. `tagsFor()` returns `Set.of("wallet")`. |
| `WalletReply` | DONE | Sealed interface: `Success(WalletState)` / `Failure(String reason)`. All commands use `replyTo` for typed responses. |
| Snapshot retention | DONE | `snapshotEvery(100, 2)` — snapshot every 100 events, keep 2 |

### 3.5 Tests

| Test | Status | Validates |
|---|---|---|
| `WalletAggregateTest` (14 tests) | DONE | Create wallet + state validation, double-create rejection, pre-creation command rejection, address recording with derivation index tracking, UTXO receipt, UTXO reservation with expiry, UTXO spending from reserved/confirmed states, transaction recording, confirmation updates, expired reservation cleanup, full recovery from persistence |
| `WalletStateTest` (11 tests) | DONE | All event application methods: UtxoEntry lifecycle (receive/reserve/release/spend), address/txid dedup sets, version increments, confirmation update no-ops |

### 3.6 Exit Criteria

- [x] Wallet creation functional with typed replies
- [x] Full UTXO lifecycle with reservation semantics (reserve with expiry, release, spend, cleanup)
- [x] Aggregate persists via Pekko JDBC journal, recovers from events
- [x] Concurrent command safety via actor mailbox serialization
- [x] All commands rejected before wallet creation with clear error messages

---

## Phase 4 — Wallet Projection & Read Models **COMPLETE** (2026-03-14)

**Goal**: Wallet state queryable via denormalized read models, updated in real-time by Pekko Projection. Aggregate state slimmed down to hold only what's needed for command validation — balances live in the read model only.

### 4.0 Prerequisite — Slim Down WalletState

| Deliverable | Status | Detail |
|---|---|---|
| `WalletState` refactor | DONE | Replaced `Map<String, BitcoinUtxo>` with `Map<String, UtxoEntry>` (inner record: status, valueSats, reservationExpiresAt, txid). Replaced `Map<String, AddressMetadata>` with `Set<String> knownAddresses`. Replaced `Map<String, BitcoinTransaction>` with `Set<String> knownTxids`. Removed `confirmedBalanceSats`, `unconfirmedBalanceSats`, `reservedBalanceSats` fields and getters/setters. |
| `WalletAggregate` update | DONE | Command handlers use `utxoEntries`/`knownAddresses`/`knownTxids` for validation. Added `tagsFor()` returning `Set.of("wallet")` for event tagging. `isEffectivelyAvailable` logic inlined from UtxoEntry fields. |
| `WalletAggregateTest` update | DONE | Removed balance assertions (balance is read-model concern). Updated state assertions to use `getUtxoEntries()`, `getKnownAddresses()`. All 14 tests pass. |
| `WalletStateTest` rewrite | DONE | 11 tests covering minimal state mutations: UtxoEntry lifecycle (receive/reserve/release/spend), address/txid dedup sets, version increments. No balance arithmetic. |

### 4.1 Projection Handler: `WalletProjectionHandler`

| Deliverable | Status | Detail |
|---|---|---|
| `WalletProjectionHandler` | DONE | Extends `JdbcHandler<EventEnvelope<WalletEvent>, SpiffyJdbcSession>`. Dispatches all 10 event types via pattern matching switch. Delegates to `WalletReadModelStorage`. Calls `updateWalletBalances()` (SQL aggregation) after every UTXO-affecting event. |

| Event | Read Model Update |
|---|---|
| `WalletCreated` | Upsert `wallet_summary` |
| `AddressRecorded` | Upsert `wallet_address`, update address count |
| `UtxoReceived` | Upsert `wallet_utxo`, recalculate balances |
| `UtxoSpent` | Update UTXO status to SPENT, recalculate balances |
| `UtxoReserved` | Update UTXO status to RESERVED with reservation fields, recalculate balances |
| `UtxoReleased` | Update UTXO status to AVAILABLE, recalculate balances |
| `UtxoConfirmationUpdated` | Update UTXO confirmations/blockHeight, recalculate balances |
| `TransactionRecorded` | Upsert `wallet_transaction`, insert `transaction_address_link` rows (SENDER/RECEIVER) |
| `TransactionConfirmed` | Update transaction status to CONFIRMED with blockHeight/confirmations |
| `WalletConfigurationUpdated` | No-op (placeholder for future metadata update) |

### 4.2 SQL Migrations

| File | Status | Tables |
|---|---|---|
| `V004__create_wallet_read_models.sql` | DONE | Fixes `projection_management` table (adds missing `projection_key` column required by Pekko JDBC projection). Creates 5 read model tables: |
| | | `wallet_summary` (PK: wallet_id, JSONB metadata, balance columns, address/utxo counts) |
| | | `wallet_address` (PK: wallet_id + address, script_type, derivation_path/index, is_change, label) |
| | | `wallet_utxo` (PK: wallet_id + txid + vout, index on wallet_id + status) |
| | | `wallet_transaction` (PK: wallet_id + txid, index on wallet_id + created_at DESC) |
| | | `transaction_address_link` (PK: wallet_id + txid + address + link_type) |

### 4.3 Read Model Storage: `WalletReadModelStorage`

| Deliverable | Status | Detail |
|---|---|---|
| `WalletReadModelStorage` | DONE | Stateless DAO. Write methods take `Connection` (for transactional projection). Read methods take `DataSource`. Balance calculation via SQL aggregation (`SUM(value_sats)` grouped by UTXO status). |

| Write Method | Detail |
|---|---|
| `upsertWalletSummary` | INSERT ON CONFLICT UPDATE name/rootAddress/metadata |
| `upsertWalletAddress` | INSERT ON CONFLICT DO NOTHING, updates address count |
| `upsertWalletUtxo` | INSERT ON CONFLICT UPDATE status/confirmations/blockHeight |
| `updateUtxoStatus` | Update status, clear reservation fields |
| `updateUtxoReserved` | Set RESERVED status with reservingTxId/expiresAt |
| `updateUtxoConfirmations` | Update confirmations/blockHeight |
| `upsertWalletTransaction` | INSERT ON CONFLICT UPDATE status/confirmations, batch-insert address links |
| `updateTransactionConfirmed` | Set CONFIRMED status with confirmations/blockHeight |
| `updateWalletBalances` | SQL aggregation: confirmed = available+confirmed UTXOs, unconfirmed = available+unconfirmed, reserved = reserved UTXOs |

| Read Method | Returns |
|---|---|
| `findWalletSummary(ds, walletId)` | `Optional<WalletSummary>` |
| `listWalletSummaries(ds, limit, offset)` | Paginated wallet summaries (ordered by created_at DESC) |
| `findUtxosByWalletId(ds, walletId)` | All UTXOs for wallet |
| `findUtxosByStatus(ds, walletId, status)` | Filtered UTXO list |
| `findTransactionsByWalletId(ds, walletId, limit, offset)` | Paginated transactions |
| `findAddressesByWalletId(ds, walletId)` | Address strings (ordered by recorded_at) |
| `findAddressesByTransaction(ds, walletId, txid, linkType)` | Addresses linked to a transaction (SENDER/RECEIVER) |
| `getWalletBalance(ds, walletId)` | `Optional<WalletBalance>` (confirmed, unconfirmed, reserved, available) |

### 4.4 Projection Wiring

| Deliverable | Status | Detail |
|---|---|---|
| `SpiffyJdbcSession` | DONE | Implements `JdbcSession`, wraps `DataSource` connection with `autoCommit=false`. Used by Pekko JDBC projection for exactly-once delivery. |
| `WalletProjectionSetup` | DONE | Static `init(ActorSystem)`. Wires `EventSourcedProvider.eventsByTag("wallet")` → `JdbcProjection.exactlyOnce` → `ShardedDaemonProcess` (1 instance). Session supplier resolves `DataSource` from `DataSourceRegistry`. |
| `LibSpiffy4jBuilder` update | DONE | Calls `WalletProjectionSetup.init(system)` after ActorSystem creation |
| `reference.conf` update | DONE | Added `pekko.projection.jdbc` config: `blocking-jdbc-dispatcher` (fixed pool 10), `postgres-dialect`, offset/management table names matching V003 schema |

### 4.5 Model Records

| Deliverable | Status | Detail |
|---|---|---|
| `WalletSummary` | DONE | Record: walletId, name, rootAddress, walletType, networkType, confirmedBalanceSats, unconfirmedBalanceSats, reservedBalanceSats, addressCount, utxoCount, createdAt, metadata |
| `WalletBalance` | DONE | Record: confirmedSats, unconfirmedSats, reservedSats, availableSats. Static factory `fromSummary(WalletSummary)` computes `availableSats = confirmed - reserved`. |

### 4.6 Tests

| Test | Status | Validates |
|---|---|---|
| `WalletReadModelStorageTest` (7 tests) | DONE | Testcontainers PostgreSQL. UPSERT insert/update, UTXO status filtering, balance calculation via SQL aggregation, wallet summary pagination, address queries, transaction record-and-confirm lifecycle |
| `WalletProjectionIntegrationTest` (7 tests) | DONE | Full integration: Testcontainers + LibSpiffy4j builder. End-to-end wallet creation → read model, balance calculation through projection, UTXO lifecycle (receive → reserve → release → spend), idempotency (duplicate rejected at aggregate), transaction pagination, address recording, projection catch-up/recovery |

### 4.7 Dependencies Added

| Dependency | Scope | Detail |
|---|---|---|
| `pekko-projection-eventsourced` 1.1.0 | implementation | `EventSourcedProvider.eventsByTag` source provider |
| `awaitility` 4.2.1 | testImplementation | Async assertion polling for projection integration tests |

### 4.8 Exit Criteria

- ✅ Real-time read model updates from wallet events
- ✅ All 8 query methods functional against PostgreSQL
- ✅ Projection survives restart (offset tracking via `projection_offset_store`)
- ✅ Exactly-once semantics verified (UPSERT idempotency + aggregate-level dedup)
- ✅ Aggregate state minimal — no unbounded maps, no balance tracking in write side

---

## Phase 5 — CryptoService & SecureStorage **COMPLETE** (2026-03-14)

**Goal**: Key derivation, address generation, message signing, and encrypted storage of private key material.

### 5.1 CryptoService

Stateless service delegating to bitcoin4j. No-arg constructor.

| Method | Delegates To | Notes |
|---|---|---|
| `generateMnemonic()` | bitcoin4j `MnemonicCode` | 128-bit entropy → 12-word BIP39 |
| `validateMnemonic(words)` | bitcoin4j `MnemonicCode` | Throws `MnemonicException` on invalid |
| `mnemonicToHDPrivateKey(mnemonic, passphrase)` | bitcoin4j `HDKeyDerivation` | BIP39 seed → BIP32 root |
| `derivePrivateKey(master, account, index, coinType, isChange)` | bitcoin4j `HDKeyDerivation` | BIP44 path: m/44'/coinType'/account'/change/index |
| `deriveKeyForPath(parent, childNumbers...)` | bitcoin4j `HDKeyDerivation` | Generic path derivation (hardened via `HARDENED_BIT`) |
| `generateAddress(key, networkType)` | bitcoin4j `LegacyAddress` | P2PKH — mainnet starts `1`, testnet starts `m`/`n` |
| `privateKeyToWIF(key, networkType)` | bitcoin4j `ECKey` | WIF encoding |
| `privateKeyFromWIF(wif)` | bitcoin4j `PrivateKey` | WIF decoding |
| `signMessage(privateKey, message)` | bitcoin4j `ECKey` | SHA-256 hash then ECDSA sign |

### 5.2 EncryptionService

Constructor takes `byte[32]` master key (defensive copy, validates length). Nullable in `LibSpiffy4j` when no master key provided.

| Method | Detail |
|---|---|
| `encrypt(plaintext, context)` | HKDF derives per-context AES key, then AES-256-GCM with random 12-byte nonce. Returns `EncryptionResult(ciphertext, nonce)`. |
| `decrypt(ciphertext, nonce, context)` | AES-256-GCM decrypt with HKDF-derived key. |
| `generateMasterKey()` | Static. Secure random 32 bytes. |

- Uses Bouncy Castle HKDF (transitive from bitcoin4j) + JCE AES-256-GCM
- HKDF salt: `"libspiffy-xpub-v1"`, info: context string
- Master key provided by host via builder (never stored in database)
- Key version field in `secure_storage` supports future key rotation

### 5.3 SecureStorage

Stateless JDBC DAO (same pattern as `WalletReadModelStorage`). Composite PK `(wallet_id, key_type)` supports multiple key types per wallet.

| Method | Detail |
|---|---|
| `storeEncryptedKey(conn, walletId, keyType, encryptedKey, nonce, keyVersion)` | Upsert to `secure_storage` table |
| `loadEncryptedKey(ds, walletId, keyType)` | Returns `Optional<EncryptedKeyRecord>` |
| `deleteEncryptedKey(conn, walletId, keyType)` | Permanent deletion |

### 5.4 Models

| Record | Fields |
|---|---|
| `EncryptionResult` | `byte[] ciphertext`, `byte[] nonce` |
| `EncryptedKeyRecord` | `walletId`, `keyType`, `encryptedKey`, `nonce`, `keyVersion`, `createdAt`, `updatedAt` |

### 5.5 SQL Migration

| File | Tables |
|---|---|
| `V005__create_secure_storage.sql` | `secure_storage` (wallet_id VARCHAR(255), key_type VARCHAR(50) DEFAULT 'MASTER_HD_KEY', encrypted_key BYTEA, nonce BYTEA, key_version INT DEFAULT 1, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, PRIMARY KEY(wallet_id, key_type)) |

### 5.6 Builder Update

```java
var spiffy = LibSpiffy4j.builder()
    .dataSource(appDataSource)
    .encryptionMasterKey(masterKeyBytes)    // optional — if omitted, encryptionService() returns null
    .build();

spiffy.cryptoService();        // always available
spiffy.encryptionService();    // nullable
spiffy.secureStorage();        // always available
```

### 5.7 Tests

| Test | Count | Validates |
|---|---|---|
| `CryptoServiceTest` | 11 | Mnemonic generation (12 words, valid BIP39), deterministic derivation, different passphrase → different key, address prefixes (mainnet `1`, testnet `m`/`n`), WIF round-trip, ECDSA signing |
| `EncryptionServiceTest` | 10 | Encrypt/decrypt round-trip, different contexts → different ciphertext, tampered ciphertext fails auth, wrong master key fails, wrong context fails, `generateMasterKey()` is 32 bytes, null/short/long key rejected |
| `SecureStorageTest` | 5 | Store/load round-trip, load nonexistent → empty, overwrite updates, delete removes, multiple key types per wallet (Testcontainers) |
| `SecureStorageIntegrationTest` | 1 | End-to-end: generate mnemonic → derive HD key → encrypt → store → load → decrypt → derive same address (Testcontainers) |

### 5.8 Exit Criteria

- ✅ bitcoin4j integrated via `mavenLocal()` + `api()` dependency
- ✅ BIP39 mnemonic generation and validation working
- ✅ BIP44 deterministic key derivation producing correct address formats per network
- ✅ AES-256-GCM encryption with HKDF per-context key derivation
- ✅ Private keys encrypted at rest, decryptable only with correct master key + context
- ✅ SecureStorage JDBC DAO with composite PK supporting multiple key types
- ✅ All 27 tests passing (11 + 10 + 5 + 1)

---

## Phase 6 — Transaction Building **COMPLETE** (2026-03-14)

**Goal**: UTXO selection, fee calculation, transaction construction, signing, and BEEF output — matching Dart `TransactionBuilderService`.

### 6.1 CoinSelector (stateless)

| Deliverable | Status | Detail |
|---|---|---|
| `CoinSelector` | DONE | Stateless class. Inner record `CoinSelectionResult(selected, totalSelected, change)`. Public `select(List<BitcoinUtxo>, targetSats, UtxoSelectionStrategy)` dispatches to per-strategy methods. Throws `IllegalArgumentException` on insufficient funds. |
| `SMALLEST_FIRST` | DONE | Sort ascending by valueSats, accumulate |
| `LARGEST_FIRST` | DONE | Sort descending by valueSats, accumulate |
| `RANDOM` | DONE | Shuffle, accumulate |
| `OPTIMAL_CHANGE` | DONE | Try exact match within dust threshold (546 sats), then branch-and-bound (100k iterations max, suffix-sum pruning), fallback to LARGEST_FIRST |

### 6.2 TransactionBuildService

| Deliverable | Status | Detail |
|---|---|---|
| `TransactionBuildResult` record | DONE | `txid`, `rawHex`, `signed`, `selectedUtxos`, `totalInputSats`, `totalOutputSats`, `feeSats`, `changeSats`, `changeAddress` (nullable), `inputCount`, `outputCount` |
| `TransactionBuildService` | DONE | Constructor takes `CryptoService`, creates internal `CoinSelector`. Stateless — UTXOs passed as params, no DataSource dependency |
| `buildTransaction(available, outputs, config, changeAddress, signingKey, networkType)` | DONE | Iterative fee estimation (max 3 rounds): sum outputs → estimate fee → select coins → recalculate with actual input count → build via bitcoin4j `TransactionBuilder`. Null `signingKey` produces unsigned transaction. |
| `calculateFee(inputCount, outputCount, feePerKb)` | DONE | Size-based: `(TX_OVERHEAD + inputs×148 + outputs×34) × feePerKb / 1000` |
| Output mapping | DONE | `P2PKHOutputSpec` → `P2PKHLockBuilder`, `P2MSOutputSpec` → `P2MSLockBuilder` (mutable list for key sorting), `OPReturnOutputSpec` → `UnspendableDataLockBuilder` |
| RBF support | DONE | Sequence `0xFFFFFFFE` when `enableRBF=true`, else `0xFFFFFFFF` |
| Dust handling | DONE | Change below `minChangeAmountSats` absorbed into fee unless `forceChange=true`. OP_RETURN outputs bypass bitcoin4j dust checks |
| Sighash | DONE | `SigHashType.ALL \| SigHashType.FORKID` (BSV standard, 0x41) |

### 6.3 MultisigTransactionService

| Deliverable | Status | Detail |
|---|---|---|
| `MultisigTransactionService` | DONE | Constructor takes `TransactionBuildService`. Delegates transaction building. |
| `buildFundingTransaction(clientPubKeyHex, serverPubKeyHex, amountSats, available, config, changeAddress, signingKey, networkType)` | DONE | Creates `P2MSOutputSpec` (2-of-2) and delegates to `TransactionBuildService.buildTransaction` |
| `signMultisigInput(rawTx, inputIndex, privateKey, inputAmountSats)` | DONE | Parses raw tx, computes sighash via `SigHash.createHash`, signs with ECDSA, returns DER-encoded signature with sighash byte |

### 6.4 UtxoSplitService

| Deliverable | Status | Detail |
|---|---|---|
| `UtxoSplitService` | DONE | Stateless. Benford first-digit distribution: `{0.301, 0.176, 0.125, 0.097, 0.079, 0.067, 0.058, 0.051, 0.046}` |
| `generateBenfordSplit(totalSats, targetAddresses, minOutputSats)` | DONE | Returns `List<P2PKHOutputSpec>`. Distributes proportionally by Benford weights, enforces minimum output, adjusts rounding on first (largest) output. Output count = address count. |

### 6.5 LibSpiffy4j Wiring

| Deliverable | Status | Detail |
|---|---|---|
| `LibSpiffy4j` | DONE | Added `transactionBuildService()`, `multisigTransactionService()`, `utxoSplitService()` accessors |
| `LibSpiffy4jBuilder` | DONE | Instantiates `TransactionBuildService(cryptoService)`, `MultisigTransactionService(txBuildService)`, `UtxoSplitService()` in `build()` |

### 6.6 Tests

| Test | Status | Validates |
|---|---|---|
| `CoinSelectorTest` (11 tests) | DONE | All 4 strategies, exact match, insufficient funds, empty/null list, negative target, all UTXOs needed, OPTIMAL_CHANGE prefers minimal change |
| `TransactionBuildServiceTest` (10 tests) | DONE | Single P2PKH, multiple outputs, OP_RETURN, P2MS, change generation, dust absorbed into fee, unsigned tx (null key), fee calculation (1-in/1-out, 3-in/2-out), insufficient funds |
| `MultisigTransactionServiceTest` (2 tests) | DONE | 2-of-2 funding tx creates valid signed transaction, multisig input signing produces valid DER signature |
| `UtxoSplitServiceTest` (9 tests) | DONE | Sum equals total, output count matches addresses, min output enforced, addresses preserved, single address, empty addresses rejected, insufficient for min outputs, Benford distribution pattern (first > last), zero total rejected |

### 6.7 Design Decisions

- **No aggregate interaction**: Services return `TransactionBuildResult` with `selectedUtxos` so the caller can issue Reserve/MarkSpent commands
- **Stateless services**: All receive UTXOs as params — no DataSource, no wallet lookup
- **Signing key per-call**: Caller handles decryption from SecureStorage
- **BEEF output**: Deferred — requires merkle proof infrastructure (Phase 8+)

---

## Phase 7 — Invoice Aggregate **COMPLETE** (2026-03-14)

**Goal**: Full invoice lifecycle with multi-output support, payment matching, and expiration.

### 7.1 Commands

| Command | Status | Key Fields |
|---|---|---|
| `CreateInvoiceCommand` | DONE | invoiceId, walletId, addresses, amountSats, outputs (`List<InvoiceOutputSpec>`), description, expiresAt, metadata, replyTo |
| `MarkInvoicePaidCommand` | DONE | invoiceId, paymentTxid, amountReceivedSats, paymentAddress, replyTo |
| `CancelInvoiceCommand` | DONE | invoiceId, reason, replyTo |
| `ExpireInvoiceCommand` | DONE | invoiceId, replyTo |
| ~~`CheckInvoiceStatus`~~ | DROPPED | Status queries belong in the read model (CQRS) |

### 7.2 Events

| Event | Status | Key Fields |
|---|---|---|
| `InvoiceCreatedEvent` | DONE | invoiceId, walletId, addresses, amountSats, outputs, description, expiresAt, metadata, createdAt |
| `InvoicePaidEvent` | DONE | invoiceId, paymentTxid, amountReceivedSats, paymentAddress, paidAt |
| `InvoiceExpiredEvent` | DONE | invoiceId, expiredAt |
| `InvoiceCancelledEvent` | DONE | invoiceId, reason, cancelledAt |
| ~~`InvoiceStatusChanged`~~ | DROPPED | Redundant — status implicit in InvoiceCreated→PENDING, InvoicePaid→PAID, etc. |

### 7.3 Aggregate State: `InvoiceState`

| Field | Type | Status |
|---|---|---|
| invoiceId | String | DONE |
| created | boolean | DONE |
| walletId | String | DONE |
| addresses | `List<String>` | DONE |
| addressSet | `Set<String>` (derived from addresses) | DONE |
| amountSats | long | DONE |
| outputs | `List<InvoiceOutputSpec>` | DONE |
| description | String | DONE |
| status | InvoiceStatus | DONE |
| createdAt | Instant | DONE |
| expiresAt | Instant (nullable) | DONE |
| paidAt | Instant (nullable) | DONE |
| paymentTxid | String (nullable) | DONE |
| amountReceivedSats | long | DONE |
| metadata | `Map<String, Object>` | DONE |
| version | long | DONE |
| lastUpdatedAt | Instant | DONE |

### 7.4 Business Rules

| Rule | Status |
|---|---|
| Cannot pay a non-PENDING invoice (expired/cancelled/already-paid) | DONE |
| `amountReceivedSats` must be ≥ invoice `amountSats` | DONE |
| `paymentAddress` must be in `addressSet` (O(1) lookup) | DONE |
| Cannot create duplicate invoice (same invoiceId) | DONE |
| Cannot cancel a PAID invoice | DONE |
| Cannot cancel an already-cancelled invoice | DONE |
| Only PENDING invoices can expire | DONE |
| P2MS output validation (threshold ≤ totalKeys ≤ 16) | DONE (in `InvoiceOutputSpec` compact constructor) |
| OP_RETURN output validation (totalSize ≤ 99KB) | DONE (in `InvoiceOutputSpec` compact constructor) |

### 7.5 Invoice Projection & Read Model

| Deliverable | Status | Detail |
|---|---|---|
| `V006__create_invoice_read_models.sql` | DONE | V006 (V005 taken by secure_storage). Tables: `invoice_summary` (invoice_id PK, wallet_id, addresses JSONB, amount_sats, description, status, created_at, expires_at, paid_at, cancelled_at, cancel_reason, payment_txid, amount_received_sats, updated_at, metadata JSONB), `invoice_output` (invoice_id, output_index, output_type, address, amount_sats, label, spec_json JSONB, PK(invoice_id, output_index), FK invoice_id) |
| Indexes | DONE | `idx_invoice_summary_wallet_id`, `idx_invoice_summary_status`, partial index on `(status, expires_at) WHERE status='PENDING'` |
| `InvoiceReadModelStorage` | DONE | Stateless JDBC DAO. Write methods (Connection): upsertInvoiceSummary, upsertInvoiceOutput, updateInvoicePaid, updateInvoiceStatus, updateInvoiceCancelled. Read methods (DataSource): findInvoice, listInvoices (with status filter), findExpiredInvoices, findInvoiceOutputs |
| `InvoiceProjectionHandler` | DONE | Extends `JdbcHandler<EventEnvelope<InvoiceEvent>, SpiffyJdbcSession>`. Pattern matches on all 4 event types, delegates to storage |
| `InvoiceProjectionSetup` | DONE | ShardedDaemonProcess `"InvoiceProjection"`, tag `"invoice"`, `JdbcProjection.exactlyOnce()` |

| Query | Status | Returns |
|---|---|---|
| `findInvoice(invoiceId)` | DONE | `Optional<Invoice>` |
| `listInvoices(walletId, statusFilter, limit, offset)` | DONE | `List<Invoice>` (paginated, filtered) |
| `findExpiredInvoices(before)` | DONE | `List<Invoice>` (for expiration sweep) |
| `findInvoiceOutputs(invoiceId)` | DONE | `List<InvoiceOutputSpec>` (deserialized from spec_json JSONB) |

### 7.6 Aggregate

| Deliverable | Status | Detail |
|---|---|---|
| `InvoiceAggregate` | DONE | Extends `EventSourcedBehavior<InvoiceCommand, InvoiceEvent, InvoiceState>`. Entity type key `"InvoiceAggregate"`. Snapshots every 100 events, keep 2. Tag `"invoice"`. Dual-state command handler (pre/post creation) |
| `LibSpiffy4jBuilder` integration | DONE | `InvoiceProjectionSetup.init(system)` added after `WalletProjectionSetup.init(system)` |

### 7.7 Tests

| Test | Status | Validates |
|---|---|---|
| `InvoiceAggregateTest` (15 tests) | DONE | Create succeeds, reject double create, command before create rejected, pay succeeds, pay fails when expired/cancelled/insufficient/wrong-address, cancel succeeds, cancel fails when paid/already-cancelled, expire succeeds, expire fails when paid, multi-output invoice, recovery restores state |
| `InvoiceProjectionIntegrationTest` (7 tests) | DONE | Creation appears in read model, paid/cancelled/expired update read model, listInvoices with status filter, findExpiredInvoices, multi-output invoice outputs persisted. Uses Testcontainers PostgreSQL |

### 7.8 Design Decisions

- **No `CheckInvoiceStatus` command**: Status queries belong in the read model (CQRS)
- **No `InvoiceStatusChanged` event**: Redundant — status is implicit in the lifecycle events
- **`addressSet` for O(1) validation**: Derived from `addresses` list; both stored for snapshot fidelity
- **Expiration is externally triggered**: Aggregate has no internal timers. External process queries `findExpiredInvoices` and sends `ExpireInvoiceCommand`
- **`spec_json` JSONB column**: Full `InvoiceOutputSpec` serialized via Jackson `@JsonTypeInfo` for faithful reconstruction, plus denormalized columns for querying

### 7.9 Exit Criteria

- [x] Full invoice lifecycle functional (PENDING → PAID/EXPIRED/CANCELLED)
- [x] Multi-output types validated and persisted correctly
- [x] Payment matching enforces all business rules
- [x] Read model projection with exactly-once semantics
- [x] 15 unit tests + 7 integration tests all passing

---

## Phase 8 — SPV Validation **COMPLETE** (2026-03-14)

**Goal**: BEEF parsing, BUMP merkle proof verification, block header chain management — matching Dart `SpvService`.

### 8.1 BUMP Records & Parser

| Deliverable | Status | Detail |
|---|---|---|
| `BumpLeaf` record | DONE | `offset`, `duplicate`, `isTxid`, `hash` (byte[32] nullable). Compact constructor: validates length, clones hash. Defensive copies on accessor. |
| `BumpLevel` record | DONE | `leaves` (List<BumpLeaf>). Compact constructor: `List.copyOf()`. |
| `Bump` class | DONE | `blockHeight` (long), `path` (List<BumpLevel>). Immutable via `List.copyOf()`. |
| `Bump.parse(byte[])` | DONE | Full BEEF-embedded parse using `VarInt(byte[], offset)` + `getOriginalSizeInBytes()`. |
| `Bump.parse(byte[], offset, int[] bytesConsumed)` | DONE | Parse from offset, report consumed bytes via out-param. |
| `Bump.serialize()` | DONE | Round-trip matches original bytes. |
| `Bump.computeMerkleRoot(txid)` | DONE | Walks path from txid leaf to root using `Sha256Hash.hashTwice(left, right)`. All hashes in internal (LE) format. |
| `Bump.validateMerklePath(txid)` | DONE | Returns true if merkle path is structurally valid. |

### 8.2 BEEF Parser

| Deliverable | Status | Detail |
|---|---|---|
| `Beef` class | DONE | `version`, `bumps` (List<Bump>), `transactions` (List<byte[]>), `hasMerkle` (List<Boolean>), `bumpIndex` (List<Integer>). |
| `Beef.parse(byte[])` / `parse(String hex)` | DONE | Validates 0100BEEF magic. Offset-based BUMP parsing, then `Transaction.fromStream(InputStream)` for tx parsing. |
| `Beef.serialize()` | DONE | Round-trip matches original bytes. |
| `Beef.calculateTxid(rawTx)` | DONE | Static: double-SHA256 via `Sha256Hash.hashTwice()`, internal format. |
| `Beef.findTransactionByTxid(txid)` | DONE | Linear scan with txid comparison. |
| `Beef.validate()` | DONE | Validates all proven transactions' merkle paths. |
| `Beef.validateTransaction(txid)` | DONE | Validates specific transaction's BUMP proof. |
| `Beef.validateTransactionWithBlockHeader(txid, header)` | DONE | Compares computed merkle root against block header's merkle root. |

### 8.3 Block Header

| Deliverable | Status | Detail |
|---|---|---|
| `BlockHeader` record | DONE | `version`, `prevBlockHash`, `merkleRoot`, `timestamp`, `bits`, `nonce`. Defensive copies in compact constructor and accessors. |
| `BlockHeader.parse(byte[])` / `parse(byte[], offset)` | DONE | Parses 80-byte block header. |
| `BlockHeader.serialize()` | DONE | 80-byte output. |
| `BlockHeader.getHash()` | DONE | Double-SHA256 of serialized, internal format. |
| `BlockHeader.getHashHex()` | DONE | Reversed hex (display format). |

### 8.4 Block Header Chain

| Deliverable | Status | Detail |
|---|---|---|
| `BlockHeaderChain` class | DONE | In-memory store, max 2016 headers. `Map<String, BlockHeader>` by hash, `Map<Integer, String>` height→hash. |
| `addHeader(height, header)` | DONE | Add with LRU eviction when at capacity. |
| `getHeader(height)` / `getHeaderByHash(hash)` | DONE | Lookup by height or display-format hash. |
| `getChainHeight()` | DONE | Tracks highest added height. |
| `validateContinuity(fromHeight, toHeight)` | DONE | Validates headers exist and prevBlockHash chains match. |

### 8.5 Tests

| Test | Status | Validates |
|---|---|---|
| `BumpTest` (10 tests) | DONE | Parse blockHeight=814435, treeHeight=7. Leaf counts. isTxid leaf at offset 21. Round-trip serialize. Merkle root computation. Deterministic results. Valid/invalid txid paths. Offset parsing. Empty data error. |
| `BeefTest` (16 tests) | DONE | Parse version/bumps/txs counts. BUMP blockHeight. hasMerkle flags. Round-trip serialize. Txid calculation. Tx lookup by txid. Validate proven/unproven tx. Full validation. BlockHeader merkle root match/mismatch. Parse errors (empty, bad magic, truncated). |
| `BlockHeaderChainTest` (11 tests) | DONE | Add/get single header. Unknown height returns null. Hash lookup. Chain height tracking. Eviction beyond MAX_HEADERS. Overwrite same height. Connected chain continuity. Broken chain detection. Missing height detection. Single-height continuity. Initial height -1. |

### 8.6 Exit Criteria

- BEEF/BUMP parsing round-trips match original bytes (Dart test vectors)
- Merkle root computation uses internal (LE) format throughout — no reversal needed (matches design decision)
- Block header chain validates continuity via prevBlockHash chaining
- 37 tests, 0 failures

---

## Phase 9 — Payment Channel Aggregate **COMPLETE** (2026-03-14)

**Goal**: Full nLockTime-based payment channel lifecycle — funding, refund, off-chain payments, settlement.

### 9.1 Commands

| Command | Key Fields | Phase |
|---|---|---|
| `RequestChannel` | channelId, walletId, clientPeerId, clientPubKeyHex, clientAddressB58, derivationIndex, fundingAmountSats, lockTimeUnix | Negotiation |
| `AcceptChannel` | channelId, serverPeerId, serverPubKeyHex, serverAddressB58 | Negotiation |
| `RejectChannel` | channelId, reason | Negotiation |
| `RecordServerAcceptance` | channelId, serverPeerId, serverPubKeyHex, serverAddressB58 | Negotiation |
| `RequestRefundSignature` | channelId, refundTxHex | Funding |
| `ProvideRefundSignature` | channelId, refundServerSigHex | Funding |
| `OpenChannel` | channelId, fundingTxId, fundingTxHex, fundingOutputIndex, fundingAncestorTxids | Opening |
| `RecordPayment` | channelId, amountSats, newClientBalanceSats, newServerBalanceSats, sequenceNumber, paymentTxHex, paymentTxId, clientSignatureHex, purpose, invoiceId | Open |
| `AcknowledgePayment` | channelId, fullySignedPaymentTxHex, serverSignatureHex | Open |
| `CloseChannel` | channelId, settlementTxHex | Closing |
| `FinalizeClose` | channelId, settlementTxId | Closing |
| `ClaimRefund` | channelId | Expired |

### 9.2 Events

`ChannelRequested`, `ChannelAccepted`, `ChannelRejected`, `ServerAcceptanceRecorded`, `RefundBuilt`, `RefundCountersigned`, `ChannelOpened`, `PaymentRecorded`, `PaymentAcknowledged`, `ChannelClosing`, `ChannelClosed`, `RefundClaimed`

### 9.3 State Machine

```
                    ┌──► REJECTED
                    │
NEGOTIATING ───► FUNDING ───► OPENING ───► OPEN ───► CLOSING ───► CLOSED
                                             │
                                             └──► EXPIRED (lockTime passed, refund claimable)
                                             │
                                             └──► FAILED (error at any point)
```

### 9.4 Channel Transactions

| Transaction | Purpose | Construction |
|---|---|---|
| **T1** (Funding) | 2-of-2 multisig output | `TransactionBuildService.buildFundingTransaction()` |
| **T2** (Refund) | Time-locked return to client | nLockTime set to `lockTimeUnix`, spends T1 output |
| **T3** (Payment) | Balance update, incrementing nSequence | Each payment increments sequence number; highest sequence wins |
| **Settlement** | Final balance distribution | Cooperative close, broadcast immediately |

### 9.5 Cross-Aggregate Coordination

Payment channels interact with the wallet aggregate for UTXO management:

- **Funding**: Channel requests UTXO reservation from wallet → wallet emits `UtxoReserved` → channel proceeds with T1
- **Settlement**: Channel close → wallet receives settlement UTXOs → wallet emits `UtxoReceived`

This coordination is implemented via Pekko persistent actors as process managers, reacting to events from both aggregates through projections.

### 9.6 Channel Projection & Read Model

| Migration | Tables |
|---|---|
| (new migration) | `payment_channel` (channel_id, wallet_id, role, state, client_peer_id, server_peer_id, funding_amount_sats, lock_time_unix, client_balance_sats, server_balance_sats, funding_tx_id, latest_sequence_number, created_at, closed_at, error_message) |

| Query | Returns |
|---|---|
| `getChannel(channelId)` | Full channel state |
| `listChannels(walletId, stateFilter)` | Filtered channel list |
| `getOpenChannels(walletId)` | Channels in OPEN state |

### 9.7 PaymentChannelBuilder

| Method | Detail |
|---|---|
| `buildFundingTransaction(clientPubKey, serverPubKey, amount, selectedUtxos)` | Creates T1 with 2-of-2 multisig output |
| `buildRefundTransaction(fundingTxId, fundingOutputIndex, multisigScript, clientAddress, lockTimeUnix)` | Creates T2 with nLockTime |
| `buildPaymentTransaction(fundingTxId, fundingOutputIndex, multisigScript, clientAddress, serverAddress, clientAmount, serverAmount, sequenceNumber)` | Creates T3 with nSequence |
| `signMultisigInput(tx, inputIndex, privateKey, redeemScript)` | Returns `MultisigSignatureResult` |

### 9.8 Tests

| Test | Validates |
|---|---|
| `ChannelNegotiationTest` | Request → accept. Request → reject. Duplicate request rejected. |
| `FundingFlowTest` | Refund built → countersigned → channel opened with funding tx |
| `PaymentFlowTest` | Sequential payments with incrementing sequence. Balance tracking correct. |
| `SettlementTest` | Cooperative close. Refund claim after lockTime expiry. |
| `StateMachineTest` | Invalid transitions rejected (e.g., can't pay while NEGOTIATING) |
| `WalletCoordinationTest` | Funding reserves wallet UTXOs. Settlement creates wallet UTXOs. |
| `ChannelProjectionTest` | Events update read model correctly |

### 9.9 Exit Criteria

- Full channel lifecycle from negotiation to settlement
- T1/T2/T3 transactions construct correctly and verify via bitcoin4j script interpreter
- Cross-aggregate coordination works (channel ↔ wallet UTXO management)
- State machine enforces valid transitions

---

## Phase 10 — Network Integration

**Goal**: ARC service client, block header CDN sync, transaction broadcast and status tracking.

### 10.1 ArcService

| Method | Detail |
|---|---|
| `submitTransaction(txHex)` | POST to ARC. Returns `ArcSubmitResponse` (txid, status). |
| `submitTransaction(txHex, callbackUrl)` | With async callback registration |
| `queryTransaction(txid)` | GET status. Returns `ArcTransactionResponse` (status, blockHeight, merkle path). |
| `getMerkleProof(txid)` | Returns `MerkleProofData` (bump, blockHeight). |

ARC transaction status enum: `QUEUED`, `RECEIVED`, `STORED`, `ANNOUNCED_TO_NETWORK`, `REQUESTED_BY_NETWORK`, `SENT_TO_NETWORK`, `ACCEPTED_BY_NETWORK`, `SEEN_IN_ORPHAN_MEMPOOL`, `SEEN_ON_NETWORK`, `DOUBLE_SPEND_ATTEMPTED`, `MINED_IN_STALE_BLOCK`, `REJECTED`, `MINED`

Uses `java.net.http.HttpClient` with virtual threads. Configurable via `ArcServiceConfig`.

### 10.2 CdnHeaderSyncService

| Method | Detail |
|---|---|
| `synchronize()` | Fetch manifest → download chunks → validate SHA-256 → parse headers → import to `BlockHeaderChain`. Returns `CdnSyncResult` (headersImported, finalHeight, elapsed). |

Phases: `FETCHING_MANIFEST`, `DOWNLOADING_CHUNKS`, `VALIDATING_CHUNKS`, `IMPORTING_CHUNKS`

Features: sequential chunk processing (8MB peak memory), optional disk caching for resumability, progress reporting.

### 10.3 TransactionImportService

| Method | Detail |
|---|---|
| `importTransaction(txid)` | Fetch raw tx from ARC → fetch merkle proof → convert to BUMP → validate SPV → return `ImportedTransaction` |
| `importTransactionBatch(txids)` | Batch import with parallel fetch |

### 10.4 AddressDiscoveryService

| Method | Detail |
|---|---|
| `discoverAddresses(hdPublicKey, networkType, gapLimit, onProgress)` | BIP44 gap limit algorithm. Scans receiving (`m/44'/coinType'/0'/0/x`) and change (`m/44'/coinType'/0'/1/x`) chains. Default gap limit: 20 consecutive unused addresses. Returns `AddressDiscoveryResult` (usedAddresses, totalTransactions, lastCheckedIndex). |

### 10.5 ChainTipTracker

| Method | Detail |
|---|---|
| `trackTransaction(txid, onConfirmation)` | Monitor confirmation count relative to network height. Emit `TransactionConfirmationUpdate` (txid, blockHeight, confirmations, isConfirmed) when confirmations change. |
| Properties | `networkHeight`, `isNetworkSynced`, `currentChainTip` |
| Confirmation threshold | 6 blocks (configurable) |

### 10.6 Wallet Import Integration

Combines `AddressDiscoveryService` with the wallet aggregate:

1. Discover used addresses via gap limit scan
2. For each discovered address: `RegisterDiscoveredAddress` command
3. Optionally: `TransactionImportService.importTransactionBatch()` per address
4. Result: Wallet fully recovered with addresses, UTXOs, and transaction history

### 10.7 Tests

| Test | Validates |
|---|---|
| `ArcServiceTest` | Submit, query, merkle proof retrieval (mock HTTP) |
| `CdnSyncTest` | Manifest parse, chunk download, header import (mock HTTP) |
| `TransactionImportTest` | Full flow: fetch → BUMP → SPV validate → import |
| `AddressDiscoveryTest` | Gap limit algorithm: discovers used addresses, stops at gap, scans both chains |
| `ChainTipTrackerTest` | Confirmation count updates. Threshold detection. |
| `WalletImportIntegrationTest` | End-to-end: XPRIV → discover addresses → import transactions → wallet recovered |

### 10.8 Exit Criteria

- ARC client handles all 13 transaction statuses
- CDN sync imports headers with sequential processing and caching
- Address discovery matches Dart gap limit algorithm
- Transaction import validates SPV before recording

---

## Phase 11 — Operations & Observability

**Goal**: Production-ready metrics, health checks, debugging tools, and snapshot tuning.

### 11.1 Pekko Telemetry → Micrometer Bridge

| Metric | Source | Micrometer Name |
|---|---|---|
| Projection lag (events) | Pekko Projection telemetry | `spiffy.projection.lag.events` |
| Projection lag (duration) | Pekko Projection telemetry | `spiffy.projection.lag.duration` |
| Journal write latency | Pekko persistence telemetry | `spiffy.journal.write.duration` |
| Snapshot write latency | Pekko persistence telemetry | `spiffy.snapshot.write.duration` |
| Active aggregates | Cluster sharding statistics | `spiffy.aggregates.active` |
| Command processing time | Custom timer around command dispatch | `spiffy.command.duration` (tagged by command type) |
| Command failure count | Custom counter | `spiffy.command.errors` (tagged by command type, error type) |
| UTXO count by status | Read model query | `spiffy.utxo.count` (tagged by status) |

### 11.2 Health Indicator

```java
public class DefaultSpiffyHealthIndicator implements SpiffyHealthIndicator {
    boolean isEventStoreReachable();      // JDBC journal connectivity
    long projectionLagEvents();           // events behind write side
    Duration projectionLagDuration();     // wall-clock lag
    int activeAggregates();              // entities in cluster sharding
}
```

### 11.3 Snapshot Tuning

| Aggregate | Snapshot Frequency | Retention | Rationale |
|---|---|---|---|
| WalletAggregate | Every 100 events | Keep 2 | Wallets accumulate many UTXO events; fast recovery critical |
| InvoiceAggregate | Every 50 events | Keep 2 | Lower event volume; smaller state |
| PaymentChannelAggregate | Every 25 events | Keep 2 | Small state but latency-sensitive (live payments) |

### 11.4 CBOR Event Inspector (CLI)

Command-line tool for debugging event store contents:

```bash
# Dump events for a wallet
spiffy-inspect events --wallet-id=abc123 --format=json

# Dump specific event by sequence number
spiffy-inspect event --persistence-id=wallet-abc123 --seq=42

# Show aggregate state at a point in time
spiffy-inspect state --wallet-id=abc123 --at-seq=100
```

Reads directly from the PostgreSQL journal, deserializes CBOR → JSON for display.

### 11.5 Tests

| Test | Validates |
|---|---|
| `MetricsBridgeTest` | Pekko telemetry events produce Micrometer metrics |
| `HealthIndicatorTest` | Reports correct values when healthy. Detects journal unreachable. |
| `SnapshotTuningTest` | Aggregates snapshot at configured intervals. Recovery uses latest snapshot. |

### 11.6 Exit Criteria

- All metrics accessible via host's MeterRegistry
- Health indicator reports accurate lag and connectivity
- Snapshot intervals tuned per aggregate type
- CBOR inspector can dump and display events as JSON

---

## Phase 12 — Integration Testing & Hardening

**Goal**: End-to-end scenarios, edge cases, failure modes, and performance validation.

### 12.1 End-to-End Scenarios

| Scenario | Flow |
|---|---|
| **Wallet lifecycle** | Create HD wallet → generate addresses → receive UTXOs → confirm → build transaction → broadcast → confirm outgoing |
| **Invoice payment** | Create invoice (P2PKH + OP_RETURN) → receive payment → match → mark paid |
| **Payment channel** | Negotiate → fund → make 100 payments → cooperative close → wallet receives settlement |
| **Wallet recovery** | Create wallet → generate addresses → receive UTXOs → import from XPRIV → verify identical state |
| **Watch-only monitoring** | Create XPUB wallet → generate addresses → receive UTXOs → confirm balances → reject signing attempt |
| **Multi-wallet isolation** | Create two wallets → interleave commands → verify no cross-contamination |

### 12.2 Failure Mode Testing

| Scenario | Expected Behavior |
|---|---|
| Aggregate crash mid-command | Actor restarts, replays from journal, no duplicate events |
| Projection crash mid-event | Resumes from last committed offset, no missing or duplicate read model entries |
| PostgreSQL connection loss | Commands fail with clear error. Recovery on reconnect without data loss. |
| Duplicate command submission | Idempotent handling (same result, no duplicate events) |
| Concurrent reservation conflict | Only one reservation succeeds for a given UTXO |
| Journal write conflict (sequence number) | Pekko rejects, actor replays to resolve |
| Expired UTXO reservation | CleanupExpiredReservations releases correctly |

### 12.3 Performance Baselines

| Metric | Target | Method |
|---|---|---|
| Command throughput (single wallet) | ≥500 commands/sec | Sequential commands to one aggregate |
| Command throughput (multi-wallet) | ≥5,000 commands/sec | Parallel commands across 100 wallets |
| Projection lag (steady state) | <100ms | Measure under sustained command load |
| Aggregate recovery time (1K events) | <200ms | Stop and restart aggregate |
| Aggregate recovery time (100K events, with snapshot) | <500ms | With snapshot every 100 events |

### 12.4 Exit Criteria

- All end-to-end scenarios pass against PostgreSQL
- Failure modes degrade gracefully (no data loss, no corruption)
- Performance baselines met on reference hardware

---

## Dependency Summary

```
Phase 1:  Scaffolding & Pekko Foundation
Phase 2:  Domain Models ─────────────────────────────────────┐
Phase 3:  Wallet Aggregate ──────────────────────────────┐   │
Phase 4:  Wallet Projection ◄───── Phase 3               │   │
Phase 5:  CryptoService & SecureStorage ◄───── Phase 3   │   │
Phase 6:  Transaction Building ◄───── Phase 3, 4, 5      │   │
Phase 7:  Invoice Aggregate ◄───── Phase 2                │   │
Phase 8:  SPV Validation ◄───── Phase 2                   │   │
Phase 9:  Payment Channels ◄───── Phase 3, 6, 7           │   │
Phase 10: Network Integration ◄───── Phase 5, 6, 8        │   │
Phase 11: Operations ◄───── Phase 3, 4, 7, 9              │   │
Phase 12: Integration Testing ◄───── All                   │   │
                                                           │   │
Legend:  ◄───── depends on                                 │   │
         Phases 3-5 can proceed in parallel after Phase 2  ┘   │
         Phases 7, 8 can proceed in parallel after Phase 2 ────┘
```

### Parallelization Opportunities

- **After Phase 2**: Phases 3, 7, and 8 can begin in parallel (wallet aggregate, invoice aggregate, and SPV are independent until integration)
- **After Phase 3**: Phases 4 and 5 can proceed in parallel (projection and crypto are independent)
- **After Phase 6**: Phase 9 (payment channels) and Phase 10 (network) can overlap if different developers

---

## Dart Feature Parity Checklist

| libspiffy Feature | Roadmap Phase | Notes |
|---|---|---|
| Wallet creation (mnemonic, WIF, XPRIV, XPUB) | Phase 3 | All four modes |
| Wallet configuration & metadata | Phase 3 | |
| Address derivation (BIP44) | Phase 3 | Via bitcoin4j |
| Address labeling & metadata | Phase 3 | |
| Change vs. receiving address separation | Phase 3 | BIP44 chain 0 vs 1 |
| UTXO lifecycle (PENDING → AVAILABLE → RESERVED → SPENT) | Phase 3 | Full state machine |
| UTXO reservation with expiration & priority | Phase 3 | |
| Batch UTXO operations | Phase 3 | Reserve/release multiple |
| Wallet projection & read models | Phase 4 | Pekko Projection |
| Balance calculation (confirmed/unconfirmed/reserved/available) | Phase 4 | |
| AES-256-GCM key encryption | Phase 5 | Bouncy Castle |
| HKDF key derivation | Phase 5 | |
| Transaction signing | Phase 5 | Via bitcoin4j |
| Coin selection (4 strategies) | Phase 6 | DONE |
| Fee calculation | Phase 6 | DONE |
| Transaction building with change | Phase 6 | DONE — via bitcoin4j |
| Dust prevention | Phase 6 | DONE |
| Multisig transaction building | Phase 6 | DONE — 2-of-2 for channels |
| UTXO Benford splitting | Phase 6 | DONE |
| BEEF output | Phase 6 | Deferred — needs merkle proofs |
| Invoice creation with expiration | Phase 7 | DONE |
| Multi-output invoices (P2PKH, P2MS, OP_RETURN) | Phase 7 | DONE |
| Invoice status lifecycle | Phase 7 | DONE |
| Payment matching & validation | Phase 7 | DONE |
| BEEF parsing | Phase 8 | DONE |
| BUMP merkle proof verification | Phase 8 | DONE |
| Block header chain & validation | Phase 8 | DONE |
| Chain reorganization handling | Phase 8 | Deferred — continuity validation done |
| Payment channel negotiation | Phase 9 | DONE |
| 2-of-2 multisig funding (T1) | Phase 9 | DONE — via PaymentChannelBuilder |
| nLockTime refund (T2) | Phase 9 | DONE — refund build/countersign flow |
| Off-chain payments with nSequence (T3) | Phase 9 | DONE — balance conservation + sequence monotonicity |
| Cooperative & timeout settlement | Phase 9 | DONE — CloseChannel/FinalizeClose/ClaimRefund |
| Channel ↔ wallet UTXO coordination | Phase 9 | Deferred — process manager pattern (Phase 11) |
| ARC service client | Phase 10 | |
| ARC callback integration | Phase 10 | |
| Block header CDN sync | Phase 10 | |
| Merkle proof retrieval | Phase 10 | |
| Address discovery (gap limit) | Phase 10 | |
| Transaction import with SPV validation | Phase 10 | |
| Chain tip tracking & confirmation monitoring | Phase 10 | |
| Wallet import/recovery from XPRIV | Phase 10 | Combines discovery + import |
| Micrometer metrics | Phase 11 | |
| Health indicator | Phase 11 | |
| Snapshot tuning | Phase 11 | |
| Event inspection tooling | Phase 11 | CLI for CBOR → JSON |
