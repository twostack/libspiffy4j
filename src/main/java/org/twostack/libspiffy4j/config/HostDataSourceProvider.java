package org.twostack.libspiffy4j.config;

import com.typesafe.config.Config;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.persistence.jdbc.db.EagerSlickDatabase;
import org.apache.pekko.persistence.jdbc.db.SlickDatabase;
import org.apache.pekko.persistence.jdbc.db.SlickDatabaseProvider;
import slick.jdbc.JdbcBackend$;
import slick.jdbc.PostgresProfile$;

import javax.sql.DataSource;

/**
 * Bridges a host-provided {@link DataSource} to Slick's database abstraction
 * for pekko-persistence-jdbc.
 */
public class HostDataSourceProvider implements SlickDatabaseProvider {

    private final SlickDatabase slickDatabase;

    public HostDataSourceProvider(ActorSystem system) {
        DataSource ds = DataSourceRegistry.get(system.name());
        if (ds == null) {
            throw new IllegalStateException(
                    "No DataSource registered for ActorSystem: " + system.name());
        }
        var dbFactory = JdbcBackend$.MODULE$.Database();
        var jdbcDb = dbFactory.forDataSource(
                ds,
                scala.Option$.MODULE$.<Object>empty(),
                dbFactory.forDataSource$default$3(),
                false);
        this.slickDatabase = new EagerSlickDatabase(jdbcDb, PostgresProfile$.MODULE$);
    }

    @Override
    public SlickDatabase database(Config config) {
        return slickDatabase;
    }
}
