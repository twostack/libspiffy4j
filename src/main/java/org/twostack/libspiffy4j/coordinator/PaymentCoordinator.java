package org.twostack.libspiffy4j.coordinator;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.twostack.bitcoin4j.Utils;
import org.twostack.bitcoin4j.address.LegacyAddress;
import org.twostack.bitcoin4j.params.NetworkAddressType;
import org.twostack.bitcoin4j.script.Script;
import org.twostack.bitcoin4j.script.ScriptPattern;
import org.twostack.bitcoin4j.transaction.Transaction;
import org.twostack.bitcoin4j.transaction.TransactionOutput;
import org.twostack.libspiffy4j.model.*;
import org.twostack.libspiffy4j.plugin.*;
import org.twostack.libspiffy4j.service.ArcService;
import org.twostack.libspiffy4j.service.ArcServiceException;
import org.twostack.libspiffy4j.service.TransactionBuildService;
import org.twostack.libspiffy4j.spv.BeefBuilder;
import org.twostack.libspiffy4j.spv.Bump;
import org.twostack.libspiffy4j.aggregate.wallet.WalletCommand;
import org.twostack.libspiffy4j.aggregate.wallet.WalletReply;
import org.twostack.libspiffy4j.storage.postgres.WalletReadModelStorage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles payment transaction building: UTXO selection, reservation,
 * plugin invocation, ARC broadcast, and UTXO lifecycle management.
 *
 * <p>Flow: select UTXOs &rarr; reserve &rarr; build TX &rarr; broadcast &rarr;
 * mark spent + record (on success) or release reservations (on failure).
 *
 * <p>Delegates signing to {@link WalletSigningActor} — never touches
 * private keys directly.
 */
public final class PaymentCoordinator {

    private static final Logger LOG = Logger.getLogger(PaymentCoordinator.class.getName());
    private static final Duration SIGNING_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(5);

    /**
     * Approximate sats needed per provisioned lifecycle step (split output + earmark fee).
     */
    private static final long SATS_PER_LIFECYCLE_STEP = 18_000L;

    /**
     * Plugin ID used to tag funding earmark UTXOs in the read model.
     * Distinguished from real plugin IDs (e.g., "tsl1-ft") which identify token scripts.
     */
    public static final String FUNDING_EARMARK_PLUGIN_ID = "funding-earmark";

