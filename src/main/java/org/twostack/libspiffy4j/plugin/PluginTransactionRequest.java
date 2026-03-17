package org.twostack.libspiffy4j.plugin;

import org.twostack.libspiffy4j.model.BitcoinUtxo;

import java.util.List;
import java.util.Map;

/**
 * Request object passed to {@link TransactionBuilderPlugin#buildTransaction}.
 * Contains everything a plugin needs to build a complete transaction without
 * ever having direct access to private keys.
 *
 * @param fundingUtxos UTXOs selected by libspiffy4j to fund the transaction
 * @param signer callback signer wrapping the private key (key never exposed)
 * @param transactionLookup callback for resolving raw transaction hex from the wallet's
 *                          read model — plugins use this to retrieve parent/witness transactions
 *                          by txid rather than receiving external hex, keeping all data flowing
 *                          through the wallet's append-only log. May be {@code null} if the
 *                          coordinator does not support transaction lookup.
 * @param publicKeyHexes hex-encoded public keys for building unlock scripts
 * @param changeAddress address for the change output
 * @param params plugin-specific parameters (e.g., tokenId, action, recipientAddress)
 */
public record PluginTransactionRequest(
        List<BitcoinUtxo> fundingUtxos,
        CallbackTransactionSigner signer,
        TransactionLookup transactionLookup,
        List<String> publicKeyHexes,
        String changeAddress,
        Map<String, Object> params
) {
    public PluginTransactionRequest {
        fundingUtxos = fundingUtxos == null ? List.of() : List.copyOf(fundingUtxos);
        if (signer == null) {
            throw new IllegalArgumentException("signer must not be null");
        }
        publicKeyHexes = publicKeyHexes == null ? List.of() : List.copyOf(publicKeyHexes);
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
