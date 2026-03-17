package org.twostack.libspiffy4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.twostack.libspiffy4j.config.ActorSystemFactory;
import org.twostack.libspiffy4j.coordinator.CoordinatorCommand;
import org.twostack.libspiffy4j.coordinator.WalletCoordinator;
import org.twostack.libspiffy4j.plugin.PluginRegistry;
import org.twostack.libspiffy4j.plugin.ScriptPlugin;
import org.twostack.libspiffy4j.projection.InvoiceProjectionSetup;
import org.twostack.libspiffy4j.projection.WalletProjectionSetup;
import org.twostack.libspiffy4j.service.CryptoService;
import org.twostack.libspiffy4j.service.EncryptionService;
import org.twostack.libspiffy4j.service.MultisigTransactionService;
import org.twostack.libspiffy4j.service.TransactionBuildService;
import org.twostack.libspiffy4j.service.UtxoSplitService;
import org.twostack.libspiffy4j.storage.postgres.SecureStorage;
import org.twostack.libspiffy4j.storage.postgres.WalletReadModelStorage;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Builder for {@link LibSpiffy4j} instances.
 */
public final class LibSpiffy4jBuilder {

    private DataSource dataSource;
    private ObjectMapper objectMapper;
    private Object meterRegistry;
    private byte[] encryptionMasterKey;
    Config configOverride; // package-private for testing
    private final List<ScriptPlugin> plugins = new ArrayList<>();
    private boolean loadPluginsFromServiceLoader = false;

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

    /**
     * Sets the 32-byte master key for encryption. If not provided,
     * {@link LibSpiffy4j#encryptionService()} will return {@code null}.
     */
    public LibSpiffy4jBuilder encryptionMasterKey(byte[] key) {
        this.encryptionMasterKey = key;
        return this;
    }

    /**
     * Register a {@link ScriptPlugin} to be available at runtime.
     * Plugins are registered in order; duplicate pluginIds will throw at build time.
     */
    public LibSpiffy4jBuilder registerPlugin(ScriptPlugin plugin) {
        this.plugins.add(plugin);
        return this;
    }

    /**
     * Enable automatic plugin discovery via {@link java.util.ServiceLoader}.
     * Plugins listed in META-INF/services will be loaded during {@link #build()}.
     */
    public LibSpiffy4jBuilder enableServiceLoaderPlugins() {
        this.loadPluginsFromServiceLoader = true;
        return this;
    }

    public LibSpiffy4j build() {
        if (dataSource == null) {
            throw new IllegalStateException("DataSource is required");
        }
        String systemName = LibSpiffy4j.nextSystemName();
        var system = ActorSystemFactory.create(
                systemName, dataSource, objectMapper, meterRegistry, configOverride);
        WalletProjectionSetup.init(system);
        InvoiceProjectionSetup.init(system);

        // Plugin registry
        PluginRegistry pluginRegistry = new PluginRegistry();
        if (loadPluginsFromServiceLoader) {
            pluginRegistry.loadFromServiceLoader();
        }
        plugins.forEach(pluginRegistry::register);

        CryptoService cryptoService = new CryptoService();
        EncryptionService encryptionService = encryptionMasterKey != null
                ? new EncryptionService(encryptionMasterKey)
                : null;
        SecureStorage secureStorage = new SecureStorage();

        TransactionBuildService transactionBuildService = new TransactionBuildService(cryptoService, pluginRegistry);
        MultisigTransactionService multisigTransactionService = new MultisigTransactionService(transactionBuildService);
        UtxoSplitService utxoSplitService = new UtxoSplitService();

        // Spawn coordinator
        WalletReadModelStorage readModelStorage = new WalletReadModelStorage();
        ClusterSharding sharding = ClusterSharding.get(system);
        ActorRef<CoordinatorCommand> coordinator = system.systemActorOf(
                WalletCoordinator.create(sharding, pluginRegistry, readModelStorage, dataSource,
                        cryptoService, secureStorage, encryptionService, transactionBuildService),
                "wallet-coordinator", Props.empty());

        return new LibSpiffy4j(system, cryptoService, encryptionService, secureStorage,
                transactionBuildService, multisigTransactionService, utxoSplitService,
                pluginRegistry, coordinator);
    }
}
