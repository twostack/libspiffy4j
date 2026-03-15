package org.twostack.libspiffy4j.saga;

public sealed interface ChannelWalletSagaReply permits
        ChannelWalletSagaReply.SagaAccepted,
        ChannelWalletSagaReply.SagaRejected {

    record SagaAccepted(String channelId) implements ChannelWalletSagaReply {}
    record SagaRejected(String channelId, String reason) implements ChannelWalletSagaReply {}
}
