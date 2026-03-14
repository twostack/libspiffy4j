package org.twostack.libspiffy4j.model;

import java.time.Instant;
import java.util.List;

public record BitcoinTransaction(
        String walletId,
        String txid,
        String rawHex,
        TransactionStatus status,
        TransactionDirection direction,
        Integer blockHeight,
        Integer confirmations,
        long inputValueSats,
        long outputValueSats,
        long feeSats,
        long netAmountSats,
        List<String> sendingAddresses,
        List<String> receivingAddresses,
        Instant createdAt,
        Instant updatedAt,
        String memo,
        long lockTime,
        int version
) {

    public BitcoinTransaction {
        sendingAddresses = sendingAddresses == null ? List.of() : List.copyOf(sendingAddresses);
        receivingAddresses = receivingAddresses == null ? List.of() : List.copyOf(receivingAddresses);
    }
}
