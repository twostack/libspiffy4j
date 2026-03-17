package org.twostack.libspiffy4j.storage.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.twostack.libspiffy4j.model.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public class WalletReadModelStorage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Write methods (take Connection) ──

    public void upsertWalletSummary(Connection conn, String walletId, String name,
                                     String rootAddress, WalletType walletType,
                                     NetworkType networkType, Map<String, Object> metadata,
                                     Instant createdAt) throws SQLException {
        String sql = """
                INSERT INTO wallet_summary (wallet_id, name, root_address, wallet_type, network_type, metadata, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (wallet_id) DO UPDATE SET
                    name = EXCLUDED.name,
                    root_address = EXCLUDED.root_address,
                    metadata = EXCLUDED.metadata
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, name);
            ps.setString(3, rootAddress);
            ps.setString(4, walletType.name());
            ps.setString(5, networkType.name());
            ps.setString(6, toJson(metadata));
            ps.setTimestamp(7, Timestamp.from(createdAt));
            ps.executeUpdate();
        }
    }

    public void upsertWalletAddress(Connection conn, String walletId, AddressMetadata addr,
                                     Instant recordedAt) throws SQLException {
        String sql = """
                INSERT INTO wallet_address (wallet_id, address, script_type, derivation_path, derivation_index, is_change, label, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (wallet_id, address) DO NOTHING
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, addr.address());
            ps.setString(3, addr.scriptType() != null ? addr.scriptType().name() : null);
            ps.setString(4, addr.derivationPath());
            if (addr.derivationIndex() != null) {
                ps.setInt(5, addr.derivationIndex());
            } else {
                ps.setNull(5, Types.INTEGER);
            }
            ps.setBoolean(6, addr.isChange());
            ps.setString(7, addr.label());
            ps.setTimestamp(8, Timestamp.from(recordedAt));
            ps.executeUpdate();
        }
        updateAddressCount(conn, walletId);
    }

    public void upsertWalletUtxo(Connection conn, String walletId, BitcoinUtxo utxo) throws SQLException {
        String sql = """
                INSERT INTO wallet_utxo (wallet_id, txid, vout, value_sats, address, status, block_height, confirmations, created_at, updated_at, plugin_id, plugin_metadata, script_pub_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (wallet_id, txid, vout) DO UPDATE SET
                    status = EXCLUDED.status,
                    block_height = EXCLUDED.block_height,
                    confirmations = EXCLUDED.confirmations,
                    updated_at = EXCLUDED.updated_at,
                    plugin_id = COALESCE(EXCLUDED.plugin_id, wallet_utxo.plugin_id),
                    plugin_metadata = COALESCE(EXCLUDED.plugin_metadata, wallet_utxo.plugin_metadata),
                    script_pub_key = COALESCE(EXCLUDED.script_pub_key, wallet_utxo.script_pub_key)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, utxo.txid());
            ps.setInt(3, utxo.vout());
            ps.setLong(4, utxo.valueSats());
            ps.setString(5, utxo.address());
            ps.setString(6, utxo.status().name());
            if (utxo.blockHeight() != null) {
                ps.setInt(7, utxo.blockHeight());
            } else {
                ps.setNull(7, Types.INTEGER);
            }
            if (utxo.confirmations() != null) {
                ps.setInt(8, utxo.confirmations());
            } else {
                ps.setNull(8, Types.INTEGER);
            }
            ps.setTimestamp(9, Timestamp.from(utxo.createdAt()));
            ps.setTimestamp(10, Timestamp.from(utxo.updatedAt()));
            if (utxo.pluginId() != null) {
                ps.setString(11, utxo.pluginId());
            } else {
                ps.setNull(11, Types.VARCHAR);
            }
            if (utxo.pluginMetadata() != null && !utxo.pluginMetadata().isEmpty()) {
                ps.setString(12, toJson(utxo.pluginMetadata()));
            } else {
                ps.setNull(12, Types.VARCHAR);
            }
            if (utxo.scriptPubKey() != null) {
                ps.setString(13, utxo.scriptPubKey());
            } else {
                ps.setNull(13, Types.VARCHAR);
            }
            ps.executeUpdate();
        }
        updateUtxoCount(conn, walletId);
    }

    public void updateUtxoStatus(Connection conn, String walletId, String utxoKey,
                                  UtxoStatus status, Instant updatedAt) throws SQLException {
        String[] parts = utxoKey.split(":");
        String txid = parts[0];
        int vout = Integer.parseInt(parts[1]);
        String sql = "UPDATE wallet_utxo SET status = ?, reserved_by_tx_id = NULL, reservation_expires_at = NULL, updated_at = ? WHERE wallet_id = ? AND txid = ? AND vout = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setTimestamp(2, Timestamp.from(updatedAt));
            ps.setString(3, walletId);
            ps.setString(4, txid);
            ps.setInt(5, vout);
            ps.executeUpdate();
        }
    }

    public void updateUtxoReserved(Connection conn, String walletId, String utxoKey,
                                    String reservingTxId, Instant expiresAt,
                                    Instant updatedAt) throws SQLException {
        String[] parts = utxoKey.split(":");
        String txid = parts[0];
        int vout = Integer.parseInt(parts[1]);
        String sql = "UPDATE wallet_utxo SET status = 'RESERVED', reserved_by_tx_id = ?, reservation_expires_at = ?, updated_at = ? WHERE wallet_id = ? AND txid = ? AND vout = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, reservingTxId);
            ps.setTimestamp(2, Timestamp.from(expiresAt));
            ps.setTimestamp(3, Timestamp.from(updatedAt));
            ps.setString(4, walletId);
            ps.setString(5, txid);
            ps.setInt(6, vout);
            ps.executeUpdate();
        }
    }

    public void updateUtxoConfirmations(Connection conn, String walletId, String txid,
                                         int confirmations, Integer blockHeight,
                                         Instant updatedAt) throws SQLException {
        String sql = "UPDATE wallet_utxo SET confirmations = ?, block_height = ?, updated_at = ? WHERE wallet_id = ? AND txid = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, confirmations);
            if (blockHeight != null) {
                ps.setInt(2, blockHeight);
            } else {
                ps.setNull(2, Types.INTEGER);
            }
            ps.setTimestamp(3, Timestamp.from(updatedAt));
            ps.setString(4, walletId);
            ps.setString(5, txid);
            ps.executeUpdate();
        }
    }

    public void upsertWalletTransaction(Connection conn, String walletId,
                                          BitcoinTransaction tx) throws SQLException {
        String sql = """
                INSERT INTO wallet_transaction (wallet_id, txid, status, direction, block_height, confirmations,
                    input_value_sats, output_value_sats, fee_sats, net_amount_sats, created_at, updated_at, raw_hex)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (wallet_id, txid) DO UPDATE SET
                    status = EXCLUDED.status,
                    block_height = EXCLUDED.block_height,
                    confirmations = EXCLUDED.confirmations,
                    updated_at = EXCLUDED.updated_at,
                    raw_hex = COALESCE(EXCLUDED.raw_hex, wallet_transaction.raw_hex)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, tx.txid());
            ps.setString(3, tx.status().name());
            ps.setString(4, tx.direction() != null ? tx.direction().name() : null);
            if (tx.blockHeight() != null) {
                ps.setInt(5, tx.blockHeight());
            } else {
                ps.setNull(5, Types.INTEGER);
            }
            if (tx.confirmations() != null) {
                ps.setInt(6, tx.confirmations());
            } else {
                ps.setNull(6, Types.INTEGER);
            }
            ps.setLong(7, tx.inputValueSats());
            ps.setLong(8, tx.outputValueSats());
            ps.setLong(9, tx.feeSats());
            ps.setLong(10, tx.netAmountSats());
            ps.setTimestamp(11, Timestamp.from(tx.createdAt()));
            ps.setTimestamp(12, Timestamp.from(tx.updatedAt()));
            if (tx.rawHex() != null) {
                ps.setString(13, tx.rawHex());
            } else {
                ps.setNull(13, Types.VARCHAR);
            }
            ps.executeUpdate();
        }

        // Insert address links
        upsertAddressLinks(conn, walletId, tx.txid(), tx.sendingAddresses(), "SENDER");
        upsertAddressLinks(conn, walletId, tx.txid(), tx.receivingAddresses(), "RECEIVER");
    }

    public void updateTransactionConfirmed(Connection conn, String walletId, String txid,
                                            int confirmations, Integer blockHeight,
                                            Instant confirmedAt) throws SQLException {
        String sql = "UPDATE wallet_transaction SET status = 'CONFIRMED', confirmations = ?, block_height = ?, updated_at = ? WHERE wallet_id = ? AND txid = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, confirmations);
            if (blockHeight != null) {
                ps.setInt(2, blockHeight);
            } else {
                ps.setNull(2, Types.INTEGER);
            }
            ps.setTimestamp(3, Timestamp.from(confirmedAt));
            ps.setString(4, walletId);
            ps.setString(5, txid);
            ps.executeUpdate();
        }
    }

    public void updateWalletBalances(Connection conn, String walletId) throws SQLException {
        String sql = """
                UPDATE wallet_summary SET
                    confirmed_balance_sats = COALESCE((
                        SELECT SUM(value_sats) FROM wallet_utxo
                        WHERE wallet_id = ? AND status = 'AVAILABLE' AND confirmations IS NOT NULL AND confirmations > 0
                    ), 0),
                    unconfirmed_balance_sats = COALESCE((
                        SELECT SUM(value_sats) FROM wallet_utxo
                        WHERE wallet_id = ? AND status = 'AVAILABLE' AND (confirmations IS NULL OR confirmations = 0)
                    ), 0),
                    reserved_balance_sats = COALESCE((
                        SELECT SUM(value_sats) FROM wallet_utxo
                        WHERE wallet_id = ? AND status = 'RESERVED'
                    ), 0)
                WHERE wallet_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, walletId);
            ps.setString(3, walletId);
            ps.setString(4, walletId);
            ps.executeUpdate();
        }
    }

    // ── Read methods (take DataSource) ──

    public Optional<WalletSummary> findWalletSummary(DataSource ds, String walletId) throws SQLException {
        String sql = "SELECT * FROM wallet_summary WHERE wallet_id = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapWalletSummary(rs));
                }
                return Optional.empty();
            }
        }
    }

    public List<WalletSummary> listWalletSummaries(DataSource ds, int limit, int offset) throws SQLException {
        String sql = "SELECT * FROM wallet_summary ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<WalletSummary> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapWalletSummary(rs));
                }
                return results;
            }
        }
    }

    public List<BitcoinUtxo> findUtxosByWalletId(DataSource ds, String walletId) throws SQLException {
        String sql = "SELECT * FROM wallet_utxo WHERE wallet_id = ? ORDER BY created_at DESC";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                List<BitcoinUtxo> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapUtxo(rs));
                }
                return results;
            }
        }
    }

    public List<BitcoinUtxo> findUtxosByStatus(DataSource ds, String walletId,
                                                UtxoStatus status) throws SQLException {
        String sql = "SELECT * FROM wallet_utxo WHERE wallet_id = ? AND status = ? ORDER BY created_at DESC";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<BitcoinUtxo> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapUtxo(rs));
                }
                return results;
            }
        }
    }

    public List<BitcoinTransaction> findTransactionsByWalletId(DataSource ds, String walletId,
                                                                int limit, int offset) throws SQLException {
        String sql = "SELECT * FROM wallet_transaction WHERE wallet_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<BitcoinTransaction> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapTransaction(rs, walletId));
                }
                return results;
            }
        }
    }

    public List<String> findAddressesByWalletId(DataSource ds, String walletId) throws SQLException {
        String sql = "SELECT address FROM wallet_address WHERE wallet_id = ? ORDER BY recorded_at";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(rs.getString("address"));
                }
                return results;
            }
        }
    }

    public List<String> findAddressesByTransaction(DataSource ds, String walletId,
                                                    String txid, String linkType) throws SQLException {
        String sql = "SELECT address FROM transaction_address_link WHERE wallet_id = ? AND txid = ? AND link_type = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, txid);
            ps.setString(3, linkType);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(rs.getString("address"));
                }
                return results;
            }
        }
    }

    public Optional<String> findRawHexByTxid(DataSource ds, String txid) throws SQLException {
        String sql = "SELECT raw_hex FROM wallet_transaction WHERE txid = ? AND raw_hex IS NOT NULL LIMIT 1";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, txid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("raw_hex"));
                }
                return Optional.empty();
            }
        }
    }

    public Optional<WalletBalance> getWalletBalance(DataSource ds, String walletId) throws SQLException {
        return findWalletSummary(ds, walletId).map(WalletBalance::fromSummary);
    }

    // ── Private helpers ──

    private void updateAddressCount(Connection conn, String walletId) throws SQLException {
        String sql = "UPDATE wallet_summary SET address_count = (SELECT COUNT(*) FROM wallet_address WHERE wallet_id = ?) WHERE wallet_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, walletId);
            ps.executeUpdate();
        }
    }

    private void updateUtxoCount(Connection conn, String walletId) throws SQLException {
        String sql = "UPDATE wallet_summary SET utxo_count = (SELECT COUNT(*) FROM wallet_utxo WHERE wallet_id = ? AND status != 'SPENT') WHERE wallet_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, walletId);
            ps.executeUpdate();
        }
    }

    private void upsertAddressLinks(Connection conn, String walletId, String txid,
                                     List<String> addresses, String linkType) throws SQLException {
        if (addresses == null || addresses.isEmpty()) return;
        String sql = "INSERT INTO transaction_address_link (wallet_id, txid, address, link_type) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String address : addresses) {
                ps.setString(1, walletId);
                ps.setString(2, txid);
                ps.setString(3, address);
                ps.setString(4, linkType);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @SuppressWarnings("unchecked")
    private WalletSummary mapWalletSummary(ResultSet rs) throws SQLException {
        Map<String, Object> metadata;
        try {
            String jsonStr = rs.getString("metadata");
            metadata = jsonStr != null ? MAPPER.readValue(jsonStr, Map.class) : Map.of();
        } catch (JsonProcessingException e) {
            metadata = Map.of();
        }
        return new WalletSummary(
                rs.getString("wallet_id"),
                rs.getString("name"),
                rs.getString("root_address"),
                WalletType.valueOf(rs.getString("wallet_type")),
                NetworkType.valueOf(rs.getString("network_type")),
                rs.getLong("confirmed_balance_sats"),
                rs.getLong("unconfirmed_balance_sats"),
                rs.getLong("reserved_balance_sats"),
                rs.getInt("address_count"),
                rs.getInt("utxo_count"),
                rs.getTimestamp("created_at").toInstant(),
                metadata
        );
    }

    private BitcoinUtxo mapUtxo(ResultSet rs) throws SQLException {
        Integer blockHeight = rs.getObject("block_height", Integer.class);
        Integer confirmations = rs.getObject("confirmations", Integer.class);
        Timestamp resExpires = rs.getTimestamp("reservation_expires_at");
        String pluginId = rs.getString("plugin_id");
        Map<String, Object> pluginMetadata = parsePluginMetadata(rs.getString("plugin_metadata"));
        return new BitcoinUtxo(
                rs.getString("txid"),
                rs.getInt("vout"),
                rs.getLong("value_sats"),
                rs.getString("script_pub_key"),
                rs.getString("address"),
                UtxoStatus.valueOf(rs.getString("status")),
                blockHeight,
                confirmations,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("reserved_by_tx_id"),
                resExpires != null ? resExpires.toInstant() : null,
                null, null, null,
                pluginId, pluginMetadata
        );
    }

    private BitcoinTransaction mapTransaction(ResultSet rs, String walletId) throws SQLException {
        Integer blockHeight = rs.getObject("block_height", Integer.class);
        Integer confirmations = rs.getObject("confirmations", Integer.class);
        String dirStr = rs.getString("direction");
        return new BitcoinTransaction(
                walletId,
                rs.getString("txid"),
                rs.getString("raw_hex"),
                TransactionStatus.valueOf(rs.getString("status")),
                dirStr != null ? TransactionDirection.valueOf(dirStr) : null,
                blockHeight,
                confirmations != null ? confirmations : 0,
                rs.getLong("input_value_sats"),
                rs.getLong("output_value_sats"),
                rs.getLong("fee_sats"),
                rs.getLong("net_amount_sats"),
                List.of(), List.of(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                null, 0, 0
        );
    }

    // ── Plugin queries ──

    public List<BitcoinUtxo> findUtxosByPlugin(DataSource ds, String walletId,
                                                String pluginId) throws SQLException {
        String sql = "SELECT * FROM wallet_utxo WHERE wallet_id = ? AND plugin_id = ? AND status != 'SPENT' ORDER BY created_at DESC";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, pluginId);
            try (ResultSet rs = ps.executeQuery()) {
                List<BitcoinUtxo> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapUtxo(rs));
                }
                return results;
            }
        }
    }

    // ── Private helpers ──

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePluginMetadata(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String toJson(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map != null ? map : Map.of());
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
