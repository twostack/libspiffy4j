package org.twostack.libspiffy4j.projection;

import org.apache.pekko.projection.eventsourced.EventEnvelope;
import org.apache.pekko.projection.jdbc.javadsl.JdbcHandler;
import org.twostack.libspiffy4j.aggregate.invoice.InvoiceEvent;
import org.twostack.libspiffy4j.model.InvoiceOutputSpec;
import org.twostack.libspiffy4j.storage.postgres.InvoiceReadModelStorage;

import java.sql.Connection;
import java.util.List;

public class InvoiceProjectionHandler extends JdbcHandler<EventEnvelope<InvoiceEvent>, SpiffyJdbcSession> {

    private final InvoiceReadModelStorage storage;

    public InvoiceProjectionHandler(InvoiceReadModelStorage storage) {
        this.storage = storage;
    }

    @Override
    public void process(SpiffyJdbcSession session, EventEnvelope<InvoiceEvent> envelope) throws Exception {
        InvoiceEvent event = envelope.event();
        session.withConnection(conn -> {
            dispatch(conn, event);
            return null;
        });
    }

    private void dispatch(Connection conn, InvoiceEvent event) throws Exception {
        switch (event) {
            case InvoiceEvent.InvoiceCreatedEvent e -> {
                storage.upsertInvoiceSummary(conn, e.invoiceId(), e.walletId(), e.addresses(),
                        e.amountSats(), e.description(), e.expiresAt(), e.metadata(), e.createdAt());
                List<InvoiceOutputSpec> outputs = e.outputs();
                if (outputs != null) {
                    for (int i = 0; i < outputs.size(); i++) {
                        storage.upsertInvoiceOutput(conn, e.invoiceId(), i, outputs.get(i));
                    }
                }
            }
            case InvoiceEvent.InvoicePaidEvent e -> {
                storage.updateInvoicePaid(conn, e.invoiceId(), e.paymentTxid(),
                        e.amountReceivedSats(), e.paidAt());
            }
            case InvoiceEvent.InvoiceExpiredEvent e -> {
                storage.updateInvoiceStatus(conn, e.invoiceId(), "EXPIRED", e.expiredAt());
            }
            case InvoiceEvent.InvoiceCancelledEvent e -> {
                storage.updateInvoiceCancelled(conn, e.invoiceId(), e.reason(), e.cancelledAt());
            }
        }
    }
}
