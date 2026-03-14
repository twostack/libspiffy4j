package org.twostack.libspiffy4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import org.twostack.libspiffy4j.config.ActorSystemFactory;

import javax.sql.DataSource;

/**
 * Builder for {@link LibSpiffy4j} instances.
 */
public final class LibSpiffy4jBuilder {

    private DataSource dataSource;
    private ObjectMapper objectMapper;
    private Object meterRegistry;
    Config configOverride; // package-private for testing

    LibSpiffy4jBuilder() {}

    public LibSpiffy4jBuilder dataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        return this;
    }

    public LibSpiffy4jBuilder objectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        return this;
    }

    /**
     * @param meterRegistry a {@code io.micrometer.core.instrument.MeterRegistry} instance
     */
    public LibSpiffy4jBuilder meterRegistry(Object meterRegistry) {
        this.meterRegistry = meterRegistry;
        return this;
    }

    public LibSpiffy4jBuilder configOverride(Config config) {
        this.configOverride = config;
        return this;
    }

    public LibSpiffy4j build() {
        if (dataSource == null) {
            throw new IllegalStateException("DataSource is required");
        }
        String systemName = LibSpiffy4j.nextSystemName();
        var system = ActorSystemFactory.create(
                systemName, dataSource, objectMapper, meterRegistry, configOverride);
        return new LibSpiffy4j(system);
    }
}
