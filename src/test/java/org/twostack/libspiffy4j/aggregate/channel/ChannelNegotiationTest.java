package org.twostack.libspiffy4j.aggregate.channel;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.JoinSeedNodes;
import org.apache.pekko.persistence.testkit.PersistenceTestKitPlugin$;
import org.apache.pekko.persistence.testkit.PersistenceTestKitSnapshotPlugin$;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.twostack.libspiffy4j.model.PaymentChannelRole;
import org.twostack.libspiffy4j.model.PaymentChannelState;

import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelNegotiationTest {

    private static ActorTestKit testKit;
    private static ClusterSharding sharding;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

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

        testKit = ActorTestKit.create("channel-negotiation-test", config);

        Cluster cluster = Cluster.get(testKit.system());
        cluster.manager().tell(new JoinSeedNodes(
                Collections.singletonList(cluster.selfMember().address())));

        Thread.sleep(2000);

        sharding = ClusterSharding.get(testKit.system());
        sharding.init(Entity.of(ChannelAggregate.ENTITY_TYPE_KEY, ctx ->
                ChannelAggregate.create(PersistenceId.of(
                        ChannelAggregate.ENTITY_TYPE_KEY.name(), ctx.getEntityId()))));
    }

    @AfterAll
    static void teardown() {
        if (testKit != null) {
            testKit.shutdownTestKit();
        }
    }

    private EntityRef<ChannelCommand> channelRef(String id) {
        return sharding.entityRefFor(ChannelAggregate.ENTITY_TYPE_KEY, id);
    }

    private ChannelReply.Success requestChannel(String channelId) {
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef(channelId).tell(new ChannelCommand.RequestChannelCommand(
                channelId, "wallet-1", "client-peer", "clientPubHex", "clientAddrB58",
                100000, 1700000000L, "test-context", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Success.class);
        return (ChannelReply.Success) reply;
    }

    @Test
    void requestChannel_succeeds() {
        ChannelReply.Success success = requestChannel("ch-neg-1");
        ChannelState state = success.state();
        assertThat(state.isCreated()).isTrue();
        assertThat(state.getChannelId()).isEqualTo("ch-neg-1");
        assertThat(state.getRole()).isEqualTo(PaymentChannelRole.CLIENT);
        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.NEGOTIATING);
        assertThat(state.getClientBalanceSats()).isEqualTo(100000);
        assertThat(state.getServerBalanceSats()).isEqualTo(0);
    }

    @Test
    void acceptChannel_succeeds() {
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-neg-2").tell(new ChannelCommand.AcceptChannelCommand(
                "ch-neg-2", "wallet-2", "client-peer", "server-peer",
                "clientPubHex", "serverPubHex", "clientAddrB58", "serverAddrB58",
                200000, 1700000000L, "ctx", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Success.class);
        ChannelState state = ((ChannelReply.Success) reply).state();
        assertThat(state.getRole()).isEqualTo(PaymentChannelRole.SERVER);
        assertThat(state.getServerPeerId()).isEqualTo("server-peer");
        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.NEGOTIATING);
    }

    @Test
    void rejectChannel_succeeds() {
        requestChannel("ch-neg-3");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-neg-3").tell(new ChannelCommand.RejectChannelCommand(
                "ch-neg-3", "Not interested", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Success.class);
        ChannelState state = ((ChannelReply.Success) reply).state();
        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.FAILED);
        assertThat(state.getErrorMessage()).isEqualTo("Not interested");
    }

    @Test
    void duplicateCreate_rejected() {
        requestChannel("ch-neg-4");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-neg-4").tell(new ChannelCommand.RequestChannelCommand(
                "ch-neg-4", "wallet-1", "client-peer", "clientPubHex", "clientAddrB58",
                100000, 1700000000L, "ctx", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).isEqualTo("Channel already exists");
    }

    @Test
    void serverAcceptance_succeeds() {
        requestChannel("ch-neg-5");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-neg-5").tell(new ChannelCommand.RecordServerAcceptanceCommand(
                "ch-neg-5", "server-peer", "serverPubHex", "serverAddrB58", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Success.class);
        ChannelState state = ((ChannelReply.Success) reply).state();
        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.FUNDING);
        assertThat(state.getServerPeerId()).isEqualTo("server-peer");
    }

    @Test
    void commandBeforeCreate_rejected() {
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-neg-uncreated").tell(new ChannelCommand.RejectChannelCommand(
                "ch-neg-uncreated", "test", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).isEqualTo("Channel not created");
    }
}
