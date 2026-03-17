package org.twostack.libspiffy4j.coordinator;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.twostack.libspiffy4j.aggregate.invoice.InvoiceAggregate;
import org.twostack.libspiffy4j.aggregate.invoice.InvoiceCommand;
import org.twostack.libspiffy4j.aggregate.invoice.InvoiceReply;
import org.twostack.libspiffy4j.aggregate.wallet.WalletAggregate;
import org.twostack.libspiffy4j.aggregate.wallet.WalletCommand;
import org.twostack.libspiffy4j.aggregate.wallet.WalletReply;
import org.twostack.libspiffy4j.model.*;
import org.twostack.libspiffy4j.plugin.*;
import org.twostack.libspiffy4j.service.CryptoService;
import org.twostack.libspiffy4j.service.EncryptionService;
import org.twostack.libspiffy4j.service.TransactionBuildService;
import org.twostack.libspiffy4j.storage.postgres.SecureStorage;
import org.twostack.libspiffy4j.storage.postgres.WalletReadModelStorage;

import org.twostack.bitcoin4j.Utils;
import org.twostack.bitcoin4j.address.LegacyAddress;
import org.twostack.bitcoin4j.params.NetworkAddressType;
import org.twostack.bitcoin4j.script.Script;
import org.twostack.bitcoin4j.script.ScriptPattern;
import org.twostack.bitcoin4j.transaction.Transaction;
import org.twostack.bitcoin4j.transaction.TransactionOutput;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stateless Pekko Behavior that provides a unified API for wallet, invoice,
 * and payment operations. Routes commands to sharded aggregates and serves
 * read queries directly from the CQRS read model.
 *
 * <p>This is NOT event-sourced. Correlation state is transient — if the node
 * crashes, in-flight operations fail and the caller retries.
 */
public final class WalletCoordinator {

    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(10);
    private static final Logger LOG = Logger.getLogger(WalletCoordinator.class.getName());

    private WalletCoordinator() {}

    public static Behavior<CoordinatorCommand> create(
            ClusterSharding sharding,
            PluginRegistry pluginRegistry,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            CryptoService cryptoService,
            SecureStorage secureStorage,
            EncryptionService encryptionService,
            TransactionBuildService transactionBuildService) {

        return Behaviors.setup(ctx -> {
            // Initialize sharded entities
            sharding.init(Entity.of(WalletAggregate.ENTITY_TYPE_KEY, entityCtx ->
                    WalletAggregate.create(PersistenceId.of(
                            WalletAggregate.ENTITY_TYPE_KEY.name(), entityCtx.getEntityId()))));

            sharding.init(Entity.of(InvoiceAggregate.ENTITY_TYPE_KEY, entityCtx ->
                    InvoiceAggregate.create(PersistenceId.of(
                            InvoiceAggregate.ENTITY_TYPE_KEY.name(), entityCtx.getEntityId()))));

            // Create message adapters for aggregate replies
            ActorRef<WalletReply> walletReplyAdapter = ctx.messageAdapter(
                    WalletReply.class,
                    reply -> new CoordinatorCommand.WrappedWalletReply("", reply));

            ActorRef<InvoiceReply> invoiceReplyAdapter = ctx.messageAdapter(
                    InvoiceReply.class,
                    reply -> new CoordinatorCommand.WrappedInvoiceReply("", reply));

            // Correlation maps for tracking in-flight asks
            Map<String, ActorRef<CoordinatorReply>> pendingCorrelations = new HashMap<>();

            return createBehavior(ctx, sharding, pluginRegistry, readModelStorage, dataSource,
                    cryptoService, secureStorage, encryptionService, transactionBuildService,
                    pendingCorrelations);
        });
    }

