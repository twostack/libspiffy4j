package org.twostack.libspiffy4j.aggregate.wallet;

import org.apache.pekko.actor.typed.ActorRef;
import org.twostack.libspiffy4j.model.AddressMetadata;
import org.twostack.libspiffy4j.model.BitcoinTransaction;
import org.twostack.libspiffy4j.model.BitcoinUtxo;
import org.twostack.libspiffy4j.model.NetworkType;
import org.twostack.libspiffy4j.model.WalletType;

import java.time.Instant;
import java.util.Map;

public sealed interface WalletCommand permits
        WalletCommand.CreateWalletCommand,
        WalletCommand.RecordAddressCommand,
        WalletCommand.RecordUtxoCommand,
        WalletCommand.RecordTransactionCommand,
        WalletCommand.ReserveUtxoCommand,
        WalletCommand.ReleaseUtxoCommand,
        WalletCommand.MarkUtxoSpentCommand,
        WalletCommand.UpdateConfirmationCommand,
        WalletCommand.CleanupExpiredReservationsCommand,
        WalletCommand.QueryAddressIndicesCommand {

    record CreateWalletCommand(
            String walletId,
            String name,
            WalletType walletType,
            NetworkType networkType,
            String rootAddress,
            Map<String, Object> metadata,
            ActorRef<WalletReply> replyTo
    ) implements WalletCommand {}

    record RecordAddressCommand(
            String walletId,
            AddressMetadata addressMetadata,
            ActorRef<WalletReply> replyTo
    ) implements WalletCommand {}

    record RecordUtxoCommand(
            String walletId,
            BitcoinUtxo utxo,
            ActorRef<WalletReply> replyTo
    ) implements WalletCommand {}

    record RecordTransactionCommand(
            String walletId,
            BitcoinTransaction transaction,
            ActorRef<WalletReply> replyTo
    ) implements WalletCommand {}

    record ReserveUtxoCommand(
            String walletId,
            String utxoKey,
            String reservingTxId,
            Instant expiresAt,
            Integer priority,
            String reason,
            ActorRef<WalletReply> replyTo
    ) implements WalletCommand {}

    record ReleaseUtxoCommand(
            String walletId,
            String utxoKey,
            ActorRef<WalletReply> replyTo
    ) implements WalletCommand {}

    record MarkUtxoSpentCommand(
            String walletId,
            String utxoKey,
            ActorRef<WalletReply> replyTo
    ) implements WalletCommand {}

    record UpdateConfirmationCommand(
            String walletId,
            String txid,
            int confirmations,
            Integer blockHeight,
            ActorRef<WalletReply> replyTo
    ) implements WalletCommand {}

    record CleanupExpiredReservationsCommand(
            String walletId,
            ActorRef<WalletReply> replyTo
    ) implements WalletCommand {}

    record QueryAddressIndicesCommand(
            String walletId,
            ActorRef<WalletReply> replyTo
    ) implements WalletCommand {}
}
