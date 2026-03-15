package org.twostack.libspiffy4j.model;

public record WalletRecoveryResult(
        String walletId,
        int addressesDiscovered,
        int transactionsImported,
        int utxosRecovered,
        long totalBalanceSats
) {}
