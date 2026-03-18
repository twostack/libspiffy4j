package org.twostack.libspiffy4j.service;

import org.junit.jupiter.api.Test;
import org.twostack.libspiffy4j.model.ArcTransactionResponse;
import org.twostack.libspiffy4j.model.ArcTransactionStatus;
import org.twostack.libspiffy4j.model.TransactionConfirmationUpdate;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ChainTipTrackerTest {

    @Test
    void trackTransaction_computesCorrectConfirmations() {
        ArcService stubArc = createStubArc(800000);

        ChainTipTracker tracker = new ChainTipTracker(stubArc);
        tracker.setNetworkHeight(800005);

        AtomicReference<TransactionConfirmationUpdate> update = new AtomicReference<>();
        tracker.trackTransaction("txid123", update::set);

        assertThat(update.get()).isNotNull();
        assertThat(update.get().blockHeight()).isEqualTo(800000);
        assertThat(update.get().confirmations()).isEqualTo(6);
        assertThat(update.get().isConfirmed()).isTrue();
    }

    @Test
    void isConfirmed_belowThreshold() {
        ArcService stubArc = createStubArc(800000);

        ChainTipTracker tracker = new ChainTipTracker(stubArc);
        tracker.setNetworkHeight(800003); // only 4 confirmations

        assertThat(tracker.isConfirmed("txid123")).isFalse();
    }

    @Test
    void isConfirmed_atThreshold() {
        ArcService stubArc = createStubArc(800000);

        ChainTipTracker tracker = new ChainTipTracker(stubArc);
        tracker.setNetworkHeight(800005); // exactly 6 confirmations

        assertThat(tracker.isConfirmed("txid123")).isTrue();
    }

    private ArcService createStubArc(long blockHeight) {
        return new ArcService(
                new org.twostack.libspiffy4j.model.ArcServiceConfig("http://stub", null, null)
        ) {
            @Override
            public ArcTransactionResponse queryTransaction(String txid) {
                return new ArcTransactionResponse(txid, ArcTransactionStatus.MINED,
                        blockHeight, "blockhash", null, null);
            }
        };
    }
}
