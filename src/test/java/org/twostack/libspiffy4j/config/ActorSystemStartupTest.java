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
import org.apache.pekko.persistence.testkit.PersistenceTestKitPlugin$;
import org.apache.pekko.persistence.testkit.PersistenceTestKitSnapshotPlugin$;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class ActorSystemStartupTest {

    private static ActorTestKit testKit;

    @BeforeAll
    static void setup() throws InterruptedException {
        Config testkitConfig = PersistenceTestKitPlugin$.MODULE$.config()
                .withFallback(PersistenceTestKitSnapshotPlugin$.MODULE$.config());

        Config config = testkitConfig
                .withFallback(ConfigFactory.parseString("""
                        pekko.actor.provider = "cluster"
                        pekko.remote.artery.canonical.hostname = "127.0.0.1"
                        pekko.remote.artery.canonical.port = 0
                        pekko.cluster.downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
                        pekko.actor.allow-java-serialization = on
                        """))
                .withFallback(ConfigFactory.load());

        testKit = ActorTestKit.create("startup-test", config);

        Cluster cluster = Cluster.get(testKit.system());
        cluster.manager().tell(new JoinSeedNodes(
                Collections.singletonList(cluster.selfMember().address())));

        // Wait for cluster to form
        Thread.sleep(2000);
    }

    @AfterAll
    static void teardown() {
        if (testKit != null) {
            testKit.shutdownTestKit();
        }
    }

    @Test
    void smokeAggregatePingPong() {
        EntityTypeKey<SmokeAggregate.SmokeCommand> typeKey =
                EntityTypeKey.create(SmokeAggregate.SmokeCommand.class, "SmokeAggregate");

        ClusterSharding sharding = ClusterSharding.get(testKit.system());
        sharding.init(Entity.of(typeKey, ctx ->
                SmokeAggregate.create(PersistenceId.of(typeKey.name(), ctx.getEntityId()))));

        var entityRef = sharding.entityRefFor(typeKey, "smoke-1");

        TestProbe<String> probe = testKit.createTestProbe();
        entityRef.tell(new SmokeAggregate.Ping(probe.ref()));
        String reply1 = probe.receiveMessage(Duration.ofSeconds(10));
        assertThat(reply1).isEqualTo("Pong:1");

        // Second ping — should increment to 2
        entityRef.tell(new SmokeAggregate.Ping(probe.ref()));
        String reply2 = probe.receiveMessage(Duration.ofSeconds(10));
        assertThat(reply2).isEqualTo("Pong:2");
    }
}
