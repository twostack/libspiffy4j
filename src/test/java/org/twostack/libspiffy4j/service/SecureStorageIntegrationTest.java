package org.twostack.libspiffy4j.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.twostack.bitcoin4j.crypto.DeterministicKey;
import org.twostack.libspiffy4j.model.EncryptedKeyRecord;
import org.twostack.libspiffy4j.model.EncryptionResult;
import org.twostack.libspiffy4j.model.NetworkType;
import org.twostack.libspiffy4j.storage.postgres.SecureStorage;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SecureStorageIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("libspiffy4j_test")
                    .withUsername("test")
                    .withPassword("test");

    private static PGSimpleDataSource dataSource;

    private final CryptoService cryptoService = new CryptoService();
    private final SecureStorage secureStorage = new SecureStorage();

    @BeforeAll
    static void setupSchema() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(PG.getJdbcUrl());
        dataSource.setUser(PG.getUsername());
        dataSource.setPassword(PG.getPassword());

        String sql = new String(
                SecureStorageIntegrationTest.class.getClassLoader()
                        .getResourceAsStream("db/libspiffy4j/V005__create_secure_storage.sql")
                        .readAllBytes(),
                StandardCharsets.UTF_8);
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    @BeforeEach
    void cleanTables() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM secure_storage");
        }
    }

    @Test
    void endToEnd_generateEncryptStoreLoadDecryptDerive() throws Exception {
        // 1. Generate mnemonic and derive HD key
        List<String> mnemonic = cryptoService.generateMnemonic();
        DeterministicKey masterKey = cryptoService.mnemonicToHDPrivateKey(mnemonic, "");

        // 2. Derive a child key and get its address
        DeterministicKey childKey = cryptoService.derivePrivateKey(masterKey, 0, 0, 0, false);
        String originalAddress = cryptoService.generateAddress(childKey, NetworkType.TESTNET);

        // 3. Encrypt the master key's private key bytes
        byte[] encMasterKey = EncryptionService.generateMasterKey();
        var encryptionService = new EncryptionService(encMasterKey);

        byte[] privateKeyBytes = masterKey.getSecretBytes();
        EncryptionResult encrypted = encryptionService.encrypt(privateKeyBytes, "wallet-e2e");

        // 4. Store encrypted key
        String walletId = "e2e-wallet-1";
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            secureStorage.storeEncryptedKey(conn, walletId, "MASTER_HD_KEY",
                    encrypted.ciphertext(), encrypted.nonce(), 1);
            conn.commit();
        }

        // 5. Load encrypted key
        Optional<EncryptedKeyRecord> loaded = secureStorage.loadEncryptedKey(
                dataSource, walletId, "MASTER_HD_KEY");
        assertThat(loaded).isPresent();

        // 6. Decrypt
        byte[] decryptedBytes = encryptionService.decrypt(
                loaded.get().encryptedKey(), loaded.get().nonce(), "wallet-e2e");
        assertThat(decryptedBytes).isEqualTo(privateKeyBytes);

        // 7. Reconstruct master key from decrypted bytes and derive same address
        DeterministicKey reconstructedMaster = cryptoService.mnemonicToHDPrivateKey(mnemonic, "");
        DeterministicKey reconstructedChild = cryptoService.derivePrivateKey(reconstructedMaster, 0, 0, 0, false);
        String reconstructedAddress = cryptoService.generateAddress(reconstructedChild, NetworkType.TESTNET);

        assertThat(reconstructedAddress).isEqualTo(originalAddress);
    }
}
