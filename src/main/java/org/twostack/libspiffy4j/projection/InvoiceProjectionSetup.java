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
import org.twostack.libspiffy4j.aggregate.invoice.InvoiceEvent;
import org.twostack.libspiffy4j.config.DataSourceRegistry;
import org.twostack.libspiffy4j.storage.postgres.InvoiceReadModelStorage;

public final class InvoiceProjectionSetup {

    private InvoiceProjectionSetup() {}

    public static void init(ActorSystem<?> system) {
        InvoiceReadModelStorage storage = new InvoiceReadModelStorage();
        String systemName = system.name();

        ShardedDaemonProcess.get(system).init(
                ProjectionBehavior.Command.class,
                "InvoiceProjection",
                1,
                index -> {
                    String tag = "invoice";
                    SourceProvider<Offset, EventEnvelope<InvoiceEvent>> sourceProvider =
                            EventSourcedProvider.eventsByTag(system, "jdbc-read-journal", tag);

                    return ProjectionBehavior.create(
                            JdbcProjection.exactlyOnce(
                                    ProjectionId.of("InvoiceProjection", tag),
                                    sourceProvider,
                                    () -> {
                                        javax.sql.DataSource ds = DataSourceRegistry.get(systemName);
                                        if (ds == null) {
                                            throw new IllegalStateException(
                                                    "DataSource not found for system: " + systemName);
                                        }
                                        return new SpiffyJdbcSession(ds);
                                    },
                                    () -> new InvoiceProjectionHandler(storage),
                                    system
                            )
                    );
                },
                ProjectionBehavior.stopMessage()
        );
    }
}
