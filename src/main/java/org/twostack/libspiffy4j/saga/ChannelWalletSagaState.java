package org.twostack.libspiffy4j.saga;

/**
 * Saga state tracking the UTXO lifecycle through channel funding.
 *
 * State machine:
 * IDLE → RESERVING → RESERVED → OPEN → SPENDING → COMPLETED
 *                                    ↘ RELEASING → COMPLETED
 *             ↘ FAILED
 */
public final class ChannelWalletSagaState {

    public enum Phase {
        IDLE, RESERVING, RESERVED, OPEN, SPENDING, RELEASING, COMPLETED, FAILED
    }

    private Phase phase = Phase.IDLE;
    private String channelId;
    private String walletId;
    private String utxoKey;
    private long fundingAmountSats;

    public ChannelWalletSagaState() {}

    // --- Apply methods ---

    public ChannelWalletSagaState applyFundingInitiated(ChannelWalletSagaEvent.FundingInitiatedEvent evt) {
        this.phase = Phase.RESERVING;
        this.channelId = evt.channelId();
        this.walletId = evt.walletId();
        this.utxoKey = evt.utxoKey();
        this.fundingAmountSats = evt.amount();
        return this;
    }

    public ChannelWalletSagaState applyUtxoReserved(ChannelWalletSagaEvent.UtxoReservedEvent evt) {
        this.phase = Phase.RESERVED;
        return this;
    }

    public ChannelWalletSagaState applyUtxoReservationFailed(ChannelWalletSagaEvent.UtxoReservationFailedEvent evt) {
        this.phase = Phase.FAILED;
        return this;
    }

    public ChannelWalletSagaState applyChannelOpenConfirmed(ChannelWalletSagaEvent.ChannelOpenConfirmedEvent evt) {
        this.phase = Phase.OPEN;
        return this;
    }

    public ChannelWalletSagaState applyUtxoMarkedSpent(ChannelWalletSagaEvent.UtxoMarkedSpentEvent evt) {
        this.phase = Phase.COMPLETED;
        return this;
    }

    public ChannelWalletSagaState applyUtxoReleased(ChannelWalletSagaEvent.UtxoReleasedEvent evt) {
        this.phase = Phase.COMPLETED;
        return this;
    }

    // --- Accessors ---

    public Phase getPhase() { return phase; }
    public String getChannelId() { return channelId; }
    public String getWalletId() { return walletId; }
    public String getUtxoKey() { return utxoKey; }
    public long getFundingAmountSats() { return fundingAmountSats; }
}
