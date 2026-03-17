package org.twostack.libspiffy4j.service;

import org.junit.jupiter.api.Test;
import org.twostack.libspiffy4j.model.BitcoinUtxo;
import org.twostack.libspiffy4j.model.UtxoSelectionStrategy;
import org.twostack.libspiffy4j.model.UtxoStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoinSelectorTest {

    private final CoinSelector selector = new CoinSelector();

    private BitcoinUtxo utxo(String txid, long valueSats) {
        return new BitcoinUtxo(txid, 0, valueSats, "76a914...88ac", "1Address",
                UtxoStatus.AVAILABLE, null, null, Instant.now(), Instant.now(),
                null, null, null, null, null, null, null);
    }

    @Test
    void smallestFirst_selectsSmallestUtxosFirst() {
        var utxos = List.of(utxo("tx3", 3000), utxo("tx1", 1000), utxo("tx2", 2000));
        var result = selector.select(utxos, 2500, UtxoSelectionStrategy.SMALLEST_FIRST);

        assertThat(result.totalSelected()).isGreaterThanOrEqualTo(2500);
        assertThat(result.selected()).hasSize(2);
        assertThat(result.selected().get(0).valueSats()).isEqualTo(1000);
        assertThat(result.selected().get(1).valueSats()).isEqualTo(2000);
        assertThat(result.change()).isEqualTo(500);
    }

    @Test
    void largestFirst_selectsLargestUtxosFirst() {
        var utxos = List.of(utxo("tx1", 1000), utxo("tx3", 3000), utxo("tx2", 2000));
        var result = selector.select(utxos, 2500, UtxoSelectionStrategy.LARGEST_FIRST);

        assertThat(result.totalSelected()).isGreaterThanOrEqualTo(2500);
        assertThat(result.selected().get(0).valueSats()).isEqualTo(3000);
        assertThat(result.selected()).hasSize(1);
        assertThat(result.change()).isEqualTo(500);
    }

    @Test
    void random_selectsEnoughToReachTarget() {
        var utxos = List.of(utxo("tx1", 500), utxo("tx2", 500), utxo("tx3", 500), utxo("tx4", 500));
        var result = selector.select(utxos, 1200, UtxoSelectionStrategy.RANDOM);

        assertThat(result.totalSelected()).isGreaterThanOrEqualTo(1200);
        assertThat(result.change()).isEqualTo(result.totalSelected() - 1200);
    }

    @Test
    void optimalChange_prefersMinimalChange() {
        var utxos = List.of(utxo("tx1", 1000), utxo("tx2", 2050), utxo("tx3", 5000));

        // Target 2000 — utxo tx2 (2050) gives only 50 change (within dust), should be preferred
        var result = selector.select(utxos, 2000, UtxoSelectionStrategy.OPTIMAL_CHANGE);

        assertThat(result.totalSelected()).isGreaterThanOrEqualTo(2000);
        assertThat(result.change()).isLessThanOrEqualTo(550); // dust threshold or less
    }

    @Test
    void optimalChange_exactMatchWithinDust() {
        var utxos = List.of(utxo("tx1", 1000), utxo("tx2", 2500), utxo("tx3", 5000));
        var result = selector.select(utxos, 2200, UtxoSelectionStrategy.OPTIMAL_CHANGE);

        // tx2 is 2500, which is within dust of 2200 (diff=300 < 546)
        assertThat(result.selected()).hasSize(1);
        assertThat(result.selected().get(0).valueSats()).isEqualTo(2500);
    }

    @Test
    void insufficientFunds_throwsException() {
        var utxos = List.of(utxo("tx1", 100), utxo("tx2", 200));

        assertThatThrownBy(() -> selector.select(utxos, 500, UtxoSelectionStrategy.SMALLEST_FIRST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    void emptyList_throwsException() {
        assertThatThrownBy(() -> selector.select(List.of(), 1000, UtxoSelectionStrategy.SMALLEST_FIRST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No UTXOs available");
    }

    @Test
    void nullList_throwsException() {
        assertThatThrownBy(() -> selector.select(null, 1000, UtxoSelectionStrategy.SMALLEST_FIRST))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactMatch_singleUtxo() {
        var utxos = List.of(utxo("tx1", 1000));
        var result = selector.select(utxos, 1000, UtxoSelectionStrategy.LARGEST_FIRST);

        assertThat(result.selected()).hasSize(1);
        assertThat(result.totalSelected()).isEqualTo(1000);
        assertThat(result.change()).isZero();
    }

    @Test
    void allUtxosNeeded() {
        var utxos = List.of(utxo("tx1", 100), utxo("tx2", 200), utxo("tx3", 300));
        var result = selector.select(utxos, 600, UtxoSelectionStrategy.SMALLEST_FIRST);

        assertThat(result.selected()).hasSize(3);
        assertThat(result.totalSelected()).isEqualTo(600);
        assertThat(result.change()).isZero();
    }

    @Test
    void negativeTarget_throwsException() {
        var utxos = List.of(utxo("tx1", 1000));

        assertThatThrownBy(() -> selector.select(utxos, -100, UtxoSelectionStrategy.SMALLEST_FIRST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
