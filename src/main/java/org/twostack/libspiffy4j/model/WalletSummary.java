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
        Map<String, Object> metadata,
        int targetLifecycleSteps,
        int lowUtxoThreshold,
        boolean autoProvisionEnabled
) {

    /**
     * Backwards-compatible constructor for code that doesn't set policy fields.
     */
    public WalletSummary(String walletId, String name, String rootAddress,
                         WalletType walletType, NetworkType networkType,
                         long confirmedBalanceSats, long unconfirmedBalanceSats, long reservedBalanceSats,
                         int addressCount, int utxoCount, Instant createdAt, Map<String, Object> metadata) {
        this(walletId, name, rootAddress, walletType, networkType,
                confirmedBalanceSats, unconfirmedBalanceSats, reservedBalanceSats,
                addressCount, utxoCount, createdAt, metadata, 5, 2, true);
    }

    public UtxoPolicy utxoPolicy() {
        return new UtxoPolicy(targetLifecycleSteps, lowUtxoThreshold, autoProvisionEnabled);
    }
}
