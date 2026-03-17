package org.twostack.libspiffy4j.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.twostack.bitcoin4j.ECKey;
import org.twostack.bitcoin4j.crypto.DeterministicKey;
import org.twostack.libspiffy4j.model.*;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionBuildServiceTest {

    private TransactionBuildService service;
    private CryptoService crypto;
    private DeterministicKey masterKey;
    private DeterministicKey derivedKey;
    private String senderAddress;
    private String recipientAddress;

    @BeforeEach
    void setUp() {
        crypto = new CryptoService();
        service = new TransactionBuildService(crypto);

        List<String> mnemonic = crypto.generateMnemonic();
        masterKey = crypto.mnemonicToHDPrivateKey(mnemonic, "");
        derivedKey = crypto.derivePrivateKey(masterKey, 0, 0, 0, false);
        senderAddress = crypto.generateAddress(derivedKey, NetworkType.TESTNET);

        DeterministicKey recipientKey = crypto.derivePrivateKey(masterKey, 0, 1, 0, false);
        recipientAddress = crypto.generateAddress(recipientKey, NetworkType.TESTNET);
    }

    private BitcoinUtxo utxo(String txid, long valueSats) {
        return new BitcoinUtxo(txid, 0, valueSats, "76a914" + "ab".repeat(20) + "88ac",
                senderAddress, UtxoStatus.AVAILABLE, 100, 6,
                Instant.now(), Instant.now(), null, null, null, null, 0, null, null);
    }

    @Test
    void buildTransaction_singleP2PKHOutput() {
        var available = List.of(utxo("aa".repeat(32), 100_000));
        var outputs = List.<InvoiceOutputSpec>of(
                new InvoiceOutputSpec.P2PKHOutputSpec(recipientAddress, 50_000, "test"));
        var config = TransactionBuildConfig.standard();

        TransactionBuildResult result = service.buildTransaction(
                available, outputs, config, senderAddress, derivedKey, NetworkType.TESTNET);

        assertThat(result.txid()).isNotNull().hasSize(64);
        assertThat(result.rawHex()).isNotNull().isNotEmpty();
        assertThat(result.signed()).isTrue();
        assertThat(result.selectedUtxos()).hasSize(1);
        assertThat(result.totalInputSats()).isEqualTo(100_000);
        assertThat(result.feeSats()).isGreaterThan(0);
        assertThat(result.totalOutputSats()).isEqualTo(result.totalInputSats() - result.feeSats());
    }

    @Test
    void buildTransaction_multipleOutputs() {
        var available = List.of(utxo("bb".repeat(32), 200_000));

        DeterministicKey key2 = crypto.derivePrivateKey(masterKey, 0, 2, 0, false);
        String addr2 = crypto.generateAddress(key2, NetworkType.TESTNET);

        var outputs = List.<InvoiceOutputSpec>of(
                new InvoiceOutputSpec.P2PKHOutputSpec(recipientAddress, 30_000, "out1"),
                new InvoiceOutputSpec.P2PKHOutputSpec(addr2, 40_000, "out2"));
        var config = TransactionBuildConfig.standard();

        TransactionBuildResult result = service.buildTransaction(
                available, outputs, config, senderAddress, derivedKey, NetworkType.TESTNET);

        assertThat(result.signed()).isTrue();
        assertThat(result.outputCount()).isGreaterThanOrEqualTo(2);
        assertThat(result.totalInputSats()).isEqualTo(200_000);
    }

    @Test
    void buildTransaction_opReturnOutput() {
        var available = List.of(utxo("cc".repeat(32), 100_000));
        var outputs = List.<InvoiceOutputSpec>of(
                new InvoiceOutputSpec.P2PKHOutputSpec(recipientAddress, 50_000, "payment"),
                new InvoiceOutputSpec.OPReturnOutputSpec(List.of("hello".getBytes()), false));
        var config = TransactionBuildConfig.standard();

        TransactionBuildResult result = service.buildTransaction(
                available, outputs, config, senderAddress, derivedKey, NetworkType.TESTNET);

        assertThat(result.signed()).isTrue();
        assertThat(result.outputCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void buildTransaction_p2msOutput() {
        var available = List.of(utxo("dd".repeat(32), 100_000));

        DeterministicKey key2 = crypto.derivePrivateKey(masterKey, 0, 2, 0, false);
        String pubKey1 = org.twostack.bitcoin4j.Utils.HEX.encode(derivedKey.getPubKey());
        String pubKey2 = org.twostack.bitcoin4j.Utils.HEX.encode(key2.getPubKey());

        var outputs = List.<InvoiceOutputSpec>of(
                new InvoiceOutputSpec.P2MSOutputSpec(List.of(pubKey1, pubKey2), 2, 40_000, "multisig"));
        var config = TransactionBuildConfig.standard();

        TransactionBuildResult result = service.buildTransaction(
                available, outputs, config, senderAddress, derivedKey, NetworkType.TESTNET);

        assertThat(result.signed()).isTrue();
        assertThat(result.totalInputSats()).isEqualTo(100_000);
    }

    @Test
    void buildTransaction_changeGenerated() {
        var available = List.of(utxo("ee".repeat(32), 100_000));
        var outputs = List.<InvoiceOutputSpec>of(
                new InvoiceOutputSpec.P2PKHOutputSpec(recipientAddress, 10_000, "small"));
        var config = TransactionBuildConfig.standard();

        TransactionBuildResult result = service.buildTransaction(
                available, outputs, config, senderAddress, derivedKey, NetworkType.TESTNET);

        assertThat(result.changeSats()).isGreaterThan(0);
        assertThat(result.changeAddress()).isEqualTo(senderAddress);
    }

    @Test
    void buildTransaction_dustAbsorbedIntoFee() {
        // Use an amount that leaves dust-level change
        long inputAmount = 10_000;
        // Fee estimate for 1 input 2 outputs ≈ (10 + 148 + 68) * 100 / 1000 = ~22
        // So sending ~9975 should leave ~3 sats change (below 546 min), absorbed into fee
        var available = List.of(utxo("ff".repeat(32), inputAmount));
        long sendAmount = inputAmount - 30; // leaves ~30 for fee + dust change
        var outputs = List.<InvoiceOutputSpec>of(
                new InvoiceOutputSpec.P2PKHOutputSpec(recipientAddress, sendAmount, "near-max"));
        var config = new TransactionBuildConfig(100, UtxoSelectionStrategy.LARGEST_FIRST, 546, false, false, false);

        TransactionBuildResult result = service.buildTransaction(
                available, outputs, config, senderAddress, derivedKey, NetworkType.TESTNET);

        // Change should be absorbed into fee since it's below minChangeAmountSats
        assertThat(result.changeSats()).isZero();
        assertThat(result.changeAddress()).isNull();
        assertThat(result.feeSats()).isEqualTo(inputAmount - sendAmount);
    }

    @Test
    void buildTransaction_unsignedTx_nullKey() {
        var available = List.of(utxo("aa".repeat(32), 100_000));
        var outputs = List.<InvoiceOutputSpec>of(
                new InvoiceOutputSpec.P2PKHOutputSpec(recipientAddress, 50_000, "unsigned"));
        var config = new TransactionBuildConfig(100, UtxoSelectionStrategy.LARGEST_FIRST, 546, false, false, false);

        TransactionBuildResult result = service.buildTransaction(
                available, outputs, config, senderAddress, null, NetworkType.TESTNET);

        assertThat(result.signed()).isFalse();
        assertThat(result.txid()).isNotNull();
        assertThat(result.rawHex()).isNotNull();
    }

    @Test
    void calculateFee_basic() {
        // 1 input, 1 output at 100 sat/kb
        // Size = 10 + 148 + 34 = 192 bytes
        // Fee = 192 * 100 / 1000 = 19
        long fee = service.calculateFee(1, 1, 100);
        assertThat(fee).isEqualTo(19);
    }

    @Test
    void calculateFee_multipleInputsOutputs() {
        // 3 inputs, 2 outputs at 500 sat/kb
        // Size = 10 + 3*148 + 2*34 = 10 + 444 + 68 = 522 bytes
        // Fee = 522 * 500 / 1000 = 261
        long fee = service.calculateFee(3, 2, 500);
        assertThat(fee).isEqualTo(261);
    }

    @Test
    void buildTransaction_insufficientFunds_throws() {
        var available = List.of(utxo("aa".repeat(32), 100));
        var outputs = List.<InvoiceOutputSpec>of(
                new InvoiceOutputSpec.P2PKHOutputSpec(recipientAddress, 50_000, "too much"));
        var config = TransactionBuildConfig.standard();

        assertThatThrownBy(() -> service.buildTransaction(
                available, outputs, config, senderAddress, derivedKey, NetworkType.TESTNET))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
