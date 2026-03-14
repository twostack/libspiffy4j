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

## Phase 2 — Domain Models & Value Objects

**Goal**: All domain value types exist as Java records with serialization, validation, and equality semantics.

### 2.1 Core Models

| Record | Key Fields | Notes |
|---|---|---|
| `BitcoinUtxo` | txid, vout, value (`Coin`), scriptPubKey, address, status, blockHeight, confirmations, reservedByTxId, reservationExpiresAt, reservationPriority, reservationReason, derivationIndex, createdAt, updatedAt | Status enum: `PENDING`, `AVAILABLE`, `RESERVED`, `SPENT`. Methods: `isReservationExpired()`, `isEffectivelyAvailable()`, `reservationTimeRemaining()` |
| `AddressMetadata` | address, label, derivationIndex, isChange, transactionCount, lastActivityDate, publicKeyHex | |
| `WalletConfig` | walletId, name, rootAddress, walletType, networkType, metadata, createdAt | WalletType enum: `HD`, `WIF`, `XPRIV`, `XPUB` |
| `BitcoinTransaction` | txid, rawHex, blockHeight, blockHash, timestamp, totalInputSats, totalOutputSats, fee, numInputs, numOutputs, txVersion, txLockTime, status, sendingAddresses, receivingAddresses, walletReceivedSats, transactionType | |
| `TransactionAddressLink` | walletId, transactionId, address, linkType (`SENT`/`RECEIVED`), amountSats | |
| `Invoice` | invoiceId, walletId, addresses, amount, outputs, description, status, createdAt, expiresAt, paidAt, paymentTxid, amountReceived, metadata | Status enum: `PENDING`, `PAID`, `EXPIRED`, `CANCELLED` |
| `InvoiceOutputSpec` | Sealed interface with: `P2PKHOutputSpec(address, amount, label)`, `P2MSOutputSpec(publicKeys, threshold, amount, label)`, `OPReturnOutputSpec(dataChunks, separateOutputs)` | Validation: P2MS threshold ≤ totalKeys ≤ 16; OP_RETURN totalSize ≤ 99KB |
| `PaymentChannel` | channelId, walletId, role, clientPeerId, serverPeerId, clientPubKeyHex, serverPubKeyHex, clientAddressB58, serverAddressB58, fundingAmountSats, lockTimeUnix, state, clientBalanceSats, serverBalanceSats, fundingTxId, fundingTxHex, fundingOutputIndex, refundTxHex, refundClientSigHex, refundServerSigHex, latestSequenceNumber, latestPaymentTxHex, latestPaymentTxId, settlementTxId, fundingAncestorTxids, context, createdAt, closedAt, errorMessage | State enum: `NEGOTIATING`, `FUNDING`, `OPENING`, `OPEN`, `CLOSING`, `CLOSED`, `EXPIRED`, `FAILED`. Role enum: `CLIENT`, `SERVER` |
| `ChannelUpdate` | channelId, newServerBalanceSats, sequenceNumber, clientSignature, timestamp, description | |
| `TransactionBuildResult` | transaction, selectedInputs, totalInput, totalOutput, fee, changeAmount, readyForSigning, transactionHex, beef | |
| `ChannelTransactionResult` | transaction, transactionHex, txid, multisigScript, fee | |
| `PaymentChannelResult` | success, channel, transactionHex, beefHex, error | |

### 2.2 Configuration Models

| Record | Key Fields | Notes |
|---|---|---|
| `ArcServiceConfig` | baseUrl, apiKey, defaultCallbackUrl | Factory methods: `taalTestnet(apiKey)`, `taalMainnet()`, `custom(baseUrl)` |
| `TransactionBuildConfig` | feePerKb (default 100), selectionStrategy, minChangeAmount (546), forceChange, enableRBF, performSanityChecks | Strategy enum: `SMALLEST_FIRST`, `LARGEST_FIRST`, `RANDOM`, `OPTIMAL_CHANGE`. Presets: `standard()`, `partial()` |
| `CdnHeaderSyncConfig` | manifestUrl, cacheDirectory, chunkDownloadTimeout, validationEnabled | |

