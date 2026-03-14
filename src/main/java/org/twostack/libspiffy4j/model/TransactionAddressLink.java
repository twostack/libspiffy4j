package org.twostack.libspiffy4j.model;

public record TransactionAddressLink(
        String address,
        String direction,
        long amountSats,
        Integer vout,
        Integer vin
) {
}
