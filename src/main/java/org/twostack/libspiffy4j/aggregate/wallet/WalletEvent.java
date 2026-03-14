package org.twostack.libspiffy4j.aggregate.wallet;

import org.twostack.libspiffy4j.model.AddressMetadata;
import org.twostack.libspiffy4j.model.BitcoinTransaction;
import org.twostack.libspiffy4j.model.BitcoinUtxo;
import org.twostack.libspiffy4j.model.NetworkType;
import org.twostack.libspiffy4j.model.WalletType;
import org.twostack.libspiffy4j.serialization.SpiffyEvent;

import java.time.Instant;
import java.util.Map;

public sealed interface WalletEvent extends SpiffyEvent permits
        WalletEvent.WalletCreatedEvent,
        WalletEvent.AddressRecordedEvent,
        WalletEvent.UtxoReceivedEvent,
        WalletEvent.UtxoSpentEvent,
        WalletEvent.UtxoReservedEvent,
        WalletEvent.UtxoReleasedEvent,
        WalletEvent.UtxoConfirmationUpdatedEvent,
        WalletEvent.TransactionRecordedEvent,
        WalletEvent.TransactionConfirmedEvent,
        WalletEvent.WalletConfigurationUpdatedEvent {

    record WalletCreatedEvent(
            String walletId,
            String name,
            WalletType walletType,
            NetworkType networkType,
            String rootAddress,
            Map<String, Object> metadata,
            Instant createdAt
    ) implements WalletEvent {}

    record AddressRecordedEvent(
            String walletId,
            AddressMetadata addressMetadata,
            int derivationIndex,
            Instant recordedAt
    ) implements WalletEvent {}

    record UtxoReceivedEvent(
            String walletId,
            BitcoinUtxo utxo,
            Instant receivedAt
    ) implements WalletEvent {}

    record UtxoSpentEvent(
            String walletId,
            String utxoKey,
            Instant spentAt
    ) implements WalletEvent {}

    record UtxoReservedEvent(
            String walletId,
            String utxoKey,
            String reservingTxId,
            Instant expiresAt,
            Integer priority,
            String reason,
            Instant reservedAt
    ) implements WalletEvent {}

    record UtxoReleasedEvent(
            String walletId,
            String utxoKey,
            Instant releasedAt
    ) implements WalletEvent {}

    record UtxoConfirmationUpdatedEvent(
            String walletId,
            String txid,
            int confirmations,
            Integer blockHeight,
            Instant updatedAt
    ) implements WalletEvent {}

    record TransactionRecordedEvent(
            String walletId,
            BitcoinTransaction transaction,
            Instant recordedAt
    ) implements WalletEvent {}

    record TransactionConfirmedEvent(
            String walletId,
            String txid,
            int confirmations,
            Integer blockHeight,
            Instant confirmedAt
    ) implements WalletEvent {}

    record WalletConfigurationUpdatedEvent(
            String walletId,
            Map<String, Object> metadata,
            Instant updatedAt
    ) implements WalletEvent {}
}
