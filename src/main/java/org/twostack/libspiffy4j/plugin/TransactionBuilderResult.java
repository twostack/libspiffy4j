package org.twostack.libspiffy4j.plugin;

/**
 * Result from {@link TransactionBuilderPlugin#buildTransaction}.
 *
 * @param txid the transaction ID (double-SHA256 hash, hex-encoded)
 * @param rawHex the raw transaction hex
 * @param feeSats the fee paid in satoshis
 */
public record TransactionBuilderResult(
        String txid,
        String rawHex,
        long feeSats
) {
    public TransactionBuilderResult {
        if (txid == null || txid.isBlank()) {
            throw new IllegalArgumentException("txid must not be null or blank");
        }
        if (rawHex == null || rawHex.isBlank()) {
            throw new IllegalArgumentException("rawHex must not be null or blank");
        }
        if (feeSats < 0) {
            throw new IllegalArgumentException("feeSats must be >= 0");
        }
    }
}