    private static Behavior<CoordinatorCommand> createBehavior(
            ActorContext<CoordinatorCommand> ctx,
            ClusterSharding sharding,
            PluginRegistry pluginRegistry,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            CryptoService cryptoService,
            SecureStorage secureStorage,
            EncryptionService encryptionService,
            TransactionBuildService transactionBuildService,
            Map<String, ActorRef<CoordinatorReply>> pendingCorrelations) {

        return Behaviors.receive(CoordinatorCommand.class)
                .onMessage(CoordinatorCommand.CreateWallet.class, cmd ->
                        onCreateWallet(ctx, sharding, pendingCorrelations, cmd))
                .onMessage(CoordinatorCommand.GetBalance.class, cmd ->
                        onGetBalance(readModelStorage, dataSource, cmd))
                .onMessage(CoordinatorCommand.GetTransactions.class, cmd ->
                        onGetTransactions(readModelStorage, dataSource, cmd))
                .onMessage(CoordinatorCommand.GetUtxos.class, cmd ->
                        onGetUtxos(readModelStorage, dataSource, cmd))
                .onMessage(CoordinatorCommand.CreateInvoice.class, cmd ->
                        onCreateInvoice(ctx, sharding, pendingCorrelations, cmd))
                .onMessage(CoordinatorCommand.MarkInvoicePaid.class, cmd ->
                        onMarkInvoicePaid(ctx, sharding, pendingCorrelations, cmd))
                .onMessage(CoordinatorCommand.BuildPayment.class, cmd ->
                        onBuildPayment(ctx, sharding, readModelStorage, dataSource,
                                cryptoService, secureStorage, encryptionService,
                                transactionBuildService, cmd))
                .onMessage(CoordinatorCommand.BuildPluginPayment.class, cmd ->
                        onBuildPluginPayment(ctx, sharding, pluginRegistry, readModelStorage,
                                dataSource, cryptoService, secureStorage, encryptionService, cmd))
                .onMessage(CoordinatorCommand.RecordUtxo.class, cmd ->
                        onRecordUtxo(ctx, sharding, pendingCorrelations, cmd))
                .onMessage(CoordinatorCommand.RecordTransaction.class, cmd ->
                        onRecordTransaction(ctx, sharding, pluginRegistry, readModelStorage,
                                dataSource, pendingCorrelations, cmd))
                .onMessage(CoordinatorCommand.RecordAddress.class, cmd ->
                        onRecordAddress(ctx, sharding, pendingCorrelations, cmd))
                .onMessage(CoordinatorCommand.WrappedWalletReply.class, cmd ->
                        onWrappedWalletReply(pendingCorrelations, cmd))
                .onMessage(CoordinatorCommand.WrappedInvoiceReply.class, cmd ->
                        onWrappedInvoiceReply(pendingCorrelations, cmd))
                .build();
    }

    // ── Wallet commands ──

    private static Behavior<CoordinatorCommand> onCreateWallet(
            ActorContext<CoordinatorCommand> ctx,
            ClusterSharding sharding,
            Map<String, ActorRef<CoordinatorReply>> pending,
            CoordinatorCommand.CreateWallet cmd) {

        String correlationId = UUID.randomUUID().toString();
        pending.put(correlationId, cmd.replyTo());

        ActorRef<WalletReply> adapter = ctx.messageAdapter(WalletReply.class,
                reply -> new CoordinatorCommand.WrappedWalletReply(correlationId, reply));

        EntityRef<WalletCommand> walletRef =
                sharding.entityRefFor(WalletAggregate.ENTITY_TYPE_KEY, cmd.walletId());
        walletRef.tell(new WalletCommand.CreateWalletCommand(
                cmd.walletId(), cmd.name(), cmd.walletType(), cmd.networkType(),
                cmd.rootAddress(), cmd.metadata(), adapter));

        return Behaviors.same();
    }

    // ── Read queries (direct from read model, no aggregate) ──

