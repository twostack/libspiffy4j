package examples;

import org.twostack.libspiffy4j.model.*;
import org.twostack.libspiffy4j.service.*;

import org.bitcoinsv.jcl.bsv.api.ECKey;
import org.bitcoinsv.jcl.bsv.api.DeterministicKey;

import java.util.List;

/**
 * Demonstrates libspiffy4j's stateless services — no Pekko actor system or database required.
 *
 * Covers:
 *   - BIP39 mnemonic generation and BIP44 key derivation
 *   - Address generation and WIF export
 *   - Transaction building with coin selection
 *   - UTXO splitting with Benford distribution
 */
public class StatelessServicesExample {

    public static void main(String[] args) throws Exception {

        // ---------------------------------------------------------------
        // 1. Key Derivation (CryptoService)
        // ---------------------------------------------------------------

        var crypto = new CryptoService();

        // Generate a 12-word BIP39 mnemonic
        List<String> mnemonic = crypto.generateMnemonic();
        System.out.println("Mnemonic: " + String.join(" ", mnemonic));

        // Derive HD master key
        DeterministicKey hdKey = crypto.mnemonicToHDPrivateKey(mnemonic, "");

        // BIP44 derivation: m/44'/0'/0'/0/0  (mainnet, account 0, external chain, index 0)
        DeterministicKey key0 = crypto.derivePrivateKey(hdKey, 0, 0, 0, false);
        String address0 = crypto.generateAddress(key0, NetworkType.MAINNET);
        System.out.println("Address 0: " + address0);

        // Derive a change address: m/44'/0'/0'/1/0
        DeterministicKey changeKey = crypto.derivePrivateKey(hdKey, 0, 0, 0, true);
        String changeAddress = crypto.generateAddress(changeKey, NetworkType.MAINNET);
        System.out.println("Change address: " + changeAddress);

        // Export private key as WIF
        String wif = crypto.privateKeyToWIF(key0, NetworkType.MAINNET);
        System.out.println("WIF: " + wif);

        // Restore from WIF
        ECKey restored = crypto.privateKeyFromWIF(wif);

        // ---------------------------------------------------------------
        // 2. Coin Selection (CoinSelector)
        // ---------------------------------------------------------------

        var selector = new CoinSelector();

        // Simulate available UTXOs
        List<BitcoinUtxo> utxos = List.of(
            createUtxo("tx1", 0, 10_000L, address0),
            createUtxo("tx2", 0, 25_000L, address0),
            createUtxo("tx3", 1, 50_000L, address0)
        );

        long targetSats = 30_000L;

        // OPTIMAL_CHANGE minimizes change output size via branch-and-bound
        CoinSelector.CoinSelectionResult selection = selector.select(
            utxos, targetSats, UtxoSelectionStrategy.OPTIMAL_CHANGE
        );

        System.out.println("\nCoin selection for " + targetSats + " sats:");
        System.out.println("  Selected: " + selection.selected().size() + " UTXOs");
        System.out.println("  Total:    " + selection.totalSelected() + " sats");
        System.out.println("  Change:   " + selection.change() + " sats");

        // ---------------------------------------------------------------
        // 3. Transaction Building (TransactionBuildService)
        // ---------------------------------------------------------------

        var buildService = new TransactionBuildService(crypto);

        String recipientAddress = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"; // example

        // Build a signed transaction
        TransactionBuildResult result = buildService.buildTransaction(
            utxos,
            List.of(new InvoiceOutputSpec.P2PKHOutputSpec(recipientAddress, targetSats, "payment")),
            TransactionBuildConfig.standard(),
            changeAddress,
            restored,               // signing key
            NetworkType.MAINNET
        );

        System.out.println("\nBuilt transaction:");
        System.out.println("  TXID: " + result.txid());
        System.out.println("  Fee:  " + result.feeSats() + " sats");
        System.out.println("  Hex:  " + result.rawHex().substring(0, 40) + "...");

        // ---------------------------------------------------------------
        // 4. OP_RETURN Data Output
        // ---------------------------------------------------------------

        // Build a transaction with a data-carrying OP_RETURN output
        TransactionBuildResult dataResult = buildService.buildTransaction(
            utxos,
            List.of(
                new InvoiceOutputSpec.P2PKHOutputSpec(recipientAddress, 10_000L, "payment"),
                new InvoiceOutputSpec.OPReturnOutputSpec(
                    List.of("Hello, BSV!".getBytes()), false
                )
            ),
            TransactionBuildConfig.standard(),
            changeAddress,
            restored,
            NetworkType.MAINNET
        );

        System.out.println("\nData transaction TXID: " + dataResult.txid());

        // ---------------------------------------------------------------
        // 5. UTXO Splitting with Benford Distribution (UtxoSplitService)
        // ---------------------------------------------------------------

        var splitService = new UtxoSplitService();

        // Generate 3 addresses for splitting
        List<String> splitAddresses = List.of(
            crypto.generateAddress(crypto.derivePrivateKey(hdKey, 0, 1, 0, false), NetworkType.MAINNET),
            crypto.generateAddress(crypto.derivePrivateKey(hdKey, 0, 2, 0, false), NetworkType.MAINNET),
            crypto.generateAddress(crypto.derivePrivateKey(hdKey, 0, 3, 0, false), NetworkType.MAINNET)
        );

        List<InvoiceOutputSpec.P2PKHOutputSpec> splitOutputs = splitService.generateBenfordSplit(
            100_000L,           // total to split
            splitAddresses,
            546L                // minimum output (dust threshold)
        );

        System.out.println("\nBenford split of 100,000 sats across 3 addresses:");
        for (var output : splitOutputs) {
            System.out.println("  " + output.address() + " -> " + output.amountSats() + " sats");
        }
    }

    /** Helper to create a test UTXO. */
    private static BitcoinUtxo createUtxo(String txid, int vout, long valueSats, String address) {
        return new BitcoinUtxo(
            txid, vout, valueSats,
            "",            // scriptPubKey (populated by real UTXOs)
            address,
            UtxoStatus.AVAILABLE,
            850_000,       // blockHeight
            6,             // confirmations
            java.time.Instant.now(),
            java.time.Instant.now(),
            null, null, null, null, null
        );
    }
}
