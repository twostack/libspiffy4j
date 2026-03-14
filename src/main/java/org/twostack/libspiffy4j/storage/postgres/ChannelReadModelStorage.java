package org.twostack.libspiffy4j.storage.postgres;

import org.twostack.libspiffy4j.model.PaymentChannelRole;
import org.twostack.libspiffy4j.model.PaymentChannelState;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ChannelReadModelStorage {

    // ── Write methods (take Connection) ──

    public void upsertChannel(Connection conn, String channelId, String walletId,
                               PaymentChannelRole role, PaymentChannelState state,
                               String clientPeerId, String serverPeerId,
                               String clientPubKeyHex, String serverPubKeyHex,
                               String clientAddressB58, String serverAddressB58,
                               long fundingAmountSats, long lockTimeUnix,
                               long clientBalanceSats, long serverBalanceSats,
                               String context, Instant createdAt) throws SQLException {
        String sql = """
                INSERT INTO payment_channel (channel_id, wallet_id, role, state,
                    client_peer_id, server_peer_id, client_pub_key_hex, server_pub_key_hex,
                    client_address_b58, server_address_b58, funding_amount_sats, lock_time_unix,
                    client_balance_sats, server_balance_sats, context, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (channel_id) DO UPDATE SET
                    state = EXCLUDED.state,
                    server_peer_id = EXCLUDED.server_peer_id,
                    server_pub_key_hex = EXCLUDED.server_pub_key_hex,
                    server_address_b58 = EXCLUDED.server_address_b58,
                    updated_at = EXCLUDED.updated_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, channelId);
            ps.setString(2, walletId);
            ps.setString(3, role.name());
            ps.setString(4, state.name());
            ps.setString(5, clientPeerId);
            ps.setString(6, serverPeerId);
            ps.setString(7, clientPubKeyHex);
            ps.setString(8, serverPubKeyHex);
            ps.setString(9, clientAddressB58);
            ps.setString(10, serverAddressB58);
            ps.setLong(11, fundingAmountSats);
            ps.setLong(12, lockTimeUnix);
            ps.setLong(13, clientBalanceSats);
            ps.setLong(14, serverBalanceSats);
            ps.setString(15, context);
            ps.setTimestamp(16, Timestamp.from(createdAt));
            ps.setTimestamp(17, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    public void updateChannelState(Connection conn, String channelId,
                                    PaymentChannelState state) throws SQLException {
        String sql = "UPDATE payment_channel SET state = ?, updated_at = ? WHERE channel_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, channelId);
            ps.executeUpdate();
        }
    }

    public void updateChannelStateWithError(Connection conn, String channelId,
                                             PaymentChannelState state, String errorMessage) throws SQLException {
        String sql = "UPDATE payment_channel SET state = ?, error_message = ?, updated_at = ? WHERE channel_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setString(2, errorMessage);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.setString(4, channelId);
            ps.executeUpdate();
        }
    }

    public void updateServerAcceptance(Connection conn, String channelId,
                                        String serverPeerId, String serverPubKeyHex,
                                        String serverAddressB58, PaymentChannelState state) throws SQLException {
        String sql = """
                UPDATE payment_channel SET server_peer_id = ?, server_pub_key_hex = ?,
                    server_address_b58 = ?, state = ?, updated_at = ?
                WHERE channel_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serverPeerId);
            ps.setString(2, serverPubKeyHex);
            ps.setString(3, serverAddressB58);
            ps.setString(4, state.name());
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.setString(6, channelId);
            ps.executeUpdate();
        }
    }

    public void updateRefundBuilt(Connection conn, String channelId,
                                   String refundTxHex, String refundClientSigHex) throws SQLException {
        String sql = """
                UPDATE payment_channel SET refund_tx_hex = ?, refund_client_sig_hex = ?, updated_at = ?
                WHERE channel_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, refundTxHex);
            ps.setString(2, refundClientSigHex);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.setString(4, channelId);
            ps.executeUpdate();
        }
    }

    public void updateRefundCountersigned(Connection conn, String channelId,
                                           String refundServerSigHex) throws SQLException {
        String sql = "UPDATE payment_channel SET refund_server_sig_hex = ?, updated_at = ? WHERE channel_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, refundServerSigHex);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, channelId);
            ps.executeUpdate();
        }
    }

    public void updateChannelOpened(Connection conn, String channelId,
                                     String fundingTxId, String fundingTxHex,
                                     int fundingOutputIndex, PaymentChannelState state) throws SQLException {
        String sql = """
                UPDATE payment_channel SET funding_tx_id = ?, funding_tx_hex = ?,
                    funding_output_index = ?, state = ?, updated_at = ?
                WHERE channel_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fundingTxId);
            ps.setString(2, fundingTxHex);
            ps.setInt(3, fundingOutputIndex);
            ps.setString(4, state.name());
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.setString(6, channelId);
            ps.executeUpdate();
        }
    }

    public void updateChannelBalances(Connection conn, String channelId,
                                       long clientBalanceSats, long serverBalanceSats,
                                       int latestSequenceNumber, String latestPaymentTxHex,
                                       String latestPaymentTxId) throws SQLException {
        String sql = """
                UPDATE payment_channel SET client_balance_sats = ?, server_balance_sats = ?,
                    latest_sequence_number = ?, latest_payment_tx_hex = ?,
                    latest_payment_tx_id = ?, updated_at = ?
                WHERE channel_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, clientBalanceSats);
            ps.setLong(2, serverBalanceSats);
            ps.setInt(3, latestSequenceNumber);
            ps.setString(4, latestPaymentTxHex);
            ps.setString(5, latestPaymentTxId);
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.setString(7, channelId);
            ps.executeUpdate();
        }
    }

    public void updatePaymentAcknowledged(Connection conn, String channelId,
                                           String fullySignedPaymentTxHex) throws SQLException {
        String sql = "UPDATE payment_channel SET latest_payment_tx_hex = ?, updated_at = ? WHERE channel_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fullySignedPaymentTxHex);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, channelId);
            ps.executeUpdate();
        }
    }

    public void updateChannelClosed(Connection conn, String channelId,
                                     String settlementTxId, PaymentChannelState state,
                                     Instant closedAt) throws SQLException {
        String sql = """
                UPDATE payment_channel SET settlement_tx_id = ?, state = ?, closed_at = ?, updated_at = ?
                WHERE channel_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, settlementTxId);
            ps.setString(2, state.name());
            ps.setTimestamp(3, Timestamp.from(closedAt));
            ps.setTimestamp(4, Timestamp.from(Instant.now()));
            ps.setString(5, channelId);
            ps.executeUpdate();
        }
    }

    public void insertPaymentHistory(Connection conn, String channelId, int sequenceNumber,
                                      long amountSats, long clientBalanceSats, long serverBalanceSats,
                                      String paymentTxHex, String paymentTxId,
                                      String clientSignatureHex, String purpose,
                                      String invoiceId, Instant recordedAt) throws SQLException {
        String sql = """
                INSERT INTO channel_payment_history (channel_id, sequence_number, amount_sats,
                    client_balance_sats, server_balance_sats, payment_tx_hex, payment_tx_id,
                    client_signature_hex, purpose, invoice_id, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (channel_id, sequence_number) DO NOTHING
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, channelId);
            ps.setInt(2, sequenceNumber);
            ps.setLong(3, amountSats);
            ps.setLong(4, clientBalanceSats);
            ps.setLong(5, serverBalanceSats);
            ps.setString(6, paymentTxHex);
            ps.setString(7, paymentTxId);
            ps.setString(8, clientSignatureHex);
            ps.setString(9, purpose);
            ps.setString(10, invoiceId);
            ps.setTimestamp(11, Timestamp.from(recordedAt));
            ps.executeUpdate();
        }
    }

    public void updatePaymentAcknowledgedHistory(Connection conn, String channelId,
                                                   int sequenceNumber, String serverSignatureHex,
                                                   Instant acknowledgedAt) throws SQLException {
        String sql = """
                UPDATE channel_payment_history SET acknowledged = TRUE,
                    server_signature_hex = ?, acknowledged_at = ?
                WHERE channel_id = ? AND sequence_number = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serverSignatureHex);
            ps.setTimestamp(2, Timestamp.from(acknowledgedAt));
            ps.setString(3, channelId);
            ps.setInt(4, sequenceNumber);
            ps.executeUpdate();
        }
    }

    // ── Read methods (take DataSource) ──

    public Optional<ChannelSummary> findChannel(DataSource ds, String channelId) throws SQLException {
        String sql = "SELECT * FROM payment_channel WHERE channel_id = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, channelId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapChannel(rs));
                }
                return Optional.empty();
            }
        }
    }

    public List<ChannelSummary> listChannels(DataSource ds, String walletId) throws SQLException {
        String sql = "SELECT * FROM payment_channel WHERE wallet_id = ? ORDER BY created_at DESC";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ChannelSummary> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapChannel(rs));
                }
                return results;
            }
        }
    }

    public List<ChannelSummary> listChannelsByState(DataSource ds, String walletId,
                                                     PaymentChannelState state) throws SQLException {
        String sql = "SELECT * FROM payment_channel WHERE wallet_id = ? AND state = ? ORDER BY created_at DESC";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            ps.setString(2, state.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<ChannelSummary> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapChannel(rs));
                }
                return results;
            }
        }
    }

    private ChannelSummary mapChannel(ResultSet rs) throws SQLException {
        Timestamp closedTs = rs.getTimestamp("closed_at");
        return new ChannelSummary(
                rs.getString("channel_id"),
                rs.getString("wallet_id"),
                PaymentChannelRole.valueOf(rs.getString("role")),
                PaymentChannelState.valueOf(rs.getString("state")),
                rs.getString("client_peer_id"),
                rs.getString("server_peer_id"),
                rs.getLong("funding_amount_sats"),
                rs.getLong("lock_time_unix"),
                rs.getLong("client_balance_sats"),
                rs.getLong("server_balance_sats"),
                rs.getString("funding_tx_id"),
                rs.getInt("latest_sequence_number"),
                rs.getString("settlement_tx_id"),
                rs.getString("context"),
                rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant(),
                closedTs != null ? closedTs.toInstant() : null
        );
    }

    public record ChannelSummary(
            String channelId,
            String walletId,
            PaymentChannelRole role,
            PaymentChannelState state,
            String clientPeerId,
            String serverPeerId,
            long fundingAmountSats,
            long lockTimeUnix,
            long clientBalanceSats,
            long serverBalanceSats,
            String fundingTxId,
            int latestSequenceNumber,
            String settlementTxId,
            String context,
            String errorMessage,
            Instant createdAt,
            Instant closedAt
    ) {}
}
