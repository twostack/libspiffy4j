package org.twostack.libspiffy4j.model;

public record TransactionBuildConfig(
        long feePerKb,
        UtxoSelectionStrategy selectionStrategy,
        long minChangeAmountSats,
        boolean forceChange,
        boolean enableRBF,
        boolean performSanityChecks
) {

    public static TransactionBuildConfig standard() {
        return new TransactionBuildConfig(100, UtxoSelectionStrategy.OPTIMAL_CHANGE, 546, false, false, true);
    }

    public static TransactionBuildConfig partial() {
        return new TransactionBuildConfig(100, UtxoSelectionStrategy.SMALLEST_FIRST, 546, false, false, false);
    }
}
