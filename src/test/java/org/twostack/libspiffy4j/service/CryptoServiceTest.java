package org.twostack.libspiffy4j.service;

import org.junit.jupiter.api.Test;
import org.twostack.bitcoin4j.ECKey;
import org.twostack.bitcoin4j.crypto.DeterministicKey;
import org.twostack.bitcoin4j.exception.InvalidKeyException;
import org.twostack.bitcoin4j.exception.MnemonicException;
import org.twostack.libspiffy4j.model.NetworkType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    private final CryptoService crypto = new CryptoService();

    @Test
    void generateMnemonic_returns12ValidWords() throws MnemonicException {
        List<String> mnemonic = crypto.generateMnemonic();
        assertThat(mnemonic).hasSize(12);
        // Should not throw
        crypto.validateMnemonic(mnemonic);
    }

    @Test
    void generateMnemonic_returnsDifferentWordsEachTime() {
        List<String> first = crypto.generateMnemonic();
        List<String> second = crypto.generateMnemonic();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void validateMnemonic_rejectsInvalidWords() {
        List<String> invalid = List.of("not", "a", "valid", "mnemonic", "phrase",
                "at", "all", "in", "any", "way", "shape", "form");
        assertThatThrownBy(() -> crypto.validateMnemonic(invalid))
                .isInstanceOf(MnemonicException.class);
    }

    @Test
    void mnemonicToHDPrivateKey_isDeterministic() {
        List<String> mnemonic = crypto.generateMnemonic();
        DeterministicKey key1 = crypto.mnemonicToHDPrivateKey(mnemonic, "");
        DeterministicKey key2 = crypto.mnemonicToHDPrivateKey(mnemonic, "");
        assertThat(key1.getPrivKey()).isEqualTo(key2.getPrivKey());
    }

    @Test
    void mnemonicToHDPrivateKey_differentPassphraseYieldsDifferentKey() {
        List<String> mnemonic = crypto.generateMnemonic();
        DeterministicKey key1 = crypto.mnemonicToHDPrivateKey(mnemonic, "passphrase1");
        DeterministicKey key2 = crypto.mnemonicToHDPrivateKey(mnemonic, "passphrase2");
        assertThat(key1.getPrivKey()).isNotEqualTo(key2.getPrivKey());
    }

    @Test
    void derivePrivateKey_bip44_isDeterministic() {
        List<String> mnemonic = crypto.generateMnemonic();
        DeterministicKey master = crypto.mnemonicToHDPrivateKey(mnemonic, "");

        DeterministicKey derived1 = crypto.derivePrivateKey(master, 0, 0, 0, false);
        DeterministicKey derived2 = crypto.derivePrivateKey(master, 0, 0, 0, false);
        assertThat(derived1.getPrivKey()).isEqualTo(derived2.getPrivKey());
    }

    @Test
    void derivePrivateKey_differentIndex_yieldsDifferentKey() {
        List<String> mnemonic = crypto.generateMnemonic();
        DeterministicKey master = crypto.mnemonicToHDPrivateKey(mnemonic, "");

        DeterministicKey key0 = crypto.derivePrivateKey(master, 0, 0, 0, false);
        DeterministicKey key1 = crypto.derivePrivateKey(master, 0, 1, 0, false);
        assertThat(key0.getPrivKey()).isNotEqualTo(key1.getPrivKey());
    }

    @Test
    void generateAddress_mainnet_startsWithCorrectPrefix() {
        List<String> mnemonic = crypto.generateMnemonic();
        DeterministicKey master = crypto.mnemonicToHDPrivateKey(mnemonic, "");
        DeterministicKey derived = crypto.derivePrivateKey(master, 0, 0, 0, false);

        String address = crypto.generateAddress(derived, NetworkType.MAINNET);
        assertThat(address).startsWith("1");
    }

    @Test
    void generateAddress_testnet_startsWithCorrectPrefix() {
        List<String> mnemonic = crypto.generateMnemonic();
        DeterministicKey master = crypto.mnemonicToHDPrivateKey(mnemonic, "");
        DeterministicKey derived = crypto.derivePrivateKey(master, 0, 0, 1, false);

        String address = crypto.generateAddress(derived, NetworkType.TESTNET);
        assertThat(address).matches("^[mn].*");
    }

    @Test
    void wifRoundTrip_preservesKey() throws InvalidKeyException {
        List<String> mnemonic = crypto.generateMnemonic();
        DeterministicKey master = crypto.mnemonicToHDPrivateKey(mnemonic, "");
        DeterministicKey derived = crypto.derivePrivateKey(master, 0, 0, 0, false);

        String wif = crypto.privateKeyToWIF(derived, NetworkType.MAINNET);
        ECKey recovered = crypto.privateKeyFromWIF(wif);

        assertThat(recovered.getPrivKey()).isEqualTo(derived.getPrivKey());
    }

    @Test
    void signMessage_producesValidSignature() {
        List<String> mnemonic = crypto.generateMnemonic();
        DeterministicKey master = crypto.mnemonicToHDPrivateKey(mnemonic, "");
        DeterministicKey derived = crypto.derivePrivateKey(master, 0, 0, 0, false);

        byte[] message = "hello world".getBytes();
        ECKey.ECDSASignature signature = crypto.signMessage(derived, message);

        assertThat(signature).isNotNull();
        assertThat(signature.r).isNotNull();
        assertThat(signature.s).isNotNull();
    }
}
