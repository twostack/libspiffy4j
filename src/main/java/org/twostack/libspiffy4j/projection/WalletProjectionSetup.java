package org.twostack.libspiffy4j.projection;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.cluster.sharding.typed.javadsl.ShardedDaemonProcess;
import org.apache.pekko.persistence.query.Offset;
import org.apache.pekko.projection.ProjectionBehavior;
import org.apache.pekko.projection.ProjectionId;
import org.apache.pekko.projection.eventsourced.EventEnvelope;
import org.apache.pekko.projection.eventsourced.javadsl.EventSourcedProvider;
import org.apache.pekko.projection.javadsl.SourceProvider;
import org.apache.pekko.projection.jdbc.javadsl.JdbcProjection;
import org.twostack.libspiffy4j.aggregate.wallet.WalletEvent;
import org.twostack.libspiffy4j.config.DataSourceRegistry;
import org.twostack.libspiffy4j.plugin.PluginRegistry;
import org.twostack.libspiffy4j.storage.postgres.WalletReadModelStorage;

public final class WalletProjectionSetup {

    private WalletProjectionSetup() {}

    public static void init(ActorSystem<?> system, PluginRegistry pluginRegistry) {
        WalletReadModelStorage storage = new WalletReadModelStorage();
        String systemName = system.name();

        ShardedDaemonProcess.get(system).init(
                ProjectionBehavior.Command.class,
                "WalletProjection",
                1,
                index -> {
                    String tag = "wallet";
                    SourceProvider<Offset, EventEnvelope<WalletEvent>> sourceProvider =
                            EventSourcedProvider.eventsByTag(system, "jdbc-read-journal", tag);

                    return ProjectionBehavior.create(
                            JdbcProjection.exactlyOnce(
                                    ProjectionId.of("WalletProjection", tag),
                                    sourceProvider,
                                    () -> {
                                        javax.sql.DataSource ds = DataSourceRegistry.get(systemName);
                                        if (ds == null) {
                                            throw new IllegalStateException(
                                                    "DataSource not found for system: " + systemName);
                                        }
                                        return new SpiffyJdbcSession(ds);
                                    },
                                    () -> new WalletProjectionHandler(storage, pluginRegistry),
                                    system
                            )
                    );
                },
                ProjectionBehavior.stopMessage()
        );
    }
}
