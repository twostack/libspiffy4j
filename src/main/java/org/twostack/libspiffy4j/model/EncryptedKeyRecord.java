package org.twostack.libspiffy4j.model;

import java.time.Instant;

public record EncryptedKeyRecord(
        String walletId,
        String keyType,
        byte[] encryptedKey,
        byte[] nonce,
        int keyVersion,
        Instant createdAt,
        Instant updatedAt
) {
}
