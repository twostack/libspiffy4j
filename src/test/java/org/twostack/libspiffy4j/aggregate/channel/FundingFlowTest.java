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

class FundingFlowTest {

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

        testKit = ActorTestKit.create("channel-funding-test", config);

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

    private void moveToFunding(String channelId) {
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef(channelId).tell(new ChannelCommand.RequestChannelCommand(
                channelId, "wallet-1", "client-peer", "clientPubHex", "clientAddrB58",
                100000, 1700000000L, "ctx", probe.ref()));
        probe.receiveMessage(TIMEOUT);
        channelRef(channelId).tell(new ChannelCommand.RecordServerAcceptanceCommand(
                channelId, "server-peer", "serverPubHex", "serverAddrB58", probe.ref()));
        probe.receiveMessage(TIMEOUT);
    }

    @Test
    void refundBuilt_succeeds() {
        moveToFunding("ch-fund-1");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-fund-1").tell(new ChannelCommand.RequestRefundSignatureCommand(
                "ch-fund-1", "refundTxHex", "clientSigHex", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Success.class);
        ChannelState state = ((ChannelReply.Success) reply).state();
        assertThat(state.getRefundTxHex()).isEqualTo("refundTxHex");
        assertThat(state.getRefundClientSigHex()).isEqualTo("clientSigHex");
        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.FUNDING);
    }

    @Test
    void refundCountersigned_succeeds() {
        moveToFunding("ch-fund-2");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();

        channelRef("ch-fund-2").tell(new ChannelCommand.RequestRefundSignatureCommand(
                "ch-fund-2", "refundTxHex", "clientSigHex", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        channelRef("ch-fund-2").tell(new ChannelCommand.ProvideRefundSignatureCommand(
                "ch-fund-2", "serverSigHex", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Success.class);
        ChannelState state = ((ChannelReply.Success) reply).state();
        assertThat(state.getRefundServerSigHex()).isEqualTo("serverSigHex");
    }

    @Test
    void openChannel_succeeds_withBothSigs() {
        moveToFunding("ch-fund-3");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();

        channelRef("ch-fund-3").tell(new ChannelCommand.RequestRefundSignatureCommand(
                "ch-fund-3", "refundTxHex", "clientSigHex", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        channelRef("ch-fund-3").tell(new ChannelCommand.ProvideRefundSignatureCommand(
                "ch-fund-3", "serverSigHex", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        channelRef("ch-fund-3").tell(new ChannelCommand.OpenChannelCommand(
                "ch-fund-3", "fundingTxId", "fundingTxHex", 0,
                List.of("ancestor1", "ancestor2"), probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Success.class);
        ChannelState state = ((ChannelReply.Success) reply).state();
        assertThat(state.getChannelState()).isEqualTo(PaymentChannelState.OPEN);
        assertThat(state.getFundingTxId()).isEqualTo("fundingTxId");
    }

    @Test
    void openChannel_failsWithoutBothSigs() {
        moveToFunding("ch-fund-4");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();

        // Only client sig, no server sig
        channelRef("ch-fund-4").tell(new ChannelCommand.RequestRefundSignatureCommand(
                "ch-fund-4", "refundTxHex", "clientSigHex", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        channelRef("ch-fund-4").tell(new ChannelCommand.OpenChannelCommand(
                "ch-fund-4", "fundingTxId", "fundingTxHex", 0,
                List.of(), probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).contains("Both refund signatures");
    }

    @Test
    void provideRefundSig_failsWithoutRefundTx() {
        moveToFunding("ch-fund-5");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();

        channelRef("ch-fund-5").tell(new ChannelCommand.ProvideRefundSignatureCommand(
                "ch-fund-5", "serverSigHex", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).contains("not yet built");
    }
}
