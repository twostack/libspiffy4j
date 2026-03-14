package org.twostack.libspiffy4j.model;

public record WalletBalance(
        long confirmedSats,
        long unconfirmedSats,
        long reservedSats,
        long availableSats
) {
    public static WalletBalance fromSummary(WalletSummary summary) {
        long available = summary.confirmedBalanceSats() - summary.reservedBalanceSats();
        return new WalletBalance(
                summary.confirmedBalanceSats(),
                summary.unconfirmedBalanceSats(),
                summary.reservedBalanceSats(),
                available
        );
    }
}