    private static Behavior<CoordinatorCommand> onGetBalance(
            WalletReadModelStorage storage, DataSource ds,
            CoordinatorCommand.GetBalance cmd) {
        try {
            Optional<WalletBalance> balance = storage.getWalletBalance(ds, cmd.walletId());
            if (balance.isPresent()) {
                cmd.replyTo().tell(new CoordinatorReply.BalanceResult(balance.get()));
            } else {
                cmd.replyTo().tell(new CoordinatorReply.Failure("Wallet not found: " + cmd.walletId()));
            }
        } catch (Exception e) {
            cmd.replyTo().tell(new CoordinatorReply.Failure("Failed to get balance: " + e.getMessage()));
        }
        return Behaviors.same();
    }

    private static Behavior<CoordinatorCommand> onGetTransactions(
            WalletReadModelStorage storage, DataSource ds,
            CoordinatorCommand.GetTransactions cmd) {
        try {
            List<BitcoinTransaction> txs = storage.findTransactionsByWalletId(
                    ds, cmd.walletId(), cmd.limit(), cmd.offset());
            cmd.replyTo().tell(new CoordinatorReply.TransactionsResult(txs));
        } catch (Exception e) {
            cmd.replyTo().tell(new CoordinatorReply.Failure("Failed to get transactions: " + e.getMessage()));
        }
        return Behaviors.same();
    }

    private static Behavior<CoordinatorCommand> onGetUtxos(
            WalletReadModelStorage storage, DataSource ds,
            CoordinatorCommand.GetUtxos cmd) {
        try {
            List<BitcoinUtxo> utxos = storage.findUtxosByWalletId(ds, cmd.walletId());
            cmd.replyTo().tell(new CoordinatorReply.UtxosResult(utxos));
        } catch (Exception e) {
            cmd.replyTo().tell(new CoordinatorReply.Failure("Failed to get UTXOs: " + e.getMessage()));
        }
        return Behaviors.same();
    }

    // ── Invoice commands ──

    private static Behavior<CoordinatorCommand> onCreateInvoice(
            ActorContext<CoordinatorCommand> ctx,
            ClusterSharding sharding,
            Map<String, ActorRef<CoordinatorReply>> pending,
            CoordinatorCommand.CreateInvoice cmd) {

        String correlationId = UUID.randomUUID().toString();
        pending.put(correlationId, cmd.replyTo());

        ActorRef<InvoiceReply> adapter = ctx.messageAdapter(InvoiceReply.class,
                reply -> new CoordinatorCommand.WrappedInvoiceReply(correlationId, reply));

        EntityRef<InvoiceCommand> invoiceRef =
                sharding.entityRefFor(InvoiceAggregate.ENTITY_TYPE_KEY, cmd.invoiceId());
        invoiceRef.tell(new InvoiceCommand.CreateInvoiceCommand(
                cmd.invoiceId(), cmd.walletId(), cmd.addresses(), cmd.amountSats(),
                cmd.outputs(), cmd.description(), cmd.expiresAt(), cmd.metadata(), adapter));

        return Behaviors.same();
    }

    private static Behavior<CoordinatorCommand> onMarkInvoicePaid(
            ActorContext<CoordinatorCommand> ctx,
            ClusterSharding sharding,
            Map<String, ActorRef<CoordinatorReply>> pending,
            CoordinatorCommand.MarkInvoicePaid cmd) {

        String correlationId = UUID.randomUUID().toString();
        pending.put(correlationId, cmd.replyTo());

        ActorRef<InvoiceReply> adapter = ctx.messageAdapter(InvoiceReply.class,
                reply -> new CoordinatorCommand.WrappedInvoiceReply(correlationId, reply));

        EntityRef<InvoiceCommand> invoiceRef =
                sharding.entityRefFor(InvoiceAggregate.ENTITY_TYPE_KEY, cmd.invoiceId());
        invoiceRef.tell(new InvoiceCommand.MarkInvoicePaidCommand(
                cmd.invoiceId(), cmd.paymentTxid(), cmd.amountReceivedSats(),
                cmd.paymentAddress(), adapter));

        return Behaviors.same();
    }