### 2.3 Tests

- CBOR round-trip for every record (including `byte[]` fields like txid, scriptPubKey)
- Validation tests for `InvoiceOutputSpec` (P2MS threshold, OP_RETURN size limits)
- `BitcoinUtxo` state transition methods (reservation expiry, effective availability)
- Sealed interface exhaustiveness for `InvoiceOutputSpec` variants

### 2.4 Exit Criteria

- All domain types compile, serialize/deserialize via CBOR, pass validation tests
- Records are immutable, use `equals`/`hashCode` correctly

---

## Phase 3 — Wallet Aggregate

**Goal**: Create wallets (all four modes), generate addresses, full UTXO lifecycle, transaction recording. The core wallet is functional end-to-end.

### 3.1 Commands

| Command | Key Fields | Validation |
|---|---|---|
| `CreateWallet` | walletId, name, mnemonic/wif/xpriv/xpub, passphrase, networkType, metadata | Exactly one key source. Mnemonic: BIP39 valid. |
| `UpdateWalletConfiguration` | walletId, newName, newMetadata | Wallet must exist |
| `GenerateAddress` | walletId, label, purpose, includePublicKey | HD/XPRIV/XPUB only (not WIF) |
| `RegisterDiscoveredAddress` | walletId, address, derivationIndex, publicKeyHex, isChange, transactionCount | For address discovery import |
| `UpdateAddressLabel` | walletId, address, newLabel | Address must exist |
| `ReceiveUtxo` | walletId, txid, vout, value, scriptPubKey, address, blockHeight | |
| `MarkUtxoAvailable` | walletId, txid, vout | UTXO must be PENDING |
| `UpdateUtxoConfirmations` | walletId, txid, vout, confirmations, blockHeight | Transitions PENDING → AVAILABLE at ≥6 |
| `ReserveUtxo` | walletId, txid, vout, reservedByTxId, duration, priority, reason | UTXO must be AVAILABLE |
| `ReserveUtxos` | walletId, utxoRefs (list), reservedByTxId, duration, priority, reason | Batch reservation |
| `ReleaseUtxo` | walletId, txid, vout | UTXO must be RESERVED |
| `ReleaseUtxos` | walletId, utxoRefs (list) | Batch release |
| `RenewUtxoReservation` | walletId, txid, vout, newDuration, newReason | UTXO must be RESERVED |
| `CleanupExpiredReservations` | walletId | Releases all expired |
| `SpendUtxo` | walletId, txid, vout, spendingTxId | |
| `RecordOutgoingTransaction` | walletId, txid, rawHex, outputs, fee | |
| `RecordImportedTransaction` | walletId, txid, rawHex, blockHeight, bump | |
| `ConfirmTransaction` | walletId, txid, blockHeight, blockHash | |

### 3.2 Events

| Event | Triggered By |
|---|---|
| `WalletCreated` | CreateWallet |
| `WalletConfigurationUpdated` | UpdateWalletConfiguration |
| `AddressGenerated` | GenerateAddress |
| `AddressDiscovered` | RegisterDiscoveredAddress |
| `AddressLabelUpdated` | UpdateAddressLabel |
| `UtxoReceived` | ReceiveUtxo |
| `UtxoMarkedAvailable` | MarkUtxoAvailable |
| `UtxoConfirmationUpdated` | UpdateUtxoConfirmations |
| `UtxoReserved` | ReserveUtxo / ReserveUtxos |
| `UtxoReleased` | ReleaseUtxo / ReleaseUtxos / CleanupExpiredReservations |
| `UtxoReservationRenewed` | RenewUtxoReservation |
| `UtxoSpent` | SpendUtxo |
| `TransactionRecorded` | RecordOutgoingTransaction |
| `TransactionImported` | RecordImportedTransaction |
| `TransactionConfirmed` | ConfirmTransaction |

### 3.3 Aggregate State: `WalletState`

