package org.twostack.libspiffy4j.service;

import org.twostack.bitcoin4j.ECKey;
import org.twostack.bitcoin4j.PrivateKey;
import org.twostack.bitcoin4j.PublicKey;
import org.twostack.bitcoin4j.Utils;
import org.twostack.bitcoin4j.address.LegacyAddress;
import org.twostack.bitcoin4j.params.NetworkType;
import org.twostack.bitcoin4j.transaction.*;
import org.twostack.libspiffy4j.model.BitcoinUtxo;
import org.twostack.libspiffy4j.model.InvoiceOutputSpec;
import org.twostack.libspiffy4j.model.TransactionBuildConfig;
import org.twostack.libspiffy4j.model.TransactionBuildResult;
import org.twostack.libspiffy4j.plugin.PluginLockSpec;
import org.twostack.libspiffy4j.plugin.PluginRegistry;
import org.twostack.libspiffy4j.plugin.ScriptPlugin;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.List;

public final class TransactionBuildService {

    private static final int MAX_FEE_ITERATIONS = 3;
    private static final long RBF_SEQUENCE = 0xFFFFFFFEL;
    private static final long FINAL_SEQUENCE = 0xFFFFFFFFL;

    // Estimated sizes in bytes
    private static final int TX_OVERHEAD = 10; // version(4) + locktime(4) + varint(~2)
    private static final int INPUT_SIZE = 148; // P2PKH input
    private static final int OUTPUT_SIZE = 34;  // P2PKH output

    private final CryptoService cryptoService;
    private final CoinSelector coinSelector;
    private final PluginRegistry pluginRegistry;

    public TransactionBuildService(CryptoService cryptoService, PluginRegistry pluginRegistry) {
        this.cryptoService = cryptoService;
        this.pluginRegistry = pluginRegistry;
        this.coinSelector = new CoinSelector();
    }

    public TransactionBuildService(CryptoService cryptoService) {
        this(cryptoService, null);
    }

    public TransactionBuildResult buildTransaction(
            List<BitcoinUtxo> available,
            List<InvoiceOutputSpec> outputs,
            TransactionBuildConfig config,
            String changeAddress,
            ECKey signingKey,
            org.twostack.libspiffy4j.model.NetworkType networkType) {

        long outputTotal = sumOutputs(outputs);
        long sequenceNumber = config.enableRBF() ? RBF_SEQUENCE : FINAL_SEQUENCE;
        NetworkType btc4jNetwork = toBitcoin4jNetwork(networkType);
        int outputCount = countOutputs(outputs);

        // Iterative fee estimation and coin selection
        int estimatedInputs = 1;
        CoinSelector.CoinSelectionResult selection = null;

        for (int i = 0; i < MAX_FEE_ITERATIONS; i++) {
            boolean hasChange = true; // assume change output initially
            long estimatedFee = calculateFee(estimatedInputs, outputCount + (hasChange ? 1 : 0), config.feePerKb());
            long target = outputTotal + estimatedFee;

            selection = coinSelector.select(available, target, config.selectionStrategy());

            // Recalculate with actual input count
            int actualInputs = selection.selected().size();
            long changeSats = selection.totalSelected() - outputTotal;

            long recalcFee = calculateFee(actualInputs, outputCount + 1, config.feePerKb());
            changeSats -= recalcFee;

            if (changeSats < 0) {
                // Need more coins, try again with updated estimate
                estimatedInputs = actualInputs + 1;
                continue;
            }

            // Handle dust change
            if (changeSats > 0 && changeSats < config.minChangeAmountSats() && !config.forceChange()) {
                // Absorb into fee
                recalcFee += changeSats;
                changeSats = 0;
            }

            // Adjust output count if no change
            int finalOutputCount = outputCount + (changeSats > 0 ? 1 : 0);
            long finalFee = changeSats > 0
                    ? calculateFee(actualInputs, finalOutputCount, config.feePerKb())
                    : recalcFee;

            // If no change, re-absorb difference
            if (changeSats <= 0) {
                finalFee = selection.totalSelected() - outputTotal;
                changeSats = 0;
            } else {
                changeSats = selection.totalSelected() - outputTotal - finalFee;
                if (changeSats < config.minChangeAmountSats() && !config.forceChange()) {
                    finalFee += changeSats;
                    changeSats = 0;
                }
            }

            // Build the transaction
            return assembleTx(selection, outputs, changeSats, changeAddress,
                    finalFee, sequenceNumber, signingKey, btc4jNetwork, config);
        }

        // Final attempt after iteration limit
        if (selection != null) {
            int actualInputs = selection.selected().size();
            int finalOutputCount = outputCount + 1;
            long finalFee = calculateFee(actualInputs, finalOutputCount, config.feePerKb());
            long changeSats = selection.totalSelected() - outputTotal - finalFee;

            if (changeSats < 0) {
                throw new IllegalArgumentException(
                        "Insufficient funds after fee adjustment: need %d more sats".formatted(-changeSats));
            }
            if (changeSats < config.minChangeAmountSats() && !config.forceChange()) {
                finalFee += changeSats;
                changeSats = 0;
            }

            return assembleTx(selection, outputs, changeSats, changeAddress,
                    finalFee, sequenceNumber, signingKey, btc4jNetwork, config);
        }

        throw new IllegalStateException("Failed to build transaction after fee iteration");
    }

