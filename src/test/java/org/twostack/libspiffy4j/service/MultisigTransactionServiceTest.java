package org.twostack.libspiffy4j.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.twostack.bitcoin4j.Utils;
import org.twostack.bitcoin4j.crypto.DeterministicKey;
import org.twostack.libspiffy4j.model.*;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultisigTransactionServiceTest {

    private MultisigTransactionService service;
    private CryptoService crypto;
    private DeterministicKey clientKey;
    private DeterministicKey serverKey;
    private String clientPubKeyHex;
    private String serverPubKeyHex;
    private String changeAddress;

    @BeforeEach
    void setUp() {
        crypto = new CryptoService();
        TransactionBuildService txBuildService = new TransactionBuildService(crypto);
        service = new MultisigTransactionService(txBuildService);

        List<String> mnemonic1 = crypto.generateMnemonic();
        DeterministicKey master1 = crypto.mnemonicToHDPrivateKey(mnemonic1, "");
        clientKey = crypto.derivePrivateKey(master1, 0, 0, 0, false);
        clientPubKeyHex = Utils.HEX.encode(clientKey.getPubKey());

        List<String> mnemonic2 = crypto.generateMnemonic();
        DeterministicKey master2 = crypto.mnemonicToHDPrivateKey(mnemonic2, "");
        serverKey = crypto.derivePrivateKey(master2, 0, 0, 0, false);
        serverPubKeyHex = Utils.HEX.encode(serverKey.getPubKey());

        changeAddress = crypto.generateAddress(clientKey, NetworkType.TESTNET);
    }

    private BitcoinUtxo utxo(String txid, long valueSats) {
        return new BitcoinUtxo(txid, 0, valueSats, "76a914" + "ab".repeat(20) + "88ac",
                changeAddress, UtxoStatus.AVAILABLE, 100, 6,
                Instant.now(), Instant.now(), null, null, null, null, 0, null, null);
    }

    @Test
    void buildFundingTransaction_creates2of2MultisigOutput() {
        var available = List.of(utxo("aa".repeat(32), 100_000));
        var config = TransactionBuildConfig.standard();

        TransactionBuildResult result = service.buildFundingTransaction(
                clientPubKeyHex, serverPubKeyHex, 50_000,
                available, config, changeAddress, clientKey, NetworkType.TESTNET);

        assertThat(result.txid()).isNotNull().hasSize(64);
        assertThat(result.rawHex()).isNotNull().isNotEmpty();
        assertThat(result.signed()).isTrue();
        assertThat(result.selectedUtxos()).isNotEmpty();
        assertThat(result.totalInputSats()).isEqualTo(100_000);
        assertThat(result.feeSats()).isGreaterThan(0);
    }

    @Test
    void signMultisigInput_producesValidSignature() {
        var available = List.of(utxo("bb".repeat(32), 100_000));
        var config = new TransactionBuildConfig(100, UtxoSelectionStrategy.LARGEST_FIRST, 546, false, false, false);

        TransactionBuildResult fundingResult = service.buildFundingTransaction(
                clientPubKeyHex, serverPubKeyHex, 50_000,
                available, config, changeAddress, clientKey, NetworkType.TESTNET);

        byte[] rawTx = Utils.HEX.decode(fundingResult.rawHex());
        byte[] signature = service.signMultisigInput(rawTx, 0, serverKey, 100_000);

        assertThat(signature).isNotNull();
        assertThat(signature.length).isGreaterThan(0);
        // DER-encoded signature + sighash byte
        // DER signatures start with 0x30
        assertThat(signature[0]).isEqualTo((byte) 0x30);
    }
}
