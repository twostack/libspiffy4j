package org.twostack.libspiffy4j.projection;

import org.apache.pekko.projection.eventsourced.EventEnvelope;
import org.apache.pekko.projection.jdbc.javadsl.JdbcHandler;
import org.twostack.libspiffy4j.aggregate.channel.ChannelEvent;
import org.twostack.libspiffy4j.model.PaymentChannelState;
import org.twostack.libspiffy4j.storage.postgres.ChannelReadModelStorage;

import java.sql.Connection;

public class ChannelProjectionHandler extends JdbcHandler<EventEnvelope<ChannelEvent>, SpiffyJdbcSession> {

    private final ChannelReadModelStorage storage;

    public ChannelProjectionHandler(ChannelReadModelStorage storage) {
        this.storage = storage;
    }

    @Override
    public void process(SpiffyJdbcSession session, EventEnvelope<ChannelEvent> envelope) throws Exception {
        ChannelEvent event = envelope.event();
        session.withConnection(conn -> {
            dispatch(conn, event);
            return null;
        });
    }

    private void dispatch(Connection conn, ChannelEvent event) throws Exception {
        switch (event) {
            case ChannelEvent.ChannelRequestedEvent e -> {
                storage.upsertChannel(conn, e.channelId(), e.walletId(), e.role(),
                        PaymentChannelState.NEGOTIATING, e.clientPeerId(), null,
                        e.clientPubKeyHex(), null, e.clientAddressB58(), null,
                        e.fundingAmountSats(), e.lockTimeUnix(),
                        e.fundingAmountSats(), 0, e.context(), e.createdAt());
            }
            case ChannelEvent.ChannelAcceptedEvent e -> {
                storage.upsertChannel(conn, e.channelId(), e.walletId(), e.role(),
                        PaymentChannelState.NEGOTIATING, e.clientPeerId(), e.serverPeerId(),
                        e.clientPubKeyHex(), e.serverPubKeyHex(),
                        e.clientAddressB58(), e.serverAddressB58(),
                        e.fundingAmountSats(), e.lockTimeUnix(),
                        e.fundingAmountSats(), 0, e.context(), e.createdAt());
            }
            case ChannelEvent.ChannelRejectedEvent e -> {
                storage.updateChannelStateWithError(conn, e.channelId(),
                        PaymentChannelState.FAILED, e.reason());
            }
            case ChannelEvent.ServerAcceptanceRecordedEvent e -> {
                storage.updateServerAcceptance(conn, e.channelId(), e.serverPeerId(),
                        e.serverPubKeyHex(), e.serverAddressB58(), PaymentChannelState.FUNDING);
            }
            case ChannelEvent.RefundBuiltEvent e -> {
                storage.updateRefundBuilt(conn, e.channelId(), e.refundTxHex(), e.refundClientSigHex());
            }
            case ChannelEvent.RefundCountersignedEvent e -> {
                storage.updateRefundCountersigned(conn, e.channelId(), e.refundServerSigHex());
            }
            case ChannelEvent.ChannelOpenedEvent e -> {
                storage.updateChannelOpened(conn, e.channelId(), e.fundingTxId(),
                        e.fundingTxHex(), e.fundingOutputIndex(), PaymentChannelState.OPEN);
            }
            case ChannelEvent.PaymentRecordedEvent e -> {
                storage.updateChannelBalances(conn, e.channelId(), e.newClientBalanceSats(),
                        e.newServerBalanceSats(), e.sequenceNumber(), e.paymentTxHex(), e.paymentTxId());
                storage.insertPaymentHistory(conn, e.channelId(), e.sequenceNumber(),
                        e.amountSats(), e.newClientBalanceSats(), e.newServerBalanceSats(),
                        e.paymentTxHex(), e.paymentTxId(), e.clientSignatureHex(),
                        e.purpose(), e.invoiceId(), e.recordedAt());
            }
            case ChannelEvent.PaymentAcknowledgedEvent e -> {
                storage.updatePaymentAcknowledged(conn, e.channelId(), e.fullySignedPaymentTxHex());
            }
            case ChannelEvent.ChannelClosingEvent e -> {
                storage.updateChannelState(conn, e.channelId(), PaymentChannelState.CLOSING);
            }
            case ChannelEvent.ChannelClosedEvent e -> {
                storage.updateChannelClosed(conn, e.channelId(), e.settlementTxId(),
                        PaymentChannelState.CLOSED, e.closedAt());
            }
            case ChannelEvent.RefundClaimedEvent e -> {
                storage.updateChannelClosed(conn, e.channelId(), null,
                        PaymentChannelState.EXPIRED, e.claimedAt());
            }
        }
    }
}
