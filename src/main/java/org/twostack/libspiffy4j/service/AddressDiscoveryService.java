package org.twostack.libspiffy4j.service;

import org.twostack.bitcoin4j.crypto.DeterministicKey;
import org.twostack.libspiffy4j.model.AddressDiscoveryResult;
import org.twostack.libspiffy4j.model.DiscoveredAddress;
import org.twostack.libspiffy4j.model.NetworkType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * BIP44 gap-limit address scanner. Derives addresses sequentially and uses
 * an {@link AddressLookupFunction} to check for on-chain activity.
 */
public final class AddressDiscoveryService {

    private static final int BSV_COIN_TYPE = 236;
    private static final int TESTNET_COIN_TYPE = 1;

    private final CryptoService cryptoService;
    private final AddressLookupFunction lookupFunction;

    public AddressDiscoveryService(CryptoService cryptoService, AddressLookupFunction lookupFunction) {
        this.cryptoService = cryptoService;
        this.lookupFunction = lookupFunction;
    }

    /**
     * Scans receiving (m/44'/coinType'/0'/0/x) and change (m/44'/coinType'/0'/1/x) chains
     * for addresses with on-chain activity, stopping after {@code gapLimit} consecutive
     * addresses with no transactions.
     *
     * @param hdKey       BIP32 master key
     * @param networkType network to generate addresses for
     * @param gapLimit    number of consecutive empty addresses before stopping
     * @param onProgress  optional callback for each discovered address (may be null)
     * @return discovery result with all found addresses
     */
    public AddressDiscoveryResult discoverAddresses(DeterministicKey hdKey, NetworkType networkType,
                                                     int gapLimit, Consumer<DiscoveredAddress> onProgress) {
        int coinType = (networkType == NetworkType.MAINNET) ? BSV_COIN_TYPE : TESTNET_COIN_TYPE;

        List<DiscoveredAddress> receiving = scanChain(hdKey, networkType, coinType, false, gapLimit, onProgress);
        List<DiscoveredAddress> change = scanChain(hdKey, networkType, coinType, true, gapLimit, onProgress);

        int totalTx = receiving.stream().mapToInt(a -> a.transactionIds().size()).sum()
                + change.stream().mapToInt(a -> a.transactionIds().size()).sum();

        Map<Boolean, Integer> lastChecked = new HashMap<>();
        lastChecked.put(false, receiving.isEmpty() ? gapLimit - 1 :
                receiving.getLast().derivationIndex() + gapLimit);
        lastChecked.put(true, change.isEmpty() ? gapLimit - 1 :
                change.getLast().derivationIndex() + gapLimit);

        return new AddressDiscoveryResult(receiving, change, totalTx, lastChecked);
    }

    private List<DiscoveredAddress> scanChain(DeterministicKey hdKey, NetworkType networkType,
                                               int coinType, boolean isChange, int gapLimit,
                                               Consumer<DiscoveredAddress> onProgress) {
        List<DiscoveredAddress> discovered = new ArrayList<>();
        int consecutiveEmpty = 0;
        int index = 0;

        while (consecutiveEmpty < gapLimit) {
            DeterministicKey derived = cryptoService.derivePrivateKey(hdKey, 0, index, coinType, isChange);
            String address = cryptoService.generateAddress(derived, networkType);

            try {
                List<String> txids = lookupFunction.lookup(address);
                if (txids != null && !txids.isEmpty()) {
                    DiscoveredAddress da = new DiscoveredAddress(address, index, isChange, txids);
                    discovered.add(da);
                    consecutiveEmpty = 0;
                    if (onProgress != null) {
                        onProgress.accept(da);
                    }
                } else {
                    consecutiveEmpty++;
                }
            } catch (Exception e) {
                throw new RuntimeException("Address lookup failed for " + address, e);
            }

            index++;
        }

        return discovered;
    }
}
