package org.twostack.libspiffy4j.coordinator;

import org.twostack.libspiffy4j.model.*;

import java.util.List;

/**
 * Replies sent by the {@link WalletCoordinator} in response to commands.
 */
public sealed interface CoordinatorReply permits
        CoordinatorReply.WalletCreated,
        CoordinatorReply.AddressDerived,
        CoordinatorReply.BalanceResult,
        CoordinatorReply.TransactionsResult,
        CoordinatorReply.UtxosResult,
        CoordinatorReply.InvoiceCreated,
        CoordinatorReply.InvoicePaid,
        CoordinatorReply.PaymentBuilt,
        CoordinatorReply.PluginPaymentBuilt,
        CoordinatorReply.PluginProvisioningBuilt,
        CoordinatorReply.CommandAccepted,
        CoordinatorReply.Failure {

    record WalletCreated(String walletId) implements CoordinatorReply {}

    record AddressDerived(String address, int index) implements CoordinatorReply {}

    record BalanceResult(WalletBalance balance) implements CoordinatorReply {}

    record TransactionsResult(List<BitcoinTransaction> transactions) implements CoordinatorReply {}

    record UtxosResult(List<BitcoinUtxo> utxos) implements CoordinatorReply {}

    record InvoiceCreated(String invoiceId) implements CoordinatorReply {}

    record InvoicePaid(String invoiceId) implements CoordinatorReply {}

    record PaymentBuilt(TransactionBuildResult result) implements CoordinatorReply {}

    record PluginPaymentBuilt(String txid, String rawHex, long feeSats) implements CoordinatorReply {}

    record PluginProvisioningBuilt(
            java.util.List<org.twostack.libspiffy4j.plugin.ProvisionedTransaction> transactions) implements CoordinatorReply {}

    record CommandAccepted(String message) implements CoordinatorReply {}

    record Failure(String reason) implements CoordinatorReply {}
}
