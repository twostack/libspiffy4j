package org.twostack.libspiffy4j.aggregate.invoice;

import org.apache.pekko.actor.typed.ActorRef;
import org.twostack.libspiffy4j.model.InvoiceOutputSpec;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public sealed interface InvoiceCommand permits
        InvoiceCommand.CreateInvoiceCommand,
        InvoiceCommand.MarkInvoicePaidCommand,
        InvoiceCommand.CancelInvoiceCommand,
        InvoiceCommand.ExpireInvoiceCommand {

    record CreateInvoiceCommand(
            String invoiceId,
            String walletId,
            List<String> addresses,
            long amountSats,
            List<InvoiceOutputSpec> outputs,
            String description,
            Instant expiresAt,
            Map<String, Object> metadata,
            ActorRef<InvoiceReply> replyTo
    ) implements InvoiceCommand {}

    record MarkInvoicePaidCommand(
            String invoiceId,
            String paymentTxid,
            long amountReceivedSats,
            String paymentAddress,
            ActorRef<InvoiceReply> replyTo
    ) implements InvoiceCommand {}

    record CancelInvoiceCommand(
            String invoiceId,
            String reason,
            ActorRef<InvoiceReply> replyTo
    ) implements InvoiceCommand {}

    record ExpireInvoiceCommand(
            String invoiceId,
            ActorRef<InvoiceReply> replyTo
    ) implements InvoiceCommand {}
}
