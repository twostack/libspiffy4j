package org.twostack.libspiffy4j.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;

public record PaymentChannel(
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
        PaymentChannelState state,
        long clientBalanceSats,
        long serverBalanceSats,
        String fundingTxId,
        String fundingTxHex,
        int fundingOutputIndex,
        String refundTxHex,
        String refundClientSigHex,
        String refundServerSigHex,
        int latestSequenceNumber,
        String latestPaymentTxHex,
        String latestPaymentTxId,
        String settlementTxId,
        List<String> fundingAncestorTxids,
        String context,
        Instant createdAt,
        Instant closedAt,
        String errorMessage
) {

    public PaymentChannel {
        fundingAncestorTxids = fundingAncestorTxids == null ? List.of() : List.copyOf(fundingAncestorTxids);
    }

    @JsonIgnore
    public boolean isOpen() {
        return state == PaymentChannelState.OPEN;
    }

    @JsonIgnore
    public boolean isExpired() {
        return state == PaymentChannelState.EXPIRED;
    }

    @JsonIgnore
    public boolean isClosed() {
        return state == PaymentChannelState.CLOSED;
    }

    @JsonIgnore
    public boolean isActive() {
        return isOpen() && !isExpired();
    }

    @JsonIgnore
    public boolean isClient() {
        return role == PaymentChannelRole.CLIENT;
    }

    @JsonIgnore
    public boolean isServer() {
        return role == PaymentChannelRole.SERVER;
    }

    public PaymentChannel withState(PaymentChannelState state) {
        return new PaymentChannel(channelId, walletId, role, clientPeerId, serverPeerId,
                clientPubKeyHex, serverPubKeyHex, clientAddressB58, serverAddressB58,
                fundingAmountSats, lockTimeUnix, state, clientBalanceSats, serverBalanceSats,
                fundingTxId, fundingTxHex, fundingOutputIndex, refundTxHex,
                refundClientSigHex, refundServerSigHex, latestSequenceNumber,
                latestPaymentTxHex, latestPaymentTxId, settlementTxId,
                fundingAncestorTxids, context, createdAt, closedAt, errorMessage);
    }

    public PaymentChannel withClientBalanceSats(long clientBalanceSats) {
        return new PaymentChannel(channelId, walletId, role, clientPeerId, serverPeerId,
                clientPubKeyHex, serverPubKeyHex, clientAddressB58, serverAddressB58,
                fundingAmountSats, lockTimeUnix, state, clientBalanceSats, serverBalanceSats,
                fundingTxId, fundingTxHex, fundingOutputIndex, refundTxHex,
                refundClientSigHex, refundServerSigHex, latestSequenceNumber,
                latestPaymentTxHex, latestPaymentTxId, settlementTxId,
                fundingAncestorTxids, context, createdAt, closedAt, errorMessage);
    }

    public PaymentChannel withServerBalanceSats(long serverBalanceSats) {
        return new PaymentChannel(channelId, walletId, role, clientPeerId, serverPeerId,
                clientPubKeyHex, serverPubKeyHex, clientAddressB58, serverAddressB58,
                fundingAmountSats, lockTimeUnix, state, clientBalanceSats, serverBalanceSats,
                fundingTxId, fundingTxHex, fundingOutputIndex, refundTxHex,
                refundClientSigHex, refundServerSigHex, latestSequenceNumber,
                latestPaymentTxHex, latestPaymentTxId, settlementTxId,
                fundingAncestorTxids, context, createdAt, closedAt, errorMessage);
    }

    public PaymentChannel withLatestSequenceNumber(int latestSequenceNumber) {
        return new PaymentChannel(channelId, walletId, role, clientPeerId, serverPeerId,
                clientPubKeyHex, serverPubKeyHex, clientAddressB58, serverAddressB58,
                fundingAmountSats, lockTimeUnix, state, clientBalanceSats, serverBalanceSats,
                fundingTxId, fundingTxHex, fundingOutputIndex, refundTxHex,
                refundClientSigHex, refundServerSigHex, latestSequenceNumber,
                latestPaymentTxHex, latestPaymentTxId, settlementTxId,
                fundingAncestorTxids, context, createdAt, closedAt, errorMessage);
    }

    public PaymentChannel withLatestPaymentTxHex(String latestPaymentTxHex) {
        return new PaymentChannel(channelId, walletId, role, clientPeerId, serverPeerId,
                clientPubKeyHex, serverPubKeyHex, clientAddressB58, serverAddressB58,
                fundingAmountSats, lockTimeUnix, state, clientBalanceSats, serverBalanceSats,
                fundingTxId, fundingTxHex, fundingOutputIndex, refundTxHex,
                refundClientSigHex, refundServerSigHex, latestSequenceNumber,
                latestPaymentTxHex, latestPaymentTxId, settlementTxId,
                fundingAncestorTxids, context, createdAt, closedAt, errorMessage);
    }

    public PaymentChannel withClosedAt(Instant closedAt) {
        return new PaymentChannel(channelId, walletId, role, clientPeerId, serverPeerId,
                clientPubKeyHex, serverPubKeyHex, clientAddressB58, serverAddressB58,
                fundingAmountSats, lockTimeUnix, state, clientBalanceSats, serverBalanceSats,
                fundingTxId, fundingTxHex, fundingOutputIndex, refundTxHex,
                refundClientSigHex, refundServerSigHex, latestSequenceNumber,
                latestPaymentTxHex, latestPaymentTxId, settlementTxId,
                fundingAncestorTxids, context, createdAt, closedAt, errorMessage);
    }

    public PaymentChannel withErrorMessage(String errorMessage) {
        return new PaymentChannel(channelId, walletId, role, clientPeerId, serverPeerId,
                clientPubKeyHex, serverPubKeyHex, clientAddressB58, serverAddressB58,
                fundingAmountSats, lockTimeUnix, state, clientBalanceSats, serverBalanceSats,
                fundingTxId, fundingTxHex, fundingOutputIndex, refundTxHex,
                refundClientSigHex, refundServerSigHex, latestSequenceNumber,
                latestPaymentTxHex, latestPaymentTxId, settlementTxId,
                fundingAncestorTxids, context, createdAt, closedAt, errorMessage);
    }
}