| Field | Type |
|---|---|
| walletId | String |
| name | String |
| rootAddress | String |
| isCreated | boolean |
| networkType | NetworkType |
| walletType | WalletType |
| utxos | `Map<String, BitcoinUtxo>` (key: `txid:vout`) |
| addresses | `Map<String, AddressMetadata>` |
| nextDerivationIndex | int |
| confirmedBalance | long |
| unconfirmedBalance | long |
| reservedBalance | long |
| metadata | `Map<String, String>` |
| version | long |
| timestamp | Instant |

### 3.4 Wallet Creation Modes

| Mode | Key Source | Address Derivation | Can Sign |
|---|---|---|---|
| HD (mnemonic) | BIP39 mnemonic + optional passphrase | BIP44: `m/44'/coinType'/0'/0/index` | Yes |
| WIF | Single private key in WIF format | Single address only | Yes |
| XPRIV | Extended private key | Full BIP44 derivation | Yes |
| XPUB | Extended public key (watch-only) | Full BIP44 derivation | No |

For HD and XPRIV modes, the root address is derived from the first key (`m/44'/coinType'/0'/0/0`). CoinType: 236 (testnet), 0 (mainnet).

### 3.5 Snapshot Configuration

```java
@Override
public RetentionCriteria retentionCriteria() {
    return RetentionCriteria.snapshotEvery(100, 2);  // snapshot every 100 events, keep 2
}
```

### 3.6 Tests

| Test | Validates |
|---|---|
| `CreateWalletTest` | All four modes. Mnemonic validation. Duplicate creation rejected. Root address derived correctly. |
| `AddressGenerationTest` | Sequential derivation index. Label assignment. WIF mode rejects generation. XPUB mode generates correctly. |
| `UtxoLifecycleTest` | Full state machine: receive → confirm → reserve → spend. Reservation expiry. Batch reserve/release. Priority handling. |
| `TransactionRecordingTest` | Outgoing, imported, confirmed. Transaction-address links created. |
| `WalletSnapshotTest` | State survives snapshot + replay. Aggregate loads from snapshot + subsequent events. |
| `WalletRecoveryTest` | Actor restarts and recovers full state from journal. |
| `ConcurrencyTest` | Two commands to same wallet serialize correctly via actor mailbox. Commands to different wallets execute concurrently. |

### 3.7 Exit Criteria

- All wallet creation modes functional
- Full UTXO lifecycle with reservation semantics
- Aggregate persists to PostgreSQL journal, recovers from snapshots
- Concurrent command safety verified

---

## Phase 4 — Wallet Projection & Read Models

**Goal**: Wallet state queryable via denormalized read models, updated in real-time by Pekko Projection.

### 4.1 Projection Handler: `WalletProjectionHandler`

Consumes all wallet events and updates PostgreSQL read model tables:

| Event | Read Model Update |
|---|---|
| `WalletCreated` | Insert into `wallet_summary` |
| `WalletConfigurationUpdated` | Update `wallet_summary` (name, metadata) |
| `AddressGenerated` / `AddressDiscovered` | Insert into `wallet_address` |
| `AddressLabelUpdated` | Update `wallet_address` label |
| `UtxoReceived` | Insert into `wallet_utxo`, update `wallet_summary` balances |
| `UtxoMarkedAvailable` / `UtxoConfirmationUpdated` | Update `wallet_utxo` status/confirmations, recalculate `wallet_summary` balances |
| `UtxoReserved` / `UtxoReleased` / `UtxoReservationRenewed` | Update `wallet_utxo` reservation fields, update `wallet_summary` reserved balance |
| `UtxoSpent` | Update `wallet_utxo` status, update `wallet_summary` balances |
| `TransactionRecorded` / `TransactionImported` | Insert into `wallet_transaction`, insert `transaction_address_link` rows |
| `TransactionConfirmed` | Update `wallet_transaction` block height/hash |

### 4.2 SQL Migrations

