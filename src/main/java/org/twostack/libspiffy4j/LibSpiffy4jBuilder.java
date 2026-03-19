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
import org.twostack.libspiffy4j.model.CdnHeaderSyncConfig;
import org.twostack.libspiffy4j.service.CdnHeaderSyncService;
import org.twostack.libspiffy4j.service.ArcService;
import org.twostack.libspiffy4j.service.CryptoService;
import org.twostack.libspiffy4j.service.EncryptionService;
import org.twostack.libspiffy4j.service.MultisigTransactionService;
import org.twostack.libspiffy4j.service.TransactionBuildService;
import org.twostack.libspiffy4j.service.UtxoSplitService;
import org.twostack.libspiffy4j.spv.BlockHeaderChain;
import org.twostack.libspiffy4j.spv.BlockHeaderStore;
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
    private ArcService arcService;
    private final List<ScriptPlugin> plugins = new ArrayList<>();
    private boolean loadPluginsFromServiceLoader = false;
    private CdnHeaderSyncConfig cdnHeaderSyncConfig;
    private BlockHeaderStore headerStore;

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
     * Sets the {@link ArcService} for transaction broadcast. When provided,
     * the coordinator will broadcast transactions after building and manage
     * UTXO lifecycle (reserve, spend, release) internally.
     */
    public LibSpiffy4jBuilder arcService(ArcService arcService) {
        this.arcService = arcService;
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

    /**
     * Configure CDN-based block header sync. If provided, the builder will
     * attempt to sync headers from the CDN during build. If unavailable,
     * the header store remains empty for the host app to populate via P2P.
     */
    public LibSpiffy4jBuilder cdnHeaderSync(CdnHeaderSyncConfig config) {
        this.cdnHeaderSyncConfig = config;
        return this;
    }

    /**
     * Provide a custom BlockHeaderStore implementation. If not set,
     * a default in-memory {@link BlockHeaderChain} is used.
     */
    public LibSpiffy4jBuilder headerStore(BlockHeaderStore store) {
        this.headerStore = store;
        return this;
    }

    public LibSpiffy4j build() {
        if (dataSource == null) {
            throw new IllegalStateException("DataSource is required");
        }
        // Plugin registry (must be created before projection setup)
        PluginRegistry pluginRegistry = new PluginRegistry();
        if (loadPluginsFromServiceLoader) {
            pluginRegistry.loadFromServiceLoader();
        }
        plugins.forEach(pluginRegistry::register);

        String systemName = LibSpiffy4j.nextSystemName();
        var system = ActorSystemFactory.create(
                systemName, dataSource, objectMapper, meterRegistry, configOverride);
        WalletProjectionSetup.init(system, pluginRegistry);
        InvoiceProjectionSetup.init(system);

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
                        cryptoService, secureStorage, encryptionService, transactionBuildService,
                        arcService),
                "wallet-coordinator", Props.empty());

        // Block header store
        BlockHeaderStore store = this.headerStore != null ? this.headerStore : new BlockHeaderChain();
        if (cdnHeaderSyncConfig != null) {
            try {
                new CdnHeaderSyncService(cdnHeaderSyncConfig, store).synchronize();
            } catch (Exception e) {
                // CDN unavailable — header store remains empty for P2P population
                java.util.logging.Logger.getLogger(LibSpiffy4jBuilder.class.getName())
                        .warning("CDN header sync failed (non-fatal): " + e.getMessage());
            }
        }

        return new LibSpiffy4j(system, cryptoService, encryptionService, secureStorage,
                transactionBuildService, multisigTransactionService, utxoSplitService,
                pluginRegistry, coordinator, store);
    }
}
