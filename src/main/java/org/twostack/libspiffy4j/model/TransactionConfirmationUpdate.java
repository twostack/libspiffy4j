package org.twostack.libspiffy4j.model;

public record TransactionConfirmationUpdate(
        String txid,
        long blockHeight,
        int confirmations,
        boolean isConfirmed
) {
}