| File | Tables |
|---|---|
| `V004__create_wallet_read_models.sql` | `wallet_summary` (id, name, root_address, wallet_type, network_type, confirmed_balance, unconfirmed_balance, reserved_balance, address_count, utxo_count, created_at, metadata) |
| | `wallet_address` (wallet_id, address, label, derivation_index, is_change, transaction_count, last_activity, public_key_hex) |
| | `wallet_utxo` (wallet_id, txid, vout, value_sats, script_pubkey, address, status, block_height, confirmations, reserved_by_tx_id, reservation_expires_at, reservation_priority, reservation_reason, derivation_index, created_at, updated_at) |
| | `wallet_transaction` (wallet_id, txid, raw_hex, block_height, block_hash, timestamp, total_input_sats, total_output_sats, fee, status, transaction_type) |
| | `transaction_address_link` (wallet_id, txid, address, link_type, amount_sats) |

### 4.3 Read Model Storage: `WalletReadModelStorage`

| Query | Returns |
|---|---|
| `getWalletSummary(walletId)` | Wallet overview with balances |
| `listWallets()` | All wallet summaries |
| `getAddresses(walletId)` | All addresses with metadata |
| `getAvailableUtxos(walletId)` | UTXOs with status AVAILABLE, sorted by value |
| `getUtxosByStatus(walletId, status)` | Filtered UTXO list |
| `getBalance(walletId)` | Confirmed, unconfirmed, reserved, available balances |
| `getTransactionHistory(walletId, offset, limit)` | Paginated transaction list |
| `getTransactionsByAddress(walletId, address)` | Transactions for a specific address |

### 4.4 Projection Wiring

```java
// EventSourcedProvider reads wallet events from journal
var sourceProvider = EventSourcedProvider.eventsByTag(actorSystem, "wallet");

// ExactlyOnceProjection with JDBC offset store
var projection = JdbcProjection.exactlyOnce(
    ProjectionId.of("wallet-projection", "wallet"),
    sourceProvider,
    () -> new JdbcSession(dataSource),
    () -> new WalletProjectionHandler(dataSource),
    actorSystem
);
```

### 4.5 Tests

| Test | Validates |
|---|---|
| `WalletProjectionTest` | Events flow through projection → read models populated correctly |
| `ProjectionIdempotencyTest` | Duplicate event delivery doesn't corrupt read models |
| `ProjectionRecoveryTest` | Projection restarts from offset after crash |
| `BalanceCalculationTest` | Confirmed/unconfirmed/reserved/available balances correct across UTXO state transitions |
| `QueryTest` | All `WalletReadModelStorage` queries return expected results |

### 4.6 Exit Criteria

- Real-time read model updates from wallet events
- All queries functional against PostgreSQL
- Projection survives restart (offset tracking)
- Exactly-once semantics verified (no duplicate read model entries)

---

## Phase 5 — CryptoService & SecureStorage

**Goal**: Key derivation, address generation, transaction signing, and encrypted storage of private key material.

### 5.1 CryptoService

| Method | Delegates To | Notes |
|---|---|---|
| `generateMnemonic()` | bitcoin4j `MnemonicCode` | 12-word BIP39 |
| `validateMnemonic(words)` | bitcoin4j `MnemonicCode` | |
| `mnemonicToHDPrivateKey(mnemonic, passphrase, network)` | bitcoin4j `HDKeyDerivation` | BIP39 seed → BIP32 root |
| `derivePrivateKey(hdKey, account, index, coinType, isChange)` | bitcoin4j `HDKeyDerivation` | BIP44 path |
| `generateAddress(privateKey, network)` | bitcoin4j `Address` | P2PKH |
| `getPublicKey(privateKey)` | bitcoin4j `ECKey` | Compressed public key |
| `signTransaction(privateKey, sigHash)` | bitcoin4j `TransactionSigner` | SIGHASH_ALL \| SIGHASH_FORKID |
| `deriveHDPublicKey(hdPrivateKey)` | bitcoin4j `HDKeyDerivation` | For watch-only export |
| `privateKeyToWIF(key, network)` | bitcoin4j | |
| `privateKeyFromWIF(wif, network)` | bitcoin4j | |

### 5.2 EncryptionService

| Method | Detail |
|---|---|
| `encrypt(plaintext, context)` | AES-256-GCM. HKDF key derivation from master key + context. Random 12-byte nonce. Returns `EncryptionResult(ciphertext, nonce)`. |
| `decrypt(ciphertext, nonce, context)` | AES-256-GCM decrypt with derived key. |
| `generateMasterKey()` | Secure random 32 bytes. |

