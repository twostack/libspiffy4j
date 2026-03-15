# Secure Storage Guide

This guide covers key management in libspiffy4j: encrypting sensitive keys with `EncryptionService`, persisting them with `SecureStorage`, and key rotation patterns.

---

## Table of Contents

1. [Overview](#overview)
2. [Master Key Management](#master-key-management)
3. [EncryptionService](#encryptionservice)
4. [SecureStorage Workflow](#securestorage-workflow)
5. [HKDF Context-Based Key Derivation](#hkdf-context-based-key-derivation)
6. [Key Versioning and Rotation](#key-versioning-and-rotation)
7. [Security Considerations](#security-considerations)

---

## Overview

libspiffy4j uses a two-layer encryption scheme for sensitive key material:

```
Master Key (32 bytes, AES-256)
    │
    ├── HKDF("wallet:abc:xpriv") ──> Derived Key A ──> AES-256-GCM ──> Encrypted XPRIV
    ├── HKDF("wallet:abc:wif")   ──> Derived Key B ──> AES-256-GCM ──> Encrypted WIF
    └── HKDF("wallet:def:xpriv") ──> Derived Key C ──> AES-256-GCM ──> Encrypted XPRIV
```

**Components:**

| Component | Role |
|-----------|------|
| `EncryptionService` | AES-256-GCM encryption with HKDF-derived subkeys |
| `SecureStorage` | JDBC DAO for encrypted key persistence (PostgreSQL) |
| `EncryptedKeyRecord` | Record returned when loading stored keys |

---

## Master Key Management

The master key is a 32-byte AES-256 key that protects all stored wallet keys.

### Generating a Master Key

```java
byte[] masterKey = EncryptionService.generateMasterKey();
// Returns 32 cryptographically random bytes
```

### Providing the Master Key at Startup

```java
var libSpiffy = LibSpiffy4j.builder()
    .dataSource(dataSource)
    .objectMapper(new ObjectMapper())
    .encryptionMasterKey(masterKey)
    .build();
```

If no master key is provided, `libSpiffy.encryptionService()` returns `null` and encrypted storage is unavailable.

### Storing the Master Key

The master key itself must be stored securely outside of the application database. Options include:

- **Environment variable** — `SPIFFY_MASTER_KEY` (hex-encoded)
- **Secrets manager** — AWS Secrets Manager, HashiCorp Vault, GCP Secret Manager
- **HSM** — Hardware Security Module for production deployments
- **Key file** — Encrypted file with restricted filesystem permissions (development only)

**Never store the master key in the same database as the encrypted wallet keys.**

---

## EncryptionService

`EncryptionService` provides AES-256-GCM encryption with HKDF-derived context keys.

### Encrypting Data

```java
var encryption = new EncryptionService(masterKey);

// The context string derives a unique subkey via HKDF
EncryptionResult result = encryption.encrypt(plaintext, "wallet:abc123:xpriv");

byte[] ciphertext = result.ciphertext();  // Encrypted data
byte[] nonce = result.nonce();            // GCM nonce (must be stored alongside ciphertext)
```

### Decrypting Data

```java
byte[] decrypted = encryption.decrypt(ciphertext, nonce, "wallet:abc123:xpriv");
```

The same `context` string must be used for encryption and decryption. Different contexts produce different derived keys, so data encrypted under one context cannot be decrypted with another.

---

## SecureStorage Workflow

`SecureStorage` persists encrypted keys to PostgreSQL. It follows the library's DAO pattern: write methods take a `Connection` (for transaction control), read methods take a `DataSource`.

### Store an Encrypted Key

```java
var encryption = libSpiffy.encryptionService();
var secureStorage = libSpiffy.secureStorage();

// 1. Encrypt the key material
String context = "wallet:" + walletId + ":xpriv";
EncryptionResult encrypted = encryption.encrypt(xprivBytes, context);

// 2. Persist to database
try (var conn = dataSource.getConnection()) {
    secureStorage.storeEncryptedKey(
        conn,
        walletId,                  // wallet identifier
        "xpriv",                   // key type (application-defined)
        encrypted.ciphertext(),    // encrypted data
        encrypted.nonce(),         // GCM nonce
        1                          // key version
    );
    conn.commit();
}
```

### Load and Decrypt a Key

```java
Optional<EncryptedKeyRecord> record = secureStorage.loadEncryptedKey(
    dataSource, walletId, "xpriv"
);

if (record.isPresent()) {
    EncryptedKeyRecord r = record.get();
    byte[] xprivBytes = encryption.decrypt(
        r.encryptedKey(),
        r.nonce(),
        "wallet:" + walletId + ":xpriv"
    );
    // Use xprivBytes...
}
```

### Delete a Key

```java
try (var conn = dataSource.getConnection()) {
    secureStorage.deleteEncryptedKey(conn, walletId, "xpriv");
    conn.commit();
}
```

### EncryptedKeyRecord Fields

| Field | Type | Description |
|-------|------|-------------|
| `walletId` | String | Wallet this key belongs to |
| `keyType` | String | Application-defined key type (e.g., "xpriv", "wif", "hdkey") |
| `encryptedKey` | byte[] | AES-256-GCM ciphertext |
| `nonce` | byte[] | GCM nonce used during encryption |
| `keyVersion` | int | Version number for rotation tracking |
| `createdAt` | Instant | When the key was first stored |
| `updatedAt` | Instant | Last update timestamp |

---

## HKDF Context-Based Key Derivation

HKDF (HMAC-based Key Derivation Function) derives unique subkeys from the master key using a context string. This means:

- The same master key protects all wallet keys
- Each wallet/key-type combination gets its own derived key
- Compromising one derived key does not compromise others

### Context String Convention

Use structured context strings for clarity and uniqueness:

```
wallet:<walletId>:<keyType>
```

Examples:
- `"wallet:abc123:xpriv"` — Extended private key for wallet abc123
- `"wallet:abc123:wif"` — WIF private key for wallet abc123
- `"wallet:def456:hdkey"` — HD master key for wallet def456

### Why Context Matters

If you encrypt two different keys with the same context, they use the same derived key. Always use unique contexts:

```java
// Correct: each key type gets a unique context
encryption.encrypt(xprivBytes, "wallet:" + walletId + ":xpriv");
encryption.encrypt(wifBytes, "wallet:" + walletId + ":wif");

// Wrong: same context for different data
encryption.encrypt(xprivBytes, "wallet:" + walletId);  // Ambiguous
encryption.encrypt(wifBytes, "wallet:" + walletId);     // Same derived key!
```

---

## Key Versioning and Rotation

`SecureStorage` tracks a `keyVersion` field for each stored key. This supports key rotation:

### Rotation Process

1. Generate a new master key
2. Load all encrypted keys using the old master key
3. Re-encrypt each key with the new master key (incrementing key version)
4. Store the re-encrypted keys
5. Replace the old master key with the new one

```java
var oldEncryption = new EncryptionService(oldMasterKey);
var newEncryption = new EncryptionService(newMasterKey);

try (var conn = dataSource.getConnection()) {
    // Load with old key
    Optional<EncryptedKeyRecord> record = secureStorage.loadEncryptedKey(
        dataSource, walletId, "xpriv"
    );

    if (record.isPresent()) {
        EncryptedKeyRecord r = record.get();
        String context = "wallet:" + walletId + ":xpriv";

        // Decrypt with old key
        byte[] plaintext = oldEncryption.decrypt(r.encryptedKey(), r.nonce(), context);

        // Re-encrypt with new key
        EncryptionResult reEncrypted = newEncryption.encrypt(plaintext, context);

        // Store with incremented version
        secureStorage.storeEncryptedKey(
            conn, walletId, "xpriv",
            reEncrypted.ciphertext(), reEncrypted.nonce(),
            r.keyVersion() + 1
        );
    }

    conn.commit();
}
```

---

## Security Considerations

### Master Key

- Store the master key in a secrets manager or HSM, not in the application database
- Use `EncryptionService.generateMasterKey()` to create cryptographically random keys
- Rotate the master key periodically according to your security policy

### Encryption

- AES-256-GCM provides authenticated encryption (integrity + confidentiality)
- Each encryption operation generates a unique random nonce
- HKDF context derivation ensures key separation between wallets and key types

### Database

- The `secure_storage` table contains only ciphertext and nonces — no plaintext keys
- Even with full database access, keys cannot be decrypted without the master key
- Use PostgreSQL's built-in encryption (TLS) for connections to the database

### Application

- Clear plaintext key material from memory after use (zero byte arrays)
- Avoid logging or serializing decrypted key material
- Use shortest possible lifetime for decrypted keys — decrypt, use, and discard

```java
byte[] plaintext = encryption.decrypt(ciphertext, nonce, context);
try {
    // Use the key...
} finally {
    Arrays.fill(plaintext, (byte) 0);  // Zero out
}
```
