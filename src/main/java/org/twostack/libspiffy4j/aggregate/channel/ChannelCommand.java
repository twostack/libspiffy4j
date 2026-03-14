package org.twostack.libspiffy4j.aggregate.channel;

import org.apache.pekko.actor.typed.ActorRef;

import java.util.List;

public sealed interface ChannelCommand permits
        ChannelCommand.RequestChannelCommand,
        ChannelCommand.AcceptChannelCommand,
        ChannelCommand.RejectChannelCommand,
        ChannelCommand.RecordServerAcceptanceCommand,
        ChannelCommand.RequestRefundSignatureCommand,
        ChannelCommand.ProvideRefundSignatureCommand,
        ChannelCommand.OpenChannelCommand,
        ChannelCommand.RecordPaymentCommand,
        ChannelCommand.AcknowledgePaymentCommand,
        ChannelCommand.CloseChannelCommand,
        ChannelCommand.FinalizeCloseCommand,
        ChannelCommand.ClaimRefundCommand {

    // Client-side creation: creates aggregate in NEGOTIATING state
    record RequestChannelCommand(
            String channelId,
            String walletId,
            String clientPeerId,
            String clientPubKeyHex,
            String clientAddressB58,
            long fundingAmountSats,
            long lockTimeUnix,
            String context,
            ActorRef<ChannelReply> replyTo
    ) implements ChannelCommand {}

    // Server-side creation: creates aggregate in NEGOTIATING state with both parties' info
    record AcceptChannelCommand(
            String channelId,
            String walletId,
            String clientPeerId,
            String serverPeerId,
            String clientPubKeyHex,
            String serverPubKeyHex,
            String clientAddressB58,
            String serverAddressB58,
            long fundingAmountSats,
            long lockTimeUnix,
            String context,
            ActorRef<ChannelReply> replyTo
    ) implements ChannelCommand {}

    // Reject channel during negotiation
    record RejectChannelCommand(
            String channelId,
            String reason,
            ActorRef<ChannelReply> replyTo
    ) implements ChannelCommand {}

    // Client records server acceptance → NEGOTIATING to FUNDING
    record RecordServerAcceptanceCommand(
            String channelId,
            String serverPeerId,
            String serverPubKeyHex,
            String serverAddressB58,
            ActorRef<ChannelReply> replyTo
    ) implements ChannelCommand {}

    // Build refund transaction (FUNDING state)
    record RequestRefundSignatureCommand(
            String channelId,
            String refundTxHex,
            String refundClientSigHex,
            ActorRef<ChannelReply> replyTo
    ) implements ChannelCommand {}

    // Provide server's refund countersignature (FUNDING state)
    record ProvideRefundSignatureCommand(
            String channelId,
            String refundServerSigHex,
            ActorRef<ChannelReply> replyTo
    ) implements ChannelCommand {}

    // Open channel with funding tx (FUNDING → OPEN)
    record OpenChannelCommand(
            String channelId,
            String fundingTxId,
            String fundingTxHex,
            int fundingOutputIndex,
            List<String> fundingAncestorTxids,
            ActorRef<ChannelReply> replyTo
    ) implements ChannelCommand {}

    // Record an off-chain payment (OPEN state)
    record RecordPaymentCommand(
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
            ActorRef<ChannelReply> replyTo
    ) implements ChannelCommand {}

    // Server acknowledges payment with countersignature (OPEN state)
    record AcknowledgePaymentCommand(
            String channelId,
            String fullySignedPaymentTxHex,
            String serverSignatureHex,
            ActorRef<ChannelReply> replyTo
    ) implements ChannelCommand {}

    // Initiate channel close (OPEN → CLOSING)
    record CloseChannelCommand(
            String channelId,
            String settlementTxHex,
            ActorRef<ChannelReply> replyTo
    ) implements ChannelCommand {}

    // Finalize close with confirmed settlement (CLOSING → CLOSED)
    record FinalizeCloseCommand(
            String channelId,
            String settlementTxId,
            ActorRef<ChannelReply> replyTo
    ) implements ChannelCommand {}

    // Claim refund after lockTime expiry
    record ClaimRefundCommand(
            String channelId,
            ActorRef<ChannelReply> replyTo
    ) implements ChannelCommand {}
}
