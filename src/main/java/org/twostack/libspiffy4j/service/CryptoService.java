package org.twostack.libspiffy4j.service;

import org.twostack.bitcoin4j.Address;
import org.twostack.bitcoin4j.ECKey;
import org.twostack.bitcoin4j.PrivateKey;
import org.twostack.bitcoin4j.PublicKey;
import org.twostack.bitcoin4j.Sha256Hash;
import org.twostack.bitcoin4j.address.LegacyAddress;
import org.twostack.bitcoin4j.crypto.ChildNumber;
import org.twostack.bitcoin4j.crypto.DeterministicKey;
import org.twostack.bitcoin4j.crypto.HDKeyDerivation;
import org.twostack.bitcoin4j.crypto.MnemonicCode;
import org.twostack.bitcoin4j.exception.InvalidKeyException;
import org.twostack.bitcoin4j.exception.MnemonicException;
import org.twostack.bitcoin4j.params.NetworkAddressType;
import org.twostack.bitcoin4j.params.NetworkType;

import java.security.SecureRandom;
import java.util.List;

/**
 * Stateless service delegating to bitcoin4j for BIP39/BIP44 key derivation and address generation.
 */
public final class CryptoService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public List<String> generateMnemonic() {
        try {
            byte[] entropy = new byte[16]; // 128 bits → 12 words
            SECURE_RANDOM.nextBytes(entropy);
            return MnemonicCode.INSTANCE.toMnemonic(entropy);
        } catch (MnemonicException.MnemonicLengthException e) {
            throw new RuntimeException("Failed to generate mnemonic", e);
        }
    }

    public void validateMnemonic(List<String> words) throws MnemonicException {
        MnemonicCode.INSTANCE.check(words);
    }

    public DeterministicKey mnemonicToHDPrivateKey(List<String> words, String passphrase) {
        byte[] seed = MnemonicCode.toSeed(words, passphrase != null ? passphrase : "");
        return HDKeyDerivation.createMasterPrivateKey(seed);
    }

    /**
     * BIP44 derivation: m / 44' / coin_type' / account' / change / index
     */
    public DeterministicKey derivePrivateKey(DeterministicKey master, int account, int index,
                                             int coinType, boolean isChange) {
        DeterministicKey purpose = HDKeyDerivation.deriveChildKey(master, new ChildNumber(44, true));
        DeterministicKey coin = HDKeyDerivation.deriveChildKey(purpose, new ChildNumber(coinType, true));
        DeterministicKey acct = HDKeyDerivation.deriveChildKey(coin, new ChildNumber(account, true));
        DeterministicKey change = HDKeyDerivation.deriveChildKey(acct, new ChildNumber(isChange ? 1 : 0, false));
        return HDKeyDerivation.deriveChildKey(change, new ChildNumber(index, false));
    }

    public DeterministicKey deriveKeyForPath(DeterministicKey parent, int... childNumbers) {
        DeterministicKey current = parent;
        for (int childNum : childNumbers) {
            boolean hardened = (childNum & ChildNumber.HARDENED_BIT) != 0;
            int num = childNum & ~ChildNumber.HARDENED_BIT;
            current = HDKeyDerivation.deriveChildKey(current, new ChildNumber(num, hardened));
        }
        return current;
    }

    public String generateAddress(DeterministicKey key,
                                  org.twostack.libspiffy4j.model.NetworkType networkType) {
        NetworkAddressType addressType = toNetworkAddressType(networkType);
        PublicKey pubKey = PublicKey.fromBytes(key.getPubKey());
        Address address = LegacyAddress.fromKey(addressType, pubKey);
        return address.toString();
    }

    public String privateKeyToWIF(DeterministicKey key,
                                  org.twostack.libspiffy4j.model.NetworkType networkType) {
        return key.getPrivateKeyAsWiF(toBitcoin4jNetwork(networkType));
    }

    public ECKey privateKeyFromWIF(String wif) throws InvalidKeyException {
        PrivateKey pk = PrivateKey.fromWIF(wif);
        return pk.getKey();
    }

    public ECKey.ECDSASignature signMessage(ECKey privateKey, byte[] message) {
        Sha256Hash hash = Sha256Hash.of(message);
        return privateKey.sign(hash);
    }

    private NetworkType toBitcoin4jNetwork(org.twostack.libspiffy4j.model.NetworkType networkType) {
        return switch (networkType) {
            case MAINNET -> NetworkType.MAIN;
            case TESTNET -> NetworkType.TEST;
            case REGTEST -> NetworkType.REGTEST;
        };
    }

    private NetworkAddressType toNetworkAddressType(org.twostack.libspiffy4j.model.NetworkType networkType) {
        return switch (networkType) {
            case MAINNET -> NetworkAddressType.MAIN_PKH;
            case TESTNET, REGTEST -> NetworkAddressType.TEST_PKH;
        };
    }
}
