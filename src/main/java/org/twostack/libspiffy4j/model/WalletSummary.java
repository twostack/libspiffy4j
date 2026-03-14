package org.twostack.libspiffy4j.model;

import java.time.Instant;
import java.util.Map;

public record WalletSummary(
        String walletId,
        String name,
        String rootAddress,
        WalletType walletType,
        NetworkType networkType,
        long confirmedBalanceSats,
        long unconfirmedBalanceSats,
        long reservedBalanceSats,
        int addressCount,
        int utxoCount,
        Instant createdAt,
        Map<String, Object> metadata
) {}
