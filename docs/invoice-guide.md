# Invoice Guide

> **The Coordinator API is the recommended way to interact with the event-sourced layer.** Direct aggregate interaction is shown in some sections for operations not yet available on the coordinator, but should be avoided where possible. Bypassing the coordinator can leave the wallet in an inconsistent state.

This guide covers the invoice lifecycle in libspiffy4j: creating invoices, defining output specifications, monitoring payment, and querying invoice state.

---

## Table of Contents

1. [Overview](#overview)
2. [Invoice State Machine](#invoice-state-machine)
3. [Creating an Invoice](#creating-an-invoice)
4. [InvoiceOutputSpec Variants](#invoiceoutputspec-variants)
5. [Multi-Output Invoices](#multi-output-invoices)
6. [Payment Matching](#payment-matching)
7. [Invoice Expiration and Cancellation](#invoice-expiration-and-cancellation)
8. [Querying Invoices](#querying-invoices)

---

## Overview

Invoices in libspiffy4j represent payment requests with:

- One or more **payment addresses** to monitor
- One or more **output specifications** describing expected outputs
- An **expiration time** after which the invoice is no longer valid
- Optional **metadata** for application-specific context (order IDs, etc.)

Invoices are managed by the `InvoiceAggregate` (event-sourced) and queryable via `InvoiceReadModelStorage`. The recommended way to create and manage invoices is through the `WalletCoordinator` API.

---

## Invoice State Machine

```
                 ┌──────────┐
   Create ──────>│ PENDING  │
                 └────┬─────┘
                      │
          ┌───────────┼───────────┐
          │           │           │
     paid │    expire │    cancel │
          v           v           v
     ┌────────┐ ┌─────────┐ ┌───────────┐
     │  PAID  │ │ EXPIRED │ │ CANCELLED │
     └────────┘ └─────────┘ └───────────┘
```

| Status | Description |
|--------|-------------|
| `PENDING` | Invoice created, awaiting payment |
| `PAID` | Payment received and confirmed |
| `EXPIRED` | Expiration time passed without payment |
| `CANCELLED` | Manually cancelled by the application |

All transitions are from `PENDING` only — `PAID`, `EXPIRED`, and `CANCELLED` are terminal states.

---

## Creating an Invoice

```java
import org.apache.pekko.actor.typed.javadsl.AskPattern;

String invoiceId = UUID.randomUUID().toString();

// Derive a fresh address for this invoice
var key = crypto.derivePrivateKey(hdKey, 0, nextIndex, 0, false);
String paymentAddress = crypto.generateAddress(key, NetworkType.MAINNET);

CompletionStage<CoordinatorReply> reply = AskPattern.ask(
    libSpiffy.coordinator(),
    replyTo -> new CoordinatorCommand.CreateInvoice(
        invoiceId,
        walletId,
        List.of(paymentAddress),        // addresses to monitor
        50_000L,                         // expected amount (satoshis)
        List.of(                         // output specifications
            new InvoiceOutputSpec.P2PKHOutputSpec(paymentAddress, 50_000L, "order-789")
        ),
        "Payment for Order #789",        // description
        Instant.now().plus(Duration.ofHours(24)),  // expires in 24h
        Map.of("orderId", "789", "customerEmail", "user@example.com"),  // metadata
        replyTo
    ),
    Duration.ofSeconds(10),
    libSpiffy.system().scheduler()
);

CoordinatorReply result = reply.toCompletableFuture().join();
// result is InvoiceCreated(id) on success, or Failure on error
```

**CreateInvoice coordinator command fields:**

| Field | Type | Description |
|-------|------|-------------|
| `invoiceId` | String | Unique invoice identifier |
| `walletId` | String | Associated wallet |
| `addresses` | List\<String\> | Addresses to monitor for payment |
| `amountSats` | long | Total expected payment amount |
| `outputs` | List\<InvoiceOutputSpec\> | Expected output structure |
| `description` | String | Human-readable description |
| `expiresAt` | Instant | Expiration timestamp |
| `metadata` | Map\<String, Object\> | Application-specific data |

---

## InvoiceOutputSpec Variants

Invoices support four output types via the sealed `InvoiceOutputSpec` interface:

### P2PKH (Pay-to-Public-Key-Hash)

Standard payment to an address:

```java
new InvoiceOutputSpec.P2PKHOutputSpec(
    "1ABC...",     // address
    50_000L,       // amount in satoshis
    "order-789"    // label
)
```

### P2MS (Pay-to-Multisig)

Threshold multisig output:

```java
new InvoiceOutputSpec.P2MSOutputSpec(
    List.of(pubKeyHex1, pubKeyHex2, pubKeyHex3),  // public keys
    2,              // threshold (2-of-3)
    100_000L,       // amount
    "escrow"        // label
)
```

**Constraints:**
- `0 < threshold <= publicKeys.size() <= 16`

### OP_RETURN (Data Carrier)

Data output with no monetary value:

```java
new InvoiceOutputSpec.OPReturnOutputSpec(
    List.of(
        "MEMO".getBytes(),
        "Hello, world!".getBytes()
    ),
    false           // separateOutputs: false = single OP_RETURN with pushdata segments
                    //                  true  = separate OP_RETURN output per chunk
)
```

**Constraints:**
- Total data size must not exceed 99,000 bytes

### Plugin (Plugin-Defined Output)

Output defined by a payment plugin:

```java
new InvoiceOutputSpec.PluginOutputSpec(
    pluginId,       // plugin identifier
    action,         // plugin action
    pluginParams    // plugin-specific parameters
)
```

Use `PluginOutputSpec` when the output structure is determined by a plugin at build time rather than being statically defined. The plugin resolves the parameters into concrete transaction outputs during `BuildPluginPayment`.

---

## Multi-Output Invoices

Invoices can specify multiple outputs. This is useful for:

- **Split payments** — Portions to different addresses (e.g., merchant + platform fee)
- **Data + payment** — OP_RETURN alongside a payment output
- **Escrow** — Multisig output for dispute resolution
- **Plugin-driven outputs** — Plugin-defined outputs alongside standard payments

```java
import org.apache.pekko.actor.typed.javadsl.AskPattern;

AskPattern.ask(
    libSpiffy.coordinator(),
    replyTo -> new CoordinatorCommand.CreateInvoice(
        invoiceId, walletId,
        List.of(merchantAddress, platformAddress),
        55_000L,     // total amount
        List.of(
            new InvoiceOutputSpec.P2PKHOutputSpec(merchantAddress, 50_000L, "merchant"),
            new InvoiceOutputSpec.P2PKHOutputSpec(platformAddress, 5_000L, "platform-fee"),
            new InvoiceOutputSpec.OPReturnOutputSpec(
                List.of(("ORDER:" + orderId).getBytes()), false
            )
        ),
        "Order #789 with platform fee",
        Instant.now().plus(Duration.ofHours(24)),
        Map.of("orderId", orderId),
        replyTo
    ),
    Duration.ofSeconds(10),
    libSpiffy.system().scheduler()
).toCompletableFuture().join();
```

---

## Payment Matching

When your application detects a transaction paying to an invoice's addresses, mark the invoice as paid:

```java
import org.apache.pekko.actor.typed.javadsl.AskPattern;

CompletionStage<CoordinatorReply> reply = AskPattern.ask(
    libSpiffy.coordinator(),
    replyTo -> new CoordinatorCommand.MarkInvoicePaid(
        invoiceId,
        paymentTxid,           // transaction that paid the invoice
        amountReceivedSats,    // actual amount received
        paymentAddress,        // address that received payment
        replyTo
    ),
    Duration.ofSeconds(10),
    libSpiffy.system().scheduler()
);

CoordinatorReply result = reply.toCompletableFuture().join();
// result is InvoicePaid(id) on success, or Failure on error
```

**Payment matching is your application's responsibility.** The coordinator records the payment but does not monitor the blockchain. Your application should:

1. Watch for transactions to the invoice's addresses (via ARC callbacks, polling, or P2P)
2. Verify the transaction outputs match the invoice specifications
3. Send `MarkInvoicePaid` via the coordinator when a valid payment is detected

---

## Invoice Expiration and Cancellation

> **Direct aggregate access (exception):** `ExpireInvoice` and `CancelInvoice` are not yet available on the coordinator. These operations still require direct aggregate interaction.

### Expiration

Invoices have an `expiresAt` timestamp. Your application should periodically check for and expire overdue invoices:

```java
// Find expired invoices via read model
var invoiceStorage = new InvoiceReadModelStorage();
List<Invoice> expired = invoiceStorage.findExpiredInvoices(dataSource, Instant.now());

for (var inv : expired) {
    EntityRef<InvoiceCommand> ref = sharding.entityRefFor(
        InvoiceAggregate.ENTITY_TYPE_KEY, inv.invoiceId()
    );

    ref.ask(
        replyTo -> new InvoiceCommand.ExpireInvoiceCommand(inv.invoiceId(), replyTo),
        Duration.ofSeconds(10)
    ).toCompletableFuture().join();
}
```

### Cancellation

Cancel an invoice that is no longer needed:

```java
EntityRef<InvoiceCommand> invoiceRef = sharding.entityRefFor(
    InvoiceAggregate.ENTITY_TYPE_KEY, invoiceId
);

invoiceRef.ask(
    replyTo -> new InvoiceCommand.CancelInvoiceCommand(invoiceId, replyTo),
    Duration.ofSeconds(10)
).toCompletableFuture().join();
```

Only `PENDING` invoices can be expired or cancelled.

---

## Querying Invoices

Use `InvoiceReadModelStorage` for read model queries:

```java
var storage = new InvoiceReadModelStorage();

// Find a specific invoice
Optional<Invoice> invoice = storage.findInvoice(dataSource, invoiceId);

// List invoices for a wallet (with status filter and pagination)
List<Invoice> pending = storage.listInvoices(
    dataSource, walletId, InvoiceStatus.PENDING, 50, 0
);

// List all invoices (no status filter)
List<Invoice> all = storage.listInvoices(
    dataSource, walletId, null, 100, 0
);

// Find invoices past their expiration time
List<Invoice> expired = storage.findExpiredInvoices(dataSource, Instant.now());

// Get output specifications for an invoice
List<InvoiceOutputSpec> outputs = storage.findInvoiceOutputs(dataSource, invoiceId);
```

**Invoice record fields:**

| Field | Type | Description |
|-------|------|-------------|
| `invoiceId` | String | Unique identifier |
| `walletId` | String | Associated wallet |
| `addresses` | List\<String\> | Payment addresses |
| `amountSats` | long | Expected amount |
| `outputs` | List\<InvoiceOutputSpec\> | Output specifications |
| `description` | String | Human-readable description |
| `status` | InvoiceStatus | Current lifecycle status |
| `createdAt` | Instant | Creation timestamp |
| `expiresAt` | Instant | Expiration timestamp |
| `paidAt` | Instant | When payment was recorded (if PAID) |
| `paymentTxid` | String | Payment transaction ID (if PAID) |
| `amountReceivedSats` | Long | Actual amount received (if PAID) |
| `metadata` | Map\<String, Object\> | Application-specific data |
