# libspiffy4j

Enterprise server-side BSV wallet library for the JVM. Builds on [bitcoin4j](https://github.com/twostack/bitcoin4j) (v1.7.0) to provide UTXO management, SPV validation, transaction broadcasting via ARC, event-sourced wallet state, address discovery, and a token plugin system.

## Dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.twostack:libspiffy4j:1.0-SNAPSHOT")
}
```

Requires Java 21+. `bitcoin4j:1.7.0` is exposed as an `api` dependency (transitive).

## Quick start

### Stateless Services (No Pekko)

```java
// 1. Configure ARC
var arcConfig = ArcServiceConfig.taalMainnet();
var arcService = new ArcService(arcConfig);

// 2. Set up block header storage
BlockHeaderStore headerStore = new BlockHeaderChain(); // in-memory, 2016-header LRU

// 3. Bulk-sync headers from CDN (optional bootstrap)
var cdnConfig = new CdnHeaderSyncConfig(
    "https://headers.example.com", "mainnet",
    4, Duration.ofSeconds(30), false, false, null, 3);
var cdnSync = new CdnHeaderSyncService(cdnConfig, headerStore);
cdnSync.synchronize();

// 4. Broadcast a transaction
ArcSubmitResponse submit = arcService.submitTransaction(rawTxHex);

// 5. Import and SPV-validate a confirmed transaction
var importService = new TransactionImportService(arcService, headerStore);
ImportedTransaction imported = importService.importTransaction(submit.txid());
assert imported.spvValid();
```

### Coordinator API (Event-Sourced Layer)

> **The Coordinator API is the recommended entry point for all event-sourced wallet, invoice, and payment operations.** It provides a single unified command/reply interface that ensures coordination logic (UTXO reservation, transaction recording, etc.) is applied consistently. Direct aggregate interaction is strongly discouraged.

```java
var libSpiffy = LibSpiffy4j.builder()
    .dataSource(dataSource)
    .encryptionMasterKey(key)
    .registerPlugin(myTokenPlugin)       // optional: programmatic plugin registration
    .enableServiceLoaderPlugins()        // optional: ServiceLoader discovery
    .build();

// All wallet operations go through the coordinator
ActorRef<CoordinatorCommand> coordinator = libSpiffy.coordinator();

// Create a wallet
CompletionStage<CoordinatorReply> reply = AskPattern.ask(
    coordinator,
    replyTo -> new CoordinatorCommand.CreateWallet(
        walletId, "My Wallet", WalletType.HD, NetworkType.MAINNET,
        rootAddress, Map.of(), replyTo),
    Duration.ofSeconds(10),
    libSpiffy.system().scheduler()
);

// Check balance (reads directly from CQRS read model)
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.GetBalance(walletId, replyTo), ...);

// Build and sign a payment
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.BuildPayment(
        walletId, outputs, config, changeAddress, replyTo), ...);
```

## Features

- UTXO management with coin selection (4 strategies)
- SPV validation with merkle proofs (BUMP format)
- BEEF (Background Evaluation Extended Format) construction and parsing
- Transaction broadcasting via ARC
- Block header sync from CDN
- BIP39/BIP44 key derivation
- AES-256-GCM encrypted key storage (HKDF context keys)
- Event-sourced wallet, invoice, and payment channel aggregates
- **Coordinator API** -- unified command/reply interface for the event-sourced layer
- **Token Plugin System** -- ScriptPlugin, TransactionBuilderPlugin, PluginRegistry, CallbackTransactionSigner
- CQRS read models for wallets, invoices, and channels
- Payment channel lifecycle (T1/T2/T3 transactions)
- Wallet recovery from XPRIV
- Chain reorganization handling

## Library layers

| Layer | Packages | Pekko required? |
|-------|----------|-----------------|
| **SPV & merkle proofs** | `spv` | No |
| **Services** (ARC, header sync, tx import, coin selection, address discovery) | `service` | No |
| **Coordinator** (unified command API for wallets, invoices, payments, plugins) | `coordinator` | Yes |
| **Aggregates & projections** (event-sourced wallet, channels, invoices) | `aggregate`, `projection` | Yes |

The `spv` and `service` packages can be used standalone without an actor system. The `coordinator` layer is the recommended entry point for the event-sourced tier -- it wraps all aggregate and projection interactions behind a single `ActorRef<CoordinatorCommand>`. Direct aggregate access is available but discouraged.

## Documentation

### Developer Guide

- **[Application Developer Guide](APP_DEVELOPER_GUIDE.md)** -- complete API reference for all tiers (stateless, SPV, coordinator, event-sourced)

### Feature Guides

- [Wallet Lifecycle](docs/wallet-lifecycle-guide.md) -- create wallet, derive addresses, build transactions, SPV confirmation, UTXO state machine
- [Payment Channels](docs/payment-channels-guide.md) -- T1/T2/T3 transactions, channel state machine, client/server workflows
- [Invoice Management](docs/invoice-guide.md) -- invoice lifecycle, output specs, payment matching, expiration
- [Secure Storage](docs/secure-storage-guide.md) -- master key management, HKDF encryption, key rotation

### Infrastructure Guides

- [Integration Guide](docs/integration-guide.md) -- composing libspiffy4j with a P2P node or other header sources
- [ARC Dependency](docs/arc-dependency.md) -- ARC configuration, error handling, and why ARC is mandatory
- [Architecture](docs/architecture.md) -- full architectural overview and design decisions

### Examples

- [`examples/StatelessServicesExample.java`](examples/StatelessServicesExample.java) -- key derivation, tx building, coin selection (no Pekko)
- [`examples/SpvValidationExample.java`](examples/SpvValidationExample.java) -- ARC, headers, transaction import, BEEF (no Pekko)
- [`examples/EventSourcedWalletExample.java`](examples/EventSourcedWalletExample.java) -- full LibSpiffy4j setup, wallet CRUD, read models (Pekko + PostgreSQL)
