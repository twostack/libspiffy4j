package org.twostack.libspiffy4j.saga;

import org.twostack.libspiffy4j.serialization.SpiffyEvent;

import java.time.Instant;

public sealed interface ChannelWalletSagaEvent extends SpiffyEvent permits
        ChannelWalletSagaEvent.FundingInitiatedEvent,
        ChannelWalletSagaEvent.UtxoReservedEvent,
        ChannelWalletSagaEvent.UtxoReservationFailedEvent,
        ChannelWalletSagaEvent.ChannelOpenConfirmedEvent,
        ChannelWalletSagaEvent.UtxoMarkedSpentEvent,
        ChannelWalletSagaEvent.UtxoReleasedEvent {

    record FundingInitiatedEvent(
            String channelId,
            String walletId,
            String utxoKey,
            long amount,
            Instant expiresAt,
            Instant initiatedAt
    ) implements ChannelWalletSagaEvent {}

    record UtxoReservedEvent(
            String channelId,
            String utxoKey,
            Instant reservedAt
    ) implements ChannelWalletSagaEvent {}

    record UtxoReservationFailedEvent(
            String channelId,
            String utxoKey,
            String reason,
            Instant failedAt
    ) implements ChannelWalletSagaEvent {}

    record ChannelOpenConfirmedEvent(
            String channelId,
            Instant confirmedAt
    ) implements ChannelWalletSagaEvent {}

    record UtxoMarkedSpentEvent(
            String channelId,
            String utxoKey,
            Instant spentAt
    ) implements ChannelWalletSagaEvent {}

    record UtxoReleasedEvent(
            String channelId,
            String utxoKey,
            Instant releasedAt
    ) implements ChannelWalletSagaEvent {}
}
