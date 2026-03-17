package org.twostack.libspiffy4j.model;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class BitcoinUtxoTest {

    private BitcoinUtxo sampleUtxo() {
        return new BitcoinUtxo(
            "abc123", 0, 50000L, "76a914...88ac", "1Address",
            UtxoStatus.AVAILABLE, null, null,
            Instant.now(), Instant.now(),
            null, null, null, null, null, null, null
        );
    }

    @Test void key_returnsTxidColonVout() {
        assertThat(sampleUtxo().key()).isEqualTo("abc123:0");
    }

    @Test void reserve_returnsNewInstanceWithReservedStatus() {
        var utxo = sampleUtxo();
        var expires = Instant.now().plusSeconds(60);
        var reserved = utxo.reserve("tx1", expires, 1, "test");
        assertThat(reserved.status()).isEqualTo(UtxoStatus.RESERVED);
        assertThat(reserved.reservedByTxId()).isEqualTo("tx1");
        assertThat(reserved.reservationExpiresAt()).isEqualTo(expires);
        // original unchanged
        assertThat(utxo.status()).isEqualTo(UtxoStatus.AVAILABLE);
    }

    @Test void markSpent_setsStatusToSpent() {
        var spent = sampleUtxo().markSpent();
        assertThat(spent.status()).isEqualTo(UtxoStatus.SPENT);
    }

    @Test void markAvailable_clearsReservation() {
        var expires = Instant.now().plusSeconds(60);
        var reserved = sampleUtxo().reserve("tx1", expires, 1, "reason");
        var available = reserved.markAvailable();
        assertThat(available.status()).isEqualTo(UtxoStatus.AVAILABLE);
        assertThat(available.reservedByTxId()).isNull();
        assertThat(available.reservationExpiresAt()).isNull();
    }

    @Test void isReservationExpired_trueWhenPast() {
        var utxo = sampleUtxo().reserve("tx1", Instant.now().minusSeconds(10), 1, "test");
        assertThat(utxo.isReservationExpired()).isTrue();
    }

    @Test void isReservationExpired_falseWhenFuture() {
        var utxo = sampleUtxo().reserve("tx1", Instant.now().plusSeconds(60), 1, "test");
        assertThat(utxo.isReservationExpired()).isFalse();
    }

    @Test void isEffectivelyAvailable_trueWhenAvailable() {
        assertThat(sampleUtxo().isEffectivelyAvailable()).isTrue();
    }

    @Test void isEffectivelyAvailable_trueWhenReservationExpired() {
        var utxo = sampleUtxo().reserve("tx1", Instant.now().minusSeconds(10), 1, "test");
        assertThat(utxo.isEffectivelyAvailable()).isTrue();
    }

    @Test void reservationTimeRemaining_zeroDurationWhenNoReservation() {
        assertThat(sampleUtxo().reservationTimeRemaining()).isEqualTo(Duration.ZERO);
    }

    @Test void updateConfirmations_returnsNewInstance() {
        var updated = sampleUtxo().updateConfirmations(6);
        assertThat(updated.confirmations()).isEqualTo(6);
    }

    @Test void validation_rejectsNullTxid() {
        assertThatThrownBy(() -> new BitcoinUtxo(
            null, 0, 50000L, null, null, UtxoStatus.AVAILABLE,
            null, null, null, null, null, null, null, null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validation_rejectsBlankTxid() {
        assertThatThrownBy(() -> new BitcoinUtxo(
            "  ", 0, 50000L, null, null, UtxoStatus.AVAILABLE,
            null, null, null, null, null, null, null, null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validation_rejectsNegativeValueSats() {
        assertThatThrownBy(() -> new BitcoinUtxo(
            "abc", 0, -1L, null, null, UtxoStatus.AVAILABLE,
            null, null, null, null, null, null, null, null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validation_rejectsNegativeVout() {
        assertThatThrownBy(() -> new BitcoinUtxo(
            "abc", -1, 50000L, null, null, UtxoStatus.AVAILABLE,
            null, null, null, null, null, null, null, null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
