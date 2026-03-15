# libspiffy4j

Enterprise server-side BSV wallet library for the JVM. Builds on [bitcoin4j](https://github.com/twostack/bitcoin4j) (v1.7.0) to provide UTXO management, SPV validation, transaction broadcasting via ARC, event-sourced wallet state, and address discovery.

## Dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.twostack:libspiffy4j:1.0-SNAPSHOT")
}
```

Requires Java 21+. `bitcoin4j:1.7.0` is exposed as an `api` dependency (transitive).

## Quick start

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

## Library layers

| Layer | Packages | Pekko required? |
|-------|----------|-----------------|
| **SPV & merkle proofs** | `spv` | No |
| **Services** (ARC, header sync, tx import, coin selection, address discovery) | `service` | No |
| **Aggregates & projections** (event-sourced wallet, channels, invoices) | `aggregate`, `projection` | Yes |

The `spv` and `service` packages can be used standalone without an actor system. The `aggregate` layer requires Apache Pekko (persistence, cluster sharding, projections) and PostgreSQL.

## Documentation

### Developer Guide

- **[Application Developer Guide](APP_DEVELOPER_GUIDE.md)** -- complete API reference for all three tiers (stateless, SPV, event-sourced)

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
