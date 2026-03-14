package org.twostack.libspiffy4j.storage.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.twostack.libspiffy4j.model.Invoice;
import org.twostack.libspiffy4j.model.InvoiceOutputSpec;
import org.twostack.libspiffy4j.model.InvoiceStatus;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public class InvoiceReadModelStorage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Write methods (take Connection) ──

    public void upsertInvoiceSummary(Connection conn, String invoiceId, String walletId,
                                      List<String> addresses, long amountSats, String description,
                                      Instant expiresAt, Map<String, Object> metadata,
                                      Instant createdAt) throws SQLException {
        String sql = """
                INSERT INTO invoice_summary (invoice_id, wallet_id, addresses, amount_sats, description,
                    status, expires_at, metadata, created_at, updated_at)
                VALUES (?, ?, ?::jsonb, ?, ?, 'PENDING', ?, ?::jsonb, ?, ?)
                ON CONFLICT (invoice_id) DO UPDATE SET
                    wallet_id = EXCLUDED.wallet_id,
                    addresses = EXCLUDED.addresses,
                    amount_sats = EXCLUDED.amount_sats,
                    description = EXCLUDED.description,
                    metadata = EXCLUDED.metadata,
                    updated_at = EXCLUDED.updated_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, invoiceId);
            ps.setString(2, walletId);
            ps.setString(3, toJson(addresses));
            ps.setLong(4, amountSats);
            ps.setString(5, description);
            if (expiresAt != null) {
                ps.setTimestamp(6, Timestamp.from(expiresAt));
            } else {
                ps.setNull(6, Types.TIMESTAMP);
            }
            ps.setString(7, toJsonMap(metadata));
            ps.setTimestamp(8, Timestamp.from(createdAt));
            ps.setTimestamp(9, Timestamp.from(createdAt));
            ps.executeUpdate();
        }
    }

    public void upsertInvoiceOutput(Connection conn, String invoiceId, int outputIndex,
                                     InvoiceOutputSpec spec) throws SQLException {
        String outputType;
        String address = null;
        Long amountSats = null;
        String label = null;

        switch (spec) {
            case InvoiceOutputSpec.P2PKHOutputSpec p2pkh -> {
                outputType = "p2pkh";
                address = p2pkh.address();
                amountSats = p2pkh.amountSats();
                label = p2pkh.label();
            }
            case InvoiceOutputSpec.P2MSOutputSpec p2ms -> {
                outputType = "p2ms";
                amountSats = p2ms.amountSats();
                label = p2ms.label();
            }
            case InvoiceOutputSpec.OPReturnOutputSpec opReturn -> {
                outputType = "op_return";
            }
        }

        String sql = """
                INSERT INTO invoice_output (invoice_id, output_index, output_type, address, amount_sats, label, spec_json)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (invoice_id, output_index) DO UPDATE SET
                    output_type = EXCLUDED.output_type,
                    address = EXCLUDED.address,
                    amount_sats = EXCLUDED.amount_sats,
                    label = EXCLUDED.label,
                    spec_json = EXCLUDED.spec_json
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, invoiceId);
            ps.setInt(2, outputIndex);
            ps.setString(3, outputType);
            ps.setString(4, address);
            if (amountSats != null) {
                ps.setLong(5, amountSats);
            } else {
                ps.setNull(5, Types.BIGINT);
            }
            ps.setString(6, label);
            ps.setString(7, toJsonSpec(spec));
            ps.executeUpdate();
        }
    }

    public void updateInvoicePaid(Connection conn, String invoiceId, String paymentTxid,
                                   long amountReceivedSats, Instant paidAt) throws SQLException {
        String sql = """
                UPDATE invoice_summary SET
                    status = 'PAID',
                    payment_txid = ?,
                    amount_received_sats = ?,
                    paid_at = ?,
                    updated_at = ?
                WHERE invoice_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, paymentTxid);
            ps.setLong(2, amountReceivedSats);
            ps.setTimestamp(3, Timestamp.from(paidAt));
            ps.setTimestamp(4, Timestamp.from(paidAt));
            ps.setString(5, invoiceId);
            ps.executeUpdate();
        }
    }

    public void updateInvoiceStatus(Connection conn, String invoiceId, String status,
                                     Instant updatedAt) throws SQLException {
        String sql = "UPDATE invoice_summary SET status = ?, updated_at = ? WHERE invoice_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setTimestamp(2, Timestamp.from(updatedAt));
            ps.setString(3, invoiceId);
            ps.executeUpdate();
        }
    }

    public void updateInvoiceCancelled(Connection conn, String invoiceId, String reason,
                                        Instant cancelledAt) throws SQLException {
        String sql = """
                UPDATE invoice_summary SET
                    status = 'CANCELLED',
                    cancel_reason = ?,
                    cancelled_at = ?,
                    updated_at = ?
                WHERE invoice_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, reason);
            ps.setTimestamp(2, Timestamp.from(cancelledAt));
            ps.setTimestamp(3, Timestamp.from(cancelledAt));
            ps.setString(4, invoiceId);
            ps.executeUpdate();
        }
    }

    // ── Read methods (take DataSource) ──

    public Optional<Invoice> findInvoice(DataSource ds, String invoiceId) throws SQLException {
        String sql = "SELECT * FROM invoice_summary WHERE invoice_id = ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, invoiceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapInvoice(rs));
                }
                return Optional.empty();
            }
        }
    }

    public List<Invoice> listInvoices(DataSource ds, String walletId, InvoiceStatus statusFilter,
                                       int limit, int offset) throws SQLException {
        String sql;
        if (statusFilter != null) {
            sql = "SELECT * FROM invoice_summary WHERE wallet_id = ? AND status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        } else {
            sql = "SELECT * FROM invoice_summary WHERE wallet_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        }
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            if (statusFilter != null) {
                ps.setString(2, statusFilter.name());
                ps.setInt(3, limit);
                ps.setInt(4, offset);
            } else {
                ps.setInt(2, limit);
                ps.setInt(3, offset);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Invoice> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapInvoice(rs));
                }
                return results;
            }
        }
    }

    public List<Invoice> findExpiredInvoices(DataSource ds, Instant before) throws SQLException {
        String sql = "SELECT * FROM invoice_summary WHERE status = 'PENDING' AND expires_at IS NOT NULL AND expires_at < ?";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(before));
            try (ResultSet rs = ps.executeQuery()) {
                List<Invoice> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapInvoice(rs));
                }
                return results;
            }
        }
    }

    public List<InvoiceOutputSpec> findInvoiceOutputs(DataSource ds, String invoiceId) throws SQLException {
        String sql = "SELECT * FROM invoice_output WHERE invoice_id = ? ORDER BY output_index";
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, invoiceId);
            try (ResultSet rs = ps.executeQuery()) {
                List<InvoiceOutputSpec> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(mapOutputSpec(rs));
                }
                return results;
            }
        }
    }

    // ── Private helpers ──

    @SuppressWarnings("unchecked")
    private Invoice mapInvoice(ResultSet rs) throws SQLException {
        List<String> addresses;
        try {
            String addrJson = rs.getString("addresses");
            addresses = addrJson != null ? MAPPER.readValue(addrJson, List.class) : List.of();
        } catch (JsonProcessingException e) {
            addresses = List.of();
        }

        Map<String, Object> metadata;
        try {
            String metaJson = rs.getString("metadata");
            metadata = metaJson != null ? MAPPER.readValue(metaJson, Map.class) : Map.of();
        } catch (JsonProcessingException e) {
            metadata = Map.of();
        }

        Timestamp paidAt = rs.getTimestamp("paid_at");
        Long amountReceivedSats = rs.getObject("amount_received_sats", Long.class);
        Timestamp expiresAt = rs.getTimestamp("expires_at");

        return new Invoice(
                rs.getString("invoice_id"),
                rs.getString("wallet_id"),
                addresses,
                rs.getLong("amount_sats"),
                List.of(), // outputs loaded separately
                rs.getString("description"),
                InvoiceStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                expiresAt != null ? expiresAt.toInstant() : null,
                paidAt != null ? paidAt.toInstant() : null,
                rs.getString("payment_txid"),
                amountReceivedSats,
                metadata
        );
    }

    private InvoiceOutputSpec mapOutputSpec(ResultSet rs) throws SQLException {
        String specJson = rs.getString("spec_json");
        try {
            return MAPPER.readValue(specJson, InvoiceOutputSpec.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to deserialize InvoiceOutputSpec", e);
        }
    }

    private String toJson(List<String> list) {
        try {
            return MAPPER.writeValueAsString(list != null ? list : List.of());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String toJsonMap(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map != null ? map : Map.of());
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String toJsonSpec(InvoiceOutputSpec spec) {
        try {
            return MAPPER.writeValueAsString(spec);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
