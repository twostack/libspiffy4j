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
        CoordinatorCommand.DeriveAddress,
        CoordinatorCommand.GetBalance,
        CoordinatorCommand.GetTransactions,
        CoordinatorCommand.GetUtxos,
        CoordinatorCommand.CreateInvoice,
        CoordinatorCommand.MarkInvoicePaid,
        CoordinatorCommand.BuildPayment,
        CoordinatorCommand.BuildPluginPayment,
        CoordinatorCommand.BuildPluginProvisioning,
        CoordinatorCommand.RecordUtxo,
        CoordinatorCommand.MarkUtxoSpent,
        CoordinatorCommand.RecordTransaction,
        CoordinatorCommand.WrappedWalletReply,
        CoordinatorCommand.WrappedInvoiceReply {

    // ── Wallet commands ──

    /**
     * Create a wallet with optional key material. Exactly one of {@code mnemonic},
     * {@code xpriv}, or {@code wif} should be provided. The coordinator encrypts
     * and stores the key in SecureStorage, derives the root address, and records it.
     */
    record CreateWallet(
            String walletId, String name, WalletType walletType,
            NetworkType networkType,
            String mnemonic, String xpriv, String wif,
            Map<String, Object> metadata,
            ActorRef<CoordinatorReply> replyTo
    ) implements CoordinatorCommand {}

    /**
     * Derive the next unused address for a wallet. The coordinator loads the
     * HD key from SecureStorage, derives the next child key, records the
     * address internally, and returns it.
     */
    record DeriveAddress(
            String walletId,
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

    /**
     * Provision funding for a token lifecycle. Produces a batch of transactions
     * (split + earmarks) that create earmarked UTXOs at vout=1.
     */
    record BuildPluginProvisioning(
            String walletId, String pluginId,
            Map<String, Object> pluginParams,
            String changeAddress,
            ActorRef<CoordinatorReply> replyTo
    ) implements CoordinatorCommand {}

    // ── UTXO/Transaction recording ──

    /**
     * Record a UTXO in the wallet. Callers should populate {@code scriptPubKey} on the
     * {@link BitcoinUtxo} to enable automatic token identification in the projection layer.
     * The projection will call {@link org.twostack.libspiffy4j.plugin.PluginRegistry#identifyScript}
     * and enrich the UTXO with plugin metadata before persisting to the read model.
     */
    record RecordUtxo(
            String walletId, BitcoinUtxo utxo,
            ActorRef<CoordinatorReply> replyTo
    ) implements CoordinatorCommand {}

    record MarkUtxoSpent(
            String walletId, String utxoKey,
            ActorRef<CoordinatorReply> replyTo
    ) implements CoordinatorCommand {}

    record RecordTransaction(
            String walletId, BitcoinTransaction transaction,
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
