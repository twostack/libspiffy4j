package org.twostack.libspiffy4j.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.JoinSeedNodes;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.twostack.libspiffy4j.LibSpiffy4j;
import org.twostack.libspiffy4j.LibSpiffy4jBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class JdbcJournalIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("libspiffy4j_test")
                    .withUsername("test")
                    .withPassword("test");

    @Test
    void fullJdbcPersistAndRecovery() throws Exception {
        PGSimpleDataSource ds = createDataSource();
        runMigrations(ds);

        String entityId = "smoke-jdbc-1";
        EntityTypeKey<SmokeAggregate.SmokeCommand> typeKey =
                EntityTypeKey.create(SmokeAggregate.SmokeCommand.class, "SmokeAggregate");

        // --- First instance: persist one Ping ---
        Config jdbcConfig = ConfigFactory.parseString("""
                pekko.actor.provider = "cluster"
                pekko.remote.artery.canonical.hostname = "127.0.0.1"
                pekko.remote.artery.canonical.port = 0
                pekko.cluster.downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
                pekko.actor.allow-java-serialization = on
                """);

        try (LibSpiffy4j lib1 = LibSpiffy4j.builder().dataSource(ds).configOverride(jdbcConfig).build()) {
            ClusterSharding sharding = ClusterSharding.get(lib1.system());
            sharding.init(Entity.of(typeKey, ctx ->
                    SmokeAggregate.create(PersistenceId.of(typeKey.name(), ctx.getEntityId()))));

            var entityRef = sharding.entityRefFor(typeKey, entityId);
            TestProbe<String> probe = ActorTestKit.create(lib1.system()).createTestProbe();
            entityRef.tell(new SmokeAggregate.Ping(probe.ref()));
            String reply = probe.receiveMessage();
            assertThat(reply).isEqualTo("Pong:1");
        }

        // --- Second instance: should recover and continue from pingCount=1 ---
        PGSimpleDataSource ds2 = createDataSource();
        try (LibSpiffy4j lib2 = LibSpiffy4j.builder().dataSource(ds2).configOverride(jdbcConfig).build()) {
            ClusterSharding sharding = ClusterSharding.get(lib2.system());
            sharding.init(Entity.of(typeKey, ctx ->
                    SmokeAggregate.create(PersistenceId.of(typeKey.name(), ctx.getEntityId()))));

            var entityRef = sharding.entityRefFor(typeKey, entityId);
            TestProbe<String> probe = ActorTestKit.create(lib2.system()).createTestProbe();
            entityRef.tell(new SmokeAggregate.Ping(probe.ref()));
            String reply = probe.receiveMessage();
            assertThat(reply).isEqualTo("Pong:2");
        }
    }

    private PGSimpleDataSource createDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        return ds;
    }

    private void runMigrations(PGSimpleDataSource ds) throws Exception {
        String[] scripts = {
                "db/libspiffy4j/V001__create_journal.sql",
                "db/libspiffy4j/V002__create_snapshot.sql",
                "db/libspiffy4j/V003__create_projection_offset.sql"
        };
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String script : scripts) {
                String sql = new String(
                        getClass().getClassLoader().getResourceAsStream(script).readAllBytes(),
                        StandardCharsets.UTF_8);
                stmt.execute(sql);
            }
        }
    }
}
