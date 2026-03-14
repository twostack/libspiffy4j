package org.twostack.libspiffy4j.aggregate.channel;

import org.twostack.libspiffy4j.model.PaymentChannelRole;
import org.twostack.libspiffy4j.model.PaymentChannelState;
import org.twostack.libspiffy4j.serialization.SpiffyEvent;

import java.time.Instant;
import java.util.List;

public class ChannelState implements SpiffyEvent {

    private String channelId;
    private boolean created;
    private String walletId;
    private PaymentChannelRole role;
    private String clientPeerId;
    private String serverPeerId;
    private String clientPubKeyHex;
    private String serverPubKeyHex;
    private String clientAddressB58;
    private String serverAddressB58;
    private long fundingAmountSats;
    private long lockTimeUnix;
    private PaymentChannelState channelState;
    private long clientBalanceSats;
    private long serverBalanceSats;
    private String fundingTxId;
    private String fundingTxHex;
    private int fundingOutputIndex;
    private String refundTxHex;
    private String refundClientSigHex;
    private String refundServerSigHex;
    private int latestSequenceNumber;
    private String latestPaymentTxHex;
    private String latestPaymentTxId;
    private String settlementTxId;
    private List<String> fundingAncestorTxids = List.of();
    private String context;
    private Instant createdAt;
    private Instant closedAt;
    private String errorMessage;
    private long version;
    private Instant lastUpdatedAt;

    public ChannelState() {
    }

    // --- Event application methods ---

    public ChannelState applyChannelRequested(ChannelEvent.ChannelRequestedEvent event) {
        this.channelId = event.channelId();
        this.walletId = event.walletId();
        this.role = event.role();
        this.clientPeerId = event.clientPeerId();
        this.clientPubKeyHex = event.clientPubKeyHex();
        this.clientAddressB58 = event.clientAddressB58();
        this.fundingAmountSats = event.fundingAmountSats();
        this.lockTimeUnix = event.lockTimeUnix();
        this.channelState = PaymentChannelState.NEGOTIATING;
        this.clientBalanceSats = event.fundingAmountSats();
        this.serverBalanceSats = 0;
        this.context = event.context();
        this.createdAt = event.createdAt();
        this.created = true;
        this.lastUpdatedAt = event.createdAt();
        this.version++;
        return this;
    }

    public ChannelState applyChannelAccepted(ChannelEvent.ChannelAcceptedEvent event) {
        this.channelId = event.channelId();
        this.walletId = event.walletId();
        this.role = event.role();
        this.clientPeerId = event.clientPeerId();
        this.serverPeerId = event.serverPeerId();
        this.clientPubKeyHex = event.clientPubKeyHex();
        this.serverPubKeyHex = event.serverPubKeyHex();
        this.clientAddressB58 = event.clientAddressB58();
        this.serverAddressB58 = event.serverAddressB58();
        this.fundingAmountSats = event.fundingAmountSats();
        this.lockTimeUnix = event.lockTimeUnix();
        this.channelState = PaymentChannelState.NEGOTIATING;
        this.clientBalanceSats = event.fundingAmountSats();
        this.serverBalanceSats = 0;
        this.context = event.context();
        this.createdAt = event.createdAt();
        this.created = true;
        this.lastUpdatedAt = event.createdAt();
        this.version++;
        return this;
    }

    public ChannelState applyChannelRejected(ChannelEvent.ChannelRejectedEvent event) {
        this.channelState = PaymentChannelState.FAILED;
        this.errorMessage = event.reason();
        this.lastUpdatedAt = event.rejectedAt();
        this.version++;
        return this;
    }

    public ChannelState applyServerAcceptanceRecorded(ChannelEvent.ServerAcceptanceRecordedEvent event) {
        this.serverPeerId = event.serverPeerId();
        this.serverPubKeyHex = event.serverPubKeyHex();
        this.serverAddressB58 = event.serverAddressB58();
        this.channelState = PaymentChannelState.FUNDING;
        this.lastUpdatedAt = event.recordedAt();
        this.version++;
        return this;
    }

    public ChannelState applyRefundBuilt(ChannelEvent.RefundBuiltEvent event) {
        this.refundTxHex = event.refundTxHex();
        this.refundClientSigHex = event.refundClientSigHex();
        this.lastUpdatedAt = event.builtAt();
        this.version++;
        return this;
    }

    public ChannelState applyRefundCountersigned(ChannelEvent.RefundCountersignedEvent event) {
        this.refundServerSigHex = event.refundServerSigHex();
        this.lastUpdatedAt = event.countersignedAt();
        this.version++;
        return this;
    }

    public ChannelState applyChannelOpened(ChannelEvent.ChannelOpenedEvent event) {
        this.fundingTxId = event.fundingTxId();
        this.fundingTxHex = event.fundingTxHex();
        this.fundingOutputIndex = event.fundingOutputIndex();
        this.fundingAncestorTxids = event.fundingAncestorTxids() != null
                ? List.copyOf(event.fundingAncestorTxids()) : List.of();
        this.channelState = PaymentChannelState.OPEN;
        this.lastUpdatedAt = event.openedAt();
        this.version++;
        return this;
    }

    public ChannelState applyPaymentRecorded(ChannelEvent.PaymentRecordedEvent event) {
        this.clientBalanceSats = event.newClientBalanceSats();
        this.serverBalanceSats = event.newServerBalanceSats();
        this.latestSequenceNumber = event.sequenceNumber();
        this.latestPaymentTxHex = event.paymentTxHex();
        this.latestPaymentTxId = event.paymentTxId();
        this.lastUpdatedAt = event.recordedAt();
        this.version++;
        return this;
    }

