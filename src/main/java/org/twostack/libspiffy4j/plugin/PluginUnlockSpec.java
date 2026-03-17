package org.twostack.libspiffy4j.plugin;

import java.util.Map;

/**
 * Specification for building an unlocking script to spend a plugin-managed UTXO.
 *
 * @param pluginId the plugin that handles this script type
 * @param scriptType the specific script type within the plugin
 * @param lockingScript the locking script being spent (raw bytes)
 * @param satoshis the output value in satoshis
 * @param params plugin-specific unlock parameters (e.g., action, parentTxBytes, rabinSignatures)
 */
public record PluginUnlockSpec(
        String pluginId,
        String scriptType,
        byte[] lockingScript,
        long satoshis,
        Map<String, Object> params
) {
    public PluginUnlockSpec {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId must not be null or blank");
        }
        if (scriptType == null || scriptType.isBlank()) {
            throw new IllegalArgumentException("scriptType must not be null or blank");
        }
        if (lockingScript == null) {
            throw new IllegalArgumentException("lockingScript must not be null");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
