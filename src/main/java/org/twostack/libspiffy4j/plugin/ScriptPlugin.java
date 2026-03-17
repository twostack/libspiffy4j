package org.twostack.libspiffy4j.plugin;

import java.util.List;
import java.util.Map;

/**
 * Base interface for all script plugins. Plugins identify, parse, and build
 * locking/unlocking scripts for custom protocol outputs (e.g., tokens, NFTs).
 *
 * <p>Implementations are registered at runtime via {@link PluginRegistry},
 * maintaining zero compile-time coupling between libspiffy4j and token libraries.
 */
public interface ScriptPlugin {

    /** Unique identifier for this plugin (e.g., "tstoken", "ordinals"). */
    String pluginId();

    /** Human-readable name for display in UIs. */
    String displayName();

    /** Script type identifiers this plugin handles (e.g., ["pp1_nft", "pp1_ft"]). */
    List<String> scriptTypes();

    /**
     * Identify whether a script belongs to this plugin.
     *
     * @param scriptPubKey the raw scriptPubKey bytes
     * @return the script type string from {@link #scriptTypes()}, or {@code null} if not recognized
     */
    String identifyScript(byte[] scriptPubKey);

    /**
     * Extract plugin-specific metadata from a recognized script.
     *
     * @param scriptPubKey the raw scriptPubKey bytes
     * @return metadata map (e.g., tokenId, ownerAddress, amount), or empty map if not recognized
     */
    Map<String, Object> extractMetadata(byte[] scriptPubKey);

    /**
     * Build a locking script for a plugin-managed output.
     *
     * @param spec the lock specification with plugin-specific parameters
     * @return raw locking script bytes
     */
    byte[] createLockingScript(PluginLockSpec spec);

    /**
     * Build an unlocking script to spend a plugin-managed UTXO.
     *
     * @param spec the unlock specification with plugin-specific parameters
     * @return raw unlocking script bytes
     */
    byte[] createUnlockingScript(PluginUnlockSpec spec);
}
