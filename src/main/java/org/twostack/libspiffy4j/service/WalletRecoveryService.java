package org.twostack.libspiffy4j.service;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.twostack.bitcoin4j.address.LegacyAddress;
import org.twostack.bitcoin4j.crypto.DeterministicKey;
import org.twostack.bitcoin4j.params.NetworkAddressType;
import org.twostack.bitcoin4j.transaction.P2PKHLockBuilder;
import org.twostack.libspiffy4j.aggregate.wallet.WalletAggregate;
import org.twostack.libspiffy4j.aggregate.wallet.WalletCommand;
import org.twostack.libspiffy4j.aggregate.wallet.WalletReply;
import org.twostack.libspiffy4j.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Stateless orchestration service that discovers addresses, imports transactions,
 * extracts UTXOs, and populates a wallet aggregate for recovery from an XPRIV key.
 */
public final class WalletRecoveryService {

    /**
     * Functional interface for address discovery, allowing test substitution.
     */
    @FunctionalInterface
    public interface DiscoveryFunction {
        AddressDiscoveryResult discover(DeterministicKey hdKey, NetworkType networkType,
                                         int gapLimit, Consumer<DiscoveredAddress> onProgress);
    }

    /**
     * Functional interface for batch transaction import, allowing test substitution.
     */
    @FunctionalInterface
    public interface ImportFunction {
        List<ImportedTransaction> importBatch(List<String> txids);
    }

    /**
     * Functional interface for generating the root address from an HD key.
     */
    @FunctionalInterface
    public interface AddressGenerator {
        String generateAddress(DeterministicKey key, NetworkType networkType);
    }

    private final ClusterSharding sharding;
    private final AddressGenerator addressGenerator;
    private final DiscoveryFunction discoveryFunction;
    private final ImportFunction importFunction;
    private final Duration askTimeout;
    private final BiFunction<ImportedTransaction, Set<String>, List<BitcoinUtxo>> utxoExtractor;

    /**
     * Production constructor using CryptoService, AddressDiscoveryService, and TransactionImportService.
     */
    public WalletRecoveryService(
            ClusterSharding sharding,
            CryptoService cryptoService,
            AddressDiscoveryService discoveryService,
            TransactionImportService importService,
            Duration askTimeout) {
        this(sharding,
                cryptoService::generateAddress,
                discoveryService::discoverAddresses,
                importService::importTransactionBatch,
                askTimeout, null);
    }

    /**
     * Flexible constructor accepting functional interfaces (useful for testing).
     */
    public WalletRecoveryService(
            ClusterSharding sharding,
            AddressGenerator addressGenerator,
            DiscoveryFunction discoveryFunction,
            ImportFunction importFunction,
            Duration askTimeout,
            BiFunction<ImportedTransaction, Set<String>, List<BitcoinUtxo>> utxoExtractor) {
        this.sharding = sharding;
        this.addressGenerator = addressGenerator;
        this.discoveryFunction = discoveryFunction;
        this.importFunction = importFunction;
        this.askTimeout = askTimeout;
        this.utxoExtractor = utxoExtractor != null ? utxoExtractor : WalletRecoveryService::defaultExtractUtxos;
    }

