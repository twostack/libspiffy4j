package org.twostack.libspiffy4j.service;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.twostack.libspiffy4j.model.EncryptionResult;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-256-GCM encryption with HKDF-derived per-context keys.
 * Holds an immutable defensive copy of the master key.
 */
public final class EncryptionService {

    private static final String HKDF_SALT = "libspiffy-xpub-v1";
    private static final int GCM_NONCE_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_LENGTH = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final byte[] masterKey;

    public EncryptionService(byte[] masterKey) {
        if (masterKey == null) {
            throw new IllegalArgumentException("Master key must not be null");
        }
        if (masterKey.length != KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "Master key must be exactly " + KEY_LENGTH + " bytes, got " + masterKey.length);
        }
        this.masterKey = Arrays.copyOf(masterKey, masterKey.length);
    }

    public EncryptionResult encrypt(byte[] plaintext, String context) {
        byte[] derivedKey = deriveKey(context);
        byte[] nonce = new byte[GCM_NONCE_LENGTH];
        SECURE_RANDOM.nextBytes(nonce);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(derivedKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new EncryptionResult(ciphertext, nonce);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] ciphertext, byte[] nonce, String context) {
        byte[] derivedKey = deriveKey(context);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(derivedKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    public static byte[] generateMasterKey() {
        byte[] key = new byte[KEY_LENGTH];
        SECURE_RANDOM.nextBytes(key);
        return key;
    }

    private byte[] deriveKey(String context) {
        byte[] salt = HKDF_SALT.getBytes(StandardCharsets.UTF_8);
        byte[] info = context.getBytes(StandardCharsets.UTF_8);

        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(masterKey, salt, info));

        byte[] derivedKey = new byte[KEY_LENGTH];
        hkdf.generateBytes(derivedKey, 0, KEY_LENGTH);
        return derivedKey;
    }
}
