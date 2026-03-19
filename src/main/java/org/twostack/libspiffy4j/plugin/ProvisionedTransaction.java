package org.twostack.libspiffy4j.plugin;

/**
 * A single transaction in a funding provision batch.
 *
 * <p>Provisioning produces a tree of transactions: one split TX that fans out
 * into earmark TXs, each placing the target funding amount at vout=1.
 * Results are ordered for sequential broadcast (split TX first).
 *
 * @param txid        transaction ID (display order, hex)
 * @param rawHex      raw serialized transaction (hex)
 * @param feeSats     fee paid by this transaction
 * @param role        "split" (level 1) or "earmark" (level 2)
 * @param purpose     null for split; "issuance-witness", "transfer", or "transfer-witness" for earmarks
 * @param fundingVout vout where the earmarked sats sit (always 1 for earmarks, -1 for split)
 * @param fundingSats sats at fundingVout (-1 for split)
 */
public record ProvisionedTransaction(
        String txid,
        String rawHex,
        long feeSats,
        String role,
        String purpose,
        int fundingVout,
        long fundingSats) {}
