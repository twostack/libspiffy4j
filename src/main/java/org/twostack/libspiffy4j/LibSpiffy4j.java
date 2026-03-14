package org.twostack.libspiffy4j;

import org.apache.pekko.actor.typed.ActorSystem;
import org.twostack.libspiffy4j.config.DataSourceRegistry;

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
    private volatile boolean closed;

    static String nextSystemName() {
        return "libspiffy4j-" + COUNTER.incrementAndGet();
    }

    LibSpiffy4j(ActorSystem<Void> system) {
        this.system = system;
        this.systemName = system.name();
    }

    public static LibSpiffy4jBuilder builder() {
        return new LibSpiffy4jBuilder();
    }

    public ActorSystem<Void> system() {
        return system;
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
