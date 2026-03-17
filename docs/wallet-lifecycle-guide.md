# Wallet Lifecycle Guide

> **The Coordinator API is the recommended way to interact with the event-sourced layer.** Direct aggregate interaction is shown in some sections for operations not yet available on the coordinator, but should be avoided where possible. Bypassing the coordinator can leave the wallet in an inconsistent state.

This guide walks through the complete wallet lifecycle in libspiffy4j: creating a wallet, deriving addresses, receiving UTXOs, building and broadcasting transactions, and SPV confirmation.

---

## Table of Contents

1. [Overview](#overview)
2. [UTXO State Machine](#utxo-state-machine)
3. [Create a Wallet](#create-a-wallet)
4. [Derive Addresses](#derive-addresses)
5. [Receive UTXOs](#receive-utxos)
6. [Build and Broadcast a Transaction](#build-and-broadcast-a-transaction)
7. [SPV Confirmation](#spv-confirmation)
8. [UTXO Reservation Semantics](#utxo-reservation-semantics)
9. [Coin Selection Strategy Guide](#coin-selection-strategy-guide)
10. [Querying Wallet State](#querying-wallet-state)

---

## Overview

A wallet in libspiffy4j manages:

- **Addresses** — BIP44-derived receiving and change addresses
- **UTXOs** — Unspent transaction outputs with lifecycle tracking
- **Transactions** — Recorded with direction (incoming/outgoing/internal), status, and confirmation count

The wallet lifecycle follows this pattern:

```
Create Wallet -> Derive Addresses -> Receive UTXOs -> Build Transaction
     |                                                       |
     |               +--- SPV Confirm <--- Broadcast <-------+
     v               v
  Record in      Update UTXO
  Read Model     Confirmations
```

---

## UTXO State Machine

Every UTXO tracked by the wallet follows this state machine:

```
                    ┌─────────────┐
    Received ──────>│   PENDING   │ (unconfirmed)
                    └──────┬──────┘
                           │ confirmed
                           v
                    ┌─────────────┐
             ┌─────│  AVAILABLE  │<────┐
             │     └──────┬──────┘     │
             │            │            │ release / expiry
             │    reserve │            │
             │            v            │
             │     ┌─────────────┐     │
             │     │  RESERVED   │─────┘
             │     └──────┬──────┘
             │            │ spend
             │            v
             │     ┌─────────────┐
             └────>│    SPENT    │
                   └─────────────┘
```

| State | Description |
|-------|-------------|
| `PENDING` | UTXO received but transaction not yet confirmed |
| `AVAILABLE` | Confirmed and spendable |
| `RESERVED` | Locked for a pending spend (with expiration time) |
| `SPENT` | Consumed by a confirmed transaction |

**Key rules:**
- Only `AVAILABLE` and expired `RESERVED` UTXOs are spendable
- Reservations have an expiration time — if the spending transaction isn't broadcast before expiry, the UTXO returns to `AVAILABLE`
- `BitcoinUtxo.isEffectivelyAvailable()` returns `true` for both `AVAILABLE` and expired `RESERVED` UTXOs

---

## Create a Wallet

### Stateless Tier (Keys Only)

```java
var crypto = new CryptoService();
List<String> mnemonic = crypto.generateMnemonic();
DeterministicKey hdKey = crypto.mnemonicToHDPrivateKey(mnemonic, "");
```

### Event-Sourced Tier (Persistent State)

```java
import org.apache.pekko.actor.typed.javadsl.AskPattern;

String walletId = UUID.randomUUID().toString();
var key0 = crypto.derivePrivateKey(hdKey, 0, 0, 0, false);
String rootAddress = crypto.generateAddress(key0, NetworkType.MAINNET);

CompletionStage<CoordinatorReply> reply = AskPattern.ask(
    libSpiffy.coordinator(),
    replyTo -> new CoordinatorCommand.CreateWallet(
        walletId, "My Wallet", WalletType.HD, NetworkType.MAINNET,
        rootAddress, Map.of(), replyTo
    ),
    Duration.ofSeconds(10),
    libSpiffy.system().scheduler()
);

CoordinatorReply result = reply.toCompletableFuture().join();
// result is WalletCreated on success, or Failure on error
```

### Secure Key Storage

After creating the wallet, encrypt and store the HD key:

```java
var encrypted = encryption.encrypt(hdKey.serializePrivate(),
    "wallet:" + walletId + ":hdkey");

try (var conn = dataSource.getConnection()) {
    secureStorage.storeEncryptedKey(conn, walletId, "hdkey",
        encrypted.ciphertext(), encrypted.nonce(), 1);
    conn.commit();
}
```

---

## Derive Addresses

BIP44 path: `m / 44' / coinType' / account' / change / index`

```java
import org.apache.pekko.actor.typed.javadsl.AskPattern;

// External (receiving) addresses: change = false
for (int i = 0; i < 10; i++) {
    var key = crypto.derivePrivateKey(hdKey, /*account=*/0, /*index=*/i,
                                      /*coinType=*/0, /*isChange=*/false);
    String address = crypto.generateAddress(key, NetworkType.MAINNET);

    // Record in the wallet via the coordinator
    AskPattern.ask(
        libSpiffy.coordinator(),
        replyTo -> new CoordinatorCommand.RecordAddress(walletId, addressMetadata, replyTo),
        Duration.ofSeconds(10),
        libSpiffy.system().scheduler()
    ).toCompletableFuture().join();
}

// Change addresses: change = true
var changeKey = crypto.derivePrivateKey(hdKey, 0, 0, 0, true);
String changeAddress = crypto.generateAddress(changeKey, NetworkType.MAINNET);
```

**Note:** `RecordAddress` takes an `AddressMetadata` object, not a plain address string. Construct the appropriate `AddressMetadata` for each address you derive.

**Coin type values:**
- `0` = mainnet
- `1` = testnet

---

## Receive UTXOs

When your application detects a payment to one of the wallet's addresses, record the UTXO:

```java
import org.apache.pekko.actor.typed.javadsl.AskPattern;

var utxo = new BitcoinUtxo(
    txid, vout, valueSats,
    scriptPubKey, address,
    UtxoStatus.PENDING,    // Unconfirmed initially
    null,                  // blockHeight (unknown until confirmed)
    0,                     // confirmations
    Instant.now(), Instant.now(),
    null, null, null, null,
    derivationIndex        // Which address index received this
);

AskPattern.ask(
    libSpiffy.coordinator(),
    replyTo -> new CoordinatorCommand.RecordUtxo(walletId, utxo, replyTo),
    Duration.ofSeconds(10),
    libSpiffy.system().scheduler()
).toCompletableFuture().join();
```

After the transaction is confirmed and SPV-validated, update confirmations:

> **Direct aggregate access (exception):** `UpdateConfirmationCommand` is not yet available on the coordinator. This is one of the few operations that still requires direct aggregate interaction.

```java
EntityRef<WalletCommand> walletRef = sharding.entityRefFor(
    WalletAggregate.ENTITY_TYPE_KEY, walletId
);

walletRef.ask(
    replyTo -> new WalletCommand.UpdateConfirmationCommand(
        walletId, txid, confirmations, blockHeight, replyTo
    ),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

---

## Build and Broadcast a Transaction

The coordinator's `BuildPayment` command handles UTXO selection, reservation, transaction building, and marking UTXOs as spent in a single atomic operation. This replaces the previous multi-step manual process.

### 1. Build the Transaction via Coordinator

```java
import org.apache.pekko.actor.typed.javadsl.AskPattern;

CompletionStage<CoordinatorReply> paymentReply = AskPattern.ask(
    libSpiffy.coordinator(),
    replyTo -> new CoordinatorCommand.BuildPayment(
        walletId,
        List.of(new InvoiceOutputSpec.P2PKHOutputSpec(recipientAddress, targetSats, "payment")),
        TransactionBuildConfig.standard(),
        changeAddress,
        replyTo
    ),
    Duration.ofSeconds(10),
    libSpiffy.system().scheduler()
);

CoordinatorReply result = paymentReply.toCompletableFuture().join();
if (result instanceof CoordinatorReply.PaymentBuilt built) {
    System.out.println("Built txid: " + built.result().txid());
    System.out.println("Fee: " + built.result().feeSats() + " sats");
    String rawHex = built.result().rawHex();
} else if (result instanceof CoordinatorReply.Failure failure) {
    System.err.println("Payment failed: " + failure.message());
}
```

The `BuildPayment` command internally:
1. Selects UTXOs using the configured coin selection strategy
2. Reserves the selected UTXOs
3. Builds and signs the transaction
4. Records the transaction in the wallet
5. Marks the consumed UTXOs as spent

### 2. Broadcast via ARC

Broadcasting is still a separate step since the coordinator does not handle network interaction:

```java
var arc = new ArcService(ArcServiceConfig.taalMainnet());
ArcSubmitResponse response = arc.submitTransaction(rawHex);
System.out.println("Broadcast txid: " + response.txid());
```

### Plugin Payments

For plugin-based payment flows, use `BuildPluginPayment`:

```java
CompletionStage<CoordinatorReply> pluginReply = AskPattern.ask(
    libSpiffy.coordinator(),
    replyTo -> new CoordinatorCommand.BuildPluginPayment(
        walletId, pluginId, action, pluginParams,
        TransactionBuildConfig.standard(), changeAddress, replyTo
    ),
    Duration.ofSeconds(10),
    libSpiffy.system().scheduler()
);

// Reply is PluginPaymentBuilt(txid, rawHex, feeSats) on success
```

---

## SPV Confirmation

After broadcasting, track the transaction for confirmation:

> **Direct aggregate access (exception):** `UpdateConfirmationCommand` is not yet available on the coordinator. This operation still requires direct aggregate interaction.

```java
EntityRef<WalletCommand> walletRef = sharding.entityRefFor(
    WalletAggregate.ENTITY_TYPE_KEY, walletId
);

var headerStore = new BlockHeaderChain();
var importService = new TransactionImportService(arc, headerStore);

// Track the broadcast transaction
importService.trackPendingTransaction(response.txid());

// On new block notification:
List<ImportedTransaction> confirmed = importService.onNewBlock(newBlockHeight);
for (var imported : confirmed) {
    if (imported.spvValid()) {
        // Update confirmation count in the wallet (direct aggregate access)
        walletRef.ask(
            replyTo -> new WalletCommand.UpdateConfirmationCommand(
                walletId, imported.txid(),
                1, (int) imported.blockHeight(),
                replyTo
            ),
            Duration.ofSeconds(10)
        ).toCompletableFuture().join();
    }
}
```

---

## UTXO Reservation Semantics

The coordinator handles UTXO reservations internally during `BuildPayment`, so manual reservation is rarely needed. The information below is provided for understanding and for the rare cases where direct reservation management is required.

Reservations prevent double-spending when multiple transactions are being built concurrently.

**Reservation fields on `BitcoinUtxo`:**

| Field | Purpose |
|-------|---------|
| `reservedByTxId` | The transaction that locked this UTXO |
| `reservationExpiresAt` | When the reservation auto-expires |
| `reservationPriority` | Priority level (higher = harder to preempt) |
| `reservationReason` | Human-readable reason |

**Expiration behavior:**
- `isReservationExpired()` — returns `true` if the current time is past `reservationExpiresAt`
- `isEffectivelyAvailable()` — returns `true` if `AVAILABLE` or if `RESERVED` with an expired reservation
- `reservationTimeRemaining()` — returns the Duration until expiration

**Cleanup (direct aggregate access):**

```java
walletRef.ask(
    replyTo -> new WalletCommand.CleanupExpiredReservationsCommand(walletId, replyTo),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

**Manual release (direct aggregate access):**

```java
walletRef.ask(
    replyTo -> new WalletCommand.ReleaseUtxoCommand(walletId, utxoKey, replyTo),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

---

## Coin Selection Strategy Guide

| Strategy | Algorithm | When to Use |
|----------|-----------|-------------|
| `SMALLEST_FIRST` | Sorts ascending, takes until target met | Consolidating many small UTXOs. Reduces UTXO set size over time. Higher fees (more inputs). |
| `LARGEST_FIRST` | Sorts descending, takes until target met | Minimizing number of inputs. Lower fees but leaves dust. |
| `RANDOM` | Shuffles then takes until target met | Privacy-sensitive applications. Prevents predictable spending patterns. |
| `OPTIMAL_CHANGE` | Branch-and-bound (max 100K iterations) | General purpose. Tries to find a combination that minimizes the change output, reducing blockchain bloat. Falls back to `LARGEST_FIRST` if no optimal solution found. |

**Recommendation:** Use `OPTIMAL_CHANGE` as the default. Switch to `SMALLEST_FIRST` when you want to consolidate dust, or `RANDOM` for privacy.

---

## Querying Wallet State

### Via the Coordinator

The coordinator provides convenient query commands as an alternative to direct storage access:

```java
import org.apache.pekko.actor.typed.javadsl.AskPattern;

// Get wallet balance
CompletionStage<CoordinatorReply> balanceReply = AskPattern.ask(
    libSpiffy.coordinator(),
    replyTo -> new CoordinatorCommand.GetBalance(walletId, replyTo),
    Duration.ofSeconds(10),
    libSpiffy.system().scheduler()
);
// Reply is BalanceResult(balance)

// Get transactions (paginated)
CompletionStage<CoordinatorReply> txReply = AskPattern.ask(
    libSpiffy.coordinator(),
    replyTo -> new CoordinatorCommand.GetTransactions(walletId, 50, 0, replyTo),
    Duration.ofSeconds(10),
    libSpiffy.system().scheduler()
);
// Reply is TransactionsResult(txs)

// Get UTXOs
CompletionStage<CoordinatorReply> utxoReply = AskPattern.ask(
    libSpiffy.coordinator(),
    replyTo -> new CoordinatorCommand.GetUtxos(walletId, replyTo),
    Duration.ofSeconds(10),
    libSpiffy.system().scheduler()
);
// Reply is UtxosResult(utxos)
```

### Via Read Model Storage (Direct Queries)

All read model queries go through stateless storage DAOs:

```java
var storage = new WalletReadModelStorage();

// Summary with balances
Optional<WalletSummary> summary = storage.findWalletSummary(dataSource, walletId);

// Balance breakdown
Optional<WalletBalance> balance = storage.getWalletBalance(dataSource, walletId);

// UTXOs by status
List<BitcoinUtxo> available = storage.findUtxosByStatus(dataSource, walletId, UtxoStatus.AVAILABLE);
List<BitcoinUtxo> reserved = storage.findUtxosByStatus(dataSource, walletId, UtxoStatus.RESERVED);
List<BitcoinUtxo> allUtxos = storage.findUtxosByWalletId(dataSource, walletId);

// Transactions (paginated)
List<BitcoinTransaction> txs = storage.findTransactionsByWalletId(dataSource, walletId, 50, 0);

// Addresses
List<String> addresses = storage.findAddressesByWalletId(dataSource, walletId);

// Addresses linked to a specific transaction
List<String> senders = storage.findAddressesByTransaction(dataSource, walletId, txid, "SENDER");
List<String> receivers = storage.findAddressesByTransaction(dataSource, walletId, txid, "RECEIVER");

// List all wallets
List<WalletSummary> wallets = storage.listWalletSummaries(dataSource, 100, 0);
```
