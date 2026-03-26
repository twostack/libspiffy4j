package org.twostack.libspiffy4j.model;

/**
 * Per-wallet UTXO provisioning policy. Controls auto-provisioning thresholds.
 *
 * @param targetLifecycleSteps  desired number of lifecycle steps worth of UTXOs to maintain
 * @param lowThreshold          when available lifecycle steps drop below this, trigger provisioning
 * @param autoProvisionEnabled  whether the coordinator should auto-provision when inventory is low
 */
public record UtxoPolicy(
        int targetLifecycleSteps,
        int lowThreshold,
        boolean autoProvisionEnabled
) {

    public static final UtxoPolicy DEFAULT = new UtxoPolicy(5, 2, true);
}
