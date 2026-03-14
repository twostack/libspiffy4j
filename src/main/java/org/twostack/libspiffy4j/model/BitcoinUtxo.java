package org.twostack.libspiffy4j.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Duration;
import java.time.Instant;

public record BitcoinUtxo(
        String txid,
        int vout,
        long valueSats,
        String scriptPubKey,
        String address,
        UtxoStatus status,
        Integer blockHeight,
        Integer confirmations,
        Instant createdAt,
        Instant updatedAt,
        String reservedByTxId,
        Instant reservationExpiresAt,
        Integer reservationPriority,
        String reservationReason,
        Integer derivationIndex
) {

    public BitcoinUtxo {
        if (txid == null || txid.isBlank()) {
            throw new IllegalArgumentException("txid must not be null or blank");
        }
        if (valueSats < 0) {
            throw new IllegalArgumentException("valueSats must be >= 0");
        }
        if (vout < 0) {
            throw new IllegalArgumentException("vout must be >= 0");
        }
    }

    public String key() {
        return txid + ":" + vout;
    }

    @JsonIgnore
    public boolean isReservationExpired() {
        return reservationExpiresAt != null && Instant.now().isAfter(reservationExpiresAt);
    }

    @JsonIgnore
    public boolean isEffectivelyAvailable() {
        return status == UtxoStatus.AVAILABLE
                || (status == UtxoStatus.RESERVED && isReservationExpired());
    }

    public Duration reservationTimeRemaining() {
        if (reservationExpiresAt == null || isReservationExpired()) {
            return Duration.ZERO;
        }
        return Duration.between(Instant.now(), reservationExpiresAt);
    }

    public BitcoinUtxo reserve(String txId, Instant expiresAt, Integer priority, String reason) {
        return new BitcoinUtxo(
                txid, vout, valueSats, scriptPubKey, address,
                UtxoStatus.RESERVED, blockHeight, confirmations,
                createdAt, Instant.now(),
                txId, expiresAt, priority, reason,
                derivationIndex
        );
    }

    public BitcoinUtxo markSpent() {
        return new BitcoinUtxo(
                txid, vout, valueSats, scriptPubKey, address,
                UtxoStatus.SPENT, blockHeight, confirmations,
                createdAt, Instant.now(),
                reservedByTxId, reservationExpiresAt, reservationPriority, reservationReason,
                derivationIndex
        );
    }

    public BitcoinUtxo markAvailable() {
        return new BitcoinUtxo(
                txid, vout, valueSats, scriptPubKey, address,
                UtxoStatus.AVAILABLE, blockHeight, confirmations,
                createdAt, Instant.now(),
                null, null, null, null,
                derivationIndex
        );
    }

    public BitcoinUtxo releaseReservation() {
        return markAvailable();
    }

    public BitcoinUtxo renewReservation(Instant newExpiresAt) {
        return new BitcoinUtxo(
                txid, vout, valueSats, scriptPubKey, address,
                status, blockHeight, confirmations,
                createdAt, Instant.now(),
                reservedByTxId, newExpiresAt, reservationPriority, reservationReason,
                derivationIndex
        );
    }

    public BitcoinUtxo updateConfirmations(int newConfirmations) {
        return new BitcoinUtxo(
                txid, vout, valueSats, scriptPubKey, address,
                status, blockHeight, newConfirmations,
                createdAt, Instant.now(),
                reservedByTxId, reservationExpiresAt, reservationPriority, reservationReason,
                derivationIndex
        );
    }
}
