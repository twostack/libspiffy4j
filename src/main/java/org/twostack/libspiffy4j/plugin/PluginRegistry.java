package org.twostack.libspiffy4j.plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for {@link ScriptPlugin} instances.
 * Plugins register at runtime, maintaining zero compile-time coupling
 * between libspiffy4j and external token/protocol libraries.
 */
public final class PluginRegistry {

    private final Map<String, ScriptPlugin> plugins = new ConcurrentHashMap<>();

    /**
     * Register a plugin. Throws if a plugin with the same ID is already registered.
     */
    public void register(ScriptPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        String id = plugin.pluginId();
        if (plugins.putIfAbsent(id, plugin) != null) {
            throw new IllegalStateException("Plugin already registered: " + id);
        }
    }

    /** Unregister a plugin by its ID. No-op if not registered. */
    public void unregister(String pluginId) {
        plugins.remove(pluginId);
    }

    /** Get a plugin by ID. */
    public Optional<ScriptPlugin> getPlugin(String pluginId) {
        return Optional.ofNullable(plugins.get(pluginId));
    }

    /** Get a plugin by ID, cast to {@link TransactionBuilderPlugin} if applicable. */
    public Optional<TransactionBuilderPlugin> getTransactionBuilderPlugin(String pluginId) {
        return getPlugin(pluginId)
                .filter(p -> p instanceof TransactionBuilderPlugin)
                .map(p -> (TransactionBuilderPlugin) p);
    }

    /** All registered plugins (snapshot). */
    public List<ScriptPlugin> allPlugins() {
        return List.copyOf(plugins.values());
    }

    /** True if any plugins are registered. */
    public boolean hasPlugins() {
        return !plugins.isEmpty();
    }

    /**
     * Try all registered plugins to identify a script.
     *
     * @param scriptPubKey the raw scriptPubKey bytes
     * @return identification result, or empty if no plugin recognizes the script
     */
    public Optional<PluginIdentification> identifyScript(byte[] scriptPubKey) {
        for (ScriptPlugin plugin : plugins.values()) {
            String scriptType = plugin.identifyScript(scriptPubKey);
            if (scriptType != null) {
                return Optional.of(new PluginIdentification(plugin.pluginId(), scriptType));
            }
        }
        return Optional.empty();
    }

    /**
     * Load plugins via {@link java.util.ServiceLoader}.
     * Call once during initialization.
     */
    public void loadFromServiceLoader() {
        ServiceLoader.load(ScriptPlugin.class).forEach(this::register);
    }

    /** Clear all registered plugins. Intended for testing only. */
    public void clear() {
        plugins.clear();
    }

    /**
     * Result of script identification.
     *
     * @param pluginId the plugin that recognized the script
     * @param scriptType the script type within that plugin
     */
    public record PluginIdentification(String pluginId, String scriptType) {}
}
