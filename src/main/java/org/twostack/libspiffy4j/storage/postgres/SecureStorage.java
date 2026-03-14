package org.twostack.libspiffy4j.storage.postgres;

import org.twostack.libspiffy4j.model.EncryptedKeyRecord;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;

/**
 * Stateless JDBC DAO for encrypted key storage.
 * Write methods accept a Connection (for transaction participation).
 * Read methods accept a DataSource (for standalone queries).
 */
public class SecureStorage {

    // ── Write methods (take Connection) ──

    public void storeEncryptedKey(Connection conn, String walletId, String keyType,
                                  byte[] encryptedKey, byte[] nonce, int keyVersion) throws SQLException {
        String sql = """
                INSERT INTO secure_storage (wallet_id, key_type, encrypted_key, nonce, key_version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (wallet_id, key_type) DO UPDATE SET
                    encrypted_key = EXCLUDED.encrypted_key,
                    nonce = EXCLUDED.nonce,
                    key_version = EXCLUDED.key_version,
                    updated_at = EXCLUDED.updated_at
                """;
        Instant now = Instant.now();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, keyType);
            ps.setBytes(3, encryptedKey);
            ps.setBytes(4, nonce);
            ps.setInt(5, keyVersion);
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setTimestamp(7, Timestamp.from(now));
            ps.executeUpdate();
        }
    }

    // ── Read methods (take DataSource) ──

    public Optional<EncryptedKeyRecord> loadEncryptedKey(DataSource ds, String walletId,
                                                          String keyType) throws SQLException {
        String sql = "SELECT * FROM secure_storage WHERE wallet_id = ? AND key_type = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, keyType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRecord(rs));
                }
                return Optional.empty();
            }
        }
    }

    // ── Delete methods (take Connection) ──

    public void deleteEncryptedKey(Connection conn, String walletId, String keyType) throws SQLException {
        String sql = "DELETE FROM secure_storage WHERE wallet_id = ? AND key_type = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, keyType);
            ps.executeUpdate();
        }
    }

    private EncryptedKeyRecord mapRecord(ResultSet rs) throws SQLException {
        return new EncryptedKeyRecord(
                rs.getString("wallet_id"),
                rs.getString("key_type"),
                rs.getBytes("encrypted_key"),
                rs.getBytes("nonce"),
                rs.getInt("key_version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
