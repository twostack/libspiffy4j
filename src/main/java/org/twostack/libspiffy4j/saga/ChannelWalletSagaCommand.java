package org.twostack.libspiffy4j.saga;

import org.twostack.libspiffy4j.aggregate.wallet.WalletReply;

import java.time.Instant;

public sealed interface ChannelWalletSagaCommand permits
        ChannelWalletSagaCommand.InitiateFundingCommand,
        ChannelWalletSagaCommand.ConfirmChannelOpenedCommand,
        ChannelWalletSagaCommand.HandleChannelClosedCommand,
        ChannelWalletSagaCommand.HandleChannelFailedCommand,
        ChannelWalletSagaCommand.HandleRefundClaimedCommand,
        ChannelWalletSagaCommand.WrappedWalletReply {

    /** Host app calls this before channel negotiation. */
    record InitiateFundingCommand(
            String channelId,
            String walletId,
            String utxoKey,
            long amount,
            Instant expiresAt
    ) implements ChannelWalletSagaCommand {}

    /** From projection when channel is opened. */
    record ConfirmChannelOpenedCommand(
            String channelId
    ) implements ChannelWalletSagaCommand {}

    /** From projection when channel is closed. */
    record HandleChannelClosedCommand(
            String channelId,
            String settlementTxId
    ) implements ChannelWalletSagaCommand {}

    /** From projection when channel negotiation fails. */
    record HandleChannelFailedCommand(
            String channelId,
            String reason
    ) implements ChannelWalletSagaCommand {}

    /** From projection when refund is claimed after lock time. */
    record HandleRefundClaimedCommand(
            String channelId
    ) implements ChannelWalletSagaCommand {}

    /** Internal adapter for async wallet responses. */
    record WrappedWalletReply(
            WalletReply reply
    ) implements ChannelWalletSagaCommand {}
}
