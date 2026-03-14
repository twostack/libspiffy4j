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
import org.twostack.libspiffy4j.aggregate.channel.ChannelEvent;
import org.twostack.libspiffy4j.config.DataSourceRegistry;
import org.twostack.libspiffy4j.storage.postgres.ChannelReadModelStorage;

public final class ChannelProjectionSetup {

    private ChannelProjectionSetup() {}

    public static void init(ActorSystem<?> system) {
        ChannelReadModelStorage storage = new ChannelReadModelStorage();
        String systemName = system.name();

        ShardedDaemonProcess.get(system).init(
                ProjectionBehavior.Command.class,
                "ChannelProjection",
                1,
                index -> {
                    String tag = "channel";
                    SourceProvider<Offset, EventEnvelope<ChannelEvent>> sourceProvider =
                            EventSourcedProvider.eventsByTag(system, "jdbc-read-journal", tag);

                    return ProjectionBehavior.create(
                            JdbcProjection.exactlyOnce(
                                    ProjectionId.of("ChannelProjection", tag),
                                    sourceProvider,
                                    () -> {
                                        javax.sql.DataSource ds = DataSourceRegistry.get(systemName);
                                        if (ds == null) {
                                            throw new IllegalStateException(
                                                    "DataSource not found for system: " + systemName);
                                        }
                                        return new SpiffyJdbcSession(ds);
                                    },
                                    () -> new ChannelProjectionHandler(storage),
                                    system
                            )
                    );
                },
                ProjectionBehavior.stopMessage()
        );
    }
}