    // ── Payment commands ──

    private static Behavior<CoordinatorCommand> onBuildPayment(
            ActorContext<CoordinatorCommand> ctx,
            ClusterSharding sharding,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            CryptoService cryptoService,
            SecureStorage secureStorage,
            EncryptionService encryptionService,
            TransactionBuildService transactionBuildService,
            CoordinatorCommand.BuildPayment cmd) {
        try {
            // 1. Read available UTXOs from read model
            List<BitcoinUtxo> available = readModelStorage.findUtxosByStatus(
                    dataSource, cmd.walletId(), UtxoStatus.AVAILABLE);

            if (available.isEmpty()) {
                cmd.replyTo().tell(new CoordinatorReply.Failure("No available UTXOs"));
                return Behaviors.same();
            }

            // 2. Retrieve and decrypt signing key
            org.twostack.bitcoin4j.ECKey signingKey = retrieveSigningKey(
                    secureStorage, encryptionService, cryptoService, dataSource, cmd.walletId());

            // 3. Determine network type from wallet
            Optional<WalletSummary> summary = readModelStorage.findWalletSummary(dataSource, cmd.walletId());
            if (summary.isEmpty()) {
                cmd.replyTo().tell(new CoordinatorReply.Failure("Wallet not found: " + cmd.walletId()));
                return Behaviors.same();
            }
            NetworkType networkType = summary.get().networkType();

            // 4. Build transaction
            TransactionBuildResult result = transactionBuildService.buildTransaction(
                    available, cmd.outputs(), cmd.config(), cmd.changeAddress(),
                    signingKey, networkType);

            cmd.replyTo().tell(new CoordinatorReply.PaymentBuilt(result));
        } catch (Exception e) {
            cmd.replyTo().tell(new CoordinatorReply.Failure("Payment build failed: " + e.getMessage()));
        }
        return Behaviors.same();
    }

