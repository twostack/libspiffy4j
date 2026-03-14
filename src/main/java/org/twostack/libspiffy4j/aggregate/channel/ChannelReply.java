package org.twostack.libspiffy4j.aggregate.channel;

public sealed interface ChannelReply permits ChannelReply.Success, ChannelReply.Failure {

    record Success(ChannelState state) implements ChannelReply {}

    record Failure(String reason) implements ChannelReply {}
}
