package org.twostack.libspiffy4j.model;

import java.util.List;

public record TransactionBuildResult(
        String txid,
        String rawHex,
        boolean signed,
        List<BitcoinUtxo> selectedUtxos,
        long totalInputSats,
        long totalOutputSats,
        long feeSats,
        long changeSats,
        String changeAddress,
        int inputCount,
        int outputCount
) {
    public TransactionBuildResult {
        selectedUtxos = selectedUtxos == null ? List.of() : List.copyOf(selectedUtxos);
    }
}