- Uses Bouncy Castle (already a bitcoin4j transitive dependency)
- HKDF salt: `"libspiffy-xpub-v1"`
- Master key provided by host via builder (never stored in database)
- Key version field supports future key rotation

### 5.3 SecureStorage

| Method | Detail |
|---|---|
| `storePrivateKey(walletId, encryptedKey, nonce, keyVersion)` | Persists to `secure_storage` table |
| `loadPrivateKey(walletId)` | Returns encrypted material for CryptoService to decrypt |
| `deletePrivateKey(walletId)` | Permanent deletion |

### 5.4 SQL Migration

| File | Tables |
|---|---|
| `V006__create_secure_storage.sql` | `secure_storage` (wallet_id PK, encrypted_key BYTEA, nonce BYTEA, key_version INT, created_at, updated_at) |

### 5.5 Builder Update

```java
var spiffy = LibSpiffy4j.builder()
    .dataSource(appDataSource)
    .encryptionMasterKey(masterKeyBytes)    // required if wallets will have private keys
    .build();
```

Watch-only (XPUB) wallets don't require the master key.

### 5.6 Tests

| Test | Validates |
|---|---|
| `MnemonicGenerationTest` | 12 words, BIP39 valid, deterministic seed |
| `KeyDerivationTest` | BIP44 paths produce expected addresses (test vectors from Dart libspiffy) |
| `EncryptDecryptTest` | Round-trip. Different contexts produce different ciphertext. Tampered ciphertext fails authentication. |
| `SecureStorageTest` | Store → load → decrypt → matches original key material |
| `WatchOnlyTest` | XPUB wallet creates without master key. Signing operations rejected. |

### 5.7 Exit Criteria

- All four wallet creation modes produce correct root addresses (verified against Dart test vectors)
- Private keys encrypted at rest, decryptable only with master key
- Signing produces valid transactions (verified by bitcoin4j script interpreter)

---

## Phase 6 — Transaction Building

**Goal**: UTXO selection, fee calculation, transaction construction, signing, and BEEF output — matching Dart `TransactionBuilderService`.

### 6.1 TransactionBuildService

| Method | Detail |
|---|---|
| `buildTransaction(walletId, outputs, config)` | Select UTXOs → build transaction → return `TransactionBuildResult` |
| `selectUtxos(available, targetAmount, config)` | Coin selection per strategy |
| `calculateFee(inputCount, outputCount, feePerKb)` | Size-based fee estimation |

### 6.2 Coin Selection Strategies

| Strategy | Algorithm |
|---|---|
| `SMALLEST_FIRST` | Sort ascending, accumulate until target met |
| `LARGEST_FIRST` | Sort descending, accumulate until target met |
| `RANDOM` | Shuffle, accumulate until target met |
| `OPTIMAL_CHANGE` | Minimize change output — prefer exact or near-exact matches |

### 6.3 Transaction Build Flow

1. Query `WalletReadModelStorage.getAvailableUtxos(walletId)`
2. Select UTXOs per strategy (target = sum of outputs + estimated fee)
3. Reserve selected UTXOs via `ReserveUtxos` command
4. Build transaction via bitcoin4j `TransactionBuilder`
   - Add inputs (P2PKH unlock from selected UTXOs)
   - Add outputs (per output specs: P2PKH, P2MS, OP_RETURN)
   - Add change output if remainder > `minChangeAmount` (546 sats)
   - Dust prevention: outputs below dust threshold are folded into fee
5. Sign inputs using `CryptoService` (skip for watch-only wallets)
6. Return `TransactionBuildResult` with hex, fee breakdown, BEEF (if merkle proofs available)

### 6.4 Multisig Support

| Method | Detail |
|---|---|
| `buildFundingTransaction(clientPubKey, serverPubKey, amount, utxos)` | 2-of-2 multisig output for payment channels |
| `signMultisigInput(tx, inputIndex, privateKey, redeemScript)` | ECDSA signature for multisig input |

### 6.5 UTXO Splitting (Benford Distribution)

