# Integration Guide

This guide is for projects that depend on libspiffy4j and need to compose it with an external header source (e.g. a P2P node) and/or a custom storage backend.

## The SPV workflow

The end-to-end flow for broadcasting a transaction and confirming it via SPV:

```
1. Build transaction          -> TransactionBuildService
2. Broadcast to ARC           -> ArcService.submitTransaction(txHex)
3. Track as pending           -> TransactionImportService.trackPendingTransaction(txid)
4. Headers arrive             -> BlockHeaderStore.addHeader(height, header)
5. New block announced        -> ChainTipTracker.updateNetworkHeight(height, blockHash)
6. Trigger proof fetch        -> TransactionImportService.onNewBlock(height)
7. For each pending tx:
   a. ArcService.getMerkleProof(txid) -> Bump
   b. Bump.computeMerkleRoot(txid) vs header.merkleRoot()
   c. Match -> ImportedTransaction(spvValid=true), removed from pending
```

Steps 1-3 are initiated by your application. Steps 4-5 come from your header source (P2P node, CDN sync, or ARC polling). Step 6 bridges the two.

## Composing with a P2P node

libspiffy4j has no dependency on any P2P library. The integration seam is the `BlockHeaderStore` interface and the `onNewBlock` / `updateNetworkHeight` methods. Your host application bridges P2P events into these.

Example using spiffynode4j (or any P2P layer):

```java
class WalletPeerHandler extends PeerHandler.Default {

    private final BlockHeaderStore headerStore;
    private final ChainTipTracker chainTipTracker;
    private final TransactionImportService importService;
    private int nextHeight; // track expected height for incoming headers

    @Override
    public CompletableFuture<Void> handleHeaders(WireMessage msg, Peer peer) {
        MsgHeaders headers = (MsgHeaders) msg;
        for (var nodeHeader : headers.getHeaders()) {
            // Bridge: serialize from P2P type, parse into libspiffy4j type
            byte[] raw = nodeHeader.serialize();
            var walletHeader = BlockHeader.parse(raw);
            headerStore.addHeader(nextHeight++, walletHeader);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> handleBlockAnnouncement(InvVect item, Peer peer) {
        long newHeight = nextHeight; // or derive from your chain state
        chainTipTracker.updateNetworkHeight(newHeight, item.hash().toString());
        List<ImportedTransaction> confirmed = importService.onNewBlock(newHeight);
        // Handle confirmed transactions (update wallet state, notify UI, etc.)
        return CompletableFuture.completedFuture(null);
    }
}
```

### The BlockHeader serialization boundary

libspiffy4j and a P2P node will typically have their own `BlockHeader` types (different field representations, different class hierarchies). The 80-byte serialized header is the interop contract:

- `nodeHeader.serialize()` produces 80 bytes
- `BlockHeader.parse(byte[])` consumes 80 bytes
- The wire format is identical -- this always works

Field format notes for `libspiffy4j.spv.BlockHeader` (a Java record):
- `version` is `long` (unsigned-safe)
- `prevBlockHash` and `merkleRoot` are `byte[32]` in **internal** (little-endian) format
- `getHash()` returns internal format; `getHashHex()` returns display format (reversed hex)

## Implementing BlockHeaderStore

`BlockHeaderStore` is the interface that decouples libspiffy4j's services from a specific storage strategy:

```java
public interface BlockHeaderStore {
    void addHeader(int height, BlockHeader header);
    BlockHeader getHeader(int height);
    int getChainHeight();
}
```

The built-in `BlockHeaderChain` is an in-memory LRU store capped at 2016 headers. This is fine for development and light usage, but production deployments may want:

- **Persistent storage** -- backed by a database so headers survive restarts
- **Shared store** -- a single store fed by P2P headers and read by wallet services
- **Larger capacity** -- if you need to validate transactions across a wider height range

### Implementation contract

