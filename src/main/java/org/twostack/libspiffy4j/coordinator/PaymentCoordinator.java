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
    public static void buildPluginPayment(
            ActorContext<CoordinatorCommand> ctx,
            PluginRegistry pluginRegistry,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            TransactionBuildService transactionBuildService,
            ArcService arcService,
            ActorRef<WalletSigningActor.SigningCommand> signingActor,
            CoordinatorCommand.BuildPluginPayment cmd) {

        List<BitcoinUtxo> reserved = List.of();
        try {
            // 1. Look up plugin
            Optional<TransactionBuilderPlugin> pluginOpt =
                    pluginRegistry.getTransactionBuilderPlugin(cmd.pluginId());
            if (pluginOpt.isEmpty()) {
                cmd.replyTo().tell(new CoordinatorReply.Failure("Plugin not found: " + cmd.pluginId()));
                return;
            }
            TransactionBuilderPlugin plugin = pluginOpt.get();

            // 2. Verify action
            if (!plugin.supportedActions().contains(cmd.action())) {
                cmd.replyTo().tell(new CoordinatorReply.Failure(
                        "Unsupported action '%s' for plugin '%s'".formatted(cmd.action(), cmd.pluginId())));
                return;
            }

            // 3. Select available UTXOs
            List<BitcoinUtxo> available = readModelStorage.findUtxosByStatus(
                    dataSource, cmd.walletId(), UtxoStatus.AVAILABLE);
            if (available.isEmpty()) {
                cmd.replyTo().tell(new CoordinatorReply.Failure("No available UTXOs"));
                return;
            }

            // 4. Reserve selected UTXOs
            reserved = available;
            reserveUtxos(readModelStorage, dataSource, cmd.walletId(), reserved, "pending-build");

            // 5. Look up wallet network type
            Optional<WalletSummary> summaryOpt = readModelStorage.findWalletSummary(dataSource, cmd.walletId());
            if (summaryOpt.isEmpty()) {
                releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                cmd.replyTo().tell(new CoordinatorReply.Failure("Wallet not found: " + cmd.walletId()));
                return;
            }
            NetworkType networkType = summaryOpt.get().networkType();

            // 6. Look up address -> derivation index map
            Map<String, Integer> addressToIndex = readModelStorage.findAddressIndexMap(dataSource, cmd.walletId());

            // 7. Ask signing actor for signer + public keys
            WalletSigningActor.SigningReply signingReply =
                    org.apache.pekko.actor.typed.javadsl.AskPattern.<WalletSigningActor.SigningCommand, WalletSigningActor.SigningReply>ask(
                            signingActor,
                            replyTo -> new WalletSigningActor.PrepareSigner(
                                    cmd.walletId(), available, addressToIndex, networkType, replyTo),
                            SIGNING_TIMEOUT, ctx.getSystem().scheduler()
                    ).toCompletableFuture().get(SIGNING_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

            if (signingReply instanceof WalletSigningActor.SigningFailure failure) {
                releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                cmd.replyTo().tell(new CoordinatorReply.Failure(failure.reason()));
                return;
            }

            WalletSigningActor.SignerReady ready = (WalletSigningActor.SignerReady) signingReply;
            LOG.info("Signer ready for wallet " + cmd.walletId()
                    + " — pubKeys=" + ready.publicKeyHexes());

            // 8. Create TransactionLookup
            TransactionLookup transactionLookup = txid -> {
                try {
                    return readModelStorage.findRawHexByTxid(dataSource, txid).orElse(null);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to look up transaction: " + txid, e);
                    return null;
                }
            };

            // 9. Build PluginTransactionRequest
            PluginTransactionRequest request = new PluginTransactionRequest(
                    available, ready.signer(), transactionLookup,
                    ready.publicKeyHexes(), cmd.changeAddress(), cmd.pluginParams());

            // 10. Plugin builds the complete transaction
            LOG.info("Invoking plugin " + cmd.pluginId() + " action=" + cmd.action());
            TransactionBuilderResult result = plugin.buildTransaction(request);
            LOG.info("Plugin returned txid=" + result.txid() + " rawHex length=" + result.rawHex().length());

            // 11. Validate transaction structure
            byte[] rawTx = Utils.HEX.decode(result.rawHex());
            if (!plugin.validateTransactionStructure(rawTx, cmd.action())) {
                releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                cmd.replyTo().tell(new CoordinatorReply.Failure(
                        "Plugin transaction failed structure validation"));
                return;
            }

            // 12. Broadcast via ARC
            try {
                arcService.submitTransaction(result.rawHex());
                LOG.info("Broadcast successful for tx " + result.txid());
            } catch (ArcServiceException e) {
                LOG.log(Level.WARNING, "Broadcast failed for tx " + result.txid()
                        + " — ARC status " + e.httpStatusCode() + ": " + e.responseBody(), e);
                releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                cmd.replyTo().tell(new CoordinatorReply.Failure(
                        "ARC broadcast failed: " + e.getMessage()));
                return;
            }

            // 13. Identify which reserved UTXOs were consumed as inputs
            List<String> spentKeys = identifySpentInputs(result.rawHex(), reserved);

            // 14. Mark consumed UTXOs SPENT, release the rest back to AVAILABLE
            finalizeUtxos(readModelStorage, dataSource, cmd.walletId(), reserved, spentKeys);

            // 15. Record transaction (triggers autoRecordOutputUtxos)
            recordTransaction(ctx, cmd.walletId(), result.txid(), result.rawHex());

            // 16. Auto-record output UTXOs
            autoRecordOutputUtxos(ctx, pluginRegistry, readModelStorage, dataSource,
                    cmd.walletId(), result.txid(), result.rawHex(), addressToIndex);

            cmd.replyTo().tell(new CoordinatorReply.PluginPaymentBuilt(
                    result.txid(), result.rawHex(), result.feeSats()));

        } catch (Exception e) {
            releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
            cmd.replyTo().tell(new CoordinatorReply.Failure(
                    "Plugin payment failed: " + e.getMessage()));
        }
    }

    /**
     * Build, broadcast, and record provisioning transactions.
     *
     * <p>Produces a batch of transactions (split + earmarks) and broadcasts them
     * sequentially. On partial failure, UTXOs consumed by already-broadcast TXs
     * remain SPENT; only the original wallet UTXOs are reserved/released.
     */
    public static void buildPluginProvisioning(
            ActorContext<CoordinatorCommand> ctx,
            PluginRegistry pluginRegistry,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            ArcService arcService,
            ActorRef<WalletSigningActor.SigningCommand> signingActor,
            CoordinatorCommand.BuildPluginProvisioning cmd) {

        List<BitcoinUtxo> reserved = List.of();
        try {
            Optional<TransactionBuilderPlugin> pluginOpt =
                    pluginRegistry.getTransactionBuilderPlugin(cmd.pluginId());
            if (pluginOpt.isEmpty()) {
                cmd.replyTo().tell(new CoordinatorReply.Failure("Plugin not found: " + cmd.pluginId()));
                return;
            }
            TransactionBuilderPlugin plugin = pluginOpt.get();

            List<BitcoinUtxo> available = readModelStorage.findUtxosByStatus(
                    dataSource, cmd.walletId(), UtxoStatus.AVAILABLE);
            if (available.isEmpty()) {
                cmd.replyTo().tell(new CoordinatorReply.Failure("No available UTXOs"));
                return;
            }

            // Reserve wallet UTXOs
            reserved = available;
            reserveUtxos(readModelStorage, dataSource, cmd.walletId(), reserved, "pending-provision");

            Optional<WalletSummary> summaryOpt = readModelStorage.findWalletSummary(dataSource, cmd.walletId());
            if (summaryOpt.isEmpty()) {
                releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                cmd.replyTo().tell(new CoordinatorReply.Failure("Wallet not found: " + cmd.walletId()));
                return;
            }
            NetworkType networkType = summaryOpt.get().networkType();

            Map<String, Integer> addressToIndex = readModelStorage.findAddressIndexMap(dataSource, cmd.walletId());

            WalletSigningActor.SigningReply signingReply =
                    org.apache.pekko.actor.typed.javadsl.AskPattern.<WalletSigningActor.SigningCommand, WalletSigningActor.SigningReply>ask(
                            signingActor,
                            replyTo -> new WalletSigningActor.PrepareSigner(
                                    cmd.walletId(), available, addressToIndex, networkType, replyTo),
                            SIGNING_TIMEOUT, ctx.getSystem().scheduler()
                    ).toCompletableFuture().get(SIGNING_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

            if (signingReply instanceof WalletSigningActor.SigningFailure failure) {
                releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
                cmd.replyTo().tell(new CoordinatorReply.Failure(failure.reason()));
                return;
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

            // Broadcast all TXs sequentially (split first, then earmarks)
            for (var ptx : transactions) {
                try {
                    arcService.submitTransaction(ptx.rawHex());
                    LOG.info("Broadcast " + ptx.role() + " " + ptx.txid()
                            + (ptx.purpose() != null ? " (" + ptx.purpose() + ")" : ""));
                } catch (ArcServiceException e) {
                    LOG.log(Level.WARNING, "Broadcast failed for " + ptx.role() + " " + ptx.txid(), e);
                    // On partial failure: identify which wallet UTXOs were consumed
                    // by already-broadcast TXs (the split TX). Mark those spent,
                    // release the rest.
                    if (!transactions.isEmpty()) {
                        List<String> spentKeys = identifySpentInputs(
                                transactions.get(0).rawHex(), reserved);
                        finalizeUtxos(readModelStorage, dataSource, cmd.walletId(),
                                reserved, spentKeys);
                    }
                    cmd.replyTo().tell(new CoordinatorReply.Failure(
                            "Broadcast failed for " + ptx.role() + " tx " + ptx.txid() + ": " + e.getMessage()));
                    return;
                }
            }

            // All broadcasts succeeded.
            // The split TX consumed some wallet UTXOs — mark those spent, release the rest.
            List<String> spentKeys = transactions.isEmpty()
                    ? List.of()
                    : identifySpentInputs(transactions.get(0).rawHex(), reserved);
            finalizeUtxos(readModelStorage, dataSource, cmd.walletId(), reserved, spentKeys);

            // Record all transactions and auto-record output UTXOs
            for (var ptx : transactions) {
                recordTransaction(ctx, cmd.walletId(), ptx.txid(), ptx.rawHex());
                autoRecordOutputUtxos(ctx, pluginRegistry, readModelStorage, dataSource,
                        cmd.walletId(), ptx.txid(), ptx.rawHex(), addressToIndex);
            }

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

            cmd.replyTo().tell(new CoordinatorReply.PluginProvisioningBuilt(transactions));

        } catch (Exception e) {
            releaseUtxos(readModelStorage, dataSource, cmd.walletId(), reserved);
            cmd.replyTo().tell(new CoordinatorReply.Failure(
                    "Plugin provisioning failed: " + e.getMessage()));
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

            // 3. Look up address -> derivation index map
            Map<String, Integer> addressToIndex = readModelStorage.findAddressIndexMap(dataSource, cmd.walletId());

            // 4. Ask signing actor for signer + public keys
            WalletSigningActor.SigningReply signingReply =
                    org.apache.pekko.actor.typed.javadsl.AskPattern.<WalletSigningActor.SigningCommand, WalletSigningActor.SigningReply>ask(
                            signingActor,
                            replyTo -> new WalletSigningActor.PrepareSigner(
                                    cmd.walletId(), available, addressToIndex, networkType, replyTo),
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

    // ── Transaction recording ──

    private static void recordTransaction(ActorContext<CoordinatorCommand> ctx,
                                           String walletId, String txid, String rawHex) {
        Transaction tx = Transaction.fromHex(rawHex);
        long outputValue = tx.getOutputs().stream()
                .mapToLong(o -> o.getAmount().longValue()).sum();

        BitcoinTransaction btcTx = new BitcoinTransaction(
                walletId, txid, rawHex, TransactionStatus.BROADCAST,
                TransactionDirection.OUTGOING, null, null,
                0L, outputValue, 0L, 0L,
                List.of(), List.of(),
                Instant.now(), Instant.now(), null, 0L, 1);

        ctx.getSelf().tell(new CoordinatorCommand.RecordTransaction(
                walletId, btcTx, ctx.getSystem().ignoreRef()));
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
            Map<String, Integer> addressToIndex) {

        if (rawHex == null || rawHex.isBlank()) return;

        try {
            Transaction tx = Transaction.fromHex(rawHex);
            List<TransactionOutput> outputs = tx.getOutputs();

            Optional<WalletSummary> summaryOpt = readModelStorage.findWalletSummary(dataSource, walletId);
            if (summaryOpt.isEmpty()) return;
            NetworkAddressType addrType = toNetworkAddressType(summaryOpt.get().networkType());

            Set<String> walletAddresses = new HashSet<>(
                    readModelStorage.findAddressesByWalletId(dataSource, walletId));

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
}
