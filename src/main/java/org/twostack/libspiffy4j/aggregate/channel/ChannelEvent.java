package org.twostack.libspiffy4j.aggregate.channel;

import org.twostack.libspiffy4j.model.PaymentChannelRole;
import org.twostack.libspiffy4j.serialization.SpiffyEvent;

import java.time.Instant;
import java.util.List;

public sealed interface ChannelEvent extends SpiffyEvent permits
        ChannelEvent.ChannelRequestedEvent,
        ChannelEvent.ChannelAcceptedEvent,
        ChannelEvent.ChannelRejectedEvent,
        ChannelEvent.ServerAcceptanceRecordedEvent,
        ChannelEvent.RefundBuiltEvent,
        ChannelEvent.RefundCountersignedEvent,
        ChannelEvent.ChannelOpenedEvent,
        ChannelEvent.PaymentRecordedEvent,
        ChannelEvent.PaymentAcknowledgedEvent,
        ChannelEvent.ChannelClosingEvent,
        ChannelEvent.ChannelClosedEvent,
        ChannelEvent.RefundClaimedEvent {

    // Client requests a new channel (NEGOTIATING state)
    record ChannelRequestedEvent(
            String channelId,
            String walletId,
            PaymentChannelRole role,
            String clientPeerId,
            String clientPubKeyHex,
            String clientAddressB58,
            long fundingAmountSats,
            long lockTimeUnix,
            String context,
            Instant createdAt
    ) implements ChannelEvent {}

    // Server accepts the channel (NEGOTIATING state, server-side creation)
    record ChannelAcceptedEvent(
            String channelId,
            String walletId,
            PaymentChannelRole role,
            String clientPeerId,
            String serverPeerId,
            String clientPubKeyHex,
            String serverPubKeyHex,
            String clientAddressB58,
            String serverAddressB58,
            long fundingAmountSats,
            long lockTimeUnix,
            String context,
            Instant createdAt
    ) implements ChannelEvent {}

    // Channel negotiation rejected
    record ChannelRejectedEvent(
            String channelId,
            String reason,
            Instant rejectedAt
    ) implements ChannelEvent {}

    // Client records that server has accepted (NEGOTIATING → FUNDING)
    record ServerAcceptanceRecordedEvent(
            String channelId,
            String serverPeerId,
            String serverPubKeyHex,
            String serverAddressB58,
            Instant recordedAt
    ) implements ChannelEvent {}

    // Refund transaction built (FUNDING state)
    record RefundBuiltEvent(
            String channelId,
            String refundTxHex,
            String refundClientSigHex,
            Instant builtAt
    ) implements ChannelEvent {}

    // Refund countersigned by server (FUNDING state)
    record RefundCountersignedEvent(
            String channelId,
            String refundServerSigHex,
            Instant countersignedAt
    ) implements ChannelEvent {}

    // Channel opened with funding transaction (FUNDING → OPEN)
    record ChannelOpenedEvent(
            String channelId,
            String fundingTxId,
            String fundingTxHex,
            int fundingOutputIndex,
            List<String> fundingAncestorTxids,
            Instant openedAt
    ) implements ChannelEvent {}

    // Off-chain payment recorded (OPEN state)
    record PaymentRecordedEvent(
            String channelId,
            long amountSats,
            long newClientBalanceSats,
            long newServerBalanceSats,
            int sequenceNumber,
            String paymentTxHex,
            String paymentTxId,
            String clientSignatureHex,
            String purpose,
            String invoiceId,
            Instant recordedAt
    ) implements ChannelEvent {}

    // Server acknowledges payment with countersignature (OPEN state)
    record PaymentAcknowledgedEvent(
            String channelId,
            String fullySignedPaymentTxHex,
            String serverSignatureHex,
            Instant acknowledgedAt
    ) implements ChannelEvent {}

    // Channel closing initiated (OPEN → CLOSING)
    record ChannelClosingEvent(
            String channelId,
            String settlementTxHex,
            Instant closingAt
    ) implements ChannelEvent {}

    // Channel closed (CLOSING → CLOSED)
    record ChannelClosedEvent(
            String channelId,
            String settlementTxId,
            Instant closedAt
    ) implements ChannelEvent {}

    // Refund claimed after lockTime expiry
    record RefundClaimedEvent(
            String channelId,
            Instant claimedAt
    ) implements ChannelEvent {}
}
