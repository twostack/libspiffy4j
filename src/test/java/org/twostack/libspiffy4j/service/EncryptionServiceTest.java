package org.twostack.libspiffy4j.service;

import org.junit.jupiter.api.Test;
import org.twostack.libspiffy4j.model.EncryptionResult;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionServiceTest {

    private static final byte[] MASTER_KEY = EncryptionService.generateMasterKey();

    @Test
    void encryptDecrypt_roundTrip() {
        var service = new EncryptionService(MASTER_KEY);
        byte[] plaintext = "sensitive data".getBytes(StandardCharsets.UTF_8);

        EncryptionResult result = service.encrypt(plaintext, "wallet-123");
        byte[] decrypted = service.decrypt(result.ciphertext(), result.nonce(), "wallet-123");

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void differentContexts_yieldDifferentCiphertext() {
        var service = new EncryptionService(MASTER_KEY);
        byte[] plaintext = "same data".getBytes(StandardCharsets.UTF_8);

        EncryptionResult result1 = service.encrypt(plaintext, "context-a");
        EncryptionResult result2 = service.encrypt(plaintext, "context-b");

        // Ciphertext should differ due to different derived keys (and different random nonces)
        assertThat(result1.ciphertext()).isNotEqualTo(result2.ciphertext());
    }

    @Test
    void tamperedCiphertext_failsAuth() {
        var service = new EncryptionService(MASTER_KEY);
        byte[] plaintext = "authentic data".getBytes(StandardCharsets.UTF_8);

        EncryptionResult result = service.encrypt(plaintext, "ctx");
        byte[] tampered = Arrays.copyOf(result.ciphertext(), result.ciphertext().length);
        tampered[0] ^= 0xFF; // flip bits

        assertThatThrownBy(() -> service.decrypt(tampered, result.nonce(), "ctx"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void wrongMasterKey_failsDecrypt() {
        var service1 = new EncryptionService(MASTER_KEY);
        byte[] plaintext = "secret".getBytes(StandardCharsets.UTF_8);

        EncryptionResult result = service1.encrypt(plaintext, "ctx");

        byte[] wrongKey = EncryptionService.generateMasterKey();
        var service2 = new EncryptionService(wrongKey);

        assertThatThrownBy(() -> service2.decrypt(result.ciphertext(), result.nonce(), "ctx"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void wrongContext_failsDecrypt() {
        var service = new EncryptionService(MASTER_KEY);
        byte[] plaintext = "secret".getBytes(StandardCharsets.UTF_8);

        EncryptionResult result = service.encrypt(plaintext, "correct-context");

        assertThatThrownBy(() -> service.decrypt(result.ciphertext(), result.nonce(), "wrong-context"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void generateMasterKey_returns32Bytes() {
        byte[] key = EncryptionService.generateMasterKey();
        assertThat(key).hasSize(32);
    }

    @Test
    void generateMasterKey_returnsDifferentKeysEachTime() {
        byte[] key1 = EncryptionService.generateMasterKey();
        byte[] key2 = EncryptionService.generateMasterKey();
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void constructor_rejectsNullKey() {
        assertThatThrownBy(() -> new EncryptionService(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void constructor_rejectsShortKey() {
        assertThatThrownBy(() -> new EncryptionService(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void constructor_rejectsLongKey() {
        assertThatThrownBy(() -> new EncryptionService(new byte[64]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }
}
