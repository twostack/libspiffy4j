package org.twostack.libspiffy4j.model;

public record ArcTransactionResponse(
        String txid,
        ArcTransactionStatus status,
        long blockHeight,
        String blockHash,
        String timestamp,
        String merklePath
) {
}
