package org.twostack.libspiffy4j.plugin;

import java.util.Map;

/**
 * Specification for building a locking script via a {@link ScriptPlugin}.
 *
 * @param pluginId the plugin that handles this script type
 * @param scriptType the specific script type within the plugin
 * @param satoshis the output value in satoshis
 * @param params plugin-specific parameters (e.g., tokenId, ownerAddress)
 */
public record PluginLockSpec(
        String pluginId,
        String scriptType,
        long satoshis,
        Map<String, Object> params
) {
    public PluginLockSpec {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId must not be null or blank");
        }
        if (scriptType == null || scriptType.isBlank()) {
            throw new IllegalArgumentException("scriptType must not be null or blank");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
