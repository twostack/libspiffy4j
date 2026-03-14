package org.twostack.libspiffy4j.model;

public record ArcSubmitResponse(
        String txid,
        ArcTransactionStatus status,
        String extraInfo,
        int statusCode
) {
}