| Method | Detail |
|---|---|
| `splitUtxosToBenford(walletId, utxos)` | Splits large UTXOs into Benford-distributed amounts for privacy |

### 6.6 Tests

| Test | Validates |
|---|---|
| `CoinSelectionTest` | Each strategy selects correct UTXOs. Edge cases: exact match, insufficient funds, dust handling |
| `FeeCalculationTest` | Fee scales with input/output count. Minimum fee respected. |
| `TransactionBuildTest` | End-to-end: UTXOs → signed transaction → valid hex. Change output generated correctly. |
| `MultisigBuildTest` | 2-of-2 funding transaction valid. Both signatures verify. |
| `DustPreventionTest` | Outputs below dust threshold handled correctly (folded into fee or rejected) |
| `ReservationIntegrationTest` | Build reserves UTXOs. Cancellation releases them. |

### 6.6 Exit Criteria

- Transaction hex accepted by bitcoin4j script interpreter
- Fee calculation matches Dart implementation
- UTXO reservation integrated with build flow (reserve on build, release on cancel)

---

## Phase 7 — Invoice Aggregate

**Goal**: Full invoice lifecycle with multi-output support, payment matching, and expiration.

### 7.1 Commands

| Command | Key Fields |
|---|---|
| `CreateInvoice` | invoiceId, walletId, amount, outputs (list of `InvoiceOutputSpec`), description, expiresAt, metadata |
| `MarkInvoicePaid` | invoiceId, txid, amountReceived, addressesPaidTo, paidAt |
| `CancelInvoice` | invoiceId, reason |
| `ExpireInvoice` | invoiceId |
| `CheckInvoiceStatus` | invoiceId |

### 7.2 Events

| Event | Key Fields |
|---|---|
| `InvoiceCreated` | Full invoice spec, output details, expiration |
| `InvoiceStatusChanged` | invoiceId, oldStatus, newStatus |
| `InvoicePaid` | invoiceId, txid, amountReceived, addressesPaidTo, paidAt |
| `InvoiceExpired` | invoiceId, expiredAt |
| `InvoiceCancelled` | invoiceId, reason, cancelledAt |

### 7.3 Aggregate State: `InvoiceState`

| Field | Type |
|---|---|
| invoiceId | String |
| isCreated | boolean |
| walletId | String |
| addresses | `List<String>` |
| amount | long (sats) |
| outputs | `List<InvoiceOutputSpec>` |
| description | String |
| status | InvoiceStatus |
| createdAt | Instant |
| expiresAt | Instant (nullable) |
| paidAt | Instant (nullable) |
| paymentTxid | String (nullable) |
| amountReceived | long |
| metadata | `Map<String, String>` |

### 7.4 Business Rules

- Cannot pay an expired or cancelled invoice
- `amountReceived` must be ≥ invoice `amount`
- Payment must go to one of the invoice's addresses
- Cannot create duplicate invoice (same invoiceId)
- P2MS outputs: validate threshold ≤ totalKeys ≤ 16
- OP_RETURN outputs: validate totalSize ≤ 99KB

### 7.5 Invoice Projection & Read Model

| Migration | Tables |
|---|---|
| `V005__create_invoice_read_models.sql` | `invoice_summary` (invoice_id, wallet_id, amount, description, status, created_at, expires_at, paid_at, payment_txid, amount_received, metadata JSONB), `invoice_output` (invoice_id, output_type, address, amount, label, public_keys, threshold, data_chunks) |

| Query | Returns |
|---|---|
| `getInvoice(invoiceId)` | Full invoice with outputs |
| `listInvoices(walletId, statusFilter)` | Filtered and paginated |
| `getExpiredInvoices(beforeTimestamp)` | For expiration sweep |

### 7.6 Tests

| Test | Validates |
|---|---|
| `InvoiceLifecycleTest` | Create → pay. Create → expire. Create → cancel. |
| `MultiOutputInvoiceTest` | P2PKH + P2MS + OP_RETURN combined |
| `PaymentMatchingTest` | Correct txid, sufficient amount, correct address. Rejects underpayment, wrong address. |
| `ExpirationTest` | Invoice becomes unpayable after expiresAt |
| `InvoiceProjectionTest` | Events update read models. Queries return correct results. |

