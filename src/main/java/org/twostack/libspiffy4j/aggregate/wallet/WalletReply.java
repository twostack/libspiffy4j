package org.twostack.libspiffy4j.aggregate.wallet;

public sealed interface WalletReply permits WalletReply.Success, WalletReply.Failure {

    record Success(WalletState state) implements WalletReply {}

    record Failure(String reason) implements WalletReply {}
}
