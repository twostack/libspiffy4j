# libspiffy4j Application Developer Guide

**Version:** 1.0.0
**Audience:** Application developers building Bitcoin SV applications with libspiffy4j

---

## Table of Contents

1. [Introduction](#introduction)
2. [Quick Start](#quick-start)
3. [Coordinator API (Recommended)](#coordinator-api-recommended)
   - [Overview](#overview)
   - [Accessing the Coordinator](#accessing-the-coordinator)
   - [Wallet Commands](#wallet-commands)
   - [Invoice Commands](#invoice-commands)
   - [Payment Commands](#payment-commands)
   - [Plugin Payment Commands](#plugin-payment-commands)
   - [Read Queries](#read-queries)
   - [Handling Replies](#handling-replies)
4. [Token Plugin System](#token-plugin-system)
   - [ScriptPlugin](#scriptplugin)
   - [TransactionBuilderPlugin](#transactionbuilderplugin)
   - [PluginRegistry](#pluginregistry)
   - [CallbackTransactionSigner](#callbacktransactionsigner)
   - [TransactionLookup](#transactionlookup)
   - [PluginTransactionRequest](#plugintransactionrequest)
   - [TransactionBuilderResult](#transactionbuilderresult)
   - [Token Capture Pipeline](#token-capture-pipeline)
   - [PluginOutputSpec](#pluginoutputspec)
   - [ServiceLoader Support](#serviceloader-support)
5. [Stateless Services (No Pekko Required)](#stateless-services-no-pekko-required)
   - [CryptoService](#cryptoservice)
   - [EncryptionService](#encryptionservice)
   - [TransactionBuildService](#transactionbuildservice)
   - [CoinSelector](#coinselector)
   - [MultisigTransactionService](#multisigtransactionservice)
   - [UtxoSplitService](#utxosplitservice)
   - [ArcService](#arcservice)
   - [CdnHeaderSyncService](#cdnheadersyncservice)
   - [TransactionImportService](#transactionimportservice)
   - [AddressDiscoveryService](#addressdiscoveryservice)
   - [ChainTipTracker](#chaintiptracker)
   - [WalletRecoveryService](#walletrecoveryservice)
   - [ReorganizationHandler](#reorganizationhandler)
   - [PaymentChannelBuilder](#paymentchannelbuilder)
6. [SPV Layer (No Pekko Required)](#spv-layer-no-pekko-required)
   - [BlockHeader](#blockheader)
   - [BlockHeaderStore / BlockHeaderChain](#blockheaderstore--blockheaderchain)
   - [Bump / BumpLeaf / BumpLevel](#bump--bumpleaf--bumplevel)
   - [Beef / BeefBuilder](#beef--beefbuilder)
7. [Event-Sourced Layer (Pekko + PostgreSQL Required)](#event-sourced-layer-pekko--postgresql-required)
   - [LibSpiffy4j Bootstrap and Lifecycle](#libspiffy4j-bootstrap-and-lifecycle)
   - [WalletAggregate](#walletaggregate)
   - [InvoiceAggregate](#invoiceaggregate)
   - [ChannelAggregate](#channelaggregate)
   - [ChannelWalletSaga](#channelwalletsaga)
   - [Read Models](#read-models)
   - [SecureStorage](#securestorage)
8. [Internal APIs](#internal-apis-)
9. [Common Patterns](#common-patterns)
10. [Error Handling](#error-handling)
11. [Testing Your Application](#testing-your-application)
12. [Model Records Reference](#model-records-reference)

---

## Introduction

libspiffy4j is an enterprise server-side BSV wallet library for the JVM. It builds on [bitcoin4j](https://github.com/twostack/bitcoin4j) (v1.7.0) to provide UTXO management, SPV validation, transaction broadcasting via ARC, event-sourced wallet state, payment channels, invoicing, and a token plugin system.

### Three Usage Tiers

The library is organized into three tiers with increasing infrastructure requirements:

| Tier | Packages | Infrastructure | Use Case |
|------|----------|---------------|----------|
| **Stateless Services** | `service` | None | Key derivation, tx building, ARC broadcast, SPV import |
| **SPV Layer** | `spv` | None | Block headers, BEEF construction, merkle proofs |
| **Event-Sourced** | `coordinator`, `aggregate`, `projection`, `storage` | Pekko + PostgreSQL | Persistent wallet/invoice/channel state, read models, plugin payments |

You can use the first two tiers without any actor system or database. The event-sourced layer adds durable state management via Apache Pekko persistence and PostgreSQL. **The Coordinator API is the recommended entry point for the event-sourced layer.**

### API Classification

- **PUBLIC API:** Intended for application developers. Stable interfaces documented in this guide.
- **INTERNAL API:** Used by the library internally (state classes, event types, projection setup, serialization). Use only when building custom integrations.

### Prerequisites

- Java 21+
- PostgreSQL (event-sourced tier only)
- `bitcoin4j:1.7.0` (transitive dependency)

---

## Quick Start

### Stateless Services Only (No Pekko, No Database)

```java
import org.twostack.libspiffy4j.service.*;
import org.twostack.libspiffy4j.model.*;

// Key derivation
var crypto = new CryptoService();
var mnemonic = crypto.generateMnemonic();
var hdKey = crypto.mnemonicToHDPrivateKey(mnemonic, "");
var privateKey = crypto.derivePrivateKey(hdKey, 0, 0, 0, false);
var address = crypto.generateAddress(privateKey, NetworkType.MAINNET);

// Transaction building
var buildService = new TransactionBuildService(crypto);
var result = buildService.buildTransaction(
    availableUtxos,
    List.of(new InvoiceOutputSpec.P2PKHOutputSpec(recipientAddress, 50_000L, "payment")),
    TransactionBuildConfig.standard(),
    changeAddress,
    signingKey,
    NetworkType.MAINNET
);

// ARC broadcast
var arc = new ArcService(ArcServiceConfig.taalMainnet());
var response = arc.submitTransaction(result.rawHex());
```

### Full Setup with Coordinator (Recommended)

```java
import org.twostack.libspiffy4j.LibSpiffy4j;
import javax.sql.DataSource;

// PostgreSQL DataSource (HikariCP, etc.)
DataSource dataSource = ...;

var libSpiffy = LibSpiffy4j.builder()
    .dataSource(dataSource)
    .objectMapper(new ObjectMapper())
    .encryptionMasterKey(EncryptionService.generateMasterKey()) // 32 bytes
    .registerPlugin(myTokenPlugin)       // optional: programmatic plugin registration
    .enableServiceLoaderPlugins()        // optional: ServiceLoader discovery
    .build();

// Access the coordinator -- the primary interface for the event-sourced layer
var coordinator = libSpiffy.coordinator(); // ActorRef<CoordinatorCommand>

// Create a wallet via the coordinator
CompletionStage<CoordinatorReply> reply = AskPattern.ask(
    coordinator,
    replyTo -> new CoordinatorCommand.CreateWallet(
        walletId, "My Wallet", WalletType.HD, NetworkType.MAINNET,
        rootAddress, Map.of(), replyTo),
    Duration.ofSeconds(10),
    libSpiffy.system().scheduler()
);

// Access stateless services (these don't go through the coordinator)
var crypto = libSpiffy.cryptoService();
var encryption = libSpiffy.encryptionService();
var txBuild = libSpiffy.transactionBuildService();

// Graceful shutdown
libSpiffy.close();
```

---

## Coordinator API (Recommended)

### Overview

The `WalletCoordinator` is a stateless Pekko Behavior that provides a unified command/reply API for all wallet, invoice, and payment operations. It is the **primary interface for the event-sourced layer**.

> **All event-sourced operations should go through the Coordinator.** Direct aggregate interaction bypasses coordination logic and can leave the wallet in an inconsistent state (e.g., UTXOs spent without transactions recorded, or payments built without proper UTXO reservation).

The coordinator:
- Ensures wallet operations are properly sequenced (reserve UTXOs before building, record tx after signing)
- Routes read queries directly to the CQRS read model (no aggregate involved)
- Delegates plugin payment flows to the appropriate `TransactionBuilderPlugin`
- Returns typed replies via `CoordinatorReply` (sealed interface)

### Accessing the Coordinator

```java
var libSpiffy = LibSpiffy4j.builder()
    .dataSource(dataSource)
    .encryptionMasterKey(key)
    .build();

// The coordinator is an ActorRef<CoordinatorCommand>
ActorRef<CoordinatorCommand> coordinator = libSpiffy.coordinator();

// Use Pekko's AskPattern to send commands and receive replies
CompletionStage<CoordinatorReply> reply = AskPattern.ask(
    coordinator,
    replyTo -> new CoordinatorCommand.CreateWallet(..., replyTo),
    Duration.ofSeconds(10),
    libSpiffy.system().scheduler()
);
```

### Wallet Commands

**CreateWallet** -- Initialize a new wallet:

```java
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.CreateWallet(
        walletId, "My Wallet", WalletType.HD, NetworkType.MAINNET,
        rootAddress, Map.of("source", "app"), replyTo),
    Duration.ofSeconds(10), scheduler);
// Reply: CoordinatorReply.WalletCreated
```

**RecordUtxo** -- Add a UTXO to a wallet:

```java
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.RecordUtxo(walletId, utxo, replyTo),
    Duration.ofSeconds(10), scheduler);
// Reply: CoordinatorReply.CommandAccepted
```

**RecordTransaction** -- Record a transaction in the wallet:

```java
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.RecordTransaction(walletId, transaction, replyTo),
    Duration.ofSeconds(10), scheduler);
// Reply: CoordinatorReply.CommandAccepted
```

**RecordAddress** -- Record a derived address:

```java
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.RecordAddress(walletId, addressMetadata, replyTo),
    Duration.ofSeconds(10), scheduler);
// Reply: CoordinatorReply.CommandAccepted
```

### Invoice Commands

**CreateInvoice** -- Create a new invoice:

```java
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.CreateInvoice(
        invoiceId, walletId,
        List.of(paymentAddress),
        50_000L,
        List.of(new InvoiceOutputSpec.P2PKHOutputSpec(paymentAddress, 50_000L, "order-123")),
        "Payment for Order #123",
        Instant.now().plus(Duration.ofHours(24)),
        Map.of("orderId", "123"),
        replyTo),
    Duration.ofSeconds(10), scheduler);
// Reply: CoordinatorReply.InvoiceCreated
```

**MarkInvoicePaid** -- Record a payment against an invoice:

```java
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.MarkInvoicePaid(
        invoiceId, paymentTxid, amountReceivedSats, paymentAddress, replyTo),
    Duration.ofSeconds(10), scheduler);
// Reply: CoordinatorReply.InvoicePaid
```

### Payment Commands

**BuildPayment** -- Select UTXOs, build, and sign a transaction:

```java
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.BuildPayment(
        walletId,
        List.of(new InvoiceOutputSpec.P2PKHOutputSpec(recipientAddr, 50_000L, "payment")),
        TransactionBuildConfig.standard(),
        changeAddress,
        replyTo),
    Duration.ofSeconds(10), scheduler);
// Reply: CoordinatorReply.PaymentBuilt (contains txid, rawHex, feeSats)
```

The coordinator handles the full payment lifecycle:
1. Selects UTXOs from the wallet (via read model)
2. Reserves selected UTXOs
3. Builds and signs the transaction
4. Records the transaction in the wallet
5. Returns the signed transaction

### Plugin Payment Commands

**BuildPluginPayment** -- Delegate transaction building to a `TransactionBuilderPlugin`:

```java
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.BuildPluginPayment(
        walletId,
        "ordinals-plugin",          // pluginId
        "mint",                      // action
        Map.of(                      // pluginParams
            "contentType", "image/png",
            "data", base64Data
        ),
        TransactionBuildConfig.standard(),
        changeAddress,
        replyTo),
    Duration.ofSeconds(10), scheduler);
// Reply: CoordinatorReply.PluginPaymentBuilt
```

The coordinator resolves the plugin from the `PluginRegistry`, then:

1. Reads available UTXOs from the wallet's read model
2. Creates a `CallbackTransactionSigner` closure (private key never exposed)
3. Creates a `TransactionLookup` closure over the read model for transaction resolution
4. Passes everything to the plugin via `PluginTransactionRequest`
5. Validates the returned transaction via `plugin.validateTransactionStructure()`
6. Auto-records wallet-owned output UTXOs (both standard P2PKH and plugin-identified)
7. The projection layer subsequently enriches each UTXO with plugin metadata via `identifyScript()` + `extractMetadata()`

### Read Queries

Read queries go directly to the CQRS read model -- no aggregate is involved, and the coordinator does not load or replay events.

**GetBalance:**

```java
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.GetBalance(walletId, replyTo),
    Duration.ofSeconds(10), scheduler);
// Reply: CoordinatorReply.BalanceResult
```

**GetTransactions:**

```java
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.GetTransactions(walletId, /*limit=*/50, /*offset=*/0, replyTo),
    Duration.ofSeconds(10), scheduler);
// Reply: CoordinatorReply.TransactionsResult
```

**GetUtxos:**

```java
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.GetUtxos(walletId, replyTo),
    Duration.ofSeconds(10), scheduler);
// Reply: CoordinatorReply.UtxosResult
```

### Handling Replies

All coordinator commands return a `CoordinatorReply` (sealed interface). Always check for `Failure`:

```java
CompletionStage<CoordinatorReply> reply = AskPattern.ask(coordinator, ...);
reply.whenComplete((result, error) -> {
    if (error != null) {
        // Pekko communication error (timeout, etc.)
    } else if (result instanceof CoordinatorReply.Failure failure) {
        // Domain error
        System.err.println("Failed: " + failure.message());
    } else if (result instanceof CoordinatorReply.WalletCreated created) {
        System.out.println("Wallet created: " + created.walletId());
    } else if (result instanceof CoordinatorReply.PaymentBuilt payment) {
        System.out.println("Tx built: " + payment.txid());
        // Broadcast via ARC
        arc.submitTransaction(payment.rawHex());
    }
});
```

---

## Token Plugin System

The plugin system enables third-party token libraries (e.g., tstokenlib4j for TSL1 tokens) to participate in transaction building, script identification, and metadata extraction without accessing private keys directly. All data flows through the wallet's append-only log.

### ScriptPlugin

Base interface for plugins that identify, parse, and build custom locking/unlocking scripts:

```java
public interface ScriptPlugin {
    String pluginId();                                    // unique ID (e.g., "tsl1")
    String displayName();                                 // human-readable name
    List<String> scriptTypes();                           // script types handled (e.g., ["pp1_nft", "pp1_ft"])
    String identifyScript(byte[] scriptPubKey);           // return script type or null
    Map<String, Object> extractMetadata(byte[] scriptPubKey); // extract token metadata from script
    byte[] createLockingScript(PluginLockSpec spec);      // build a locking script
    byte[] createUnlockingScript(PluginUnlockSpec spec);  // build an unlocking script
}
```

The `extractMetadata()` method should include an `"ownerAddress"` key (base58 string) in the returned map -- the coordinator uses this to determine which outputs belong to the wallet.

### TransactionBuilderPlugin

Extended interface for plugins that build complete multi-output transactions (e.g., TSL1's 5-output token structure). Extends `ScriptPlugin`:

```java
public interface TransactionBuilderPlugin extends ScriptPlugin {
    List<String> supportedActions();                                  // e.g., ["nft.issue", "nft.transfer"]
    TransactionBuilderResult buildTransaction(PluginTransactionRequest request);
    boolean validateTransactionStructure(byte[] rawTx, String action);
}
```

The plugin receives funding UTXOs, a signing callback, a transaction lookup, public keys, and plugin-specific parameters via `PluginTransactionRequest`. It returns a fully signed transaction without ever seeing the private key.

### PluginRegistry

Thread-safe registry for plugin discovery and lookup:

```java
// Programmatic registration via the builder
var libSpiffy = LibSpiffy4j.builder()
    .dataSource(ds)
    .encryptionMasterKey(key)
    .registerPlugin(new MyTokenPlugin())
    .build();

// Or register at runtime
PluginRegistry registry = libSpiffy.pluginRegistry();
registry.register(new AnotherPlugin());

// Lookup
Optional<TransactionBuilderPlugin> plugin = registry.getTransactionBuilderPlugin("tsl1");

// Script identification (tries all registered plugins)
Optional<PluginIdentification> id = registry.identifyScript(scriptPubKeyBytes);
// Returns PluginIdentification(pluginId, scriptType) or empty
```

### CallbackTransactionSigner

Secure signing callback passed to plugins. The coordinator creates it by closing over the private key in a lambda -- the plugin can request signatures but cannot extract the key:

```java
@FunctionalInterface
public interface CallbackTransactionSigner {
    byte[] sign(byte[] sighash, int inputIndex);  // returns DER-encoded signature
}
```

### TransactionLookup

Callback for resolving raw transaction hex from the wallet's read model. Ensures plugins retrieve transaction data through the wallet rather than receiving it externally:

```java
@FunctionalInterface
public interface TransactionLookup {
    String lookupRawHex(String txid);  // returns raw hex or null if not found
}
```

The coordinator creates this by closing over the read model storage:

```java
TransactionLookup lookup = txid ->
    readModelStorage.findRawHexByTxid(dataSource, txid).orElse(null);
```

Plugins use it to resolve parent transactions, witness transactions, and other context needed for transaction construction -- all from the wallet's own append-only log.

### PluginTransactionRequest

Request object passed to `TransactionBuilderPlugin.buildTransaction()`. Contains everything a plugin needs without direct key access:

```java
public record PluginTransactionRequest(
    List<BitcoinUtxo> fundingUtxos,         // wallet's available UTXOs
    CallbackTransactionSigner signer,       // secure signing callback (required)
    TransactionLookup transactionLookup,    // wallet transaction resolution (nullable)
    List<String> publicKeyHexes,            // hex-encoded public keys for unlock scripts
    String changeAddress,                   // address for the change output
    Map<String, Object> params              // plugin-specific parameters
)
```

### TransactionBuilderResult

Result from `TransactionBuilderPlugin.buildTransaction()`:

```java
public record TransactionBuilderResult(
    String txid,      // transaction ID (double-SHA256, hex-encoded)
    String rawHex,    // raw transaction hex
    long feeSats      // fee paid in satoshis (>= 0)
)
```

### Token Capture Pipeline

When a transaction flows through the wallet (via `BuildPluginPayment` or `RecordTransaction`), the plugin system automatically captures token outputs:

1. **Auto-record**: The coordinator parses the transaction's outputs and records wallet-owned UTXOs. For plugin-managed scripts, it calls `identifyScript()` + `extractMetadata()` to match `ownerAddress` against wallet addresses.
2. **Event**: Each recorded UTXO emits a `UtxoReceivedEvent` through the event-sourced aggregate.
3. **Projection enrichment**: The `WalletProjectionHandler` subscribes to `UtxoReceivedEvent` and calls `pluginRegistry.identifyScript()` + `plugin.extractMetadata()` on each UTXO's `scriptPubKey`. The `pluginId` and `pluginMetadata` fields are populated before persisting to the read model.
4. **Query**: The enriched UTXOs are available via `GetUtxos` with `pluginId` and `pluginMetadata` populated -- applications can filter by `pluginId` to find token UTXOs.

The `wallet_utxo` table stores `script_pub_key` so that retroactive identification is possible when new plugins are registered.

### PluginOutputSpec

A new variant of `InvoiceOutputSpec` for plugin-managed outputs in invoices:

```java
// Create an invoice with plugin-managed outputs
var pluginOutput = new InvoiceOutputSpec.PluginOutputSpec(
    "ordinals-plugin",              // pluginId
    50_000L,                         // amountSats
    Map.of("contentType", "text/plain", "data", "hello world")
);

AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.CreateInvoice(
        invoiceId, walletId,
        List.of(address),
        50_000L,
        List.of(pluginOutput),       // PluginOutputSpec in output list
        "Ordinal inscription",
        expiresAt, Map.of(), replyTo),
    Duration.ofSeconds(10), scheduler);
```

### ServiceLoader Support

Plugins can be discovered automatically via Java's `ServiceLoader` mechanism:

```java
// Enable in the builder
var libSpiffy = LibSpiffy4j.builder()
    .dataSource(ds)
    .encryptionMasterKey(key)
    .enableServiceLoaderPlugins()    // discovers plugins via META-INF/services
    .build();
```

To make a plugin discoverable, create a file `META-INF/services/org.twostack.libspiffy4j.plugin.TransactionBuilderPlugin` containing the fully qualified class name of your plugin implementation.

---

## Stateless Services (No Pekko Required)

These services can be instantiated directly without an actor system or database. They are pure functions over their inputs.

### CryptoService

BIP39 mnemonic generation, BIP44 key derivation, address generation, WIF conversion, and message signing.

```java
var crypto = new CryptoService();
```

**Mnemonic & HD Key Derivation:**

```java
// Generate a 12-word BIP39 mnemonic
List<String> mnemonic = crypto.generateMnemonic();

// Validate an existing mnemonic (throws MnemonicException)
crypto.validateMnemonic(mnemonic);

// Derive HD master key from mnemonic
DeterministicKey hdKey = crypto.mnemonicToHDPrivateKey(mnemonic, "optional-passphrase");

// BIP44 derivation: m/44'/coinType'/account'/change/index
// coinType: 0 = mainnet, 1 = testnet
DeterministicKey key = crypto.derivePrivateKey(hdKey, /*account=*/0, /*index=*/0,
                                                /*coinType=*/0, /*isChange=*/false);

// Arbitrary derivation path
DeterministicKey child = crypto.deriveKeyForPath(hdKey, 44, 0, 0, 0, 5);
```

**Address & WIF:**

```java
String address = crypto.generateAddress(key, NetworkType.MAINNET);
String wif = crypto.privateKeyToWIF(key, NetworkType.MAINNET);
ECKey restored = crypto.privateKeyFromWIF(wif); // throws InvalidKeyException
```

**Signing:**

```java
ECKey.ECDSASignature sig = crypto.signMessage(privateKey, messageBytes);
```

---

### EncryptionService

AES-256-GCM encryption with HKDF-derived context keys. Used internally by SecureStorage for encrypted key persistence.

```java
// Generate a 32-byte master key
byte[] masterKey = EncryptionService.generateMasterKey();
var encryption = new EncryptionService(masterKey);

// Encrypt with context-based key derivation
// The context string is used with HKDF to derive a unique subkey
EncryptionResult result = encryption.encrypt(plaintext, "wallet:abc123:xpriv");
byte[] ciphertext = result.ciphertext();
byte[] nonce = result.nonce();

// Decrypt with the same context
byte[] decrypted = encryption.decrypt(ciphertext, nonce, "wallet:abc123:xpriv");
```

The context parameter ensures that the same master key produces different derived keys for different purposes. Use structured context strings like `"wallet:<walletId>:<keyType>"`.

---

### TransactionBuildService

Builds signed Bitcoin transactions with automatic fee estimation, coin selection, and change output handling.

```java
var buildService = new TransactionBuildService(cryptoService);

TransactionBuildResult result = buildService.buildTransaction(
    availableUtxos,          // List<BitcoinUtxo> - spendable UTXOs
    outputs,                 // List<InvoiceOutputSpec> - payment outputs
    TransactionBuildConfig.standard(),
    changeAddress,           // String - address for change output
    signingKey,              // ECKey - private key for signing inputs
    NetworkType.MAINNET
);

// Result fields
String txid = result.txid();
String rawHex = result.rawHex();
long feeSats = result.feeSats();
```

**TransactionBuildConfig Options:**

```java
// Pre-configured profiles
var standard = TransactionBuildConfig.standard();  // Full build with sanity checks
var partial = TransactionBuildConfig.partial();     // For partial/multisig builds

// Custom configuration
var config = new TransactionBuildConfig(
    500L,                              // feePerKb (satoshis)
    UtxoSelectionStrategy.OPTIMAL_CHANGE,
    546L,                              // minChangeAmountSats (dust threshold)
    false,                             // forceChange
    false,                             // enableRBF
    true                               // performSanityChecks
);
```

**Fee Estimation:**

```java
long fee = buildService.calculateFee(inputCount, outputCount, feePerKb);
```

The build process uses iterative fee estimation (up to 3 iterations) to account for the variable size of the signed transaction.

**Output Types:**

```java
// P2PKH (pay-to-address)
new InvoiceOutputSpec.P2PKHOutputSpec(address, 50_000L, "payment")

// P2MS (pay-to-multisig) - threshold-of-n
new InvoiceOutputSpec.P2MSOutputSpec(
    List.of(pubKeyHex1, pubKeyHex2), // public keys
    2,                                // threshold (2-of-2)
    100_000L,                         // amount
    "multisig-escrow"
)

// OP_RETURN (data carrier)
new InvoiceOutputSpec.OPReturnOutputSpec(
    List.of("hello".getBytes()),      // data chunks (max 99KB total)
    false                              // separateOutputs
)

// Plugin-managed output (see Token Plugin System)
new InvoiceOutputSpec.PluginOutputSpec("my-plugin", 50_000L, Map.of(...))
```

---

### CoinSelector

Selects UTXOs to fund a transaction using one of four strategies.

```java
var selector = new CoinSelector();

CoinSelector.CoinSelectionResult result = selector.select(
    availableUtxos,    // List<BitcoinUtxo>
    targetSats,        // long - amount needed
    UtxoSelectionStrategy.OPTIMAL_CHANGE
);

List<BitcoinUtxo> selected = result.selected();
long totalSelected = result.totalSelected();
long change = result.change();
```

**Strategies:**

| Strategy | Algorithm | Best For |
|----------|-----------|----------|
| `SMALLEST_FIRST` | Ascending by value | Consolidating dust UTXOs |
| `LARGEST_FIRST` | Descending by value | Minimizing input count |
| `RANDOM` | Shuffled selection | Privacy (avoids patterns) |
| `OPTIMAL_CHANGE` | Branch-and-bound (100K iterations) | Minimizing change output size |

If no combination satisfies the target, `select()` throws an insufficient-funds exception.

---

### MultisigTransactionService

Builds 2-of-2 multisig funding transactions and signs individual multisig inputs.

```java
var multisig = new MultisigTransactionService(transactionBuildService);

// Build a 2-of-2 multisig funding transaction
TransactionBuildResult funding = multisig.buildFundingTransaction(
    clientPubKeyHex,
    serverPubKeyHex,
    100_000L,              // funding amount
    availableUtxos,
    TransactionBuildConfig.standard(),
    changeAddress,
    signingKey,
    NetworkType.MAINNET
);

// Sign a specific multisig input (e.g., for refund or payment tx)
byte[] signature = multisig.signMultisigInput(
    rawTxBytes,
    0,                     // input index
    privateKey,
    inputAmountSats
);
```

---

### UtxoSplitService

Splits a UTXO value across multiple addresses using a Benford distribution. This creates outputs with naturally-distributed amounts that resist chain analysis.

```java
var splitService = new UtxoSplitService();

List<InvoiceOutputSpec.P2PKHOutputSpec> outputs = splitService.generateBenfordSplit(
    1_000_000L,                        // total satoshis to split
    List.of(addr1, addr2, addr3),      // target addresses
    546L                               // minimum output (dust threshold)
);

// Each output gets a Benford-distributed portion of the total
```

---

### ArcService

HTTP client for the ARC transaction processor. Handles broadcast, status queries, and merkle proof retrieval.

```java
// Pre-configured for TAAL
var arc = new ArcService(ArcServiceConfig.taalMainnet());

// Or custom
var arc = new ArcService(ArcServiceConfig.custom(
    "https://arc.example.com", "your-api-key", "https://your-app.com/callback"
));
```

**Submit a Transaction:**

```java
ArcSubmitResponse response = arc.submitTransaction(rawTxHex);
// With callback URL for async status updates
ArcSubmitResponse response = arc.submitTransaction(rawTxHex, "https://your-app.com/tx-callback");

String txid = response.txid();
ArcTransactionStatus status = response.status();
```

**Query Transaction Status:**

```java
ArcTransactionResponse txInfo = arc.queryTransaction(txid);
int blockHeight = txInfo.blockHeight();
```

**Get Merkle Proof:**

```java
MerkleProofData proof = arc.getMerkleProof(txid);
Bump bump = proof.bump();
long blockHeight = proof.blockHeight();
```

See [ARC Dependency Guide](docs/arc-dependency.md) for configuration details, error handling, and why ARC is mandatory.

---

### CdnHeaderSyncService

Bulk-downloads block headers from a CDN endpoint to bootstrap your local header store.

```java
var config = new CdnHeaderSyncConfig(
    "https://headers.example.com",  // base URL
    "mainnet",                      // network
    4,                              // parallel download threads
    Duration.ofSeconds(30),         // request timeout
    false,                          // verifyHeaders
    false,                          // downloadAll
    null,                           // fromHeight (null = continue from chain tip)
    3                               // maxRetries
);

var cdnSync = new CdnHeaderSyncService(config, headerStore);
CdnSyncResult result = cdnSync.synchronize();
```

---

### TransactionImportService

Fetches transactions and merkle proofs from ARC, then validates them against local block headers (SPV).

```java
var importService = new TransactionImportService(arcService, headerStore);

// Import a single confirmed transaction with SPV validation
ImportedTransaction imported = importService.importTransaction(txid);
boolean valid = imported.spvValid();
Bump bump = imported.bump();

// Batch import
List<ImportedTransaction> batch = importService.importTransactionBatch(
    List.of(txid1, txid2, txid3)
);
```

**Pending Transaction Tracking:**

```java
// Track an unconfirmed transaction
importService.trackPendingTransaction(txid);

// Check pending set
Set<String> pending = importService.getPendingTxids();

// On new block, attempt to import all pending txs
List<ImportedTransaction> confirmed = importService.onNewBlock(blockHeight);
```

**Reorg Support:**

```java
// Move a transaction back to pending (e.g., during chain reorg)
importService.moveToPending(txid);

// Find confirmed txids in a height range (for reorg handling)
Set<String> affected = importService.getConfirmedTxidsInRange(fromHeight, toHeight);
```

---

### AddressDiscoveryService

BIP44 gap-limit address scanning. Discovers used addresses by probing an external lookup function.

```java
var discoveryService = new AddressDiscoveryService(
    cryptoService,
    (address) -> {
        // Return true if address has been used on-chain
        return yourBlockchainLookup.hasTransactions(address);
    }
);

AddressDiscoveryResult result = discoveryService.discoverAddresses(
    hdKey,
    NetworkType.MAINNET,
    20,                            // gap limit
    (discovered) -> {              // progress callback
        System.out.println("Found: " + discovered.address());
    }
);
```

---

### ChainTipTracker

Monitors the chain tip and tracks confirmation progress for specific transactions.

```java
var tracker = new ChainTipTracker(arcService);
// Or with custom confirmation threshold
var tracker = new ChainTipTracker(arcService, 6);

// Update chain tip (call when you learn of a new block)
tracker.updateNetworkHeight(850_000L, blockHash);

// Track a transaction's confirmations
tracker.trackTransaction(txid, (update) -> {
    System.out.println(txid + " now has " + update.confirmations() + " confirmations");
});

// Check if a tracked transaction is confirmed
boolean confirmed = tracker.isConfirmed(txid);
long height = tracker.getNetworkHeight();
```

---

### WalletRecoveryService

Recovers a complete wallet from an extended private key (XPRIV) by discovering addresses, importing transactions, and reconstructing UTXO state. Requires the event-sourced layer (Pekko).

```java
var recoveryService = new WalletRecoveryService(
    sharding, cryptoService, discoveryService, importService,
    Duration.ofSeconds(30)  // ask timeout
);

CompletionStage<WalletRecoveryResult> future = recoveryService.recoverWallet(
    "wallet-123",
    "Recovered Wallet",
    hdKey,
    NetworkType.MAINNET,
    20,                            // gap limit
    (status) -> System.out.println(status)  // progress callback
);

WalletRecoveryResult result = future.toCompletableFuture().join();
```

---

### ReorganizationHandler

Responds to chain reorganizations by invalidating block headers and moving affected transactions back to pending.

```java
var reorgHandler = new ReorganizationHandler(headerStore, importService);

// Register a listener for reorg events
reorgHandler.addListener((result) -> {
    System.out.println("Reorg: " + result.invalidatedTxids().size() + " txs affected");
});

// Handle a reorg: invalidate headers and provide replacements
ReorgResult result = reorgHandler.handleReorganization(
    850_000,                       // invalidFromHeight
    850_002,                       // invalidToHeight
    replacementHeaders             // Map<Integer, BlockHeader>
);
```

---

### PaymentChannelBuilder

Constructs the transactions used in payment channel lifecycle: funding (T1), refund (T2), and payment updates (T3).

```java
var channelBuilder = new PaymentChannelBuilder(multisigService);

// Build funding transaction (T1) - 2-of-2 multisig output
TransactionBuildResult funding = channelBuilder.buildFundingTransaction(
    clientPubKeyHex, serverPubKeyHex,
    100_000L,
    availableUtxos,
    TransactionBuildConfig.standard(),
    changeAddress, signingKey,
    NetworkType.MAINNET
);

// Sign a multisig input (for T2 refund or T3 payment)
byte[] sig = channelBuilder.signMultisigInput(rawTx, 0, privateKey, inputAmountSats);
```

See [Payment Channels Guide](docs/payment-channels-guide.md) for the full channel lifecycle.

---

## SPV Layer (No Pekko Required)

The SPV (Simplified Payment Verification) layer enables client-side transaction validation without a full node. It works with block headers, merkle proofs (BUMP), and the BEEF container format.

### BlockHeader

Minimal 80-byte Bitcoin block header. Parses, serializes, and computes the double-SHA256 block hash.

```java
// Parse from raw bytes
BlockHeader header = BlockHeader.parse(rawBytes);
BlockHeader header = BlockHeader.parse(rawBytes, offset);

// Serialize back to 80 bytes
byte[] serialized = header.serialize();

// Compute block hash
byte[] hash = header.getHash();          // Internal byte order (little-endian)
String hashHex = header.getHashHex();    // Display format (reversed, as shown in explorers)

// Access fields
long version = header.version();
byte[] prevHash = header.prevBlockHash();
byte[] merkleRoot = header.merkleRoot();
long timestamp = header.timestamp();
long bits = header.bits();
long nonce = header.nonce();
```

---

### BlockHeaderStore / BlockHeaderChain

`BlockHeaderStore` is the interface for block header storage. `BlockHeaderChain` is the default in-memory LRU implementation (max 2016 headers).

```java
// In-memory store (suitable for most applications)
BlockHeaderChain chain = new BlockHeaderChain();

// Add headers
chain.addHeader(850_000, header);

// Query
BlockHeader h = chain.getHeader(850_000);
BlockHeader h = chain.getHeaderByHash(hashHex);
int height = chain.getChainHeight();
int count = chain.size();

// Validate header continuity
boolean valid = chain.validateContinuity(fromHeight, toHeight);

// Reorg support
chain.invalidateRange(fromHeight, toHeight);
```

For custom storage (e.g., database-backed), implement `BlockHeaderStore`:

```java
public interface BlockHeaderStore {
    void addHeader(int height, BlockHeader header);
    BlockHeader getHeader(int height);
    int getChainHeight();
    default void invalidateRange(int fromHeight, int toHeight) {} // Optional
}
```

---

### Bump / BumpLeaf / BumpLevel

BSV Unified Merkle Path (BUMP) proves a transaction's inclusion in a block by encoding the merkle path from the transaction to the block's merkle root.

```java
// Parse from binary
Bump bump = Bump.parse(data);
Bump bump = Bump.parse(data, offset, bytesConsumed); // With offset tracking

// Serialize
byte[] serialized = bump.serialize();

// Access
long blockHeight = bump.blockHeight();
List<BumpLevel> path = bump.path();
int treeHeight = bump.treeHeight();

// Compute merkle root from a transaction's txid
byte[] merkleRoot = bump.computeMerkleRoot(txid);

// Validate the merkle path (checks internal consistency)
boolean valid = bump.validateMerklePath(txid);
```

To fully validate SPV, compare the computed merkle root against a trusted block header:

```java
byte[] computedRoot = bump.computeMerkleRoot(txid);
BlockHeader header = headerStore.getHeader((int) bump.blockHeight());
boolean spvValid = Arrays.equals(computedRoot, header.merkleRoot());
```

---

### Beef / BeefBuilder

Background Evaluation Extended Format (BEEF) bundles transactions with their merkle proofs into a single portable container. BEEF allows a recipient to verify transaction validity without querying the network.

**Building BEEF:**

```java
var builder = new BeefBuilder();

// Add transactions with merkle proofs (proven)
builder.addProvenTransaction(rawTx1, bump1);
builder.addProvenTransaction(rawTx2, bump2);

// Add unproven transactions (e.g., the new transaction being sent)
builder.addUnprovenTransaction(newRawTx);

Beef beef = builder.build();
byte[] serialized = beef.serialize();
```

**Parsing and Validating BEEF:**

```java
// Parse from hex or bytes
Beef beef = Beef.parse(hexString);
Beef beef = Beef.parse(rawBytes);

// Validate all proven transactions
boolean allValid = beef.validate();

// Validate a specific transaction
boolean valid = beef.validateTransaction(txid);

// Validate against a specific block header
boolean valid = beef.validateTransactionWithBlockHeader(txid, header);

// Access contents
int txCount = beef.transactionCount();
byte[] tx = beef.getTransaction(0);
byte[] found = beef.findTransactionByTxid(txid);
List<Bump> bumps = beef.bumps();
```

---

## Event-Sourced Layer (Pekko + PostgreSQL Required)

The event-sourced layer provides durable, auditable wallet state using Apache Pekko persistence with PostgreSQL. All state changes are captured as immutable events, enabling full history replay and temporal queries.

> **Use the [Coordinator API](#coordinator-api-recommended) for all event-sourced operations.** The aggregates documented below are internal implementation details. They are retained here for reference, but application code should interact exclusively through the coordinator.

### LibSpiffy4j Bootstrap and Lifecycle

```java
DataSource dataSource = ...; // PostgreSQL (HikariCP recommended)

var libSpiffy = LibSpiffy4j.builder()
    .dataSource(dataSource)
    .objectMapper(new ObjectMapper())               // For JSON serialization
    .encryptionMasterKey(EncryptionService.generateMasterKey()) // Optional
    .registerPlugin(myPlugin)                        // Optional: register plugins
    .enableServiceLoaderPlugins()                    // Optional: ServiceLoader discovery
    .meterRegistry(meterRegistry)                    // Optional (Micrometer)
    .configOverride(customConfig)                    // Optional (Typesafe Config)
    .build();
```

The builder:
1. Initializes the Pekko ActorSystem with JDBC persistence (PostgreSQL)
2. Sets up cluster sharding for wallet, invoice, and channel aggregates
3. Starts the WalletCoordinator behavior
4. Starts projection handlers that maintain read models
5. Initializes crypto, encryption, and transaction services
6. Registers plugins in the PluginRegistry

**Lifecycle:**

```java
// Access the coordinator (recommended)
var coordinator = libSpiffy.coordinator();  // ActorRef<CoordinatorCommand>

// Access services
var system = libSpiffy.system();             // ActorSystem<Void>
var crypto = libSpiffy.cryptoService();
var encryption = libSpiffy.encryptionService(); // null if no master key
var secureStorage = libSpiffy.secureStorage();
var txBuild = libSpiffy.transactionBuildService();
var multisig = libSpiffy.multisigTransactionService();
var utxoSplit = libSpiffy.utxoSplitService();
var pluginRegistry = libSpiffy.pluginRegistry();

// Graceful shutdown (30-second timeout)
libSpiffy.close();
```

---

### WalletAggregate

> **Direct aggregate interaction is discouraged.** Use the [Coordinator API](#coordinator-api-recommended) instead. Sending commands directly to aggregates bypasses coordination logic and can leave the wallet in an inconsistent state (e.g., UTXOs spent without transactions recorded, or payments built without proper UTXO reservation).

Event-sourced aggregate managing wallet state: addresses, UTXOs, transactions, and reservations. Interact via commands sent through Pekko's `ClusterSharding`.

**Sending Commands:**

```java
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;

ClusterSharding sharding = ClusterSharding.get(libSpiffy.system());
EntityRef<WalletCommand> walletRef = sharding.entityRefFor(
    WalletAggregate.ENTITY_TYPE_KEY, walletId
);

// Send command and wait for reply
CompletionStage<WalletReply> reply = walletRef.ask(
    replyTo -> new WalletCommand.CreateWalletCommand(
        walletId, "My Wallet", rootAddress,
        WalletType.HD, NetworkType.MAINNET,
        Map.of(), replyTo
    ),
    Duration.ofSeconds(10)
);
```

**Available Commands:**

| Command | Purpose |
|---------|---------|
| `CreateWalletCommand` | Initialize a new wallet |
| `RecordAddressCommand` | Record a derived address |
| `RecordUtxoCommand` | Add a UTXO to the wallet |
| `RecordTransactionCommand` | Record a transaction |
| `ReserveUtxoCommand` | Lock a UTXO for spending (with expiration) |
| `ReleaseUtxoCommand` | Unlock a reserved UTXO |
| `MarkUtxoSpentCommand` | Mark a UTXO as spent |
| `UpdateConfirmationCommand` | Update confirmation count |
| `CleanupExpiredReservationsCommand` | Release expired reservations |

See [Wallet Lifecycle Guide](docs/wallet-lifecycle-guide.md) for detailed command sequences.

---

### InvoiceAggregate

> **Direct aggregate interaction is discouraged.** Use the [Coordinator API](#coordinator-api-recommended) instead. Sending commands directly to aggregates bypasses coordination logic and can leave the wallet in an inconsistent state (e.g., UTXOs spent without transactions recorded, or payments built without proper UTXO reservation).

Event-sourced invoice lifecycle: creation, payment, expiration, and cancellation.

**Commands:**

| Command | Transition |
|---------|------------|
| `CreateInvoiceCommand` | -> PENDING |
| `MarkInvoicePaidCommand` | PENDING -> PAID |
| `ExpireInvoiceCommand` | PENDING -> EXPIRED |
| `CancelInvoiceCommand` | PENDING -> CANCELLED |

```java
EntityRef<InvoiceCommand> invoiceRef = sharding.entityRefFor(
    InvoiceAggregate.ENTITY_TYPE_KEY, invoiceId
);

invoiceRef.ask(
    replyTo -> new InvoiceCommand.CreateInvoiceCommand(
        invoiceId, walletId,
        List.of(paymentAddress),
        50_000L,
        List.of(new InvoiceOutputSpec.P2PKHOutputSpec(paymentAddress, 50_000L, "order-123")),
        "Payment for Order #123",
        Instant.now().plus(Duration.ofHours(24)),
        Map.of("orderId", "123"),
        replyTo
    ),
    Duration.ofSeconds(10)
);
```

See [Invoice Guide](docs/invoice-guide.md) for the full lifecycle.

---

### ChannelAggregate

> **Direct aggregate interaction is discouraged.** Use the [Coordinator API](#coordinator-api-recommended) instead. Sending commands directly to aggregates bypasses coordination logic and can leave the wallet in an inconsistent state (e.g., UTXOs spent without transactions recorded, or payments built without proper UTXO reservation).

Event-sourced payment channel state machine supporting the full lifecycle from negotiation through settlement.

**State Machine:**

```
NEGOTIATING -> FUNDING -> OPENING -> OPEN -> CLOSING -> CLOSED
     |                                  |
     +-> FAILED                         +-> EXPIRED
```

**Commands:**

| Command | Description |
|---------|-------------|
| `RequestChannelCommand` | Client initiates channel |
| `AcceptChannelCommand` | Server accepts |
| `RejectChannelCommand` | Reject during negotiation |
| `RecordServerAcceptanceCommand` | Client records server's acceptance |
| `RequestRefundSignatureCommand` | Build refund transaction |
| `ProvideRefundSignatureCommand` | Server countersigns refund |
| `OpenChannelCommand` | Confirm funding tx on-chain |
| `RecordPaymentCommand` | Record off-chain payment |
| `AcknowledgePaymentCommand` | Server acknowledges payment |
| `CloseChannelCommand` | Initiate settlement |
| `FinalizeCloseCommand` | Confirm settlement on-chain |
| `ClaimRefundCommand` | Claim refund after locktime |

See [Payment Channels Guide](docs/payment-channels-guide.md) for the full lifecycle.

---

### ChannelWalletSaga

Cross-aggregate coordinator that synchronizes wallet operations with channel lifecycle events:

- Reserves UTXOs in the wallet when a channel is being funded
- Releases reserved UTXOs if channel negotiation fails
- Records settlement transactions in the wallet when a channel closes

The saga runs automatically via Pekko projections. No direct interaction is needed.

---

### Read Models

Read models provide optimized query access to aggregate state. They are updated asynchronously by projection handlers. The [Coordinator API](#coordinator-api-recommended) uses these read models for `GetBalance`, `GetTransactions`, and `GetUtxos` queries.

**WalletReadModelStorage:**

```java
var walletStorage = new WalletReadModelStorage();

// Query wallet summary
Optional<WalletSummary> summary = walletStorage.findWalletSummary(dataSource, walletId);

// List all wallets
List<WalletSummary> wallets = walletStorage.listWalletSummaries(dataSource, 100, 0);

// Query UTXOs
List<BitcoinUtxo> allUtxos = walletStorage.findUtxosByWalletId(dataSource, walletId);
List<BitcoinUtxo> available = walletStorage.findUtxosByStatus(dataSource, walletId,
                                                              UtxoStatus.AVAILABLE);

// Query transactions
List<BitcoinTransaction> txs = walletStorage.findTransactionsByWalletId(dataSource,
                                                                         walletId, 50, 0);

// Query addresses
List<String> addresses = walletStorage.findAddressesByWalletId(dataSource, walletId);

// Get balance
Optional<WalletBalance> balance = walletStorage.getWalletBalance(dataSource, walletId);
```

**InvoiceReadModelStorage:**

```java
var invoiceStorage = new InvoiceReadModelStorage();

Optional<Invoice> invoice = invoiceStorage.findInvoice(dataSource, invoiceId);
List<Invoice> pending = invoiceStorage.listInvoices(dataSource, walletId,
                                                     InvoiceStatus.PENDING, 50, 0);
List<Invoice> expired = invoiceStorage.findExpiredInvoices(dataSource, Instant.now());
List<InvoiceOutputSpec> outputs = invoiceStorage.findInvoiceOutputs(dataSource, invoiceId);
```

**ChannelReadModelStorage** follows the same pattern for payment channel queries.

---

### SecureStorage

Encrypted key persistence using EncryptionService + PostgreSQL.

```java
var secureStorage = libSpiffy.secureStorage();
var encryption = libSpiffy.encryptionService();

// Encrypt and store a key
EncryptionResult encrypted = encryption.encrypt(xprivBytes, "wallet:" + walletId + ":xpriv");
secureStorage.storeEncryptedKey(conn, walletId, "xpriv",
    encrypted.ciphertext(), encrypted.nonce(), 1);

// Load and decrypt
Optional<EncryptedKeyRecord> record = secureStorage.loadEncryptedKey(dataSource,
    walletId, "xpriv");
if (record.isPresent()) {
    byte[] xpriv = encryption.decrypt(
        record.get().encryptedKey(),
        record.get().nonce(),
        "wallet:" + walletId + ":xpriv"
    );
}

// Delete
secureStorage.deleteEncryptedKey(conn, walletId, "xpriv");
```

See [Secure Storage Guide](docs/secure-storage-guide.md) for key management patterns.

---

## Internal APIs

The following are used internally by the library. Use only when building custom integrations:

- **WalletState / WalletEvent** -- Aggregate state and event types. Events are persisted by Pekko and replayed on recovery. State is rebuilt by folding events.
- **InvoiceState / InvoiceEvent** -- Invoice aggregate internals.
- **ChannelState / ChannelEvent** -- Channel aggregate internals.
- **WalletCoordinator** -- The Pekko Behavior behind `libSpiffy.coordinator()`. Stateless; routes commands to aggregates and read models.
- **Projection setup** -- `WalletProjectionHandler`, `InvoiceProjectionHandler`, `ChannelProjectionHandler` update read models from event streams.
- **Serialization** -- CBOR-based serialization for Pekko persistence.
- **ActorSystemFactory** -- Creates configured Pekko actor systems with JDBC persistence.
- **DataSourceRegistry** -- Global DataSource registry keyed by ActorSystem.
- **Config internals** -- Pekko persistence, sharding, and cluster configuration.

---

## Common Patterns

### Create Wallet, Build Payment, Broadcast (Coordinator)

```java
var coordinator = libSpiffy.coordinator();
var scheduler = libSpiffy.system().scheduler();

// 1. Create wallet via coordinator
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.CreateWallet(
        walletId, "My Wallet", WalletType.HD, NetworkType.MAINNET,
        rootAddress, Map.of(), replyTo),
    Duration.ofSeconds(10), scheduler);

// 2. Record addresses (derive first, then record)
var crypto = libSpiffy.cryptoService();
var hdKey = crypto.mnemonicToHDPrivateKey(mnemonic, "");
var key0 = crypto.derivePrivateKey(hdKey, 0, 0, 0, false);
var addr0 = crypto.generateAddress(key0, NetworkType.MAINNET);

AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.RecordAddress(walletId, addressMetadata, replyTo),
    Duration.ofSeconds(10), scheduler);

// 3. Once UTXOs arrive, record them
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.RecordUtxo(walletId, utxo, replyTo),
    Duration.ofSeconds(10), scheduler);

// 4. Build a payment (coordinator handles UTXO selection, reservation, signing, recording)
CompletionStage<CoordinatorReply> paymentReply = AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.BuildPayment(
        walletId,
        List.of(new InvoiceOutputSpec.P2PKHOutputSpec(recipientAddr, 50_000L, "payment")),
        TransactionBuildConfig.standard(),
        changeAddress,
        replyTo),
    Duration.ofSeconds(10), scheduler);

paymentReply.thenAccept(reply -> {
    if (reply instanceof CoordinatorReply.PaymentBuilt payment) {
        // 5. Broadcast
        var arc = new ArcService(ArcServiceConfig.taalMainnet());
        arc.submitTransaction(payment.rawHex());
    }
});
```

### Invoice-Driven Payment Receipt (Coordinator)

```java
// 1. Create invoice via coordinator
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.CreateInvoice(
        invoiceId, walletId, List.of(paymentAddress), 50_000L,
        List.of(new InvoiceOutputSpec.P2PKHOutputSpec(paymentAddress, 50_000L, "order")),
        "Order #123", Instant.now().plus(Duration.ofHours(24)),
        Map.of(), replyTo),
    Duration.ofSeconds(10), scheduler);

// 2. When a payment is detected on-chain:
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.MarkInvoicePaid(
        invoiceId, paymentTxid, amountReceivedSats, paymentAddress, replyTo),
    Duration.ofSeconds(10), scheduler);

// 3. Query via read model (through coordinator or directly)
AskPattern.ask(coordinator,
    replyTo -> new CoordinatorCommand.GetBalance(walletId, replyTo),
    Duration.ofSeconds(10), scheduler);
```

### Plugin Payment (Token Mint Example)

```java
// 1. Register plugin at build time
var libSpiffy = LibSpiffy4j.builder()
    .dataSource(ds)
    .encryptionMasterKey(key)
    .registerPlugin(new OrdinalsPlugin())
    .build();

// 2. Build a plugin payment via coordinator
AskPattern.ask(libSpiffy.coordinator(),
    replyTo -> new CoordinatorCommand.BuildPluginPayment(
        walletId,
        "ordinals-plugin",
        "mint",
        Map.of("contentType", "image/png", "data", base64Data),
        TransactionBuildConfig.standard(),
        changeAddress,
        replyTo),
    Duration.ofSeconds(10), libSpiffy.system().scheduler());
// Reply: CoordinatorReply.PluginPaymentBuilt
```

### Wallet Recovery from XPRIV

```java
var recoveryService = new WalletRecoveryService(
    sharding, crypto, discoveryService, importService,
    Duration.ofSeconds(30)
);

var result = recoveryService.recoverWallet(
    walletId, "Recovered Wallet", hdKey, NetworkType.MAINNET,
    20, System.out::println
).toCompletableFuture().join();

// Result contains discovered addresses, imported transactions, and UTXO count
```

### Stateless Tx Build, Broadcast, SPV Confirm (No Coordinator)

```java
// 1. Create wallet
var crypto = new CryptoService();
var mnemonic = crypto.generateMnemonic();
var hdKey = crypto.mnemonicToHDPrivateKey(mnemonic, "");

// 2. Derive addresses
var address0 = crypto.generateAddress(
    crypto.derivePrivateKey(hdKey, 0, 0, 0, false), NetworkType.MAINNET);
var changeAddr = crypto.generateAddress(
    crypto.derivePrivateKey(hdKey, 0, 0, 0, true), NetworkType.MAINNET);

// 3. Build transaction (once UTXOs are available)
var buildService = new TransactionBuildService(crypto);
var result = buildService.buildTransaction(
    utxos, outputs, TransactionBuildConfig.standard(),
    changeAddr, signingKey, NetworkType.MAINNET
);

// 4. Broadcast
var arc = new ArcService(ArcServiceConfig.taalMainnet());
var submitResp = arc.submitTransaction(result.rawHex());

// 5. SPV confirmation
var headerStore = new BlockHeaderChain();
var importService = new TransactionImportService(arc, headerStore);
importService.trackPendingTransaction(submitResp.txid());

// Later, when a new block arrives:
List<ImportedTransaction> confirmed = importService.onNewBlock(newBlockHeight);
for (var tx : confirmed) {
    assert tx.spvValid();
}
```

---

## Error Handling

### ArcServiceException

Thrown when ARC returns an error response. Contains the HTTP status code and error details.

```java
try {
    arc.submitTransaction(rawHex);
} catch (ArcServiceException e) {
    // Handle ARC errors (invalid tx, network issues, etc.)
    System.err.println("ARC error: " + e.getMessage());
}
```

### CoinSelector Insufficient Funds

When no combination of available UTXOs can fund the target amount:

```java
try {
    selector.select(utxos, targetSats, strategy);
} catch (IllegalArgumentException e) {
    // Not enough funds
}
```

### CoordinatorReply.Failure

Coordinator commands return replies that may indicate failure:

```java
CompletionStage<CoordinatorReply> reply = AskPattern.ask(coordinator, ...);
reply.whenComplete((result, error) -> {
    if (error != null) {
        // Pekko communication error (timeout, etc.)
    } else if (result instanceof CoordinatorReply.Failure failure) {
        // Domain error (duplicate wallet, unknown UTXO, insufficient funds, etc.)
        System.err.println("Failed: " + failure.message());
    } else {
        // Success -- pattern match on specific reply type
    }
});
```

### WalletReply.Failure (Direct Aggregate -- Discouraged)

If interacting with aggregates directly (not recommended):

```java
CompletionStage<WalletReply> reply = walletRef.ask(...);
reply.whenComplete((result, error) -> {
    if (error != null) {
        // Pekko communication error (timeout, etc.)
    } else if (result instanceof WalletReply.Failure failure) {
        // Domain error (duplicate wallet, unknown UTXO, etc.)
        System.err.println("Failed: " + failure.message());
    } else {
        // Success
    }
});
```

---

## Testing Your Application

### Unit Tests with Stateless Services (No Infrastructure)

All stateless services can be instantiated directly in tests:

```java
@Test
void testKeyDerivation() {
    var crypto = new CryptoService();
    var mnemonic = crypto.generateMnemonic();
    var hdKey = crypto.mnemonicToHDPrivateKey(mnemonic, "");
    var key = crypto.derivePrivateKey(hdKey, 0, 0, 0, false);
    var address = crypto.generateAddress(key, NetworkType.TESTNET);
    assertNotNull(address);
    assertTrue(address.startsWith("m") || address.startsWith("n"));
}
```

### In-Memory BlockHeaderChain for SPV Tests

```java
@Test
void testSpvValidation() {
    var chain = new BlockHeaderChain();
    chain.addHeader(850_000, testBlockHeader);

    var bump = Bump.parse(testBumpData);
    byte[] root = bump.computeMerkleRoot(testTxid);
    assertEquals(
        Hex.toHexString(testBlockHeader.merkleRoot()),
        Hex.toHexString(root)
    );
}
```

### Pekko Persistence TestKit for Aggregate Tests

```java
// Use Pekko's persistence-testkit for event-sourced aggregate tests
// See Apache Pekko documentation: persistence-testkit module
```

---

## Model Records Reference

| Record | Package | Key Fields |
|--------|---------|------------|
| `BitcoinUtxo` | `model` | `txid`, `vout`, `valueSats`, `status`, `address`, `reservedByTxId`, `reservationExpiresAt`, `pluginId`, `pluginMetadata` |
| `BitcoinTransaction` | `model` | `walletId`, `txid`, `status`, `direction`, `feeSats`, `netAmountSats`, `blockHeight` |
| `PaymentChannel` | `model` | `channelId`, `walletId`, `role`, `state`, `clientBalanceSats`, `serverBalanceSats`, `fundingTxId` |
| `Invoice` | `model` | `invoiceId`, `walletId`, `amountSats`, `status`, `addresses`, `outputs`, `expiresAt` |
| `InvoiceOutputSpec.P2PKHOutputSpec` | `model` | `address`, `amountSats`, `label` |
| `InvoiceOutputSpec.P2MSOutputSpec` | `model` | `publicKeys`, `threshold`, `amountSats` |
| `InvoiceOutputSpec.OPReturnOutputSpec` | `model` | `dataChunks`, `separateOutputs` |
| `InvoiceOutputSpec.PluginOutputSpec` | `model` | `pluginId`, `amountSats`, `pluginParams` |
| `AddressMetadata` | `model` | `address`, `scriptType`, `derivationPath`, `derivationIndex`, `isChange` |
| `WalletSummary` | `model` | `walletId`, `name`, `walletType`, `networkType`, `confirmedBalanceSats` |
| `WalletBalance` | `model` | Spendable, reserved, and total balances |
| `TransactionBuildResult` | `model` | `txid`, `rawHex`, `feeSats`, inputs, outputs, change |
| `TransactionBuildConfig` | `model` | `feePerKb`, `selectionStrategy`, `minChangeAmountSats`, `enableRBF` |
| `ArcServiceConfig` | `model` | `baseUrl`, `apiKey`, `defaultCallbackUrl` |
| `ArcSubmitResponse` | `model` | `txid`, `status`, `statusCode` |
| `ArcTransactionResponse` | `model` | `txid`, `status`, `blockHeight`, `merklePath` |
| `ImportedTransaction` | `model` | `txid`, `bump`, `blockHeight`, `spvValid` |
| `MerkleProofData` | `model` | `bump`, `blockHeight` |
| `EncryptedKeyRecord` | `model` | `walletId`, `keyType`, `encryptedKey`, `nonce`, `keyVersion` |
| `BlockHeader` | `spv` | `version`, `prevBlockHash`, `merkleRoot`, `timestamp`, `bits`, `nonce` |
| `Bump` | `spv` | `blockHeight`, `path` (List of BumpLevel) |
| `Beef` | `spv` | `version`, `bumps`, transactions, `hasMerkle`, `bumpIndex` |

### Enums

| Enum | Values |
|------|--------|
| `NetworkType` | `MAINNET`, `TESTNET`, `REGTEST` |
| `WalletType` | `HD`, `WIF`, `XPRIV`, `XPUB` |
| `UtxoStatus` | `PENDING`, `AVAILABLE`, `RESERVED`, `SPENT` |
| `TransactionStatus` | `CREATED`, `SIGNED`, `BROADCAST`, `PENDING`, `CONFIRMED`, `FAILED` |
| `TransactionDirection` | `INCOMING`, `OUTGOING`, `INTERNAL` |
| `InvoiceStatus` | `PENDING`, `PAID`, `EXPIRED`, `CANCELLED` |
| `PaymentChannelState` | `NEGOTIATING`, `FUNDING`, `OPENING`, `OPEN`, `CLOSING`, `CLOSED`, `EXPIRED`, `FAILED` |
| `PaymentChannelRole` | `CLIENT`, `SERVER` |
| `BitcoinScriptType` | `P2PKH`, `P2MS`, `P2SH`, `OP_RETURN` |
| `UtxoSelectionStrategy` | `SMALLEST_FIRST`, `LARGEST_FIRST`, `RANDOM`, `OPTIMAL_CHANGE` |
