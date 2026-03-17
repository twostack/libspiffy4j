package org.twostack.libspiffy4j.coordinator;

import org.apache.pekko.actor.typed.ActorRef;
import org.twostack.libspiffy4j.aggregate.invoice.InvoiceReply;
import org.twostack.libspiffy4j.aggregate.wallet.WalletReply;
import org.twostack.libspiffy4j.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Commands accepted by the {@link WalletCoordinator}.
 * Provides a unified API for wallet, invoice, and payment operations.
 */
public sealed interface CoordinatorCommand permits
        CoordinatorCommand.CreateWallet,
        CoordinatorCommand.GetBalance,
        CoordinatorCommand.GetTransactions,
        CoordinatorCommand.GetUtxos,
        CoordinatorCommand.CreateInvoice,
        CoordinatorCommand.MarkInvoicePaid,
        CoordinatorCommand.BuildPayment,
        CoordinatorCommand.BuildPluginPayment,
        CoordinatorCommand.RecordUtxo,
        CoordinatorCommand.RecordTransaction,
        CoordinatorCommand.RecordAddress,
        CoordinatorCommand.WrappedWalletReply,
        CoordinatorCommand.WrappedInvoiceReply {

    // ── Wallet commands ──

    record CreateWallet(
            String walletId, String name, WalletType walletType,
            NetworkType networkType, String rootAddress,
            Map<String, Object> metadata,
            ActorRef<CoordinatorReply> replyTo
    ) implements CoordinatorCommand {}

    record GetBalance(
            String walletId,
            ActorRef<CoordinatorReply> replyTo
    ) implements CoordinatorCommand {}

    record GetTransactions(
            String walletId, int limit, int offset,
            ActorRef<CoordinatorReply> replyTo
    ) implements CoordinatorCommand {}

    record GetUtxos(
            String walletId,
            ActorRef<CoordinatorReply> replyTo
    ) implements CoordinatorCommand {}

    // ── Invoice commands ──

    record CreateInvoice(
            String invoiceId, String walletId, List<String> addresses,
            long amountSats, List<InvoiceOutputSpec> outputs,
            String description, Instant expiresAt,
            Map<String, Object> metadata,
            ActorRef<CoordinatorReply> replyTo
    ) implements CoordinatorCommand {}

    record MarkInvoicePaid(
            String invoiceId, String paymentTxid,
            long amountReceivedSats, String paymentAddress,
            ActorRef<CoordinatorReply> replyTo
    ) implements CoordinatorCommand {}

    // ── Payment commands ──

    record BuildPayment(
            String walletId, List<InvoiceOutputSpec> outputs,
            TransactionBuildConfig config, String changeAddress,
            ActorRef<CoordinatorReply> replyTo
    ) implements CoordinatorCommand {}

    record BuildPluginPayment(
            String walletId, String pluginId, String action,
            Map<String, Object> pluginParams,
            TransactionBuildConfig config, String changeAddress,
            ActorRef<CoordinatorReply> replyTo
    ) implements CoordinatorCommand {}

    // ── UTXO/Transaction recording ──

    record RecordUtxo(
            String walletId, BitcoinUtxo utxo,
            ActorRef<CoordinatorReply> replyTo
    ) implements CoordinatorCommand {}

    record RecordTransaction(
            String walletId, BitcoinTransaction transaction,
            ActorRef<CoordinatorReply> replyTo
    ) implements CoordinatorCommand {}

    record RecordAddress(
            String walletId, AddressMetadata addressMetadata,
            ActorRef<CoordinatorReply> replyTo
    ) implements CoordinatorCommand {}

    // ── Internal: wrapped aggregate replies ──

    record WrappedWalletReply(
            String correlationId,
            WalletReply reply
    ) implements CoordinatorCommand {}

    record WrappedInvoiceReply(
            String correlationId,
            InvoiceReply reply
    ) implements CoordinatorCommand {}
}