    private static Behavior<CoordinatorCommand> onBuildPluginPayment(
            ActorContext<CoordinatorCommand> ctx,
            ClusterSharding sharding,
            PluginRegistry pluginRegistry,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            CryptoService cryptoService,
            SecureStorage secureStorage,
            EncryptionService encryptionService,
            CoordinatorCommand.BuildPluginPayment cmd) {
        try {
            // 1. Look up the plugin
            Optional<TransactionBuilderPlugin> pluginOpt =
                    pluginRegistry.getTransactionBuilderPlugin(cmd.pluginId());
            if (pluginOpt.isEmpty()) {
                cmd.replyTo().tell(new CoordinatorReply.Failure("Plugin not found: " + cmd.pluginId()));
                return Behaviors.same();
            }
            TransactionBuilderPlugin plugin = pluginOpt.get();

            // 2. Verify action is supported
            if (!plugin.supportedActions().contains(cmd.action())) {
                cmd.replyTo().tell(new CoordinatorReply.Failure(
                        "Unsupported action '%s' for plugin '%s'".formatted(cmd.action(), cmd.pluginId())));
                return Behaviors.same();
            }

            // 3. Read available UTXOs
            List<BitcoinUtxo> available = readModelStorage.findUtxosByStatus(
                    dataSource, cmd.walletId(), UtxoStatus.AVAILABLE);
            if (available.isEmpty()) {
                cmd.replyTo().tell(new CoordinatorReply.Failure("No available UTXOs"));
                return Behaviors.same();
            }

            // 4. Retrieve and decrypt signing key
            org.twostack.bitcoin4j.ECKey signingKey = retrieveSigningKey(
                    secureStorage, encryptionService, cryptoService, dataSource, cmd.walletId());

            // 5. Create CallbackTransactionSigner (key stays in closure)
            CallbackTransactionSigner signer = (sighash, inputIndex) -> {
                org.twostack.bitcoin4j.ECKey.ECDSASignature sig =
                        signingKey.sign(org.twostack.bitcoin4j.Sha256Hash.wrap(sighash));
                return sig.encodeToDER();
            };

            // 6. Build public key list
            List<String> publicKeyHexes = List.of(
                    org.twostack.bitcoin4j.Utils.HEX.encode(signingKey.getPubKey()));

            // 7. Create TransactionLookup (resolves raw hex from wallet's read model)
            TransactionLookup transactionLookup = txid -> {
                try {
                    return readModelStorage.findRawHexByTxid(dataSource, txid).orElse(null);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to look up transaction: " + txid, e);
                    return null;
                }
            };

            // 8. Build PluginTransactionRequest
            PluginTransactionRequest request = new PluginTransactionRequest(
                    available, signer, transactionLookup, publicKeyHexes, cmd.changeAddress(), cmd.pluginParams());

            // 8. Plugin builds the complete transaction
            TransactionBuilderResult result = plugin.buildTransaction(request);

            // 9. Validate transaction structure
            byte[] rawTx = org.twostack.bitcoin4j.Utils.HEX.decode(result.rawHex());
            if (!plugin.validateTransactionStructure(rawTx, cmd.action())) {
                cmd.replyTo().tell(new CoordinatorReply.Failure(
                        "Plugin transaction failed structure validation"));
                return Behaviors.same();
            }

            // 10. Auto-record wallet-owned output UTXOs from the built transaction
            autoRecordOutputUtxos(ctx, pluginRegistry, readModelStorage, dataSource,
                    cmd.walletId(), result.txid(), result.rawHex());

            cmd.replyTo().tell(new CoordinatorReply.PluginPaymentBuilt(
                    result.txid(), result.rawHex(), result.feeSats()));
        } catch (Exception e) {
            cmd.replyTo().tell(new CoordinatorReply.Failure(
                    "Plugin payment failed: " + e.getMessage()));
        }
        return Behaviors.same();
    }

    // ── UTXO/Transaction recording ──

    private static Behavior<CoordinatorCommand> onRecordUtxo(
            ActorContext<CoordinatorCommand> ctx,
            ClusterSharding sharding,
            Map<String, ActorRef<CoordinatorReply>> pending,
            CoordinatorCommand.RecordUtxo cmd) {

        String correlationId = UUID.randomUUID().toString();
        pending.put(correlationId, cmd.replyTo());

        ActorRef<WalletReply> adapter = ctx.messageAdapter(WalletReply.class,
                reply -> new CoordinatorCommand.WrappedWalletReply(correlationId, reply));

        EntityRef<WalletCommand> walletRef =
                sharding.entityRefFor(WalletAggregate.ENTITY_TYPE_KEY, cmd.walletId());
        walletRef.tell(new WalletCommand.RecordUtxoCommand(cmd.walletId(), cmd.utxo(), adapter));

        return Behaviors.same();
    }

    private static Behavior<CoordinatorCommand> onRecordTransaction(
            ActorContext<CoordinatorCommand> ctx,
            ClusterSharding sharding,
            PluginRegistry pluginRegistry,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            Map<String, ActorRef<CoordinatorReply>> pending,
            CoordinatorCommand.RecordTransaction cmd) {

        String correlationId = UUID.randomUUID().toString();
        pending.put(correlationId, cmd.replyTo());

        ActorRef<WalletReply> adapter = ctx.messageAdapter(WalletReply.class,
                reply -> new CoordinatorCommand.WrappedWalletReply(correlationId, reply));

        EntityRef<WalletCommand> walletRef =
                sharding.entityRefFor(WalletAggregate.ENTITY_TYPE_KEY, cmd.walletId());
        walletRef.tell(new WalletCommand.RecordTransactionCommand(
                cmd.walletId(), cmd.transaction(), adapter));

        // Auto-record wallet-owned output UTXOs from the raw transaction
        autoRecordOutputUtxos(ctx, pluginRegistry, readModelStorage, dataSource,
                cmd.walletId(), cmd.transaction().txid(), cmd.transaction().rawHex());

        return Behaviors.same();
    }

