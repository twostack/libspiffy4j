package org.twostack.libspiffy4j.model;

import org.twostack.libspiffy4j.spv.Bump;

public record ImportedTransaction(
        String txid,
        String rawHex,
        Bump bump,
        long blockHeight,
        boolean spvValid
) {
}
