package org.twostack.libspiffy4j.aggregate.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.twostack.libspiffy4j.model.PaymentChannelRole;
import org.twostack.libspiffy4j.model.PaymentChannelState;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelStateTest {

    private ChannelState state;

    @BeforeEach
    void setUp() {
        state = new ChannelState();
        state.applyChannelRequested(new ChannelEvent.ChannelRequestedEvent(
                "ch1", "wallet-1", PaymentChannelRole.CLIENT,
                "client-peer", "clientPubHex", "clientAddrB58",
                100000, 1700000000L, "test-context", Instant.now()));
    }

    @Test
    void channelRequested_setsFields() {
        assertThat(state.isCreated()).isTrue();
        assertThat(state.getChannelId()).isEqualTo("ch1");
        assertThat(state.getWalletId()).isEqualTo("wallet-1");
        assertThat(state.getRole()).isEqualTo(PaymentChannelRole.CLIENT);
        assertThat(state.getClientPeerId()).isEqualTo("client-peer");
        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.NEGOTIATING);
        assertThat(state.getClientBalanceSats()).isEqualTo(100000);
        assertThat(state.getServerBalanceSats()).isEqualTo(0);
        assertThat(state.getFundingAmountSats()).isEqualTo(100000);
        assertThat(state.getVersion()).isEqualTo(1);
    }

    @Test
    void channelAccepted_setsServerFields() {
        ChannelState s = new ChannelState();
        s.applyChannelAccepted(new ChannelEvent.ChannelAcceptedEvent(
                "ch2", "wallet-2", PaymentChannelRole.SERVER,
                "client-peer", "server-peer",
                "clientPubHex", "serverPubHex",
                "clientAddrB58", "serverAddrB58",
                200000, 1700000000L, "ctx", Instant.now()));

        assertThat(s.isCreated()).isTrue();
        assertThat(s.getRole()).isEqualTo(PaymentChannelRole.SERVER);
        assertThat(s.getServerPeerId()).isEqualTo("server-peer");
        assertThat(s.getServerPubKeyHex()).isEqualTo("serverPubHex");
        assertThat(s.getChannelState()).isEqualTo(PaymentChannelState.NEGOTIATING);
    }

    @Test
    void channelRejected_setsFailedState() {
        state.applyChannelRejected(new ChannelEvent.ChannelRejectedEvent(
                "ch1", "Not interested", Instant.now()));

        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.FAILED);
        assertThat(state.getErrorMessage()).isEqualTo("Not interested");
        assertThat(state.getVersion()).isEqualTo(2);
    }

    @Test
    void serverAcceptanceRecorded_transitionsToFunding() {
        state.applyServerAcceptanceRecorded(new ChannelEvent.ServerAcceptanceRecordedEvent(
                "ch1", "server-peer", "serverPubHex", "serverAddrB58", Instant.now()));

        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.FUNDING);
        assertThat(state.getServerPeerId()).isEqualTo("server-peer");
        assertThat(state.getServerPubKeyHex()).isEqualTo("serverPubHex");
        assertThat(state.getServerAddressB58()).isEqualTo("serverAddrB58");
    }

    @Test
    void refundBuilt_setsRefundFields() {
        state.applyServerAcceptanceRecorded(new ChannelEvent.ServerAcceptanceRecordedEvent(
                "ch1", "server-peer", "serverPubHex", "serverAddrB58", Instant.now()));
        state.applyRefundBuilt(new ChannelEvent.RefundBuiltEvent(
                "ch1", "refundTxHex", "clientSigHex", Instant.now()));

        assertThat(state.getRefundTxHex()).isEqualTo("refundTxHex");
        assertThat(state.getRefundClientSigHex()).isEqualTo("clientSigHex");
    }

    @Test
    void refundCountersigned_setsServerSig() {
        state.applyServerAcceptanceRecorded(new ChannelEvent.ServerAcceptanceRecordedEvent(
                "ch1", "server-peer", "serverPubHex", "serverAddrB58", Instant.now()));
        state.applyRefundBuilt(new ChannelEvent.RefundBuiltEvent(
                "ch1", "refundTxHex", "clientSigHex", Instant.now()));
        state.applyRefundCountersigned(new ChannelEvent.RefundCountersignedEvent(
                "ch1", "serverSigHex", Instant.now()));

        assertThat(state.getRefundServerSigHex()).isEqualTo("serverSigHex");
    }

    @Test
    void channelOpened_transitionsToOpen() {
        state.applyServerAcceptanceRecorded(new ChannelEvent.ServerAcceptanceRecordedEvent(
                "ch1", "server-peer", "serverPubHex", "serverAddrB58", Instant.now()));
        state.applyRefundBuilt(new ChannelEvent.RefundBuiltEvent(
                "ch1", "refundTxHex", "clientSigHex", Instant.now()));
        state.applyRefundCountersigned(new ChannelEvent.RefundCountersignedEvent(
                "ch1", "serverSigHex", Instant.now()));
        state.applyChannelOpened(new ChannelEvent.ChannelOpenedEvent(
                "ch1", "fundingTxId", "fundingTxHex", 0,
                List.of("ancestor1"), Instant.now()));

        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.OPEN);
        assertThat(state.getFundingTxId()).isEqualTo("fundingTxId");
        assertThat(state.getFundingTxHex()).isEqualTo("fundingTxHex");
        assertThat(state.getFundingOutputIndex()).isEqualTo(0);
        assertThat(state.getFundingAncestorTxids()).containsExactly("ancestor1");
    }

    @Test
    void paymentRecorded_updatesBalances() {
        moveToOpenState();
        state.applyPaymentRecorded(new ChannelEvent.PaymentRecordedEvent(
                "ch1", 10000, 90000, 10000, 1,
                "payTxHex", "payTxId", "clientSig", "payment", null, Instant.now()));

        assertThat(state.getClientBalanceSats()).isEqualTo(90000);
        assertThat(state.getServerBalanceSats()).isEqualTo(10000);
        assertThat(state.getLatestSequenceNumber()).isEqualTo(1);
        assertThat(state.getLatestPaymentTxHex()).isEqualTo("payTxHex");
        assertThat(state.getLatestPaymentTxId()).isEqualTo("payTxId");
    }

    @Test
    void paymentAcknowledged_updatesFullySignedTx() {
        moveToOpenState();
        state.applyPaymentRecorded(new ChannelEvent.PaymentRecordedEvent(
                "ch1", 10000, 90000, 10000, 1,
                "payTxHex", "payTxId", "clientSig", "payment", null, Instant.now()));
        state.applyPaymentAcknowledged(new ChannelEvent.PaymentAcknowledgedEvent(
                "ch1", "fullySignedHex", "serverSig", Instant.now()));

        assertThat(state.getLatestPaymentTxHex()).isEqualTo("fullySignedHex");
    }

    @Test
    void channelClosing_transitionsToClosing() {
        moveToOpenState();
        state.applyChannelClosing(new ChannelEvent.ChannelClosingEvent(
                "ch1", "settlementHex", Instant.now()));

        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.CLOSING);
    }

    @Test
    void channelClosed_transitionsToClosed() {
        moveToOpenState();
        state.applyChannelClosing(new ChannelEvent.ChannelClosingEvent(
                "ch1", "settlementHex", Instant.now()));
        state.applyChannelClosed(new ChannelEvent.ChannelClosedEvent(
                "ch1", "settlementTxId", Instant.now()));

        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.CLOSED);
        assertThat(state.getSettlementTxId()).isEqualTo("settlementTxId");
        assertThat(state.getClosedAt()).isNotNull();
    }

    @Test
    void refundClaimed_transitionsToExpired() {
        moveToOpenState();
        state.applyRefundClaimed(new ChannelEvent.RefundClaimedEvent("ch1", Instant.now()));

        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.EXPIRED);
        assertThat(state.getClosedAt()).isNotNull();
    }

    @Test
    void versionIncrements_eachEvent() {
        assertThat(state.getVersion()).isEqualTo(1);
        state.applyServerAcceptanceRecorded(new ChannelEvent.ServerAcceptanceRecordedEvent(
                "ch1", "server-peer", "serverPubHex", "serverAddrB58", Instant.now()));
        assertThat(state.getVersion()).isEqualTo(2);
        state.applyRefundBuilt(new ChannelEvent.RefundBuiltEvent(
                "ch1", "refundTxHex", "clientSigHex", Instant.now()));
        assertThat(state.getVersion()).isEqualTo(3);
    }

    private void moveToOpenState() {
        state.applyServerAcceptanceRecorded(new ChannelEvent.ServerAcceptanceRecordedEvent(
                "ch1", "server-peer", "serverPubHex", "serverAddrB58", Instant.now()));
        state.applyRefundBuilt(new ChannelEvent.RefundBuiltEvent(
                "ch1", "refundTxHex", "clientSigHex", Instant.now()));
        state.applyRefundCountersigned(new ChannelEvent.RefundCountersignedEvent(
                "ch1", "serverSigHex", Instant.now()));
        state.applyChannelOpened(new ChannelEvent.ChannelOpenedEvent(
                "ch1", "fundingTxId", "fundingTxHex", 0, List.of(), Instant.now()));
    }
}