    private static Behavior<CoordinatorCommand> onRecordAddress(
            ActorContext<CoordinatorCommand> ctx,
            ClusterSharding sharding,
            Map<String, ActorRef<CoordinatorReply>> pending,
            CoordinatorCommand.RecordAddress cmd) {

        String correlationId = UUID.randomUUID().toString();
        pending.put(correlationId, cmd.replyTo());

        ActorRef<WalletReply> adapter = ctx.messageAdapter(WalletReply.class,
                reply -> new CoordinatorCommand.WrappedWalletReply(correlationId, reply));

        EntityRef<WalletCommand> walletRef =
                sharding.entityRefFor(WalletAggregate.ENTITY_TYPE_KEY, cmd.walletId());
        walletRef.tell(new WalletCommand.RecordAddressCommand(
                cmd.walletId(), cmd.addressMetadata(), adapter));

        return Behaviors.same();
    }

    // ── Wrapped aggregate replies ──

    private static Behavior<CoordinatorCommand> onWrappedWalletReply(
            Map<String, ActorRef<CoordinatorReply>> pending,
            CoordinatorCommand.WrappedWalletReply cmd) {

        ActorRef<CoordinatorReply> replyTo = pending.remove(cmd.correlationId());
        if (replyTo == null) return Behaviors.same();

        switch (cmd.reply()) {
            case WalletReply.Success success ->
                    replyTo.tell(new CoordinatorReply.CommandAccepted("OK"));
            case WalletReply.Failure failure ->
                    replyTo.tell(new CoordinatorReply.Failure(failure.reason()));
        }
        return Behaviors.same();
    }

    private static Behavior<CoordinatorCommand> onWrappedInvoiceReply(
            Map<String, ActorRef<CoordinatorReply>> pending,
            CoordinatorCommand.WrappedInvoiceReply cmd) {

        ActorRef<CoordinatorReply> replyTo = pending.remove(cmd.correlationId());
        if (replyTo == null) return Behaviors.same();

        switch (cmd.reply()) {
            case InvoiceReply.Success success ->
                    replyTo.tell(new CoordinatorReply.CommandAccepted("OK"));
            case InvoiceReply.Failure failure ->
                    replyTo.tell(new CoordinatorReply.Failure(failure.reason()));
        }
        return Behaviors.same();
    }

    // ── Private helpers ──