    public long calculateFee(int inputCount, int outputCount, long feePerKb) {
        long estimatedSize = TX_OVERHEAD + ((long) inputCount * INPUT_SIZE) + ((long) outputCount * OUTPUT_SIZE);
        return Math.max(1, (estimatedSize * feePerKb) / 1000);
    }

    private TransactionBuildResult assembleTx(
            CoinSelector.CoinSelectionResult selection,
            List<InvoiceOutputSpec> outputs,
            long changeSats,
            String changeAddress,
            long feeSats,
            long sequenceNumber,
            ECKey signingKey,
            NetworkType btc4jNetwork,
            TransactionBuildConfig config) {

        try {
            TransactionBuilder builder = new TransactionBuilder();
            builder.withFeePerKb(config.feePerKb());

            boolean signing = signingKey != null;
            int sighashType = SigHashType.ALL.value | SigHashType.FORKID.value;

            PublicKey pubKey = signing ? PublicKey.fromBytes(signingKey.getPubKey()) : null;
            PrivateKey privKey = signing
                    ? new PrivateKey(signingKey, true, btc4jNetwork)
                    : null;
            TransactionSigner signer = signing
                    ? new TransactionSigner(sighashType, privKey)
                    : null;

            // For unsigned transactions, generate a throwaway key for the unlock builder placeholder
            PublicKey unsignedPlaceholderPubKey = null;
            if (!signing) {
                ECKey placeholderKey = new ECKey();
                unsignedPlaceholderPubKey = PublicKey.fromBytes(placeholderKey.getPubKey());
            }

            // Add inputs
            for (var utxo : selection.selected()) {
                P2PKHUnlockBuilder unlocker = new P2PKHUnlockBuilder(
                        signing ? pubKey : unsignedPlaceholderPubKey);

                if (signing) {
                    builder.spendFromOutput(signer, utxo.txid(), utxo.vout(),
                            BigInteger.valueOf(utxo.valueSats()), sequenceNumber, unlocker);
                } else {
                    builder.spendFromOutput(utxo.txid(), utxo.vout(),
                            BigInteger.valueOf(utxo.valueSats()), sequenceNumber, unlocker);
                }
            }

            // Add payment outputs
            for (var spec : outputs) {
                addOutput(builder, spec, btc4jNetwork);
            }

            // Add change output
            if (changeSats > 0 && changeAddress != null) {
                LegacyAddress addr = LegacyAddress.fromBase58(btc4jNetwork, changeAddress);
                builder.spendTo(new P2PKHLockBuilder(addr), BigInteger.valueOf(changeSats));
            }

            // Set explicit fee to prevent TransactionBuilder from recalculating
            builder.setFee(BigInteger.valueOf(feeSats));

            // Disable sanity checks when OP_RETURN outputs are present (0-sat outputs trigger dust check)
            boolean hasOpReturn = outputs.stream().anyMatch(o -> o instanceof InvoiceOutputSpec.OPReturnOutputSpec);
            Transaction tx = builder.build(config.performSanityChecks() && !hasOpReturn);

            String rawHex = Utils.HEX.encode(tx.serialize());
            String txid = tx.getTransactionId();

            long totalOutputSats = sumOutputs(outputs) + changeSats;

            return new TransactionBuildResult(
                    txid, rawHex, signing,
                    selection.selected(),
                    selection.totalSelected(),
                    totalOutputSats,
                    feeSats, changeSats,
                    changeSats > 0 ? changeAddress : null,
                    selection.selected().size(),
                    countOutputs(outputs) + (changeSats > 0 ? 1 : 0)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to build transaction: " + e.getMessage(), e);
        }
    }

    private void addOutput(TransactionBuilder builder, InvoiceOutputSpec spec, NetworkType network)
            throws Exception {
        switch (spec) {
            case InvoiceOutputSpec.P2PKHOutputSpec p2pkh -> {
                LegacyAddress addr = LegacyAddress.fromBase58(network, p2pkh.address());
                builder.spendTo(new P2PKHLockBuilder(addr), BigInteger.valueOf(p2pkh.amountSats()));
            }
            case InvoiceOutputSpec.P2MSOutputSpec p2ms -> {
                List<PublicKey> pubKeys = new java.util.ArrayList<>(p2ms.publicKeys().stream()
                        .map(PublicKey::fromHex)
                        .toList());
                builder.spendTo(new P2MSLockBuilder(pubKeys, p2ms.threshold()), BigInteger.valueOf(p2ms.amountSats()));
            }
            case InvoiceOutputSpec.OPReturnOutputSpec opReturn -> {
                List<ByteBuffer> buffers = opReturn.dataChunks().stream()
                        .map(ByteBuffer::wrap)
                        .toList();
                builder.spendTo(new UnspendableDataLockBuilder(buffers), BigInteger.ZERO);
            }
            case InvoiceOutputSpec.PluginOutputSpec plugin -> {
                if (pluginRegistry == null) {
                    throw new IllegalStateException("PluginRegistry required for plugin outputs");
                }
                ScriptPlugin p = pluginRegistry.getPlugin(plugin.pluginId())
                        .orElseThrow(() -> new IllegalArgumentException("Plugin not found: " + plugin.pluginId()));
                byte[] lockScript = p.createLockingScript(new PluginLockSpec(
                        plugin.pluginId(), plugin.pluginScriptType(), plugin.amountSats(), plugin.params()));
                builder.spendTo(new DefaultLockBuilder(new org.twostack.bitcoin4j.script.Script(lockScript)),
                        BigInteger.valueOf(plugin.amountSats()));
            }
        }
    }

    private long sumOutputs(List<InvoiceOutputSpec> outputs) {
        long total = 0;
        for (var spec : outputs) {
            total += switch (spec) {
                case InvoiceOutputSpec.P2PKHOutputSpec p -> p.amountSats();
                case InvoiceOutputSpec.P2MSOutputSpec p -> p.amountSats();
                case InvoiceOutputSpec.OPReturnOutputSpec ignored -> 0;
                case InvoiceOutputSpec.PluginOutputSpec p -> p.amountSats();
            };
        }
        return total;
    }

    private int countOutputs(List<InvoiceOutputSpec> outputs) {
        int count = 0;
        for (var spec : outputs) {
            if (spec instanceof InvoiceOutputSpec.OPReturnOutputSpec opReturn && opReturn.separateOutputs()) {
                count += opReturn.dataChunks().size();
            } else {
                count++;
            }
        }
        return count;
    }

    private NetworkType toBitcoin4jNetwork(org.twostack.libspiffy4j.model.NetworkType networkType) {
        return switch (networkType) {
            case MAINNET -> NetworkType.MAIN;
            case TESTNET -> NetworkType.TEST;
            case REGTEST -> NetworkType.REGTEST;
        };
    }
}
