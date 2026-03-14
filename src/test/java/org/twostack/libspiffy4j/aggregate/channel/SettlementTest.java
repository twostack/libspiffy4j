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
import org.twostack.libspiffy4j.model.PaymentChannelState;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementTest {

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

        testKit = ActorTestKit.create("channel-settlement-test", config);

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

    private void moveToOpen(String channelId) {
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef(channelId).tell(new ChannelCommand.RequestChannelCommand(
                channelId, "wallet-1", "client-peer", "clientPubHex", "clientAddrB58",
                100000, 1700000000L, "ctx", probe.ref()));
        probe.receiveMessage(TIMEOUT);
        channelRef(channelId).tell(new ChannelCommand.RecordServerAcceptanceCommand(
                channelId, "server-peer", "serverPubHex", "serverAddrB58", probe.ref()));
        probe.receiveMessage(TIMEOUT);
        channelRef(channelId).tell(new ChannelCommand.RequestRefundSignatureCommand(
                channelId, "refundTxHex", "clientSigHex", probe.ref()));
        probe.receiveMessage(TIMEOUT);
        channelRef(channelId).tell(new ChannelCommand.ProvideRefundSignatureCommand(
                channelId, "serverSigHex", probe.ref()));
        probe.receiveMessage(TIMEOUT);
        channelRef(channelId).tell(new ChannelCommand.OpenChannelCommand(
                channelId, "fundingTxId", "fundingTxHex", 0, List.of(), probe.ref()));
        probe.receiveMessage(TIMEOUT);
    }

    @Test
    void closeChannel_transitionsToClosing() {
        moveToOpen("ch-settle-1");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-settle-1").tell(new ChannelCommand.CloseChannelCommand(
                "ch-settle-1", "settlementTxHex", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Success.class);
        ChannelState state = ((ChannelReply.Success) reply).state();
        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.CLOSING);
    }

    @Test
    void finalizeClose_transitionsToClosed() {
        moveToOpen("ch-settle-2");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();

        channelRef("ch-settle-2").tell(new ChannelCommand.CloseChannelCommand(
                "ch-settle-2", "settlementTxHex", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        channelRef("ch-settle-2").tell(new ChannelCommand.FinalizeCloseCommand(
                "ch-settle-2", "settlementTxId", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Success.class);
        ChannelState state = ((ChannelReply.Success) reply).state();
        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.CLOSED);
        assertThat(state.getSettlementTxId()).isEqualTo("settlementTxId");
        assertThat(state.getClosedAt()).isNotNull();
    }

    @Test
    void claimRefund_transitionsToExpired() {
        moveToOpen("ch-settle-3");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-settle-3").tell(new ChannelCommand.ClaimRefundCommand(
                "ch-settle-3", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Success.class);
        ChannelState state = ((ChannelReply.Success) reply).state();
        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.EXPIRED);
        assertThat(state.getClosedAt()).isNotNull();
    }

    @Test
    void finalizeClose_failsWhenNotClosing() {
        moveToOpen("ch-settle-4");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-settle-4").tell(new ChannelCommand.FinalizeCloseCommand(
                "ch-settle-4", "txId", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).contains("CLOSING");
    }
}
