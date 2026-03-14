package org.twostack.libspiffy4j.storage.postgres;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.twostack.libspiffy4j.model.EncryptedKeyRecord;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SecureStorageTest {

    @Container
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("libspiffy4j_test")
                    .withUsername("test")
                    .withPassword("test");

    private static PGSimpleDataSource dataSource;
    private final SecureStorage storage = new SecureStorage();

    @BeforeAll
    static void setupSchema() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(PG.getJdbcUrl());
        dataSource.setUser(PG.getUsername());
        dataSource.setPassword(PG.getPassword());

        String sql = new String(
                SecureStorageTest.class.getClassLoader()
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
    void storeAndLoad_roundTrip() throws Exception {
        byte[] encKey = "encrypted-key-data".getBytes();
        byte[] nonce = "twelve-bytes".getBytes();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.storeEncryptedKey(conn, "wallet-1", "MASTER_HD_KEY", encKey, nonce, 1);
            conn.commit();
        }

        Optional<EncryptedKeyRecord> result = storage.loadEncryptedKey(dataSource, "wallet-1", "MASTER_HD_KEY");
        assertThat(result).isPresent();
        assertThat(result.get().walletId()).isEqualTo("wallet-1");
        assertThat(result.get().keyType()).isEqualTo("MASTER_HD_KEY");
        assertThat(result.get().encryptedKey()).isEqualTo(encKey);
        assertThat(result.get().nonce()).isEqualTo(nonce);
        assertThat(result.get().keyVersion()).isEqualTo(1);
        assertThat(result.get().createdAt()).isNotNull();
        assertThat(result.get().updatedAt()).isNotNull();
    }

    @Test
    void loadNonexistent_returnsEmpty() throws Exception {
        Optional<EncryptedKeyRecord> result = storage.loadEncryptedKey(dataSource, "no-such-wallet", "MASTER_HD_KEY");
        assertThat(result).isEmpty();
    }

    @Test
    void overwrite_updatesExisting() throws Exception {
        byte[] original = "original-data".getBytes();
        byte[] updated = "updated-data".getBytes();
        byte[] nonce = "twelve-bytes".getBytes();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.storeEncryptedKey(conn, "wallet-2", "MASTER_HD_KEY", original, nonce, 1);
            conn.commit();
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.storeEncryptedKey(conn, "wallet-2", "MASTER_HD_KEY", updated, nonce, 2);
            conn.commit();
        }

        Optional<EncryptedKeyRecord> result = storage.loadEncryptedKey(dataSource, "wallet-2", "MASTER_HD_KEY");
        assertThat(result).isPresent();
        assertThat(result.get().encryptedKey()).isEqualTo(updated);
        assertThat(result.get().keyVersion()).isEqualTo(2);
    }

    @Test
    void delete_removesRecord() throws Exception {
        byte[] encKey = "to-delete".getBytes();
        byte[] nonce = "twelve-bytes".getBytes();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.storeEncryptedKey(conn, "wallet-3", "MASTER_HD_KEY", encKey, nonce, 1);
            conn.commit();
        }

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.deleteEncryptedKey(conn, "wallet-3", "MASTER_HD_KEY");
            conn.commit();
        }

        Optional<EncryptedKeyRecord> result = storage.loadEncryptedKey(dataSource, "wallet-3", "MASTER_HD_KEY");
        assertThat(result).isEmpty();
    }

    @Test
    void multipleKeyTypes_perWallet() throws Exception {
        byte[] masterKey = "master-key-data".getBytes();
        byte[] backupKey = "backup-key-data".getBytes();
        byte[] nonce = "twelve-bytes".getBytes();

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.storeEncryptedKey(conn, "wallet-4", "MASTER_HD_KEY", masterKey, nonce, 1);
            storage.storeEncryptedKey(conn, "wallet-4", "BACKUP_KEY", backupKey, nonce, 1);
            conn.commit();
        }

        Optional<EncryptedKeyRecord> master = storage.loadEncryptedKey(dataSource, "wallet-4", "MASTER_HD_KEY");
        Optional<EncryptedKeyRecord> backup = storage.loadEncryptedKey(dataSource, "wallet-4", "BACKUP_KEY");

        assertThat(master).isPresent();
        assertThat(backup).isPresent();
        assertThat(master.get().encryptedKey()).isEqualTo(masterKey);
        assertThat(backup.get().encryptedKey()).isEqualTo(backupKey);
    }
}
