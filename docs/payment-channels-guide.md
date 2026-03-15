# Payment Channels Guide

This guide covers the payment channel lifecycle in libspiffy4j: from negotiation through funding, off-chain payments, and settlement.

---

## Table of Contents

1. [Overview](#overview)
2. [Channel State Machine](#channel-state-machine)
3. [Transaction Types](#transaction-types)
4. [Channel Lifecycle: Client Perspective](#channel-lifecycle-client-perspective)
5. [Channel Lifecycle: Server Perspective](#channel-lifecycle-server-perspective)
6. [ChannelAggregate Commands](#channelaggregate-commands)
7. [ChannelWalletSaga Coordination](#channelwalletsaga-coordination)
8. [Building Channel Transactions](#building-channel-transactions)
9. [Querying Channel State](#querying-channel-state)

---

## Overview

Payment channels enable off-chain micropayments between two parties. A 2-of-2 multisig output locks funds on-chain, and the parties exchange signed payment updates without broadcasting each one. Only the funding and settlement transactions go on-chain.

**Key components:**

| Component | Purpose |
|-----------|---------|
| `PaymentChannelBuilder` | Constructs T1/T2/T3 transactions (stateless) |
| `MultisigTransactionService` | 2-of-2 multisig operations (stateless) |
| `ChannelAggregate` | Event-sourced channel state machine (Pekko) |
| `ChannelWalletSaga` | Coordinates channel + wallet state (Pekko) |
| `ChannelReadModelStorage` | Read model queries (PostgreSQL) |

---

## Channel State Machine

```
                  ┌──────────────┐
   Request ──────>│ NEGOTIATING  │
                  └──────┬───────┘
                    accept│    │reject
                         v    v
                  ┌──────────┐  ┌────────┐
                  │ FUNDING  │  │ FAILED │
                  └─────┬────┘  └────────┘
               open     │
                        v
                  ┌──────────┐
                  │ OPENING  │
                  └─────┬────┘
          funding confirmed
                        v
                  ┌──────────┐
                  │   OPEN   │──── payments ────┐
                  └─────┬────┘                  │
                   close│                       │
                        v               (record + ack)
                  ┌──────────┐
                  │ CLOSING  │
                  └─────┬────┘
              finalize  │
                        v
                  ┌──────────┐
                  │  CLOSED  │
                  └──────────┘

                  ┌──────────┐
                  │ EXPIRED  │  (refund claimed after locktime)
                  └──────────┘
```

| State | Description |
|-------|-------------|
| `NEGOTIATING` | Channel requested, waiting for counterparty acceptance |
| `FUNDING` | Parties building refund transaction (exchange signatures) |
| `OPENING` | Funding transaction broadcast, waiting for confirmation |
| `OPEN` | Channel active — off-chain payments in progress |
| `CLOSING` | Settlement initiated, latest payment tx being broadcast |
| `CLOSED` | Settlement confirmed on-chain |
| `EXPIRED` | Refund claimed after locktime expiry |
| `FAILED` | Negotiation rejected or error |

---

## Transaction Types

Payment channels use three transaction types:

### T1: Funding Transaction

A 2-of-2 multisig output that locks funds from the client.

```
Client's UTXOs ──> [2-of-2 multisig output: clientPubKey + serverPubKey]
                   [change output (back to client)]
```

### T2: Refund Transaction

A pre-signed transaction that returns all funds to the client after a locktime. This is the client's safety net — if the server disappears, the client can claim a refund after the locktime expires.

```
[T1 multisig output] ──> [client address: full amount]
                         (locktime: future timestamp)
```

Both parties sign T2 **before** T1 is broadcast.

### T3: Payment Transaction

Updated balance allocation between client and server. Each payment creates a new T3 with decremented sequence numbers (higher priority than older T3s).

```
[T1 multisig output] ──> [client address: clientBalance]
                         [server address: serverBalance]
                         (no locktime, sequence < T2 sequence)
```

---

## Channel Lifecycle: Client Perspective

### 1. Request Channel

```java
EntityRef<ChannelCommand> channelRef = sharding.entityRefFor(
    ChannelAggregate.ENTITY_TYPE_KEY, channelId
);

channelRef.ask(
    replyTo -> new ChannelCommand.RequestChannelCommand(
        channelId,
        walletId,
        PaymentChannelRole.CLIENT,
        clientPeerId,
        serverPeerId,
        clientPubKeyHex,
        100_000L,           // funding amount
        lockTimeUnix,       // refund locktime
        "payment-context",
        replyTo
    ),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

### 2. Record Server's Acceptance

When the server accepts (via your P2P protocol), record it:

```java
channelRef.ask(
    replyTo -> new ChannelCommand.RecordServerAcceptanceCommand(
        channelId,
        serverPubKeyHex,
        serverAddressB58,
        replyTo
    ),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

### 3. Build and Sign Refund (T2)

```java
channelRef.ask(
    replyTo -> new ChannelCommand.RequestRefundSignatureCommand(
        channelId,
        refundTxHex,
        clientSigHex,
        replyTo
    ),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

### 4. Open Channel (After T1 Confirmed)

```java
channelRef.ask(
    replyTo -> new ChannelCommand.OpenChannelCommand(
        channelId,
        fundingTxId,
        fundingTxHex,
        fundingOutputIndex,
        replyTo
    ),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

### 5. Make Payments (Off-Chain)

```java
channelRef.ask(
    replyTo -> new ChannelCommand.RecordPaymentCommand(
        channelId,
        newClientBalance,
        newServerBalance,
        sequenceNumber,
        paymentTxHex,
        paymentTxId,
        replyTo
    ),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

### 6. Close Channel

```java
channelRef.ask(
    replyTo -> new ChannelCommand.CloseChannelCommand(channelId, replyTo),
    Duration.ofSeconds(10)
).toCompletableFuture().join();

// After settlement tx is confirmed:
channelRef.ask(
    replyTo -> new ChannelCommand.FinalizeCloseCommand(
        channelId, settlementTxId, replyTo
    ),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

---

## Channel Lifecycle: Server Perspective

### 1. Accept Channel Request

```java
channelRef.ask(
    replyTo -> new ChannelCommand.AcceptChannelCommand(
        channelId,
        walletId,
        PaymentChannelRole.SERVER,
        serverPubKeyHex,
        serverAddressB58,
        replyTo
    ),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

### 2. Countersign Refund (T2)

```java
channelRef.ask(
    replyTo -> new ChannelCommand.ProvideRefundSignatureCommand(
        channelId,
        serverSigHex,
        replyTo
    ),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

### 3. Acknowledge Payments

When the client sends a payment update, the server validates and acknowledges:

```java
channelRef.ask(
    replyTo -> new ChannelCommand.AcknowledgePaymentCommand(
        channelId,
        sequenceNumber,
        serverSigHex,
        replyTo
    ),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

### 4. Reject Channel

If the server doesn't want to participate:

```java
channelRef.ask(
    replyTo -> new ChannelCommand.RejectChannelCommand(
        channelId, "insufficient capacity", replyTo
    ),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

---

## ChannelAggregate Commands

| Command | From State | To State | Description |
|---------|-----------|----------|-------------|
| `RequestChannelCommand` | (new) | NEGOTIATING | Client initiates channel |
| `AcceptChannelCommand` | NEGOTIATING | NEGOTIATING | Server accepts with pubkey |
| `RejectChannelCommand` | NEGOTIATING | FAILED | Either party rejects |
| `RecordServerAcceptanceCommand` | NEGOTIATING | FUNDING | Client records server acceptance |
| `RequestRefundSignatureCommand` | FUNDING | FUNDING | Client builds refund tx |
| `ProvideRefundSignatureCommand` | FUNDING | FUNDING | Server countersigns refund |
| `OpenChannelCommand` | FUNDING/OPENING | OPEN | Funding tx confirmed |
| `RecordPaymentCommand` | OPEN | OPEN | Client records new payment |
| `AcknowledgePaymentCommand` | OPEN | OPEN | Server acknowledges payment |
| `CloseChannelCommand` | OPEN | CLOSING | Initiate settlement |
| `FinalizeCloseCommand` | CLOSING | CLOSED | Settlement tx confirmed |
| `ClaimRefundCommand` | OPEN/CLOSING | EXPIRED | Claim after locktime |

---

## ChannelWalletSaga Coordination

The `ChannelWalletSaga` automatically coordinates between the `ChannelAggregate` and `WalletAggregate`:

- **Channel funding:** When a channel enters FUNDING, the saga reserves UTXOs in the client's wallet for the funding transaction
- **Channel rejection:** When a channel is rejected or fails, the saga releases the reserved UTXOs back to AVAILABLE
- **Channel settlement:** When a channel closes, the saga records the settlement transaction and updates UTXO state in the wallet

The saga runs via Pekko projections — no manual interaction is needed.

---

## Building Channel Transactions

Use `PaymentChannelBuilder` (stateless) to construct the actual Bitcoin transactions:

```java
var channelBuilder = new PaymentChannelBuilder(multisigService);

// Build T1: Funding transaction (2-of-2 multisig output)
TransactionBuildResult funding = channelBuilder.buildFundingTransaction(
    clientPubKeyHex,
    serverPubKeyHex,
    100_000L,
    clientUtxos,
    TransactionBuildConfig.standard(),
    clientChangeAddress,
    clientSigningKey,
    NetworkType.MAINNET
);

// Sign a multisig input (for T2 refund or T3 payment)
byte[] signature = channelBuilder.signMultisigInput(
    rawTxBytes,
    0,                  // input index
    privateKey,
    inputAmountSats
);
```

The `PaymentChannelBuilder` delegates to `MultisigTransactionService` which handles the 2-of-2 multisig script construction.

---

## Querying Channel State

Use the read model for channel queries:

```java
// The PaymentChannel record provides convenience methods:
PaymentChannel channel = ...; // from read model

channel.isOpen();     // state == OPEN
channel.isClosed();   // state == CLOSED
channel.isExpired();  // locktime passed
channel.isActive();   // OPEN and not expired
channel.isClient();   // role == CLIENT
channel.isServer();   // role == SERVER

// Balance tracking
long clientBalance = channel.clientBalanceSats();
long serverBalance = channel.serverBalanceSats();
int latestSeq = channel.latestSequenceNumber();
```
