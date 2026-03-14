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
    void walletCreated_setsFields() {
        assertThat(state.isCreated()).isTrue();
        assertThat(state.getWalletId()).isEqualTo("w1");
        assertThat(state.getName()).isEqualTo("Test");
        assertThat(state.getNetworkType()).isEqualTo(NetworkType.TESTNET);
        assertThat(state.getWalletType()).isEqualTo(WalletType.HD);
        assertThat(state.getVersion()).isEqualTo(1);
    }

    @Test
    void receiveUtxo_addsEntry() {
        state.applyUtxoReceived(new WalletEvent.UtxoReceivedEvent(
                "w1", makeUtxo("tx1", 0, 50000, 6), Instant.now()));
        assertThat(state.getUtxoEntries()).containsKey("tx1:0");
        assertThat(state.getUtxoEntries().get("tx1:0").valueSats()).isEqualTo(50000);
        assertThat(state.getUtxoEntries().get("tx1:0").status()).isEqualTo(UtxoStatus.AVAILABLE);
    }

    @Test
    void receiveUtxo_tracksMinimalInfo() {
        state.applyUtxoReceived(new WalletEvent.UtxoReceivedEvent(
                "w1", makeUtxo("tx1", 0, 30000, 0), Instant.now()));
        WalletState.UtxoEntry entry = state.getUtxoEntries().get("tx1:0");
        assertThat(entry.txid()).isEqualTo("tx1");
        assertThat(entry.valueSats()).isEqualTo(30000);
        assertThat(entry.reservationExpiresAt()).isNull();
    }

    @Test
    void reserveUtxo_updatesStatusAndExpiry() {
        state.applyUtxoReceived(new WalletEvent.UtxoReceivedEvent(
                "w1", makeUtxo("tx1", 0, 50000, 3), Instant.now()));

        Instant expiresAt = Instant.now().plusSeconds(3600);
        state.applyUtxoReserved(new WalletEvent.UtxoReservedEvent(
                "w1", "tx1:0", "spending-tx", expiresAt,
                1, "payment", Instant.now()));

        WalletState.UtxoEntry entry = state.getUtxoEntries().get("tx1:0");
        assertThat(entry.status()).isEqualTo(UtxoStatus.RESERVED);
        assertThat(entry.reservationExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void spendUtxo_marksSpent() {
        state.applyUtxoReceived(new WalletEvent.UtxoReceivedEvent(
                "w1", makeUtxo("tx1", 0, 40000, 3), Instant.now()));

        state.applyUtxoSpent(new WalletEvent.UtxoSpentEvent("w1", "tx1:0", Instant.now()));

        assertThat(state.getUtxoEntries().get("tx1:0").status()).isEqualTo(UtxoStatus.SPENT);
    }

    @Test
    void releaseUtxo_restoresAvailable() {
        state.applyUtxoReceived(new WalletEvent.UtxoReceivedEvent(
                "w1", makeUtxo("tx1", 0, 45000, 5), Instant.now()));

        state.applyUtxoReserved(new WalletEvent.UtxoReservedEvent(
                "w1", "tx1:0", "spending-tx", Instant.now().plusSeconds(3600),
                1, "payment", Instant.now()));

        state.applyUtxoReleased(new WalletEvent.UtxoReleasedEvent("w1", "tx1:0", Instant.now()));

        WalletState.UtxoEntry entry = state.getUtxoEntries().get("tx1:0");
        assertThat(entry.status()).isEqualTo(UtxoStatus.AVAILABLE);
        assertThat(entry.reservationExpiresAt()).isNull();
    }

    @Test
    void addressRecorded_addsToKnownSet() {
        AddressMetadata addr = new AddressMetadata("tb1qaddr1", BitcoinScriptType.P2PKH,
                "m/44'/0'/0'/0/0", 0, false, null, null, null, null, 0, 0, Instant.now(), false);
        state.applyAddressRecorded(new WalletEvent.AddressRecordedEvent("w1", addr, 0, Instant.now()));

        assertThat(state.getKnownAddresses()).contains("tb1qaddr1");
        assertThat(state.getNextDerivationIndex()).isEqualTo(1);
    }

    @Test
    void transactionRecorded_addsToKnownTxids() {
        BitcoinTransaction tx = new BitcoinTransaction(
                "w1", "txABC", "rawhex",
                TransactionStatus.BROADCAST, TransactionDirection.INCOMING,
                null, 0, 0, 50000, 1000, 49000,
                java.util.List.of(), java.util.List.of(), Instant.now(), Instant.now(), null, 0, 2);

        state.applyTransactionRecorded(new WalletEvent.TransactionRecordedEvent("w1", tx, Instant.now()));
        assertThat(state.getKnownTxids()).contains("txABC");
    }

    @Test
    void transactionConfirmed_incrementsVersion() {
        BitcoinTransaction tx = new BitcoinTransaction(
                "w1", "txABC", "rawhex",
                TransactionStatus.BROADCAST, TransactionDirection.INCOMING,
                null, 0, 0, 50000, 1000, 49000,
                java.util.List.of(), java.util.List.of(), Instant.now(), Instant.now(), null, 0, 2);

        state.applyTransactionRecorded(new WalletEvent.TransactionRecordedEvent("w1", tx, Instant.now()));
        long versionBefore = state.getVersion();
        state.applyTransactionConfirmed(new WalletEvent.TransactionConfirmedEvent(
                "w1", "txABC", 6, 800000, Instant.now()));
        assertThat(state.getVersion()).isEqualTo(versionBefore + 1);
    }

    @Test
    void confirmationUpdate_incrementsVersion() {
        state.applyUtxoReceived(new WalletEvent.UtxoReceivedEvent(
                "w1", makeUtxo("tx1", 0, 60000, 0), Instant.now()));

        long versionBefore = state.getVersion();
        state.applyUtxoConfirmationUpdated(new WalletEvent.UtxoConfirmationUpdatedEvent(
                "w1", "tx1", 3, 800000, Instant.now()));
        assertThat(state.getVersion()).isEqualTo(versionBefore + 1);
    }
}
