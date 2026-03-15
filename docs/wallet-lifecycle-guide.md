# Wallet Lifecycle Guide

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
String walletId = UUID.randomUUID().toString();
var key0 = crypto.derivePrivateKey(hdKey, 0, 0, 0, false);
String rootAddress = crypto.generateAddress(key0, NetworkType.MAINNET);

EntityRef<WalletCommand> walletRef = sharding.entityRefFor(
    WalletAggregate.ENTITY_TYPE_KEY, walletId
);

WalletReply reply = walletRef.ask(
    replyTo -> new WalletCommand.CreateWalletCommand(
        walletId, "My Wallet", rootAddress,
        WalletType.HD, NetworkType.MAINNET,
        Map.of(), replyTo
    ),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
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
// External (receiving) addresses: change = false
for (int i = 0; i < 10; i++) {
    var key = crypto.derivePrivateKey(hdKey, /*account=*/0, /*index=*/i,
                                      /*coinType=*/0, /*isChange=*/false);
    String address = crypto.generateAddress(key, NetworkType.MAINNET);

    // Record in the aggregate (event-sourced tier)
    walletRef.ask(
        replyTo -> new WalletCommand.RecordAddressCommand(walletId, address, replyTo),
        Duration.ofSeconds(10)
    ).toCompletableFuture().join();
}

// Change addresses: change = true
var changeKey = crypto.derivePrivateKey(hdKey, 0, 0, 0, true);
String changeAddress = crypto.generateAddress(changeKey, NetworkType.MAINNET);
```

**Coin type values:**
- `0` = mainnet
- `1` = testnet

---

## Receive UTXOs

When your application detects a payment to one of the wallet's addresses, record the UTXO:

```java
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

walletRef.ask(
    replyTo -> new WalletCommand.RecordUtxoCommand(walletId, utxo, replyTo),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

After the transaction is confirmed and SPV-validated, update confirmations:

```java
walletRef.ask(
    replyTo -> new WalletCommand.UpdateConfirmationCommand(
        walletId, txid, confirmations, blockHeight, replyTo
    ),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

---

## Build and Broadcast a Transaction

### 1. Select UTXOs

```java
// Query available UTXOs from the read model
var walletStorage = new WalletReadModelStorage();
List<BitcoinUtxo> available = walletStorage.findUtxosByStatus(
    dataSource, walletId, UtxoStatus.AVAILABLE
);

// Or use CoinSelector for specific strategies
var selector = new CoinSelector();
var selection = selector.select(available, targetSats, UtxoSelectionStrategy.OPTIMAL_CHANGE);
```

### 2. Reserve Selected UTXOs

```java
for (var utxo : selection.selected()) {
    walletRef.ask(
        replyTo -> new WalletCommand.ReserveUtxoCommand(
            walletId, utxo.key(),
            "pending-tx-id",
            Instant.now().plus(Duration.ofMinutes(5)),
            1, "building payment",
            replyTo
        ),
        Duration.ofSeconds(10)
    ).toCompletableFuture().join();
}
```

### 3. Build the Transaction

```java
var buildService = new TransactionBuildService(crypto);

TransactionBuildResult result = buildService.buildTransaction(
    selection.selected(),
    List.of(new InvoiceOutputSpec.P2PKHOutputSpec(recipientAddress, targetSats, "payment")),
    TransactionBuildConfig.standard(),
    changeAddress,
    signingKey,
    NetworkType.MAINNET
);
```

### 4. Broadcast via ARC

```java
var arc = new ArcService(ArcServiceConfig.taalMainnet());
ArcSubmitResponse response = arc.submitTransaction(result.rawHex());
System.out.println("Broadcast txid: " + response.txid());
```

### 5. Record the Transaction

```java
var tx = new BitcoinTransaction(
    walletId, result.txid(), result.rawHex(),
    TransactionStatus.BROADCAST, TransactionDirection.OUTGOING,
    null, 0,
    result.inputValueSats(), result.outputValueSats(),
    result.feeSats(), -targetSats,
    List.of(/* sending addresses */),
    List.of(recipientAddress),
    Instant.now(), Instant.now(),
    "payment", 0L, 2
);

walletRef.ask(
    replyTo -> new WalletCommand.RecordTransactionCommand(walletId, tx, replyTo),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

### 6. Mark UTXOs as Spent

```java
for (var utxo : selection.selected()) {
    walletRef.ask(
        replyTo -> new WalletCommand.MarkUtxoSpentCommand(walletId, utxo.key(), replyTo),
        Duration.ofSeconds(10)
    ).toCompletableFuture().join();
}
```

---

## SPV Confirmation

After broadcasting, track the transaction for confirmation:

```java
var headerStore = new BlockHeaderChain();
var importService = new TransactionImportService(arc, headerStore);

// Track the broadcast transaction
importService.trackPendingTransaction(response.txid());

// On new block notification:
List<ImportedTransaction> confirmed = importService.onNewBlock(newBlockHeight);
for (var imported : confirmed) {
    if (imported.spvValid()) {
        // Update confirmation count in the wallet
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

**Cleanup:**

```java
// Periodically clean up expired reservations
walletRef.ask(
    replyTo -> new WalletCommand.CleanupExpiredReservationsCommand(walletId, replyTo),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

**Manual release:**

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
