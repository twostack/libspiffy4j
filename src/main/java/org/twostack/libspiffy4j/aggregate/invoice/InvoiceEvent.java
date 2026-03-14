package org.twostack.libspiffy4j.aggregate.invoice;

import org.twostack.libspiffy4j.model.InvoiceOutputSpec;
import org.twostack.libspiffy4j.serialization.SpiffyEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public sealed interface InvoiceEvent extends SpiffyEvent permits
        InvoiceEvent.InvoiceCreatedEvent,
        InvoiceEvent.InvoicePaidEvent,
        InvoiceEvent.InvoiceExpiredEvent,
        InvoiceEvent.InvoiceCancelledEvent {

    record InvoiceCreatedEvent(
            String invoiceId,
            String walletId,
            List<String> addresses,
            long amountSats,
            List<InvoiceOutputSpec> outputs,
            String description,
            Instant expiresAt,
            Map<String, Object> metadata,
            Instant createdAt
    ) implements InvoiceEvent {}

    record InvoicePaidEvent(
            String invoiceId,
            String paymentTxid,
            long amountReceivedSats,
            String paymentAddress,
            Instant paidAt
    ) implements InvoiceEvent {}

    record InvoiceExpiredEvent(
            String invoiceId,
            Instant expiredAt
    ) implements InvoiceEvent {}

    record InvoiceCancelledEvent(
            String invoiceId,
            String reason,
            Instant cancelledAt
    ) implements InvoiceEvent {}
}
