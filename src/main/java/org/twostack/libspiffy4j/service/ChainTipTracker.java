package org.twostack.libspiffy4j.service;

import org.twostack.libspiffy4j.model.ArcTransactionResponse;
import org.twostack.libspiffy4j.model.ArcTransactionStatus;
import org.twostack.libspiffy4j.model.TransactionConfirmationUpdate;

import java.util.function.Consumer;

/**
 * Poll-based confirmation monitor. Queries ARC for transaction status and computes
 * confirmation depth relative to a known network height.
 * <p>
 * Stateless — caller schedules periodic calls.
 */
public final class ChainTipTracker {

    public static final int DEFAULT_CONFIRMATION_THRESHOLD = 6;

    private final ArcService arcService;
    private final int confirmationThreshold;
    private long networkHeight;
    private String latestBlockHash;

    public ChainTipTracker(ArcService arcService) {
        this(arcService, DEFAULT_CONFIRMATION_THRESHOLD);
    }

    public ChainTipTracker(ArcService arcService, int confirmationThreshold) {
        this.arcService = arcService;
        this.confirmationThreshold = confirmationThreshold;
        this.networkHeight = 0;
    }

    /**
     * Updates the known network height and block hash. Preferred entry point — called by
     * the host app when a new block is announced (e.g. via P2P or ARC polling).
     *
     * @param height    the new chain tip height
     * @param blockHash the hash of the new tip block (display-format hex)
     */
    public void updateNetworkHeight(long height, String blockHash) {
        this.networkHeight = height;
        this.latestBlockHash = blockHash;
    }

    /**
     * Updates the known network height. Should be called periodically by the host.
     *
     * @deprecated Use {@link #updateNetworkHeight(long, String)} to also track the block hash.
     */
    @Deprecated
    public void setNetworkHeight(long height) {
        this.networkHeight = height;
    }

    public long getNetworkHeight() {
        return networkHeight;
    }

    /**
     * Returns the hash of the latest known block, or {@code null} if not yet set.
     */
    public String getLatestBlockHash() {
        return latestBlockHash;
    }

    /**
     * Queries ARC for the given transaction and invokes the callback with a
     * confirmation update.
     */
    public void trackTransaction(String txid, Consumer<TransactionConfirmationUpdate> callback) {
        ArcTransactionResponse response = arcService.queryTransaction(txid);

        int confirmations = 0;
        if (response.blockHeight() > 0 && networkHeight > 0) {
            confirmations = (int) (networkHeight - response.blockHeight() + 1);
            if (confirmations < 0) confirmations = 0;
        }

        boolean confirmed = confirmations >= confirmationThreshold;
        TransactionConfirmationUpdate update = new TransactionConfirmationUpdate(
                txid, response.blockHeight(), confirmations, confirmed);

        callback.accept(update);
    }

    /**
     * Checks if a transaction has reached the confirmation threshold.
     */
    public boolean isConfirmed(String txid) {
        ArcTransactionResponse response = arcService.queryTransaction(txid);
        if (response.blockHeight() <= 0 || networkHeight <= 0) {
            return false;
        }
        int confirmations = (int) (networkHeight - response.blockHeight() + 1);
        return confirmations >= confirmationThreshold;
    }
}