    /**
     * Runs a full wallet recovery from an HD key.
     */
    public CompletionStage<WalletRecoveryResult> recoverWallet(
            String walletId, String name,
            DeterministicKey hdKey, NetworkType networkType,
            int gapLimit, Consumer<String> progressCallback) {

        EntityRef<WalletCommand> walletRef = sharding.entityRefFor(
                WalletAggregate.ENTITY_TYPE_KEY, walletId);

        String rootAddress = addressGenerator.generateAddress(hdKey, networkType);

        // 1. Create wallet
        return askWallet(walletRef, replyTo -> new WalletCommand.CreateWalletCommand(
                walletId, name, WalletType.XPRIV, networkType,
                rootAddress, Map.of(), replyTo))
            .thenCompose(createReply -> {
                notifyProgress(progressCallback, "Wallet created: " + walletId);

                // 2. Discover addresses
                AddressDiscoveryResult discovery = discoveryFunction.discover(
                        hdKey, networkType, gapLimit,
                        da -> notifyProgress(progressCallback,
                                "Found address: " + da.address() + " (" + da.transactionIds().size() + " txs)"));

                List<DiscoveredAddress> allAddresses = new ArrayList<>();
                allAddresses.addAll(discovery.receivingAddresses());
                allAddresses.addAll(discovery.changeAddresses());

                notifyProgress(progressCallback,
                        "Discovered " + allAddresses.size() + " addresses");

                // 3. Record addresses in wallet
                CompletionStage<Void> addressStage = recordAddresses(walletRef, walletId, allAddresses);

                return addressStage.thenCompose(v -> {
                    // 4. De-duplicate txids
                    Set<String> allTxids = new LinkedHashSet<>();
                    for (DiscoveredAddress da : allAddresses) {
                        allTxids.addAll(da.transactionIds());
                    }

                    if (allTxids.isEmpty()) {
                        return CompletableFuture.completedFuture(
                                new WalletRecoveryResult(walletId, allAddresses.size(), 0, 0, 0L));
                    }

                    notifyProgress(progressCallback,
                            "Importing " + allTxids.size() + " transactions");

                    // 5. Import transactions
                    List<ImportedTransaction> imported = importFunction.importBatch(
                            new ArrayList<>(allTxids));

                    notifyProgress(progressCallback,
                            "Imported " + imported.size() + " transactions");

                    // 6. Record transactions in wallet
                    CompletionStage<Void> txStage = recordTransactions(
                            walletRef, walletId, imported);

                    return txStage.thenCompose(v2 -> {
                        // 7. Extract and record UTXOs
                        Set<String> addressSet = new HashSet<>();
                        for (DiscoveredAddress da : allAddresses) {
                            addressSet.add(da.address());
                        }

                        return extractAndRecordUtxos(
                                walletRef, walletId, imported, addressSet, progressCallback)
                            .thenApply(utxoResult -> new WalletRecoveryResult(
                                    walletId,
                                    allAddresses.size(),
                                    imported.size(),
                                    utxoResult.count(),
                                    utxoResult.totalSats()));
                    });
                });
            });
    }

    private CompletionStage<Void> recordAddresses(
            EntityRef<WalletCommand> walletRef, String walletId,
            List<DiscoveredAddress> addresses) {
        CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
        for (DiscoveredAddress da : addresses) {
            AddressMetadata metadata = new AddressMetadata(
                    da.address(), BitcoinScriptType.P2PKH,
                    "m/44'/236'/0'/" + (da.isChange() ? "1" : "0") + "/" + da.derivationIndex(),
                    da.derivationIndex(), da.isChange(),
                    null, null, null, null,
                    da.transactionIds().size(), 0L, Instant.now(), false);
            stage = stage.thenCompose(v -> askWallet(walletRef,
                    replyTo -> new WalletCommand.RecordAddressCommand(walletId, metadata, replyTo))
                    .thenApply(r -> null));
        }
        return stage;
    }

    private CompletionStage<Void> recordTransactions(
            EntityRef<WalletCommand> walletRef, String walletId,
            List<ImportedTransaction> imported) {
        CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
        for (ImportedTransaction tx : imported) {
            BitcoinTransaction btx = new BitcoinTransaction(
                    walletId, tx.txid(), tx.rawHex(),
                    tx.spvValid() ? TransactionStatus.CONFIRMED : TransactionStatus.PENDING,
                    TransactionDirection.UNKNOWN,
                    tx.spvValid() ? (int) tx.blockHeight() : null,
                    0, 0L, 0L, 0L, 0L,
                    List.of(), List.of(),
                    Instant.now(), Instant.now(), null, 0, 2);
            stage = stage.thenCompose(v -> askWallet(walletRef,
                    replyTo -> new WalletCommand.RecordTransactionCommand(walletId, btx, replyTo))
                    .thenApply(r -> null));
        }
        return stage;
    }

