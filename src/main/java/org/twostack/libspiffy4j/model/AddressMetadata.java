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
}
