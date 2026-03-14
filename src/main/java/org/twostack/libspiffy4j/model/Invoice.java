package org.twostack.libspiffy4j.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Invoice(
        String invoiceId,
        String walletId,
        List<String> addresses,
        long amountSats,
        List<InvoiceOutputSpec> outputs,
        String description,
        InvoiceStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant paidAt,
        String paymentTxid,
        Long amountReceivedSats,
        Map<String, Object> metadata
) {

    public Invoice {
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public Invoice withStatus(InvoiceStatus status) {
        return new Invoice(invoiceId, walletId, addresses, amountSats, outputs, description,
                status, createdAt, expiresAt, paidAt, paymentTxid, amountReceivedSats, metadata);
    }

    public Invoice withPaidAt(Instant paidAt) {
        return new Invoice(invoiceId, walletId, addresses, amountSats, outputs, description,
                status, createdAt, expiresAt, paidAt, paymentTxid, amountReceivedSats, metadata);
    }

    public Invoice withPaymentTxid(String paymentTxid) {
        return new Invoice(invoiceId, walletId, addresses, amountSats, outputs, description,
                status, createdAt, expiresAt, paidAt, paymentTxid, amountReceivedSats, metadata);
    }

    public Invoice withAmountReceivedSats(Long amountReceivedSats) {
        return new Invoice(invoiceId, walletId, addresses, amountSats, outputs, description,
                status, createdAt, expiresAt, paidAt, paymentTxid, amountReceivedSats, metadata);
    }

    public Invoice withMetadata(Map<String, Object> metadata) {
        return new Invoice(invoiceId, walletId, addresses, amountSats, outputs, description,
                status, createdAt, expiresAt, paidAt, paymentTxid, amountReceivedSats, metadata);
    }
}
