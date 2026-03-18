package org.twostack.libspiffy4j.model;

import java.time.Instant;

public record AddressMetadata(
        String address,
        BitcoinScriptType scriptType,
        String derivationPath,
        Integer derivationIndex,
        boolean isChange,
        String label,
        String purpose,
        Instant firstUsedAt,
        Instant lastUsedAt,
        int usageCount,
        long balanceSats,
        Instant createdAt,
        boolean isWatched
) {
    /**
     * Convenience constructor for HD-derived addresses.
     */
    public AddressMetadata(String address, int derivationIndex, boolean isChange) {
        this(address, BitcoinScriptType.P2PKH, null, derivationIndex, isChange,
                null, null, null, null, 0, 0L, Instant.now(), true);
    }
}
