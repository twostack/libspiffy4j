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
import org.twostack.libspiffy4j.plugin.PluginRegistry;
import org.twostack.libspiffy4j.service.ArcService;
import org.twostack.libspiffy4j.service.CryptoService;
import org.twostack.libspiffy4j.service.EncryptionService;
import org.twostack.libspiffy4j.service.TransactionBuildService;
import org.twostack.libspiffy4j.storage.postgres.SecureStorage;
import org.twostack.libspiffy4j.storage.postgres.WalletReadModelStorage;

import org.twostack.bitcoin4j.crypto.DeterministicKey;
import org.twostack.bitcoin4j.params.NetworkAddressType;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Stateless Pekko Behavior that provides a unified API for wallet, invoice,
 * and payment operations. Routes commands to sharded aggregates, serves
 * read queries from the CQRS read model, and delegates payment operations
 * to {@link PaymentCoordinator} and signing to {@link WalletSigningActor}.
 *
 * <p>This is NOT event-sourced. Correlation state is transient — if the node
 * crashes, in-flight operations fail and the caller retries.
 */
public final class WalletCoordinator {

    private static final Logger LOG = Logger.getLogger(WalletCoordinator.class.getName());

    private WalletCoordinator() {}

    /** Tracks a pending request with its reply target and originating command type. */
    private record PendingRequest(ActorRef<CoordinatorReply> replyTo, String commandType) {}