### 7.7 Exit Criteria

- Full invoice lifecycle functional
- Multi-output types validated and persisted correctly
- Payment matching enforces business rules

---

## Phase 8 — SPV Validation

**Goal**: BEEF parsing, BUMP merkle proof verification, block header chain management — matching Dart `SpvService`.

### 8.1 BEEF Parser

| Class | Detail |
|---|---|
| `Beef` | Parse from `byte[]`: version (0100BEEF magic), BUMPs array, transactions array, hasMerkle flags, bumpIndex map |
| `Beef.parse(byte[])` | Static factory. Validates magic bytes, parses VarInt-encoded arrays. |
| `Beef.toBytes()` | Serialize back to wire format. |

### 8.2 BUMP Merkle Proof

| Class | Detail |
|---|---|
| `Bump` | blockHeight, path (`List<BumpLevel>`). Each level contains leaves. |
| `BumpLeaf` | offset, flags (duplicate, isTxid), hash (32 bytes, optional if duplicate) |
| `Bump.parse(byte[])` | Static factory from compact binary encoding |
| `Bump.computeMerkleRoot(txid)` | Walk path from txid leaf to root. Returns 32-byte merkle root. |

### 8.3 Block Header Chain

| Class | Detail |
|---|---|
| `BlockHeaderChain` | In-memory store of block headers indexed by height and hash |
| Methods | `addHeader(height, header)`, `getHeader(height)`, `getHeaderByHash(hash)`, `getChainHeight()` |
| `processHeaders(headers, peerId)` | Validate chain continuity, detect orphans, handle reorgs |
| Statistics | totalHeadersReceived, reorganizationsHandled, lastReorgAt |

### 8.4 SPV Service

| Method | Detail |
|---|---|
| `validateBeef(beef)` | Parse BEEF → extract BUMPs → compute merkle roots → verify against block headers |
| `validateBump(bump, txid)` | Compute merkle root from BUMP path → compare to header's merkle root |
| `trackTransaction(txid)` | Monitor confirmation count via chain tip tracking |

### 8.5 Tests

| Test | Validates |
|---|---|
| `BeefParseTest` | Round-trip: bytes → Beef → bytes. Test vectors from Dart libspiffy. |
| `BumpMerkleRootTest` | Known BUMP + txid → expected merkle root (test vectors) |
| `BlockHeaderChainTest` | Sequential headers accepted. Orphan detection. Reorg handling. |
| `SpvValidationTest` | Valid BEEF accepted. Tampered transaction rejected. Missing header detected. |

### 8.6 Exit Criteria

- BEEF/BUMP parsing produces identical results to Dart implementation (shared test vectors)
- Merkle root computation handles byte-order correctly (internal LE ↔ display BE)
- Block header chain validates continuity and detects reorgs

---

## Phase 9 — Payment Channel Aggregate

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
| Coin selection (4 strategies) | Phase 6 | |
| Fee calculation | Phase 6 | |
| Transaction building with change | Phase 6 | Via bitcoin4j |
| Dust prevention | Phase 6 | |
| Multisig transaction building | Phase 6 | 2-of-2 for channels |
| UTXO Benford splitting | Phase 6 | |
| BEEF output | Phase 6 | |
| Invoice creation with expiration | Phase 7 | |
| Multi-output invoices (P2PKH, P2MS, OP_RETURN) | Phase 7 | |
| Invoice status lifecycle | Phase 7 | |
| Payment matching & validation | Phase 7 | |
| BEEF parsing | Phase 8 | |
| BUMP merkle proof verification | Phase 8 | |
| Block header chain & validation | Phase 8 | |
| Chain reorganization handling | Phase 8 | |
| Payment channel negotiation | Phase 9 | |
| 2-of-2 multisig funding (T1) | Phase 9 | |
| nLockTime refund (T2) | Phase 9 | |
| Off-chain payments with nSequence (T3) | Phase 9 | |
| Cooperative & timeout settlement | Phase 9 | |
| Channel ↔ wallet UTXO coordination | Phase 9 | Process manager pattern |
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
