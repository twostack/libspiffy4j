package org.twostack.libspiffy4j.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.JoinSeedNodes;

import javax.sql.DataSource;
import java.util.Collections;

/**
 * Creates and configures a Pekko ActorSystem for LibSpiffy4j.
 */
public final class ActorSystemFactory {

    private ActorSystemFactory() {}

    public static ActorSystem<Void> create(
            String systemName,
            DataSource dataSource,
            ObjectMapper objectMapper,
            Object meterRegistry,
            Config configOverride) {

        // Register DataSource BEFORE creating the system so HostDataSourceProvider can find it
        DataSourceRegistry.register(systemName, dataSource);

        try {
            Config config = configOverride != null
                    ? configOverride.withFallback(ConfigFactory.load())
                    : ConfigFactory.load();

            ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), systemName, config);

            // Programmatic cluster self-join (single-node mode)
            Cluster cluster = Cluster.get(system);
            cluster.manager().tell(
                    new JoinSeedNodes(Collections.singletonList(cluster.selfMember().address())));

            return system;
        } catch (Exception e) {
            // Clean up registry on failure
            DataSourceRegistry.remove(systemName);
            throw e;
        }
    }
}
