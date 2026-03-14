package org.twostack.libspiffy4j.config;

import javax.sql.DataSource;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static registry of DataSources keyed by ActorSystem name.
 * Supports multiple LibSpiffy4j instances in the same JVM.
 */
public final class DataSourceRegistry {

    private static final ConcurrentHashMap<String, DataSource> REGISTRY = new ConcurrentHashMap<>();

    private DataSourceRegistry() {}

    public static void register(String systemName, DataSource dataSource) {
        DataSource prev = REGISTRY.putIfAbsent(systemName, dataSource);
        if (prev != null) {
            throw new IllegalStateException(
                    "DataSource already registered for system: " + systemName);
        }
    }

    public static DataSource get(String systemName) {
        return REGISTRY.get(systemName);
    }

    public static void remove(String systemName) {
        REGISTRY.remove(systemName);
    }
}
