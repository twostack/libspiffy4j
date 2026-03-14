package org.twostack.libspiffy4j;

import org.apache.pekko.actor.typed.ActorSystem;
import org.twostack.libspiffy4j.config.DataSourceRegistry;
import org.twostack.libspiffy4j.service.CryptoService;
import org.twostack.libspiffy4j.service.EncryptionService;
import org.twostack.libspiffy4j.storage.postgres.SecureStorage;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Main entry point for the libspiffy4j library.
 * Manages the lifecycle of a Pekko ActorSystem configured for
 * event-sourced persistence with cluster sharding.
 */
public final class LibSpiffy4j implements AutoCloseable {

    private static final AtomicLong COUNTER = new AtomicLong();

    private final ActorSystem<Void> system;
    private final String systemName;
    private final CryptoService cryptoService;
    private final EncryptionService encryptionService;
    private final SecureStorage secureStorage;
    private volatile boolean closed;

    static String nextSystemName() {
        return "libspiffy4j-" + COUNTER.incrementAndGet();
    }

    LibSpiffy4j(ActorSystem<Void> system, CryptoService cryptoService,
                EncryptionService encryptionService, SecureStorage secureStorage) {
        this.system = system;
        this.systemName = system.name();
        this.cryptoService = cryptoService;
        this.encryptionService = encryptionService;
        this.secureStorage = secureStorage;
    }

    public static LibSpiffy4jBuilder builder() {
        return new LibSpiffy4jBuilder();
    }

    public ActorSystem<Void> system() {
        return system;
    }

    public CryptoService cryptoService() {
        return cryptoService;
    }

    /**
     * Returns the encryption service, or {@code null} if no master key was provided.
     */
    public EncryptionService encryptionService() {
        return encryptionService;
    }

    public SecureStorage secureStorage() {
        return secureStorage;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            system.terminate();
            try {
                system.getWhenTerminated().toCompletableFuture().get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Best-effort shutdown
            }
            DataSourceRegistry.remove(systemName);
        }
    }
}
