package org.twostack.libspiffy4j.model;

/**
 * Snapshot of a wallet's UTXO inventory broken down by earmark purpose.
 *
 * <p>Available = unearmarked P2PKH UTXOs (general-purpose funding).
 * Per-purpose counts track funding earmarks created by provisioning.
 * {@link #lifecycleSteps()} is the minimum across the three purposes —
 * the bottleneck determines how many complete token operations are possible.
 */
public record UtxoInventory(
        int availableCount,
        long availableSats,
        int issuanceWitnessCount,
        long issuanceWitnessSats,
        int transferCount,
        long transferSats,
        int transferWitnessCount,
        long transferWitnessSats,
        int lifecycleSteps,
        PolicyStatus policyStatus
) {

    public int earmarkedCount() {
        return issuanceWitnessCount + transferCount + transferWitnessCount;
    }

    public long earmarkedSats() {
        return issuanceWitnessSats + transferSats + transferWitnessSats;
    }

    public enum PolicyStatus {
        SUFFICIENT,
        LOW,
        DEPLETED
    }
}