    /**
     * Per-wallet concurrency guard — prevents overlapping auto-provision operations.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>
            provisioningInProgress = new java.util.concurrent.ConcurrentHashMap<>();

    private PaymentCoordinator() {}

    /**
     * Build, broadcast, and record a plugin payment transaction.
     *
     * <ol>
     *   <li>Select available UTXOs</li>
     *   <li>Reserve selected UTXOs (AVAILABLE &rarr; RESERVED)</li>
     *   <li>Resolve signing keys via {@link WalletSigningActor}</li>
     *   <li>Invoke plugin to build the transaction</li>
     *   <li>Broadcast via {@link ArcService}</li>
     *   <li>On success: mark reserved UTXOs as SPENT, record transaction</li>
     *   <li>On failure: release reservations (RESERVED &rarr; AVAILABLE)</li>
     * </ol>
     */
    public static CoordinatorReply buildPluginPayment(
            ActorContext<CoordinatorCommand> ctx,
            PluginRegistry pluginRegistry,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            TransactionBuildService transactionBuildService,
            ArcService arcService,
            ActorRef<WalletSigningActor.SigningCommand> signingActor,
            org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding sharding,
            CoordinatorCommand.BuildPluginPayment cmd) {

        List<BitcoinUtxo> reserved = List.of();
        try {
            Optional<TransactionBuilderPlugin> pluginOpt =
                    pluginRegistry.getTransactionBuilderPlugin(cmd.pluginId());
            if (pluginOpt.isEmpty()) {
                return new CoordinatorReply.Failure("Plugin not found: " + cmd.pluginId());
            }
            TransactionBuilderPlugin plugin = pluginOpt.get();

            if (!plugin.supportedActions().contains(cmd.action())) {
                return new CoordinatorReply.Failure(
                        "Unsupported action '%s' for plugin '%s'".formatted(cmd.action(), cmd.pluginId()));
            }

            List<BitcoinUtxo> available = readModelStorage.findUtxosByStatus(
                    dataSource, cmd.walletId(), UtxoStatus.AVAILABLE);
            if (available.isEmpty()) {
                return new CoordinatorReply.Failure("No available UTXOs");
            }

            reserved = available;
            reserveUtxos(readModelStorage, dataSource, cmd.walletId(), reserved, "pending-build");

            Optional<WalletSummary> summaryOpt = readModelStorage.findWalletSummary(dataSource, cmd.walletId());
            if (summaryOpt.isEmpty()) {
                releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                return new CoordinatorReply.Failure("Wallet not found: " + cmd.walletId());
            }
            NetworkType networkType = summaryOpt.get().networkType();

            Map<String, Integer> addressToIndex = queryAddressIndices(sharding, cmd.walletId(), ctx);

            WalletSigningActor.SigningReply signingReply =
                    org.apache.pekko.actor.typed.javadsl.AskPattern.<WalletSigningActor.SigningCommand, WalletSigningActor.SigningReply>ask(
                            signingActor,
                            replyTo -> new WalletSigningActor.PrepareSigner(
                                    cmd.walletId(), available, addressToIndex, networkType,
                                    buildScriptResolver(pluginRegistry, networkType), replyTo),
                            SIGNING_TIMEOUT, ctx.getSystem().scheduler()
                    ).toCompletableFuture().get(SIGNING_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

            if (signingReply instanceof WalletSigningActor.SigningFailure failure) {
                releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                return new CoordinatorReply.Failure(failure.reason());
            }

            WalletSigningActor.SignerReady ready = (WalletSigningActor.SignerReady) signingReply;
            LOG.info("Signer ready for wallet " + cmd.walletId()
                    + " — pubKeys=" + ready.publicKeyHexes());

            TransactionLookup transactionLookup = txid -> {
                try {
                    return readModelStorage.findRawHexByTxid(dataSource, txid).orElse(null);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to look up transaction: " + txid, e);
                    return null;
                }
            };

            PluginTransactionRequest request = new PluginTransactionRequest(
                    available, ready.signer(), transactionLookup,
                    ready.publicKeyHexes(), cmd.changeAddress(), cmd.pluginParams());

            LOG.info("Invoking plugin " + cmd.pluginId() + " action=" + cmd.action());
            TransactionBuilderResult result = plugin.buildTransaction(request);
            LOG.info("Plugin returned txid=" + result.txid() + " rawHex length=" + result.rawHex().length());

            byte[] rawTx = Utils.HEX.decode(result.rawHex());
            if (!plugin.validateTransactionStructure(rawTx, cmd.action())) {
                releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                return new CoordinatorReply.Failure("Plugin transaction failed structure validation");
            }

            // Build BEEF before broadcast — includes unbroadcast ancestors
            String beefHex = buildBeefEnvelope(result.rawHex(), readModelStorage, dataSource, arcService);

            try {
                ArcSubmitResponse arcResponse;
                if (beefHex != null) {
                    arcResponse = arcService.submitBeef(beefHex);
                    LOG.info("Broadcast (BEEF) for tx " + result.txid()
                            + " — ARC status: " + arcResponse.status());
                } else {
                    arcResponse = arcService.submitTransaction(result.rawHex());
                    LOG.info("Broadcast (raw) for tx " + result.txid()
                            + " — ARC status: " + arcResponse.status());
                }

                if (arcResponse.status() == ArcTransactionStatus.SEEN_IN_ORPHAN_MEMPOOL) {
                    LOG.warning("TX " + result.txid() + " landed in orphan mempool — "
                            + "parent TX may be missing or spent");
                    releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                    return new CoordinatorReply.Failure(
                            "ARC accepted TX but placed in orphan mempool — parent TX may be invalid");
                }
            } catch (ArcServiceException e) {
                LOG.log(Level.WARNING, "Broadcast failed for tx " + result.txid()
                        + " — ARC status " + e.httpStatusCode() + ": " + e.responseBody(), e);
                releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                return new CoordinatorReply.Failure("ARC broadcast failed: " + e.getMessage());
            }

            // Broadcast paired witness TX if present (same UTXO reservation)
            if (result.hasPairedWitness()) {
                LOG.info("Broadcasting paired witness TX " + result.witnessTxid()
                        + " rawHex length=" + result.witnessRawHex().length());
                try {
                    arcService.submitTransaction(result.witnessRawHex());
                } catch (ArcServiceException we) {
                    LOG.log(Level.WARNING, "Witness broadcast failed for tx " + result.witnessTxid()
                            + " — ARC status " + we.httpStatusCode() + ": " + we.responseBody(), we);
                    releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                    return new CoordinatorReply.Failure(
                            "Witness TX broadcast failed: " + we.getMessage());
                }
            }

            List<String> spentKeys = identifySpentInputs(result.rawHex(), reserved);
            if (result.hasPairedWitness()) {
                spentKeys.addAll(identifySpentInputs(result.witnessRawHex(), reserved));
            }
            finalizeUtxos(readModelStorage, dataSource, cmd.walletId(), reserved, spentKeys);

            // Post-operation: check inventory and trigger auto-provision if needed
            checkInventoryAndAutoProvision(ctx, readModelStorage, dataSource,
                    cmd.walletId(), cmd.pluginId(), cmd.changeAddress());

            return new CoordinatorReply.PluginPaymentBuilt(
                    result.txid(), result.rawHex(), beefHex, result.feeSats(),
                    result.witnessTxid(), result.witnessRawHex());

        } catch (Exception e) {
            releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
            return new CoordinatorReply.Failure("Plugin payment failed: " + e.getMessage());
        }
    }

    /**
     * Build a plugin payment transaction without broadcasting to ARC.
     *
     * <p>Identical to {@link #buildPluginPayment} except the ARC broadcast step
     * is skipped. The caller is responsible for broadcasting the returned raw hex
     * when appropriate (e.g., after mobile enrollment co-signing).
     */
    public static CoordinatorReply buildPluginPaymentNoBroadcast(
            ActorContext<CoordinatorCommand> ctx,
            PluginRegistry pluginRegistry,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            TransactionBuildService transactionBuildService,
            ArcService arcService,
            ActorRef<WalletSigningActor.SigningCommand> signingActor,
            org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding sharding,
            CoordinatorCommand.BuildPluginPaymentNoBroadcast cmd) {

        List<BitcoinUtxo> reserved = List.of();
        try {
            Optional<TransactionBuilderPlugin> pluginOpt =
                    pluginRegistry.getTransactionBuilderPlugin(cmd.pluginId());
            if (pluginOpt.isEmpty()) {
                return new CoordinatorReply.Failure("Plugin not found: " + cmd.pluginId());
            }
            TransactionBuilderPlugin plugin = pluginOpt.get();

            if (!plugin.supportedActions().contains(cmd.action())) {
                return new CoordinatorReply.Failure(
                        "Unsupported action '%s' for plugin '%s'".formatted(cmd.action(), cmd.pluginId()));
            }

            List<BitcoinUtxo> available = readModelStorage.findUtxosByStatus(
                    dataSource, cmd.walletId(), UtxoStatus.AVAILABLE);
            if (available.isEmpty()) {
                return new CoordinatorReply.Failure("No available UTXOs");
            }

            reserved = available;
            reserveUtxos(readModelStorage, dataSource, cmd.walletId(), reserved, "pending-build");

            Optional<WalletSummary> summaryOpt = readModelStorage.findWalletSummary(dataSource, cmd.walletId());
            if (summaryOpt.isEmpty()) {
                releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                return new CoordinatorReply.Failure("Wallet not found: " + cmd.walletId());
            }
            NetworkType networkType = summaryOpt.get().networkType();

            Map<String, Integer> addressToIndex = queryAddressIndices(sharding, cmd.walletId(), ctx);

            WalletSigningActor.SigningReply signingReply =
                    org.apache.pekko.actor.typed.javadsl.AskPattern.<WalletSigningActor.SigningCommand, WalletSigningActor.SigningReply>ask(
                            signingActor,
                            replyTo -> new WalletSigningActor.PrepareSigner(
                                    cmd.walletId(), available, addressToIndex, networkType,
                                    buildScriptResolver(pluginRegistry, networkType), replyTo),
                            SIGNING_TIMEOUT, ctx.getSystem().scheduler()
                    ).toCompletableFuture().get(SIGNING_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

            if (signingReply instanceof WalletSigningActor.SigningFailure failure) {
                releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                return new CoordinatorReply.Failure(failure.reason());
            }

            WalletSigningActor.SignerReady ready = (WalletSigningActor.SignerReady) signingReply;
            LOG.info("Signer ready for wallet " + cmd.walletId()
                    + " — pubKeys=" + ready.publicKeyHexes());

            TransactionLookup transactionLookup = txid -> {
                try {
                    return readModelStorage.findRawHexByTxid(dataSource, txid).orElse(null);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to look up transaction: " + txid, e);
                    return null;
                }
            };

            PluginTransactionRequest request = new PluginTransactionRequest(
                    available, ready.signer(), transactionLookup,
                    ready.publicKeyHexes(), cmd.changeAddress(), cmd.pluginParams());

            LOG.info("Invoking plugin " + cmd.pluginId() + " action=" + cmd.action());
            TransactionBuilderResult result = plugin.buildTransaction(request);
            LOG.info("Plugin returned txid=" + result.txid() + " rawHex length=" + result.rawHex().length());

            byte[] rawTx = Utils.HEX.decode(result.rawHex());
            if (!plugin.validateTransactionStructure(rawTx, cmd.action())) {
                releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                return new CoordinatorReply.Failure("Plugin transaction failed structure validation");
            }

            // No ARC broadcast — caller is responsible for broadcasting

            List<String> spentKeys = identifySpentInputs(result.rawHex(), reserved);
            finalizeUtxos(readModelStorage, dataSource, cmd.walletId(), reserved, spentKeys);

            // Post-operation: check inventory and trigger auto-provision if needed
            checkInventoryAndAutoProvision(ctx, readModelStorage, dataSource,
                    cmd.walletId(), cmd.pluginId(), cmd.changeAddress());

            String beefHex = buildBeefEnvelope(result.rawHex(), readModelStorage, dataSource, arcService);

            return new CoordinatorReply.PluginPaymentBuilt(
                    result.txid(), result.rawHex(), beefHex, result.feeSats(),
                    result.witnessTxid(), result.witnessRawHex());

        } catch (Exception e) {
            releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
            return new CoordinatorReply.Failure("Plugin payment failed: " + e.getMessage());
        }
    }

    /**
     * Build, broadcast, and record provisioning transactions.
     *
     * <p>Produces a batch of transactions (split + earmarks) and broadcasts them
     * sequentially. On partial failure, UTXOs consumed by already-broadcast TXs
     * remain SPENT; only the original wallet UTXOs are reserved/released.
     */
    public static CoordinatorReply buildPluginProvisioning(
            ActorContext<CoordinatorCommand> ctx,
            PluginRegistry pluginRegistry,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            ArcService arcService,
            ActorRef<WalletSigningActor.SigningCommand> signingActor,
            org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding sharding,
            CoordinatorCommand.BuildPluginProvisioning cmd) {

        List<BitcoinUtxo> reserved = List.of();
        try {
            Optional<TransactionBuilderPlugin> pluginOpt =
                    pluginRegistry.getTransactionBuilderPlugin(cmd.pluginId());
            if (pluginOpt.isEmpty()) {
                return new CoordinatorReply.Failure("Plugin not found: " + cmd.pluginId());
            }
            TransactionBuilderPlugin plugin = pluginOpt.get();

            List<BitcoinUtxo> available = readModelStorage.findUtxosByStatus(
                    dataSource, cmd.walletId(), UtxoStatus.AVAILABLE);
            if (available.isEmpty()) {
                return new CoordinatorReply.Failure("No available UTXOs");
            }

            reserved = available;
            reserveUtxos(readModelStorage, dataSource, cmd.walletId(), reserved, "pending-provision");

            Optional<WalletSummary> summaryOpt = readModelStorage.findWalletSummary(dataSource, cmd.walletId());
            if (summaryOpt.isEmpty()) {
                releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                return new CoordinatorReply.Failure("Wallet not found: " + cmd.walletId());
            }
            NetworkType networkType = summaryOpt.get().networkType();

            Map<String, Integer> addressToIndex = queryAddressIndices(sharding, cmd.walletId(), ctx);

            WalletSigningActor.SigningReply signingReply =
                    org.apache.pekko.actor.typed.javadsl.AskPattern.<WalletSigningActor.SigningCommand, WalletSigningActor.SigningReply>ask(
                            signingActor,
                            replyTo -> new WalletSigningActor.PrepareSigner(
                                    cmd.walletId(), available, addressToIndex, networkType,
                                    buildScriptResolver(pluginRegistry, networkType), replyTo),
                            SIGNING_TIMEOUT, ctx.getSystem().scheduler()
                    ).toCompletableFuture().get(SIGNING_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

            if (signingReply instanceof WalletSigningActor.SigningFailure failure) {
                releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                return new CoordinatorReply.Failure(failure.reason());
            }

            WalletSigningActor.SignerReady ready = (WalletSigningActor.SignerReady) signingReply;

            TransactionLookup transactionLookup = txid -> {
                try {
                    return readModelStorage.findRawHexByTxid(dataSource, txid).orElse(null);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to look up transaction: " + txid, e);
                    return null;
                }
            };

            PluginTransactionRequest request = new PluginTransactionRequest(
                    available, ready.signer(), transactionLookup,
                    ready.publicKeyHexes(), cmd.changeAddress(), cmd.pluginParams());

            LOG.info("Invoking plugin " + cmd.pluginId() + " provisionFunding");
            List<ProvisionedTransaction> transactions = plugin.provisionFunding(request);
            LOG.info("Provisioning returned " + transactions.size() + " transactions");

            for (var ptx : transactions) {
                try {
                    ArcSubmitResponse arcResponse = arcService.submitTransaction(ptx.rawHex());
                    LOG.info("Broadcast " + ptx.role() + " " + ptx.txid()
                            + (ptx.purpose() != null ? " (" + ptx.purpose() + ")" : "")
                            + " — ARC status: " + arcResponse.status());

                    if (arcResponse.status() == ArcTransactionStatus.SEEN_IN_ORPHAN_MEMPOOL) {
                        throw new ArcServiceException(
                                "TX placed in orphan mempool — parent TX may be missing",
                                200, "txStatus: SEEN_IN_ORPHAN_MEMPOOL");
                    }
                } catch (ArcServiceException e) {
                    LOG.log(Level.WARNING, "Broadcast failed for " + ptx.role() + " " + ptx.txid(), e);
                    if (!transactions.isEmpty()) {
                        List<String> spentKeys = identifySpentInputs(
                                transactions.get(0).rawHex(), reserved);
                        finalizeUtxos(readModelStorage, dataSource, cmd.walletId(),
                                reserved, spentKeys);
                    }
                    return new CoordinatorReply.Failure(
                            "Broadcast failed for " + ptx.role() + " tx " + ptx.txid() + ": " + e.getMessage());
                }
            }

            List<String> spentKeys = transactions.isEmpty()
                    ? List.of()
                    : identifySpentInputs(transactions.get(0).rawHex(), reserved);
            finalizeUtxos(readModelStorage, dataSource, cmd.walletId(), reserved, spentKeys);

            // Mark intermediate UTXOs as spent: split TX outputs consumed by earmark TXs
            if (transactions.size() > 1) {
                String splitTxid = transactions.get(0).txid();
                for (int i = 1; i < transactions.size(); i++) {
                    var earmark = transactions.get(i);
                    Transaction earmarkTx = Transaction.fromHex(earmark.rawHex());
                    for (var input : earmarkTx.getInputs()) {
                        String prevTxid = Utils.HEX.encode(input.getPrevTxnId());
                        int prevVout = (int) input.getPrevTxnOutputIndex();
                        if (prevTxid.equals(splitTxid)) {
                            markSingleUtxoSpent(readModelStorage, dataSource,
                                    cmd.walletId(), prevTxid + ":" + prevVout);
                        }
                    }
                }
            }

            return new CoordinatorReply.PluginProvisioningBuilt(transactions);

        } catch (Exception e) {
            releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
            return new CoordinatorReply.Failure("Plugin provisioning failed: " + e.getMessage());
        }
    }

    /**
     * Build a standard payment (non-plugin). Called by the {@link WalletCoordinator}
     * when it receives a {@link CoordinatorCommand.BuildPayment}.
     */
    public static void buildPayment(
            ActorContext<CoordinatorCommand> ctx,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            TransactionBuildService transactionBuildService,
            ActorRef<WalletSigningActor.SigningCommand> signingActor,
            org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding sharding,
            CoordinatorCommand.BuildPayment cmd) {

        try {
            // 1. Select available UTXOs
            List<BitcoinUtxo> available = readModelStorage.findUtxosByStatus(
                    dataSource, cmd.walletId(), UtxoStatus.AVAILABLE);
            if (available.isEmpty()) {
                cmd.replyTo().tell(new CoordinatorReply.Failure("No available UTXOs"));
                return;
            }

            // 2. Look up wallet
            Optional<WalletSummary> summaryOpt = readModelStorage.findWalletSummary(dataSource, cmd.walletId());
            if (summaryOpt.isEmpty()) {
                cmd.replyTo().tell(new CoordinatorReply.Failure("Wallet not found: " + cmd.walletId()));
                return;
            }
            NetworkType networkType = summaryOpt.get().networkType();

            // 3. Look up address -> derivation index map from aggregate
            Map<String, Integer> addressToIndex = queryAddressIndices(sharding, cmd.walletId(), ctx);

            // 4. Ask signing actor for signer + public keys
            // Standard payments only spend P2PKH — no plugin script resolver needed.
            WalletSigningActor.SigningReply signingReply =
                    org.apache.pekko.actor.typed.javadsl.AskPattern.<WalletSigningActor.SigningCommand, WalletSigningActor.SigningReply>ask(
                            signingActor,
                            replyTo -> new WalletSigningActor.PrepareSigner(
                                    cmd.walletId(), available, addressToIndex, networkType,
                                    null, replyTo),
                            SIGNING_TIMEOUT, ctx.getSystem().scheduler()
                    ).toCompletableFuture().get(SIGNING_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

            if (signingReply instanceof WalletSigningActor.SigningFailure failure) {
                cmd.replyTo().tell(new CoordinatorReply.Failure(failure.reason()));
                return;
            }

            WalletSigningActor.SignerReady ready = (WalletSigningActor.SignerReady) signingReply;

            // 5. Build transaction using the first public key's ECKey
            org.twostack.bitcoin4j.ECKey signingKey = org.twostack.bitcoin4j.ECKey.fromPublicOnly(
                    Utils.HEX.decode(ready.publicKeyHexes().get(0)));

            TransactionBuildResult result = transactionBuildService.buildTransaction(
                    available, cmd.outputs(), cmd.config(), cmd.changeAddress(),
                    signingKey, networkType);

            cmd.replyTo().tell(new CoordinatorReply.PaymentBuilt(result));

        } catch (Exception e) {
            cmd.replyTo().tell(new CoordinatorReply.Failure("Payment build failed: " + e.getMessage()));
        }
    }

    // ── UTXO reservation helpers ──

    private static void reserveUtxos(WalletReadModelStorage storage, DataSource ds,
                                      String walletId, List<BitcoinUtxo> utxos,
                                      String reservingTxId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(RESERVATION_TTL);
        try (Connection conn = ds.getConnection()) {
            boolean wasAutoCommit = conn.getAutoCommit();
            if (wasAutoCommit) conn.setAutoCommit(false);
            for (BitcoinUtxo utxo : utxos) {
                storage.updateUtxoReserved(conn, walletId, utxo.key(),
                        reservingTxId, expiresAt, now);
            }
            conn.commit();
            if (wasAutoCommit) conn.setAutoCommit(true);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to reserve UTXOs for wallet " + walletId, e);
        }
    }

    private static void releaseUtxos(WalletReadModelStorage storage, DataSource ds,
                                      String walletId, List<BitcoinUtxo> utxos) {
        if (utxos.isEmpty()) return;
        Instant now = Instant.now();
        try (Connection conn = ds.getConnection()) {
            boolean wasAutoCommit = conn.getAutoCommit();
            if (wasAutoCommit) conn.setAutoCommit(false);
            for (BitcoinUtxo utxo : utxos) {
                storage.updateUtxoStatus(conn, walletId, utxo.key(), UtxoStatus.AVAILABLE, now);
            }
            conn.commit();
            if (wasAutoCommit) conn.setAutoCommit(true);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to release UTXO reservations for wallet " + walletId, e);
        }
    }

    /**
     * Identify which wallet UTXOs were consumed as inputs in a built transaction.
     */
    private static List<String> identifySpentInputs(String rawHex, List<BitcoinUtxo> walletUtxos) {
        List<String> spentKeys = new ArrayList<>();
        if (rawHex == null || rawHex.isBlank()) return spentKeys;
        try {
            Transaction tx = Transaction.fromHex(rawHex);
            Set<String> walletKeys = new HashSet<>();
            for (BitcoinUtxo utxo : walletUtxos) {
                walletKeys.add(utxo.key());
            }
            for (var input : tx.getInputs()) {
                String prevTxid = Utils.HEX.encode(input.getPrevTxnId());
                int prevVout = (int) input.getPrevTxnOutputIndex();
                String key = prevTxid + ":" + prevVout;
                if (walletKeys.contains(key)) {
                    spentKeys.add(key);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to identify spent inputs", e);
        }
        return spentKeys;
    }

    /**
     * After broadcast: mark consumed UTXOs as SPENT, release unconsumed ones
     * back to AVAILABLE.
     */
    private static void finalizeUtxos(WalletReadModelStorage storage, DataSource ds,
                                       String walletId, List<BitcoinUtxo> reserved,
                                       List<String> spentKeys) {
        if (reserved.isEmpty()) return;
        Set<String> spentSet = new HashSet<>(spentKeys);
        Instant now = Instant.now();
        try (Connection conn = ds.getConnection()) {
            boolean wasAutoCommit = conn.getAutoCommit();
            if (wasAutoCommit) conn.setAutoCommit(false);
            for (BitcoinUtxo utxo : reserved) {
                if (spentSet.contains(utxo.key())) {
                    storage.updateUtxoStatus(conn, walletId, utxo.key(), UtxoStatus.SPENT, now);
                } else {
                    storage.updateUtxoStatus(conn, walletId, utxo.key(), UtxoStatus.AVAILABLE, now);
                }
            }
            conn.commit();
            if (wasAutoCommit) conn.setAutoCommit(true);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to finalize UTXOs for wallet " + walletId, e);
        }
    }

    private static void markSingleUtxoSpent(WalletReadModelStorage storage, DataSource ds,
                                             String walletId, String utxoKey) {
        try (Connection conn = ds.getConnection()) {
            storage.updateUtxoStatus(conn, walletId, utxoKey, UtxoStatus.SPENT, Instant.now());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to mark UTXO spent: " + utxoKey, e);
        }
    }

    // ── Auto-record helpers ──

    /**
     * Parse a raw transaction and auto-record any outputs owned by this wallet.
     * Each matched output becomes a RecordUtxo self-tell to the coordinator.
     */
    static void autoRecordOutputUtxos(
            ActorContext<CoordinatorCommand> ctx,
            PluginRegistry pluginRegistry,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            String walletId, String txid, String rawHex,
            Map<String, Integer> addressToIndex,
            String changeAddress) {

        if (rawHex == null || rawHex.isBlank()) return;

        try {
            Transaction tx = Transaction.fromHex(rawHex);
            List<TransactionOutput> outputs = tx.getOutputs();

            Optional<WalletSummary> summaryOpt = readModelStorage.findWalletSummary(dataSource, walletId);
            if (summaryOpt.isEmpty()) return;
            NetworkAddressType addrType = toNetworkAddressType(summaryOpt.get().networkType());

            Set<String> walletAddresses = new HashSet<>(
                    readModelStorage.findAddressesByWalletId(dataSource, walletId));
            if (changeAddress != null) walletAddresses.add(changeAddress);

            for (int vout = 0; vout < outputs.size(); vout++) {
                TransactionOutput output = outputs.get(vout);
                Script script = output.getScript();
                byte[] scriptBytes = script.getProgram();
                String scriptHex = Utils.HEX.encode(scriptBytes);

                String address = deriveStandardAddress(script, addrType);

                if (address != null && walletAddresses.contains(address)) {
                    Integer derivIdx = addressToIndex != null ? addressToIndex.get(address) : null;
                    recordUtxo(ctx, walletId, txid, vout, output, scriptHex, address, derivIdx);
                    continue;
                }

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
                                Integer derivIdx = addressToIndex != null ? addressToIndex.get(ownerAddress) : null;
                                recordUtxo(ctx, walletId, txid, vout, output, scriptHex, ownerAddress, derivIdx);
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
            TransactionOutput output, String scriptHex, String address,
            Integer derivationIndex) {

        Instant now = Instant.now();
        BitcoinUtxo utxo = new BitcoinUtxo(
                txid, vout, output.getAmount().longValue(), scriptHex,
                address, UtxoStatus.AVAILABLE,
                null, null, now, now,
                null, null, null, null,
                derivationIndex,
                null, null
        );
        ctx.getSelf().tell(new CoordinatorCommand.RecordUtxo(
                walletId, utxo, ctx.getSystem().ignoreRef()));
    }

    /**
     * Earmark-aware variant: tags the output at {@code fundingVout} with earmark metadata.
     * All other outputs are recorded as plain wallet UTXOs.
     */
    static void autoRecordOutputUtxos(
            ActorContext<CoordinatorCommand> ctx,
            PluginRegistry pluginRegistry,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            String walletId, String txid, String rawHex,
            Map<String, Integer> addressToIndex,
            String changeAddress,
            String earmarkPurpose, int fundingVout) {

        if (rawHex == null || rawHex.isBlank()) return;

        try {
            Transaction tx = Transaction.fromHex(rawHex);
            List<TransactionOutput> outputs = tx.getOutputs();

            Optional<WalletSummary> summaryOpt = readModelStorage.findWalletSummary(dataSource, walletId);
            if (summaryOpt.isEmpty()) return;
            NetworkAddressType addrType = toNetworkAddressType(summaryOpt.get().networkType());

            Set<String> walletAddresses = new HashSet<>(
                    readModelStorage.findAddressesByWalletId(dataSource, walletId));
            if (changeAddress != null) walletAddresses.add(changeAddress);

            for (int vout = 0; vout < outputs.size(); vout++) {
                TransactionOutput output = outputs.get(vout);
                Script script = output.getScript();
                byte[] scriptBytes = script.getProgram();
                String scriptHex = Utils.HEX.encode(scriptBytes);

                String address = deriveStandardAddress(script, addrType);

                if (address != null && walletAddresses.contains(address)) {
                    Integer derivIdx = addressToIndex != null ? addressToIndex.get(address) : null;
                    String purpose = (vout == fundingVout) ? earmarkPurpose : null;
                    recordUtxo(ctx, walletId, txid, vout, output, scriptHex, address, derivIdx, purpose);
                    continue;
                }

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
                                Integer derivIdx = addressToIndex != null ? addressToIndex.get(ownerAddress) : null;
                                recordUtxo(ctx, walletId, txid, vout, output, scriptHex, ownerAddress, derivIdx, null);
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
            TransactionOutput output, String scriptHex, String address,
            Integer derivationIndex, String earmarkPurpose) {

        String pluginId = null;
        Map<String, Object> pluginMetadata = null;
        if (earmarkPurpose != null) {
            pluginId = FUNDING_EARMARK_PLUGIN_ID;
            pluginMetadata = Map.of("purpose", earmarkPurpose);
        }

        Instant now = Instant.now();
        BitcoinUtxo utxo = new BitcoinUtxo(
                txid, vout, output.getAmount().longValue(), scriptHex,
                address, UtxoStatus.AVAILABLE,
                null, null, now, now,
                null, null, null, null,
                derivationIndex,
                pluginId, pluginMetadata
        );
        ctx.getSelf().tell(new CoordinatorCommand.RecordUtxo(
                walletId, utxo, ctx.getSystem().ignoreRef()));
    }

    private static WalletSigningActor.ScriptAddressResolver buildScriptResolver(
            PluginRegistry pluginRegistry, NetworkType networkType) {
        NetworkAddressType addrType = toNetworkAddressType(networkType);
        return scriptBytes -> {
            Script script = new Script(scriptBytes);
            String addr = deriveStandardAddress(script, addrType);
            if (addr != null) return addr;
            if (pluginRegistry.hasPlugins()) {
                Optional<PluginRegistry.PluginIdentification> id = pluginRegistry.identifyScript(scriptBytes);
                if (id.isPresent()) {
                    ScriptPlugin plugin = pluginRegistry.getPlugin(id.get().pluginId()).orElse(null);
                    if (plugin != null) {
                        Map<String, Object> meta = plugin.extractMetadata(scriptBytes);
                        if (meta != null) return (String) meta.get("ownerAddress");
                    }
                }
            }
            return null;
        };
    }

    private static Map<String, Integer> queryAddressIndices(
            org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding sharding,
            String walletId, ActorContext<CoordinatorCommand> ctx) {
        try {
            var walletRef = sharding.entityRefFor(
                    org.twostack.libspiffy4j.aggregate.wallet.WalletAggregate.ENTITY_TYPE_KEY, walletId);
            WalletReply reply = org.apache.pekko.actor.typed.javadsl.AskPattern
                    .<WalletCommand, WalletReply>ask(
                            walletRef,
                            replyTo -> new WalletCommand.QueryAddressIndicesCommand(walletId, replyTo),
                            SIGNING_TIMEOUT, ctx.getSystem().scheduler()
                    ).toCompletableFuture().get(SIGNING_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (reply instanceof WalletReply.AddressIndices ai) {
                return ai.addressToIndex();
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to query address indices from aggregate for wallet " + walletId, e);
        }
        return Map.of();
    }

    static String deriveStandardAddress(Script script, NetworkAddressType addrType) {
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

    // ── Auto-provisioning ──

    /**
     * Check post-operation UTXO inventory against policy and trigger auto-provisioning
     * if inventory is below the low threshold. Fire-and-forget — does not block the
     * current operation's reply.
     */
    static void checkInventoryAndAutoProvision(
            ActorContext<CoordinatorCommand> ctx,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            String walletId, String pluginId, String changeAddress) {

        try {
            Optional<WalletSummary> summaryOpt = readModelStorage.findWalletSummary(dataSource, walletId);
            if (summaryOpt.isEmpty()) return;

            WalletSummary summary = summaryOpt.get();
            UtxoPolicy policy = summary.utxoPolicy();
            if (!policy.autoProvisionEnabled()) return;

            UtxoInventory inventory = readModelStorage.getUtxoInventory(dataSource, walletId, policy);
            LOG.info("Post-operation inventory for wallet " + walletId
                    + ": available=" + inventory.availableCount()
                    + " lifecycleSteps=" + inventory.lifecycleSteps()
                    + " (iw=" + inventory.issuanceWitnessCount()
                    + " xfer=" + inventory.transferCount()
                    + " xw=" + inventory.transferWitnessCount() + ")"
                    + " status=" + inventory.policyStatus());

            if (inventory.policyStatus() == UtxoInventory.PolicyStatus.SUFFICIENT) return;

            // Check concurrency guard — skip if another provisioning is in flight
            var guard = provisioningInProgress.computeIfAbsent(walletId,
                    k -> new java.util.concurrent.atomic.AtomicBoolean(false));
            if (!guard.compareAndSet(false, true)) {
                LOG.info("Skipping auto-provision for wallet " + walletId + " — already in progress");
                return;
            }

            // Check if balance is sufficient for provisioning
            int stepsNeeded = policy.targetLifecycleSteps() - inventory.lifecycleSteps();
            if (stepsNeeded <= 0) {
                guard.set(false);
                return;
            }

            long satsNeeded = stepsNeeded * SATS_PER_LIFECYCLE_STEP;
            long availableSats = inventory.availableSats();

            if (availableSats < satsNeeded) {
                guard.set(false);
                LOG.warning("Wallet " + walletId + " needs funding: available="
                        + availableSats + " sats, need=" + satsNeeded + " sats for " + stepsNeeded + " steps");
                // Emit WalletFundingNeeded via a self-tell to the coordinator
                // The coordinator ignores the reply; the host app can listen for this event type
                // by querying inventory status.
                return;
            }

            // Fire-and-forget: self-tell a BuildPluginProvisioning command
            LOG.info("Auto-provisioning " + stepsNeeded + " lifecycle steps for wallet " + walletId);

            Map<String, Object> provisionParams = new HashMap<>();
            provisionParams.put("lifecycleSteps", stepsNeeded);

            ctx.getSelf().tell(new CoordinatorCommand.BuildPluginProvisioning(
                    walletId, pluginId, provisionParams, changeAddress,
                    ctx.spawnAnonymous(org.apache.pekko.actor.typed.javadsl.Behaviors.receiveMessage(reply -> {
                        guard.set(false);
                        if (reply instanceof CoordinatorReply.Failure failure) {
                            LOG.warning("Auto-provisioning failed for wallet " + walletId + ": " + failure.reason());
                        } else {
                            LOG.info("Auto-provisioning completed for wallet " + walletId);
                        }
                        return org.apache.pekko.actor.typed.javadsl.Behaviors.stopped();
                    }))));

        } catch (Exception e) {
            LOG.log(Level.WARNING, "Post-operation inventory check failed for wallet " + walletId, e);
            provisioningInProgress.computeIfPresent(walletId, (k, v) -> { v.set(false); return v; });
        }
    }

    /**
     * Construct a BEEF envelope for a built transaction.
     *
     * <p>Walks the ancestor chain: for each parent TX, if a merkle proof is
     * available it's added as proven. If not (e.g., the parent was never broadcast),
     * it's added as unproven and its own parents are recursively included until
     * proven roots are found. This handles the case where a token TX references
     * an unbroadcast enrollment TX whose parent (the issuance TX) is mined.
     *
     * @return hex-encoded BEEF, or null if construction fails
     */
    static String buildBeefEnvelope(String tipRawHex,
                                             WalletReadModelStorage readModelStorage,
                                             DataSource dataSource,
                                             ArcService arcService) {
        try {
            byte[] tipRawBytes = Utils.HEX.decode(tipRawHex);
            BeefBuilder builder = new BeefBuilder();
            Set<String> visited = new HashSet<>();

            // Recursively add ancestors
            Transaction tipTx = Transaction.fromHex(tipRawHex);
            Set<String> tipInputTxIds = extractInputTxIds(tipTx);
            for (String inputTxId : tipInputTxIds) {
                if (!addAncestor(inputTxId, builder, readModelStorage, dataSource, arcService, visited)) {
                    return null;
                }
            }

            builder.addUnprovenTransaction(tipRawBytes);

            byte[] beefBytes = builder.build().serialize();
            LOG.info("BEEF constructed: " + beefBytes.length + " bytes, "
                    + visited.size() + " ancestors (" + tipInputTxIds.size() + " direct)");
            return Utils.HEX.encode(beefBytes);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "BEEF construction failed", e);
            return null;
        }
    }

    /**
     * Add an ancestor TX to the BEEF. If it has a merkle proof, add as proven.
     * If not, add as unproven and recurse to its own parents.
     */
    private static boolean addAncestor(String txid, BeefBuilder builder,
                                        WalletReadModelStorage readModelStorage,
                                        DataSource dataSource, ArcService arcService,
                                        Set<String> visited) {
        if (!visited.add(txid)) return true; // already processed

        Optional<String> rawHexOpt;
        try {
            rawHexOpt = readModelStorage.findRawHexByTxid(dataSource, txid);
        } catch (Exception e) {
            LOG.warning("BEEF: failed to look up ancestor TX " + txid + ": " + e.getMessage());
            return false;
        }

        if (rawHexOpt.isEmpty()) {
            LOG.warning("BEEF: ancestor TX " + txid + " not found in wallet storage");
            return false;
        }

        byte[] rawBytes = Utils.HEX.decode(rawHexOpt.get());

        // Try to get merkle proof (cached, then ARC)
        Bump bump = null;
        try {
            Optional<String> cachedProof = readModelStorage.findMerkleProofByTxid(dataSource, txid);
            if (cachedProof.isPresent()) {
                bump = Bump.parse(Utils.HEX.decode(cachedProof.get()));
            }
        } catch (Exception e) {
            LOG.fine("Cached proof lookup failed for " + txid + ": " + e.getMessage());
        }

        if (bump == null) {
            try {
                MerkleProofData proofData = arcService.getMerkleProof(txid);
                bump = proofData.bump();
            } catch (Exception e) {
                // No proof available — this ancestor is unproven (e.g., never broadcast)
                LOG.fine("No merkle proof for ancestor " + txid + " — adding as unproven");
            }
        }

        if (bump != null) {
            builder.addProvenTransaction(rawBytes, bump);
        } else {
            // Unproven ancestor: add it and recurse to its parents
            builder.addUnprovenTransaction(rawBytes);
            Transaction ancestorTx = Transaction.fromHex(rawHexOpt.get());
            for (String parentTxId : extractInputTxIds(ancestorTx)) {
                if (!addAncestor(parentTxId, builder, readModelStorage, dataSource, arcService, visited)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Set<String> extractInputTxIds(Transaction tx) {
        Set<String> txIds = new LinkedHashSet<>();
        for (var input : tx.getInputs()) {
            txIds.add(Utils.HEX.encode(Utils.reverseBytes(input.getPrevTxnId())));
        }
        return txIds;
    }
}