    private record UtxoResult(int count, long totalSats) {}

    private CompletionStage<UtxoResult> extractAndRecordUtxos(
            EntityRef<WalletCommand> walletRef, String walletId,
            List<ImportedTransaction> imported, Set<String> addresses,
            Consumer<String> progressCallback) {

        List<BitcoinUtxo> utxos = new ArrayList<>();
        for (ImportedTransaction itx : imported) {
            utxos.addAll(utxoExtractor.apply(itx, addresses));
        }

        notifyProgress(progressCallback, "Found " + utxos.size() + " UTXOs");

        CompletionStage<UtxoResult> stage = CompletableFuture.completedFuture(
                new UtxoResult(0, 0L));
        for (BitcoinUtxo utxo : utxos) {
            stage = stage.thenCompose(prev ->
                    askWallet(walletRef, replyTo ->
                            new WalletCommand.RecordUtxoCommand(walletId, utxo, replyTo))
                    .thenApply(reply -> new UtxoResult(
                            prev.count() + 1,
                            prev.totalSats() + utxo.valueSats())));
        }
        return stage;
    }

    /**
     * Default UTXO extractor that parses raw transactions via bitcoin4j.
     * Uses P2PKHLockBuilder to extract addresses from output scripts, matching
     * against wallet addresses — mirrors the Dart TransactionAnalyzer._analyzeOutput pattern.
     */
    static List<BitcoinUtxo> defaultExtractUtxos(
            ImportedTransaction itx, Set<String> addresses) {
        return defaultExtractUtxos(itx, addresses, NetworkAddressType.TEST_PKH);
    }

    static List<BitcoinUtxo> defaultExtractUtxos(
            ImportedTransaction itx, Set<String> addresses, NetworkAddressType networkAddressType) {
        if (itx.rawHex() == null || itx.rawHex().isEmpty()) {
            return List.of();
        }
        try {
            byte[] rawBytes = hexToBytes(itx.rawHex());
            var is = new java.io.ByteArrayInputStream(rawBytes);
            var tx = org.twostack.bitcoin4j.transaction.Transaction.fromStream(is);
            var outputs = tx.getOutputs();
            List<BitcoinUtxo> result = new ArrayList<>();
            for (int vout = 0; vout < outputs.size(); vout++) {
                var output = outputs.get(vout);
                try {
                    // Parse P2PKH script to extract pubkey hash, then derive address
                    var locker = new P2PKHLockBuilder(output.getScript());
                    byte[] pubkeyHash = locker.getPubkeyHash();
                    if (pubkeyHash == null) continue;

                    String outputAddress = LegacyAddress.fromPubKeyHash(
                            networkAddressType, pubkeyHash).toBase58();

                    if (addresses.contains(outputAddress)) {
                        String scriptHex = bytesToHex(output.getScript().getProgram());
                        long valueSats = output.getAmount().longValueExact();
                        BitcoinUtxo utxo = new BitcoinUtxo(
                                itx.txid(), vout, valueSats, scriptHex,
                                outputAddress, UtxoStatus.AVAILABLE,
                                itx.spvValid() ? (int) itx.blockHeight() : null,
                                null, Instant.now(), Instant.now(),
                                null, null, null, null, null, null, null);
                        result.add(utxo);
                    }
                } catch (Exception e) {
                    // Not a P2PKH output or parsing error — skip
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private CompletionStage<WalletReply> askWallet(
            EntityRef<WalletCommand> walletRef,
            java.util.function.Function<ActorRef<WalletReply>, WalletCommand> commandFactory) {
        return walletRef.ask(commandFactory::apply, askTimeout);
    }

    private static void notifyProgress(Consumer<String> callback, String message) {
        if (callback != null) {
            callback.accept(message);
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }
}
