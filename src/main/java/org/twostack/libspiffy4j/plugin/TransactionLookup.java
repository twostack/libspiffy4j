package org.twostack.libspiffy4j.plugin;

/**
 * Callback for resolving raw transaction hex from the wallet's read model.
 *
 * <p>The coordinator creates an instance by closing over the read model storage.
 * Plugins call {@link #lookupRawHex} to retrieve full transaction data for
 * transactions already recorded in the wallet — ensuring all data flows through
 * the wallet's append-only log rather than being passed externally.
 *
 * <pre>{@code
 * // Created internally by the coordinator:
 * TransactionLookup lookup = txid ->
 *     storage.findTransactionByTxid(ds, walletId, txid)
 *         .map(BitcoinTransaction::rawHex).orElse(null);
 *
 * // Plugin resolves a transaction by ID:
 * String rawHex = lookup.lookupRawHex(tokenTxId);
 * Transaction tx = Transaction.fromHex(rawHex);
 * }</pre>
 */
@FunctionalInterface
public interface TransactionLookup {

    /**
     * Look up the raw hex of a transaction by its ID.
     *
     * @param txid the transaction ID (hex-encoded, double-SHA256)
     * @return the raw transaction hex, or {@code null} if not found in the wallet
     */
    String lookupRawHex(String txid);
}