    public ChannelState applyPaymentAcknowledged(ChannelEvent.PaymentAcknowledgedEvent event) {
        this.latestPaymentTxHex = event.fullySignedPaymentTxHex();
        this.lastUpdatedAt = event.acknowledgedAt();
        this.version++;
        return this;
    }

    public ChannelState applyChannelClosing(ChannelEvent.ChannelClosingEvent event) {
        this.channelState = PaymentChannelState.CLOSING;
        this.lastUpdatedAt = event.closingAt();
        this.version++;
        return this;
    }

    public ChannelState applyChannelClosed(ChannelEvent.ChannelClosedEvent event) {
        this.channelState = PaymentChannelState.CLOSED;
        this.settlementTxId = event.settlementTxId();
        this.closedAt = event.closedAt();
        this.lastUpdatedAt = event.closedAt();
        this.version++;
        return this;
    }

    public ChannelState applyRefundClaimed(ChannelEvent.RefundClaimedEvent event) {
        this.channelState = PaymentChannelState.EXPIRED;
        this.closedAt = event.claimedAt();
        this.lastUpdatedAt = event.claimedAt();
        this.version++;
        return this;
    }

    // --- Getters and Setters ---

    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }

    public boolean isCreated() { return created; }
    public void setCreated(boolean created) { this.created = created; }

    public String getWalletId() { return walletId; }
    public void setWalletId(String walletId) { this.walletId = walletId; }

    public PaymentChannelRole getRole() { return role; }
    public void setRole(PaymentChannelRole role) { this.role = role; }

    public String getClientPeerId() { return clientPeerId; }
    public void setClientPeerId(String clientPeerId) { this.clientPeerId = clientPeerId; }

    public String getServerPeerId() { return serverPeerId; }
    public void setServerPeerId(String serverPeerId) { this.serverPeerId = serverPeerId; }

    public String getClientPubKeyHex() { return clientPubKeyHex; }
    public void setClientPubKeyHex(String clientPubKeyHex) { this.clientPubKeyHex = clientPubKeyHex; }

    public String getServerPubKeyHex() { return serverPubKeyHex; }
    public void setServerPubKeyHex(String serverPubKeyHex) { this.serverPubKeyHex = serverPubKeyHex; }

    public String getClientAddressB58() { return clientAddressB58; }
    public void setClientAddressB58(String clientAddressB58) { this.clientAddressB58 = clientAddressB58; }

    public String getServerAddressB58() { return serverAddressB58; }
    public void setServerAddressB58(String serverAddressB58) { this.serverAddressB58 = serverAddressB58; }

    public long getFundingAmountSats() { return fundingAmountSats; }
    public void setFundingAmountSats(long fundingAmountSats) { this.fundingAmountSats = fundingAmountSats; }

    public long getLockTimeUnix() { return lockTimeUnix; }
    public void setLockTimeUnix(long lockTimeUnix) { this.lockTimeUnix = lockTimeUnix; }

    public PaymentChannelState getChannelState() { return channelState; }
    public void setChannelState(PaymentChannelState channelState) { this.channelState = channelState; }

    public long getClientBalanceSats() { return clientBalanceSats; }
    public void setClientBalanceSats(long clientBalanceSats) { this.clientBalanceSats = clientBalanceSats; }

    public long getServerBalanceSats() { return serverBalanceSats; }
    public void setServerBalanceSats(long serverBalanceSats) { this.serverBalanceSats = serverBalanceSats; }

    public String getFundingTxId() { return fundingTxId; }
    public void setFundingTxId(String fundingTxId) { this.fundingTxId = fundingTxId; }

    public String getFundingTxHex() { return fundingTxHex; }
    public void setFundingTxHex(String fundingTxHex) { this.fundingTxHex = fundingTxHex; }

    public int getFundingOutputIndex() { return fundingOutputIndex; }
    public void setFundingOutputIndex(int fundingOutputIndex) { this.fundingOutputIndex = fundingOutputIndex; }

    public String getRefundTxHex() { return refundTxHex; }
    public void setRefundTxHex(String refundTxHex) { this.refundTxHex = refundTxHex; }

    public String getRefundClientSigHex() { return refundClientSigHex; }
    public void setRefundClientSigHex(String refundClientSigHex) { this.refundClientSigHex = refundClientSigHex; }

    public String getRefundServerSigHex() { return refundServerSigHex; }
    public void setRefundServerSigHex(String refundServerSigHex) { this.refundServerSigHex = refundServerSigHex; }

    public int getLatestSequenceNumber() { return latestSequenceNumber; }
    public void setLatestSequenceNumber(int latestSequenceNumber) { this.latestSequenceNumber = latestSequenceNumber; }

    public String getLatestPaymentTxHex() { return latestPaymentTxHex; }
    public void setLatestPaymentTxHex(String latestPaymentTxHex) { this.latestPaymentTxHex = latestPaymentTxHex; }

    public String getLatestPaymentTxId() { return latestPaymentTxId; }
    public void setLatestPaymentTxId(String latestPaymentTxId) { this.latestPaymentTxId = latestPaymentTxId; }

    public String getSettlementTxId() { return settlementTxId; }
    public void setSettlementTxId(String settlementTxId) { this.settlementTxId = settlementTxId; }

    public List<String> getFundingAncestorTxids() { return fundingAncestorTxids; }
    public void setFundingAncestorTxids(List<String> fundingAncestorTxids) { this.fundingAncestorTxids = fundingAncestorTxids; }

    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }
}
