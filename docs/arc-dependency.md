# ARC Dependency

ARC (previously "mAPI") is the transaction processing API that BSV miners expose. In libspiffy4j, ARC is **mandatory** -- it is the only source for merkle proofs of your confirmed transactions. Without ARC broadcast, no merkle proofs, no SPV.

## Why ARC is not optional

The SPV workflow requires a merkle proof (BUMP format) to verify that a transaction was included in a block. ARC is the service that:

1. Accepts your raw transaction for broadcast
2. Tracks it through the mining pipeline
3. Returns the merkle proof once the transaction is mined

P2P broadcast is supplementary (better propagation speed), but only ARC provides the structured merkle proof response that `TransactionImportService` consumes.

## Configuration

```java
// TAAL mainnet (no API key required for basic usage)
ArcServiceConfig config = ArcServiceConfig.taalMainnet();

// TAAL testnet (API key required)
ArcServiceConfig config = ArcServiceConfig.taalTestnet("your-api-key");

// Custom ARC endpoint
ArcServiceConfig config = ArcServiceConfig.custom(
    "https://arc.example.com",  // baseUrl
    "your-api-key",             // apiKey (null if not required)
    "https://your-app/callback" // defaultCallbackUrl (null to omit)
);
```

The `ArcServiceConfig` record has three fields:

| Field | Purpose |
|-------|---------|
| `baseUrl` | ARC endpoint base URL (no trailing slash) |
| `apiKey` | Bearer token sent as `Authorization` header; `null` to omit |
| `defaultCallbackUrl` | Sent as `X-CallbackUrl` on `submitTransaction`; `null` to omit |

## API surface

`ArcService` exposes three operations:

### submitTransaction

```java
ArcSubmitResponse submitTransaction(String txHex)
ArcSubmitResponse submitTransaction(String txHex, String callbackUrl)
```

Broadcasts a raw transaction. Returns an `ArcSubmitResponse` with:
- `txid` -- the transaction ID
- `status` -- `ArcTransactionStatus` enum (SEEN_ON_NETWORK, MINED, etc.)
- `extraInfo` -- optional detail string from ARC
- `statusCode` -- raw integer status code

The second overload lets you specify a per-transaction callback URL, overriding the default.

### queryTransaction

```java
ArcTransactionResponse queryTransaction(String txid)
```

Queries the current status of a previously submitted transaction. Returns:
- `txid`, `status`, `blockHeight`, `blockHash`, `timestamp`, `merklePath`

`blockHeight` is `0` if the transaction has not yet been mined.

### getMerkleProof

```java
MerkleProofData getMerkleProof(String txid)
```

Fetches the BUMP merkle proof for a mined transaction. Returns:
- `bump` -- parsed `Bump` object ready for merkle root computation
- `blockHeight` -- the block height the transaction was mined in

This will throw `ArcServiceException` if the transaction is not yet mined or if the proof is not available.

## Error handling

All methods throw `ArcServiceException` (unchecked) on failure:

```java
try {
    arcService.submitTransaction(txHex);
} catch (ArcServiceException e) {
    int httpStatus = e.httpStatusCode(); // HTTP status code, or -1 for connection errors
    String body = e.responseBody();      // raw response body, or null

    if (httpStatus == 409) {
        // Transaction already known -- not necessarily an error
    } else if (httpStatus == 465) {
        // Fee too low
    } else if (httpStatus == -1) {
        // Network/connection error (timeout, DNS, etc.)
    }
}
```

### When to retry

| Scenario | Retry? |
|----------|--------|
| HTTP 5xx | Yes, with backoff |
| HTTP 409 (already known) | No -- the tx was already accepted |
| HTTP 465 (fee too low) | No -- rebuild the transaction |
| HTTP 461 (malformed tx) | No -- fix the transaction |
| Connection timeout (`httpStatusCode == -1`) | Yes, with backoff |
| `getMerkleProof` throws for unconfirmed tx | Yes, after next block |

## ARC in the pending transaction flow

The typical pattern:

```java
// Broadcast
ArcSubmitResponse submit = arcService.submitTransaction(txHex);

// Track for later SPV validation
importService.trackPendingTransaction(submit.txid());

// ... time passes, blocks arrive ...

// On new block, onNewBlock() internally calls:
//   arcService.queryTransaction(txid)  -- to get blockHeight
//   arcService.getMerkleProof(txid)    -- to get the BUMP proof
// Then validates the BUMP against the block header in the store.
List<ImportedTransaction> confirmed = importService.onNewBlock(blockHeight);
```

If `getMerkleProof` throws (tx not yet mined), the transaction stays in the pending set and will be retried on the next block.
