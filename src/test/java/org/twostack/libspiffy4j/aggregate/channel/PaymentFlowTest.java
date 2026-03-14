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

class PaymentFlowTest {

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

        testKit = ActorTestKit.create("channel-payment-test", config);

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
    void recordPayment_succeeds() {
        moveToOpen("ch-pay-1");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-pay-1").tell(new ChannelCommand.RecordPaymentCommand(
                "ch-pay-1", 10000, 90000, 10000, 1,
                "payTxHex1", "payTxId1", "clientSig", "payment", null, probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Success.class);
        ChannelState state = ((ChannelReply.Success) reply).state();
        assertThat(state.getClientBalanceSats()).isEqualTo(90000);
        assertThat(state.getServerBalanceSats()).isEqualTo(10000);
        assertThat(state.getLatestSequenceNumber()).isEqualTo(1);
    }

    @Test
    void sequentialPayments_incrementBalances() {
        moveToOpen("ch-pay-2");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();

        // Payment 1
        channelRef("ch-pay-2").tell(new ChannelCommand.RecordPaymentCommand(
                "ch-pay-2", 10000, 90000, 10000, 1,
                "payTxHex1", "payTxId1", "clientSig1", "payment1", null, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        // Payment 2
        channelRef("ch-pay-2").tell(new ChannelCommand.RecordPaymentCommand(
                "ch-pay-2", 20000, 70000, 30000, 2,
                "payTxHex2", "payTxId2", "clientSig2", "payment2", null, probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Success.class);
        ChannelState state = ((ChannelReply.Success) reply).state();
        assertThat(state.getClientBalanceSats()).isEqualTo(70000);
        assertThat(state.getServerBalanceSats()).isEqualTo(30000);
        assertThat(state.getLatestSequenceNumber()).isEqualTo(2);
    }

    @Test
    void balanceConservation_violated() {
        moveToOpen("ch-pay-3");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-pay-3").tell(new ChannelCommand.RecordPaymentCommand(
                "ch-pay-3", 10000, 90000, 20000, 1,
                "payTxHex", "payTxId", "clientSig", "payment", null, probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).contains("Balance conservation violated");
    }

    @Test
    void sequenceMonotonicity_violated() {
        moveToOpen("ch-pay-4");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();

        // First payment
        channelRef("ch-pay-4").tell(new ChannelCommand.RecordPaymentCommand(
                "ch-pay-4", 10000, 90000, 10000, 5,
                "payTxHex1", "payTxId1", "clientSig1", "payment1", null, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        // Second payment with lower sequence
        channelRef("ch-pay-4").tell(new ChannelCommand.RecordPaymentCommand(
                "ch-pay-4", 5000, 85000, 15000, 3,
                "payTxHex2", "payTxId2", "clientSig2", "payment2", null, probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).contains("Sequence number must be greater");
    }

    @Test
    void acknowledgePayment_succeeds() {
        moveToOpen("ch-pay-5");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();

        channelRef("ch-pay-5").tell(new ChannelCommand.RecordPaymentCommand(
                "ch-pay-5", 10000, 90000, 10000, 1,
                "payTxHex", "payTxId", "clientSig", "payment", null, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        channelRef("ch-pay-5").tell(new ChannelCommand.AcknowledgePaymentCommand(
                "ch-pay-5", "fullySignedPayTxHex", "serverSig", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Success.class);
        ChannelState state = ((ChannelReply.Success) reply).state();
        assertThat(state.getLatestPaymentTxHex()).isEqualTo("fullySignedPayTxHex");
    }
}
