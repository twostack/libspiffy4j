package org.twostack.libspiffy4j.aggregate.wallet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.twostack.libspiffy4j.model.*;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WalletStateTest {

    private WalletState state;

    @BeforeEach
    void setUp() {
        state = new WalletState();
        state.applyWalletCreated(new WalletEvent.WalletCreatedEvent(
                "w1", "Test", WalletType.HD, NetworkType.TESTNET,
                "tb1qroot", Map.of(), Instant.now()));
    }

    private BitcoinUtxo makeUtxo(String txid, int vout, long valueSats, Integer confirmations) {
        return new BitcoinUtxo(txid, vout, valueSats, "76a914...", "tb1qaddr",
                UtxoStatus.AVAILABLE, confirmations != null && confirmations > 0 ? 100 : null,
                confirmations, Instant.now(), Instant.now(),
                null, null, null, null, 0);
    }

    @Test
    void receiveConfirmedUtxo_updatesConfirmedBalance() {
        state.applyUtxoReceived(new WalletEvent.UtxoReceivedEvent(
                "w1", makeUtxo("tx1", 0, 50000, 6), Instant.now()));
        assertThat(state.getConfirmedBalanceSats()).isEqualTo(50000);
        assertThat(state.getUnconfirmedBalanceSats()).isEqualTo(0);
    }

    @Test
    void receiveUnconfirmedUtxo_updatesUnconfirmedBalance() {
        state.applyUtxoReceived(new WalletEvent.UtxoReceivedEvent(
                "w1", makeUtxo("tx1", 0, 30000, 0), Instant.now()));
        assertThat(state.getUnconfirmedBalanceSats()).isEqualTo(30000);
        assertThat(state.getConfirmedBalanceSats()).isEqualTo(0);
    }

    @Test
    void receiveUtxoWithNullConfirmations_treatedAsUnconfirmed() {
        state.applyUtxoReceived(new WalletEvent.UtxoReceivedEvent(
                "w1", makeUtxo("tx1", 0, 20000, null), Instant.now()));
        assertThat(state.getUnconfirmedBalanceSats()).isEqualTo(20000);
        assertThat(state.getConfirmedBalanceSats()).isEqualTo(0);
    }

    @Test
    void reserveUtxo_movesFromConfirmedToReserved() {
        state.applyUtxoReceived(new WalletEvent.UtxoReceivedEvent(
                "w1", makeUtxo("tx1", 0, 50000, 3), Instant.now()));

        state.applyUtxoReserved(new WalletEvent.UtxoReservedEvent(
                "w1", "tx1:0", "spending-tx", Instant.now().plusSeconds(3600),
                1, "payment", Instant.now()));

        assertThat(state.getConfirmedBalanceSats()).isEqualTo(0);
        assertThat(state.getReservedBalanceSats()).isEqualTo(50000);
    }

    @Test
    void reserveUtxo_movesFromUnconfirmedToReserved() {
        state.applyUtxoReceived(new WalletEvent.UtxoReceivedEvent(
                "w1", makeUtxo("tx1", 0, 25000, 0), Instant.now()));

        state.applyUtxoReserved(new WalletEvent.UtxoReservedEvent(
                "w1", "tx1:0", "spending-tx", Instant.now().plusSeconds(3600),
                1, "payment", Instant.now()));

        assertThat(state.getUnconfirmedBalanceSats()).isEqualTo(0);
        assertThat(state.getReservedBalanceSats()).isEqualTo(25000);
    }

    @Test
    void spendReservedUtxo_decreasesReservedBalance() {
        state.applyUtxoReceived(new WalletEvent.UtxoReceivedEvent(
                "w1", makeUtxo("tx1", 0, 40000, 3), Instant.now()));

        state.applyUtxoReserved(new WalletEvent.UtxoReservedEvent(
                "w1", "tx1:0", "spending-tx", Instant.now().plusSeconds(3600),
                1, "payment", Instant.now()));

        state.applyUtxoSpent(new WalletEvent.UtxoSpentEvent("w1", "tx1:0", Instant.now()));

        assertThat(state.getReservedBalanceSats()).isEqualTo(0);
        assertThat(state.getConfirmedBalanceSats()).isEqualTo(0);
        assertThat(state.getUtxos().get("tx1:0").status()).isEqualTo(UtxoStatus.SPENT);
    }

    @Test
    void confirmationUpdate_skipsReservedUtxos() {
        // Add unconfirmed UTXO then reserve it
        state.applyUtxoReceived(new WalletEvent.UtxoReceivedEvent(
                "w1", makeUtxo("tx1", 0, 35000, 0), Instant.now()));

        state.applyUtxoReserved(new WalletEvent.UtxoReservedEvent(
                "w1", "tx1:0", "spending-tx", Instant.now().plusSeconds(3600),
                1, "payment", Instant.now()));

        // Now confirm it — should NOT move from reserved to confirmed
        state.applyUtxoConfirmationUpdated(new WalletEvent.UtxoConfirmationUpdatedEvent(
                "w1", "tx1", 6, 800000, Instant.now()));

        assertThat(state.getReservedBalanceSats()).isEqualTo(35000);
        assertThat(state.getConfirmedBalanceSats()).isEqualTo(0);
        assertThat(state.getUnconfirmedBalanceSats()).isEqualTo(0);
    }

    @Test
    void confirmationUpdate_movesUnconfirmedToConfirmed() {
        state.applyUtxoReceived(new WalletEvent.UtxoReceivedEvent(
                "w1", makeUtxo("tx1", 0, 60000, 0), Instant.now()));

        state.applyUtxoConfirmationUpdated(new WalletEvent.UtxoConfirmationUpdatedEvent(
                "w1", "tx1", 3, 800000, Instant.now()));

        assertThat(state.getUnconfirmedBalanceSats()).isEqualTo(0);
        assertThat(state.getConfirmedBalanceSats()).isEqualTo(60000);
    }

    @Test
    void releaseUtxo_movesReservedBackToConfirmed() {
        state.applyUtxoReceived(new WalletEvent.UtxoReceivedEvent(
                "w1", makeUtxo("tx1", 0, 45000, 5), Instant.now()));

        state.applyUtxoReserved(new WalletEvent.UtxoReservedEvent(
                "w1", "tx1:0", "spending-tx", Instant.now().plusSeconds(3600),
                1, "payment", Instant.now()));

        state.applyUtxoReleased(new WalletEvent.UtxoReleasedEvent("w1", "tx1:0", Instant.now()));

        assertThat(state.getReservedBalanceSats()).isEqualTo(0);
        assertThat(state.getConfirmedBalanceSats()).isEqualTo(45000);
    }

    @Test
    void transactionRecordedAndConfirmed() {
        BitcoinTransaction tx = new BitcoinTransaction(
                "w1", "txABC", "rawhex",
                TransactionStatus.BROADCAST, TransactionDirection.INCOMING,
                null, 0, 0, 50000, 1000, 49000,
                java.util.List.of(), java.util.List.of(), Instant.now(), Instant.now(), null, 0, 2);

        state.applyTransactionRecorded(new WalletEvent.TransactionRecordedEvent("w1", tx, Instant.now()));
        assertThat(state.getTransactions()).containsKey("txABC");
        assertThat(state.getTransactions().get("txABC").status()).isEqualTo(TransactionStatus.BROADCAST);

        state.applyTransactionConfirmed(new WalletEvent.TransactionConfirmedEvent(
                "w1", "txABC", 6, 800000, Instant.now()));
        assertThat(state.getTransactions().get("txABC").status()).isEqualTo(TransactionStatus.CONFIRMED);
        assertThat(state.getTransactions().get("txABC").confirmations()).isEqualTo(6);
    }
}
