package org.twostack.libspiffy4j.plugin;

import java.util.List;

/**
 * Extended plugin interface for protocols that require building complete
 * multi-output transactions (e.g., token issuance with 5-output structure).
 *
 * <p>Unlike {@link ScriptPlugin} which builds individual lock/unlock scripts,
 * this interface controls the entire transaction structure. The plugin receives
 * funding UTXOs and a {@link CallbackTransactionSigner} (private key never exposed),
 * and returns a fully constructed and signed transaction.
 */
public interface TransactionBuilderPlugin extends ScriptPlugin {

    /** Actions this plugin supports (e.g., ["issuance", "transfer", "burn"]). */
    List<String> supportedActions();

    /**
     * Build a complete transaction for this protocol.
     *
     * @param request contains funding UTXOs, signer, public keys, change address, and plugin params
     * @return the built transaction result with txid, raw hex, and fee
     */
    TransactionBuilderResult buildTransaction(PluginTransactionRequest request);

    /**
     * Validate that a transaction conforms to the protocol structure for the given action.
     *
     * @param rawTx the raw transaction bytes
     * @param action the action type (must be one of {@link #supportedActions()})
     * @return true if the transaction structure is valid for the action
     */
    boolean validateTransactionStructure(byte[] rawTx, String action);
}
