package org.twostack.libspiffy4j.plugin;

/**
 * Result from {@link TransactionBuilderPlugin#buildTransaction}.
 *
 * <p>For paired actions (e.g., issue + witness), both the primary TX and
 * witness TX are returned in a single result. The coordinator broadcasts
 * both atomically from the same UTXO reservation.
 *
 * @param txid the primary transaction ID (hex)
 * @param rawHex the primary raw transaction hex
 * @param feeSats the fee paid in satoshis (primary TX)
 * @param witnessTxid the witness transaction ID (hex), null if not paired
 * @param witnessRawHex the witness raw transaction hex, null if not paired
 * @param witnessFeeSats the fee paid for the witness TX
 */
public record TransactionBuilderResult(
        String txid,
        String rawHex,
        long feeSats,
        String witnessTxid,
        String witnessRawHex,
        long witnessFeeSats
) {
    /** Single-TX result (no witness). */
    public TransactionBuilderResult(String txid, String rawHex, long feeSats) {
        this(txid, rawHex, feeSats, null, null, 0);
    }

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

    /** Whether this result includes a paired witness TX. */
    public boolean hasPairedWitness() {
        return witnessTxid != null && witnessRawHex != null;
    }
}