- `addHeader` must accept being called with the same height twice (overwrite semantics)
- `getHeader` returns `null` for unknown heights (callers handle this)
- `getChainHeight` returns the highest height ever added, or `-1` if empty
- Thread safety is the implementor's responsibility; `TransactionImportService.onNewBlock` may read headers concurrently with a P2P thread writing them

## Service composition

### Required services

| Service | Purpose | Dependencies |
|---------|---------|-------------|
| `ArcService` | Transaction broadcast, status queries, merkle proof fetch | `ArcServiceConfig` |
| `TransactionImportService` | SPV validation of confirmed transactions | `ArcService`, `BlockHeaderStore` |

### Optional services

| Service | Purpose | When to use |
|---------|---------|-------------|
| `CdnHeaderSyncService` | Bulk header download from CDN | Initial bootstrap before P2P catches up |
| `ChainTipTracker` | Confirmation depth monitoring | When you need confirmation counts/thresholds |
| `AddressDiscoveryService` | BIP44 gap-limit address scanning | Wallet recovery or first-time setup |
| `CoinSelector` | UTXO selection with multiple strategies | Transaction building |
| `TransactionBuildService` | Transaction construction from UTXOs | Spending |

### Startup sequence

A typical startup for an application with both CDN bootstrap and P2P headers:

```java
// 1. Create shared header store
BlockHeaderStore store = new BlockHeaderChain(); // or your persistent impl

// 2. Bootstrap headers from CDN (fast bulk catch-up)
new CdnHeaderSyncService(cdnConfig, store).synchronize();

// 3. Start P2P node -- it feeds headers into the same store
startPeerManager(store);

// 4. Create wallet services pointing at the shared store
var arcService = new ArcService(arcConfig);
var importService = new TransactionImportService(arcService, store);
var tipTracker = new ChainTipTracker(arcService);
```

Once the P2P node is running, CDN sync becomes redundant. The CDN is only needed to avoid the slow P2P initial block header download.

## Pending transaction lifecycle

```java
// After broadcasting via ARC
ArcSubmitResponse response = arcService.submitTransaction(txHex);
importService.trackPendingTransaction(response.txid());

// When a new block arrives (from P2P, polling, or any source)
List<ImportedTransaction> confirmed = importService.onNewBlock(blockHeight);

// confirmed contains only SPV-validated transactions
for (ImportedTransaction tx : confirmed) {
    assert tx.spvValid();
    // tx is automatically removed from pending set
}

// Check what's still pending
Set<String> stillPending = importService.getPendingTxids();
```

`onNewBlock` is safe to call frequently -- if there are no pending transactions, it returns immediately. Transactions that fail SPV validation (proof not yet available, header missing) remain in the pending set for the next block.

## Threading model

- `ArcService` uses virtual threads internally for HTTP calls
- `TransactionImportService.importTransactionBatch` uses virtual threads for parallel imports
- `TransactionImportService.onNewBlock` iterates pending txids sequentially (one ARC call per txid)
- `ChainTipTracker` is stateless per call -- the caller owns scheduling (e.g. `ScheduledExecutorService`, Pekko timers, or P2P block events)
- `CdnHeaderSyncService` runs synchronously on the caller's thread
- `BlockHeaderStore` implementations must be thread-safe if headers are written from a P2P thread while services read concurrently

## NewBlockListener

`NewBlockListener` is a functional interface for block arrival notifications:

```java
@FunctionalInterface
public interface NewBlockListener {
    void onNewBlock(long blockHeight, String blockHash);
}
```

Use it to decouple your P2P layer from specific service calls:

```java
List<NewBlockListener> listeners = List.of(
    (height, hash) -> tipTracker.updateNetworkHeight(height, hash),
    (height, hash) -> {
        List<ImportedTransaction> confirmed = importService.onNewBlock(height);
        confirmed.forEach(walletService::applyConfirmation);
    }
);

// In your P2P handler
void onBlockAnnounced(long height, String hash) {
    listeners.forEach(l -> l.onNewBlock(height, hash));
}
```
