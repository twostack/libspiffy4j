package org.twostack.libspiffy4j.model;

public record EncryptionResult(byte[] ciphertext, byte[] nonce) {
}
