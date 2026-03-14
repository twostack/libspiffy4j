package org.twostack.libspiffy4j.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.twostack.bitcoin4j.crypto.DeterministicKey;
import org.twostack.libspiffy4j.model.AddressDiscoveryResult;
import org.twostack.libspiffy4j.model.DiscoveredAddress;
import org.twostack.libspiffy4j.model.NetworkType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AddressDiscoveryServiceTest {

    private CryptoService cryptoService;
    private DeterministicKey masterKey;

    @BeforeEach
    void setUp() {
        cryptoService = new CryptoService();
        List<String> mnemonic = cryptoService.generateMnemonic();
        masterKey = cryptoService.mnemonicToHDPrivateKey(mnemonic, "");
    }

    @Test
    void discoverAddresses_findsReceivingAddresses() {
        // Mock lookup: first 3 receiving addresses have transactions
        Map<String, List<String>> addressTxMap = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            DeterministicKey key = cryptoService.derivePrivateKey(masterKey, 0, i, 236, false);
            String address = cryptoService.generateAddress(key, NetworkType.MAINNET);
            addressTxMap.put(address, List.of("tx_" + i));
        }

        AddressLookupFunction lookup = address ->
                addressTxMap.getOrDefault(address, List.of());

        AddressDiscoveryService service = new AddressDiscoveryService(cryptoService, lookup);
        AddressDiscoveryResult result = service.discoverAddresses(masterKey, NetworkType.MAINNET, 5, null);

        assertThat(result.receivingAddresses()).hasSize(3);
        assertThat(result.receivingAddresses().get(0).derivationIndex()).isEqualTo(0);
        assertThat(result.receivingAddresses().get(2).derivationIndex()).isEqualTo(2);
    }

    @Test
    void discoverAddresses_respectsGapLimit() {
        // No addresses have transactions — should stop after gap limit
        AddressLookupFunction lookup = address -> List.of();

        AddressDiscoveryService service = new AddressDiscoveryService(cryptoService, lookup);
        AddressDiscoveryResult result = service.discoverAddresses(masterKey, NetworkType.MAINNET, 3, null);

        assertThat(result.receivingAddresses()).isEmpty();
        assertThat(result.changeAddresses()).isEmpty();
        assertThat(result.totalTransactions()).isEqualTo(0);
    }

    @Test
    void discoverAddresses_scansBothChains() {
        Map<String, List<String>> addressTxMap = new HashMap<>();

        // 2 receiving addresses
        for (int i = 0; i < 2; i++) {
            DeterministicKey key = cryptoService.derivePrivateKey(masterKey, 0, i, 236, false);
            String address = cryptoService.generateAddress(key, NetworkType.MAINNET);
            addressTxMap.put(address, List.of("rx_tx_" + i));
        }

        // 1 change address
        DeterministicKey changeKey = cryptoService.derivePrivateKey(masterKey, 0, 0, 236, true);
        String changeAddress = cryptoService.generateAddress(changeKey, NetworkType.MAINNET);
        addressTxMap.put(changeAddress, List.of("change_tx_0"));

        AddressLookupFunction lookup = address ->
                addressTxMap.getOrDefault(address, List.of());

        AddressDiscoveryService service = new AddressDiscoveryService(cryptoService, lookup);
        AddressDiscoveryResult result = service.discoverAddresses(masterKey, NetworkType.MAINNET, 5, null);

        assertThat(result.receivingAddresses()).hasSize(2);
        assertThat(result.changeAddresses()).hasSize(1);
        assertThat(result.changeAddresses().get(0).isChange()).isTrue();
        assertThat(result.totalTransactions()).isEqualTo(3);
    }

    @Test
    void discoverAddresses_emptyResult() {
        AddressLookupFunction lookup = address -> List.of();

        AddressDiscoveryService service = new AddressDiscoveryService(cryptoService, lookup);
        AddressDiscoveryResult result = service.discoverAddresses(masterKey, NetworkType.TESTNET, 2, null);

        assertThat(result.receivingAddresses()).isEmpty();
        assertThat(result.changeAddresses()).isEmpty();
        assertThat(result.totalTransactions()).isEqualTo(0);
    }

    @Test
    void discoverAddresses_callsProgressCallback() {
        Map<String, List<String>> addressTxMap = new HashMap<>();
        DeterministicKey key = cryptoService.derivePrivateKey(masterKey, 0, 0, 236, false);
        String address = cryptoService.generateAddress(key, NetworkType.MAINNET);
        addressTxMap.put(address, List.of("tx_0"));

        AddressLookupFunction lookup = addr ->
                addressTxMap.getOrDefault(addr, List.of());

        List<DiscoveredAddress> progressUpdates = new ArrayList<>();
        AddressDiscoveryService service = new AddressDiscoveryService(cryptoService, lookup);
        service.discoverAddresses(masterKey, NetworkType.MAINNET, 3, progressUpdates::add);

        assertThat(progressUpdates).isNotEmpty();
        assertThat(progressUpdates.get(0).address()).isEqualTo(address);
    }
}