    /**
     * Parse a raw transaction and auto-record any outputs owned by this wallet.
     * Ownership is determined by:
     * <ol>
     *   <li>Standard scripts (P2PKH): derive address, match against wallet addresses</li>
     *   <li>Plugin scripts: call identifyScript/extractMetadata, match ownerAddress
     *       from metadata against wallet addresses</li>
     * </ol>
     * Each matched output becomes a RecordUtxo self-tell, which flows through
     * the normal aggregate → event → projection pipeline (where plugin enrichment happens).
     */
    private static void autoRecordOutputUtxos(
            ActorContext<CoordinatorCommand> ctx,
            PluginRegistry pluginRegistry,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            String walletId, String txid, String rawHex) {

        if (rawHex == null || rawHex.isBlank()) return;

        try {
            Transaction tx = Transaction.fromHex(rawHex);
            List<TransactionOutput> outputs = tx.getOutputs();

            // Look up wallet's network type for address derivation
            Optional<WalletSummary> summaryOpt = readModelStorage.findWalletSummary(dataSource, walletId);
            if (summaryOpt.isEmpty()) return;
            NetworkAddressType addrType = toNetworkAddressType(summaryOpt.get().networkType());

            // Collect wallet addresses for matching
            Set<String> walletAddresses = new HashSet<>(
                    readModelStorage.findAddressesByWalletId(dataSource, walletId));

            for (int vout = 0; vout < outputs.size(); vout++) {
                TransactionOutput output = outputs.get(vout);
                Script script = output.getScript();
                byte[] scriptBytes = script.getProgram();
                String scriptHex = Utils.HEX.encode(scriptBytes);

                // Try standard address derivation first
                String address = deriveStandardAddress(script, addrType);

                if (address != null && walletAddresses.contains(address)) {
                    recordUtxo(ctx, walletId, txid, vout, output, scriptHex, address);
                    continue;
                }

                // Fall back to plugin identification for non-standard scripts
                if (pluginRegistry.hasPlugins()) {
                    Optional<PluginRegistry.PluginIdentification> identification =
                            pluginRegistry.identifyScript(scriptBytes);
                    if (identification.isPresent()) {
                        ScriptPlugin plugin = pluginRegistry.getPlugin(
                                identification.get().pluginId()).orElse(null);
                        if (plugin != null) {
                            Map<String, Object> metadata = plugin.extractMetadata(scriptBytes);
                            String ownerAddress = metadata != null
                                    ? (String) metadata.get("ownerAddress") : null;
                            if (ownerAddress != null && walletAddresses.contains(ownerAddress)) {
                                recordUtxo(ctx, walletId, txid, vout, output, scriptHex, ownerAddress);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to auto-record output UTXOs for tx " + txid, e);
        }
    }

    private static void recordUtxo(
            ActorContext<CoordinatorCommand> ctx,
            String walletId, String txid, int vout,
            TransactionOutput output, String scriptHex, String address) {

        Instant now = Instant.now();
        BitcoinUtxo utxo = new BitcoinUtxo(
                txid, vout, output.getAmount().longValue(), scriptHex,
                address, UtxoStatus.AVAILABLE,
                null, null, now, now,
                null, null, null, null, null,
                null, null  // pluginId/metadata enriched by projection
        );
        ctx.getSelf().tell(new CoordinatorCommand.RecordUtxo(
                walletId, utxo, ctx.getSystem().ignoreRef()));
    }

    /**
     * Derive address from a standard script type (P2PKH).
     * Returns null for non-standard scripts.
     */
    private static String deriveStandardAddress(Script script, NetworkAddressType addrType) {
        try {
            if (ScriptPattern.isP2PKH(script)) {
                byte[] hash160 = ScriptPattern.extractHashFromP2PKH(script);
                return LegacyAddress.fromPubKeyHash(addrType, hash160).toBase58();
            }
        } catch (Exception e) {
            // Non-standard script — not an error
        }
        return null;
    }

    private static NetworkAddressType toNetworkAddressType(NetworkType networkType) {
        return switch (networkType) {
            case MAINNET -> NetworkAddressType.MAIN_PKH;
            case TESTNET, REGTEST -> NetworkAddressType.TEST_PKH;
        };
    }

    private static org.twostack.bitcoin4j.ECKey retrieveSigningKey(
            SecureStorage secureStorage,
            EncryptionService encryptionService,
            CryptoService cryptoService,
            DataSource dataSource,
            String walletId) throws Exception {

        if (encryptionService == null) {
            throw new IllegalStateException("EncryptionService required for payment operations");
        }

        Optional<EncryptedKeyRecord> keyRecord =
                secureStorage.loadEncryptedKey(dataSource, walletId, "wif");
        if (keyRecord.isEmpty()) {
            throw new IllegalStateException("No signing key found for wallet: " + walletId);
        }

        EncryptedKeyRecord record = keyRecord.get();
        byte[] decrypted = encryptionService.decrypt(
                record.encryptedKey(), record.nonce(), "wallet:" + walletId);
        String wif = new String(decrypted, java.nio.charset.StandardCharsets.UTF_8);

        return cryptoService.privateKeyFromWIF(wif);
    }
}
