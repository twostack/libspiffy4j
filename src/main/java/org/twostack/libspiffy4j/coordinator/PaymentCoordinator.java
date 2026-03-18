package org.twostack.libspiffy4j.coordinator;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.twostack.bitcoin4j.Utils;
import org.twostack.bitcoin4j.address.LegacyAddress;
import org.twostack.bitcoin4j.params.NetworkAddressType;
import org.twostack.bitcoin4j.script.Script;
import org.twostack.bitcoin4j.script.ScriptPattern;
import org.twostack.bitcoin4j.transaction.Transaction;
import org.twostack.bitcoin4j.transaction.TransactionOutput;
import org.twostack.libspiffy4j.model.*;
import org.twostack.libspiffy4j.plugin.*;
import org.twostack.libspiffy4j.service.TransactionBuildService;
import org.twostack.libspiffy4j.storage.postgres.WalletReadModelStorage;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles payment transaction building: UTXO selection, derivation index
 * resolution, plugin invocation, and auto-recording of output UTXOs.
 *
 * <p>Delegates signing to {@link WalletSigningActor} — never touches
 * private keys directly.
 */
public final class PaymentCoordinator {

    private static final Logger LOG = Logger.getLogger(PaymentCoordinator.class.getName());
    private static final Duration SIGNING_TIMEOUT = Duration.ofSeconds(10);

    private PaymentCoordinator() {}

    /**
     * Build a plugin payment. Called by the {@link WalletCoordinator} when it
     * receives a {@link CoordinatorCommand.BuildPluginPayment}.
     *
     * <p>This method is synchronous within the coordinator's message processing.
     * The signing actor ask is blocking (via CompletableFuture.get) because the
     * plugin's {@code buildTransaction} call requires the signer synchronously.
     */
    public static void buildPluginPayment(
            ActorContext<CoordinatorCommand> ctx,
            PluginRegistry pluginRegistry,
            WalletReadModelStorage readModelStorage,
            DataSource dataSource,
            TransactionBuildService transactionBuildService,
            ActorRef<WalletSigningActor.SigningCommand> signingActor,
            CoordinatorCommand.BuildPluginPayment cmd) {

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

            // 4. Look up wallet network type
            Optional<WalletSummary> summaryOpt = readModelStorage.findWalletSummary(dataSource, cmd.walletId());
            if (summaryOpt.isEmpty()) {
                cmd.replyTo().tell(new CoordinatorReply.Failure("Wallet not found: " + cmd.walletId()));
                return;
            }
            NetworkType networkType = summaryOpt.get().networkType();

            // 5. Look up address → derivation index map
            Map<String, Integer> addressToIndex = readModelStorage.findAddressIndexMap(dataSource, cmd.walletId());

            // 6. Ask signing actor for signer + public keys
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

            // 7. Create TransactionLookup
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
                    available, ready.signer(), transactionLookup,
                    ready.publicKeyHexes(), cmd.changeAddress(), cmd.pluginParams());

            // 9. Plugin builds the complete transaction
            TransactionBuilderResult result = plugin.buildTransaction(request);

            // 10. Validate transaction structure
            byte[] rawTx = Utils.HEX.decode(result.rawHex());
            if (!plugin.validateTransactionStructure(rawTx, cmd.action())) {
                cmd.replyTo().tell(new CoordinatorReply.Failure(
                        "Plugin transaction failed structure validation"));
                return;
            }

            // 11. Auto-record wallet-owned output UTXOs
            autoRecordOutputUtxos(ctx, pluginRegistry, readModelStorage, dataSource,
                    cmd.walletId(), result.txid(), result.rawHex(), addressToIndex);

            cmd.replyTo().tell(new CoordinatorReply.PluginPaymentBuilt(
                    result.txid(), result.rawHex(), result.feeSats()));

        } catch (Exception e) {
            cmd.replyTo().tell(new CoordinatorReply.Failure(
                    "Plugin payment failed: " + e.getMessage()));
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

            // 3. Look up address → derivation index map
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
