package org.twostack.libspiffy4j.model;

import java.time.Instant;
import java.util.Map;

public record WalletConfig(
        String walletId,
        String name,
        String rootAddress,
        WalletType walletType,
        String networkType,
        Map<String, Object> metadata,
        Instant createdAt
) {

    public WalletConfig {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
