package org.twostack.libspiffy4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import org.twostack.libspiffy4j.config.ActorSystemFactory;
import org.twostack.libspiffy4j.projection.InvoiceProjectionSetup;
import org.twostack.libspiffy4j.projection.WalletProjectionSetup;
import org.twostack.libspiffy4j.service.CryptoService;
import org.twostack.libspiffy4j.service.EncryptionService;
import org.twostack.libspiffy4j.service.MultisigTransactionService;
import org.twostack.libspiffy4j.service.TransactionBuildService;
import org.twostack.libspiffy4j.service.UtxoSplitService;
import org.twostack.libspiffy4j.storage.postgres.SecureStorage;

import javax.sql.DataSource;

/**
 * Builder for {@link LibSpiffy4j} instances.
 */
public final class LibSpiffy4jBuilder {

    private DataSource dataSource;
    private ObjectMapper objectMapper;
    private Object meterRegistry;
    private byte[] encryptionMasterKey;
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

    /**
     * Sets the 32-byte master key for encryption. If not provided,
     * {@link LibSpiffy4j#encryptionService()} will return {@code null}.
     */
    public LibSpiffy4jBuilder encryptionMasterKey(byte[] key) {
        this.encryptionMasterKey = key;
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

        CryptoService cryptoService = new CryptoService();
        EncryptionService encryptionService = encryptionMasterKey != null
                ? new EncryptionService(encryptionMasterKey)
                : null;
        SecureStorage secureStorage = new SecureStorage();

        TransactionBuildService transactionBuildService = new TransactionBuildService(cryptoService);
        MultisigTransactionService multisigTransactionService = new MultisigTransactionService(transactionBuildService);
        UtxoSplitService utxoSplitService = new UtxoSplitService();

        return new LibSpiffy4j(system, cryptoService, encryptionService, secureStorage,
                transactionBuildService, multisigTransactionService, utxoSplitService);
    }
}