    public static Behavior<CoordinatorCommand> create(
            ClusterSharding sharding,
            PluginRegistry pluginRegistry,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            CryptoService cryptoService,
            SecureStorage secureStorage,
            EncryptionService encryptionService,
            TransactionBuildService transactionBuildService,
            ArcService arcService) {

        return Behaviors.setup(ctx -> {
            // Initialize sharded entities
            sharding.init(Entity.of(WalletAggregate.ENTITY_TYPE_KEY, entityCtx ->
                    WalletAggregate.create(PersistenceId.of(
                            WalletAggregate.ENTITY_TYPE_KEY.name(), entityCtx.getEntityId()))));

            sharding.init(Entity.of(InvoiceAggregate.ENTITY_TYPE_KEY, entityCtx ->
                    InvoiceAggregate.create(PersistenceId.of(
                            InvoiceAggregate.ENTITY_TYPE_KEY.name(), entityCtx.getEntityId()))));

            // Spawn the signing actor
            ActorRef<WalletSigningActor.SigningCommand> signingActor = ctx.spawn(
                    WalletSigningActor.create(secureStorage, encryptionService, cryptoService, dataSource),
                    "wallet-signing-actor");

            // Correlation maps for tracking in-flight asks
            Map<String, PendingRequest> pendingCorrelations = new HashMap<>();

            return createBehavior(ctx, sharding, pluginRegistry, readModelStorage, dataSource,
                    cryptoService, secureStorage, encryptionService, transactionBuildService,
                    arcService, signingActor, pendingCorrelations);
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
            ArcService arcService,
            ActorRef<WalletSigningActor.SigningCommand> signingActor,
            Map<String, PendingRequest> pendingCorrelations) {

        return Behaviors.receive(CoordinatorCommand.class)
                .onMessage(CoordinatorCommand.CreateWallet.class, cmd ->
                        onCreateWallet(ctx, sharding, cryptoService, secureStorage,
                                encryptionService, dataSource, pendingCorrelations, cmd))
                .onMessage(CoordinatorCommand.DeriveAddress.class, cmd ->
                        onDeriveAddress(ctx, sharding, readModelStorage, dataSource,
                                cryptoService, secureStorage, encryptionService, pendingCorrelations, cmd))
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
                .onMessage(CoordinatorCommand.BuildPayment.class, cmd -> {
                    PaymentCoordinator.buildPayment(ctx, readModelStorage, dataSource,
                            transactionBuildService, signingActor, cmd);
                    return Behaviors.same();
                })
                .onMessage(CoordinatorCommand.BuildPluginPayment.class, cmd -> {
                    PaymentCoordinator.buildPluginPayment(ctx, pluginRegistry, readModelStorage,
                            dataSource, transactionBuildService, arcService, signingActor, cmd);
                    return Behaviors.same();
                })
                .onMessage(CoordinatorCommand.BuildPluginProvisioning.class, cmd -> {
                    PaymentCoordinator.buildPluginProvisioning(ctx, pluginRegistry, readModelStorage,
                            dataSource, arcService, signingActor, cmd);
                    return Behaviors.same();
                })
                .onMessage(CoordinatorCommand.RecordUtxo.class, cmd ->
                        onRecordUtxo(ctx, sharding, pendingCorrelations, cmd))
                .onMessage(CoordinatorCommand.RecordTransaction.class, cmd ->
                        onRecordTransaction(ctx, sharding, pluginRegistry, readModelStorage,
                                dataSource, pendingCorrelations, cmd))
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
            CryptoService cryptoService,
            SecureStorage secureStorage,
            EncryptionService encryptionService,
            DataSource dataSource,
            Map<String, PendingRequest> pending,
            CoordinatorCommand.CreateWallet cmd) {

        try {
            DeterministicKey hdKey = resolveHDKey(cryptoService, cmd);

            NetworkAddressType addrType = toNetworkAddressType(cmd.networkType());
            int coinType = (cmd.networkType() == NetworkType.MAINNET) ? 236 : 1;
            DeterministicKey key0 = cryptoService.derivePrivateKey(hdKey, 0, 0, coinType, false);
            String rootAddress = cryptoService.generateAddress(key0, cmd.networkType());

            if (encryptionService != null) {
                org.twostack.bitcoin4j.params.NetworkType bitcoin4jNetwork = toBitcoin4jNetworkType(cmd.networkType());
                byte[] hdKeyBytes = hdKey.serializePrivate(bitcoin4jNetwork);
                String context = "wallet:" + cmd.walletId() + ":hdkey";
                EncryptionResult encrypted = encryptionService.encrypt(hdKeyBytes, context);

                try (var conn = dataSource.getConnection()) {
                    boolean wasAutoCommit = conn.getAutoCommit();
                    if (wasAutoCommit) conn.setAutoCommit(false);
                    secureStorage.storeEncryptedKey(conn, cmd.walletId(), "hdkey",
                            encrypted.ciphertext(), encrypted.nonce(), 1);
                    conn.commit();
                    if (wasAutoCommit) conn.setAutoCommit(true);
                }
            } else {
                LOG.warning("No EncryptionService — wallet key will not be stored for " + cmd.walletId());
            }

            String correlationId = UUID.randomUUID().toString();
            pending.put(correlationId, new PendingRequest(cmd.replyTo(), "CreateWallet"));

            ActorRef<WalletReply> adapter = spawnWalletReplyBridge(ctx, correlationId);

            EntityRef<WalletCommand> walletRef =
                    sharding.entityRefFor(WalletAggregate.ENTITY_TYPE_KEY, cmd.walletId());
            walletRef.tell(new WalletCommand.CreateWalletCommand(
                    cmd.walletId(), cmd.name(), cmd.walletType(), cmd.networkType(),
                    rootAddress, cmd.metadata(), adapter));

            String rootCorrelationId = UUID.randomUUID().toString();
            pending.put(rootCorrelationId, new PendingRequest(ctx.getSystem().ignoreRef(), "RecordAddress"));
            ActorRef<WalletReply> rootAddrAdapter = spawnWalletReplyBridge(ctx, rootCorrelationId);
            walletRef.tell(new WalletCommand.RecordAddressCommand(
                    cmd.walletId(), new AddressMetadata(rootAddress, 0, false), rootAddrAdapter));

        } catch (Exception e) {
            cmd.replyTo().tell(new CoordinatorReply.Failure("Wallet creation failed: " + e.getMessage()));
        }

        return Behaviors.same();
    }

    private static DeterministicKey resolveHDKey(CryptoService cryptoService,
                                                   CoordinatorCommand.CreateWallet cmd) {
        org.twostack.bitcoin4j.params.NetworkType bitcoin4jNetwork = toBitcoin4jNetworkType(cmd.networkType());

        if (cmd.mnemonic() != null && !cmd.mnemonic().isBlank()) {
            List<String> words = List.of(cmd.mnemonic().split("\\s+"));
            return cryptoService.mnemonicToHDPrivateKey(words, "");
        }
        if (cmd.xpriv() != null && !cmd.xpriv().isBlank()) {
            return DeterministicKey.deserializeB58(cmd.xpriv(), bitcoin4jNetwork);
        }
        if (cmd.wif() != null && !cmd.wif().isBlank()) {
            throw new IllegalArgumentException(
                    "WIF keys are single-purpose and do not support address derivation. Use mnemonic or xpriv.");
        }
        List<String> mnemonic = cryptoService.generateMnemonic();
        LOG.info("No key material provided for wallet " + cmd.walletId()
                + " — generated mnemonic: " + String.join(" ", mnemonic));
        return cryptoService.mnemonicToHDPrivateKey(mnemonic, "");
    }

    private static Behavior<CoordinatorCommand> onDeriveAddress(
            ActorContext<CoordinatorCommand> ctx,
            ClusterSharding sharding,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            CryptoService cryptoService,
            SecureStorage secureStorage,
            EncryptionService encryptionService,
            Map<String, PendingRequest> pending,
            CoordinatorCommand.DeriveAddress cmd) {

        try {
            Optional<WalletSummary> summary = readModelStorage.findWalletSummary(dataSource, cmd.walletId());
            if (summary.isEmpty()) {
                cmd.replyTo().tell(new CoordinatorReply.Failure("Wallet not found: " + cmd.walletId()));
                return Behaviors.same();
            }
            int nextIndex = summary.get().addressCount();
            NetworkType networkType = summary.get().networkType();
            int coinType = (networkType == NetworkType.MAINNET) ? 236 : 1;

            org.twostack.bitcoin4j.params.NetworkType bitcoin4jNetwork = toBitcoin4jNetworkType(networkType);
            DeterministicKey hdKey = retrieveHDKey(secureStorage, encryptionService, dataSource,
                    cmd.walletId(), bitcoin4jNetwork);

            DeterministicKey childKey = cryptoService.derivePrivateKey(hdKey, 0, nextIndex, coinType, false);
            String address = cryptoService.generateAddress(childKey, networkType);

            String correlationId = UUID.randomUUID().toString();
            pending.put(correlationId, new PendingRequest(ctx.getSystem().ignoreRef(), "RecordAddress"));
            ActorRef<WalletReply> adapter = spawnWalletReplyBridge(ctx, correlationId);

            EntityRef<WalletCommand> walletRef =
                    sharding.entityRefFor(WalletAggregate.ENTITY_TYPE_KEY, cmd.walletId());
            walletRef.tell(new WalletCommand.RecordAddressCommand(
                    cmd.walletId(), new AddressMetadata(address, nextIndex, false), adapter));

            cmd.replyTo().tell(new CoordinatorReply.AddressDerived(address, nextIndex));
        } catch (Exception e) {
            cmd.replyTo().tell(new CoordinatorReply.Failure("Address derivation failed: " + e.getMessage()));
        }

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
            List<BitcoinUtxo> utxos = storage.findUtxosByStatus(ds, cmd.walletId(), UtxoStatus.AVAILABLE);
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
            Map<String, PendingRequest> pending,
            CoordinatorCommand.CreateInvoice cmd) {

        String correlationId = UUID.randomUUID().toString();
        pending.put(correlationId, new PendingRequest(cmd.replyTo(), cmd.getClass().getSimpleName()));

        ActorRef<InvoiceReply> adapter = spawnInvoiceReplyBridge(ctx, correlationId);

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
            Map<String, PendingRequest> pending,
            CoordinatorCommand.MarkInvoicePaid cmd) {

        String correlationId = UUID.randomUUID().toString();
        pending.put(correlationId, new PendingRequest(cmd.replyTo(), cmd.getClass().getSimpleName()));

        ActorRef<InvoiceReply> adapter = spawnInvoiceReplyBridge(ctx, correlationId);

        EntityRef<InvoiceCommand> invoiceRef =
                sharding.entityRefFor(InvoiceAggregate.ENTITY_TYPE_KEY, cmd.invoiceId());
        invoiceRef.tell(new InvoiceCommand.MarkInvoicePaidCommand(
                cmd.invoiceId(), cmd.paymentTxid(), cmd.amountReceivedSats(),
                cmd.paymentAddress(), adapter));

        return Behaviors.same();
    }

    // ── UTXO/Transaction recording ──

    private static Behavior<CoordinatorCommand> onRecordUtxo(
            ActorContext<CoordinatorCommand> ctx,
            ClusterSharding sharding,
            Map<String, PendingRequest> pending,
            CoordinatorCommand.RecordUtxo cmd) {

        String correlationId = UUID.randomUUID().toString();
        pending.put(correlationId, new PendingRequest(cmd.replyTo(), cmd.getClass().getSimpleName()));

        ActorRef<WalletReply> adapter = spawnWalletReplyBridge(ctx, correlationId);

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
            Map<String, PendingRequest> pending,
            CoordinatorCommand.RecordTransaction cmd) {

        String correlationId = UUID.randomUUID().toString();
        pending.put(correlationId, new PendingRequest(cmd.replyTo(), cmd.getClass().getSimpleName()));

        ActorRef<WalletReply> adapter = spawnWalletReplyBridge(ctx, correlationId);

        EntityRef<WalletCommand> walletRef =
                sharding.entityRefFor(WalletAggregate.ENTITY_TYPE_KEY, cmd.walletId());
        walletRef.tell(new WalletCommand.RecordTransactionCommand(
                cmd.walletId(), cmd.transaction(), adapter));

        // Auto-record wallet-owned output UTXOs
        PaymentCoordinator.autoRecordOutputUtxos(ctx, pluginRegistry, readModelStorage, dataSource,
                cmd.walletId(), cmd.transaction().txid(), cmd.transaction().rawHex(), null);

        return Behaviors.same();
    }

    // ── Wrapped aggregate replies ──

    private static Behavior<CoordinatorCommand> onWrappedWalletReply(
            Map<String, PendingRequest> pending,
            CoordinatorCommand.WrappedWalletReply cmd) {

        PendingRequest request = pending.remove(cmd.correlationId());
        if (request == null) return Behaviors.same();

        switch (cmd.reply()) {
            case WalletReply.Success success -> {
                CoordinatorReply reply = switch (request.commandType()) {
                    case "CreateWallet" -> new CoordinatorReply.WalletCreated(
                            success.state().getWalletId());
                    default -> new CoordinatorReply.CommandAccepted("OK");
                };
                request.replyTo().tell(reply);
            }
            case WalletReply.Failure failure ->
                    request.replyTo().tell(new CoordinatorReply.Failure(failure.reason()));
        }
        return Behaviors.same();
    }

    private static Behavior<CoordinatorCommand> onWrappedInvoiceReply(
            Map<String, PendingRequest> pending,
            CoordinatorCommand.WrappedInvoiceReply cmd) {

        PendingRequest request = pending.remove(cmd.correlationId());
        if (request == null) return Behaviors.same();

        switch (cmd.reply()) {
            case InvoiceReply.Success success -> {
                CoordinatorReply reply = switch (request.commandType()) {
                    case "CreateInvoice" -> new CoordinatorReply.InvoiceCreated(
                            success.state().getInvoiceId());
                    case "MarkInvoicePaid" -> new CoordinatorReply.InvoicePaid(
                            success.state().getInvoiceId());
                    default -> new CoordinatorReply.CommandAccepted("OK");
                };
                request.replyTo().tell(reply);
            }
            case InvoiceReply.Failure failure ->
                    request.replyTo().tell(new CoordinatorReply.Failure(failure.reason()));
        }
        return Behaviors.same();
    }

    // ── Reply bridge helpers ──

    private static ActorRef<WalletReply> spawnWalletReplyBridge(
            ActorContext<CoordinatorCommand> ctx, String correlationId) {
        return ctx.spawnAnonymous(Behaviors.receiveMessage(reply -> {
            ctx.getSelf().tell(new CoordinatorCommand.WrappedWalletReply(correlationId, reply));
            return Behaviors.stopped();
        }));
    }

    private static ActorRef<InvoiceReply> spawnInvoiceReplyBridge(
            ActorContext<CoordinatorCommand> ctx, String correlationId) {
        return ctx.spawnAnonymous(Behaviors.receiveMessage(reply -> {
            ctx.getSelf().tell(new CoordinatorCommand.WrappedInvoiceReply(correlationId, reply));
            return Behaviors.stopped();
        }));
    }

    // ── Key helpers (used by CreateWallet and DeriveAddress only) ──

    private static DeterministicKey retrieveHDKey(
            SecureStorage secureStorage,
            EncryptionService encryptionService,
            DataSource dataSource,
            String walletId,
            org.twostack.bitcoin4j.params.NetworkType bitcoin4jNetwork) throws Exception {

        if (encryptionService == null) {
            throw new IllegalStateException("EncryptionService required for key operations");
        }

        Optional<EncryptedKeyRecord> keyRecord =
                secureStorage.loadEncryptedKey(dataSource, walletId, "hdkey");
        if (keyRecord.isEmpty()) {
            throw new IllegalStateException("No HD key found for wallet: " + walletId);
        }

        String context = "wallet:" + walletId + ":hdkey";
        byte[] decrypted = encryptionService.decrypt(keyRecord.get().encryptedKey(), keyRecord.get().nonce(), context);
        return DeterministicKey.deserialize(bitcoin4jNetwork, decrypted);
    }

    private static NetworkAddressType toNetworkAddressType(NetworkType networkType) {
        return switch (networkType) {
            case MAINNET -> NetworkAddressType.MAIN_PKH;
            case TESTNET, REGTEST -> NetworkAddressType.TEST_PKH;
        };
    }

    private static org.twostack.bitcoin4j.params.NetworkType toBitcoin4jNetworkType(NetworkType networkType) {
        return switch (networkType) {
            case MAINNET -> org.twostack.bitcoin4j.params.NetworkType.MAIN;
            case TESTNET -> org.twostack.bitcoin4j.params.NetworkType.TEST;
            case REGTEST -> org.twostack.bitcoin4j.params.NetworkType.REGTEST;
        };
    }
}
