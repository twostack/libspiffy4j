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

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StateMachineTest {

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

        testKit = ActorTestKit.create("channel-statemachine-test", config);

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

    private void requestChannel(String channelId) {
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef(channelId).tell(new ChannelCommand.RequestChannelCommand(
                channelId, "wallet-1", "client-peer", "clientPubHex", "clientAddrB58",
                100000, 1700000000L, "ctx", probe.ref()));
        probe.receiveMessage(TIMEOUT);
    }

    private void moveToFunding(String channelId) {
        requestChannel(channelId);
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef(channelId).tell(new ChannelCommand.RecordServerAcceptanceCommand(
                channelId, "server-peer", "serverPubHex", "serverAddrB58", probe.ref()));
        probe.receiveMessage(TIMEOUT);
    }

    private void moveToOpen(String channelId) {
        moveToFunding(channelId);
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
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

    // NEGOTIATING state: reject invalid transitions
    @Test
    void negotiating_rejectsRecordPayment() {
        requestChannel("ch-sm-1");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-sm-1").tell(new ChannelCommand.RecordPaymentCommand(
                "ch-sm-1", 10000, 90000, 10000, 1,
                "tx", "txId", "sig", "purpose", null, probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).contains("OPEN");
    }

    @Test
    void negotiating_rejectsOpenChannel() {
        requestChannel("ch-sm-2");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-sm-2").tell(new ChannelCommand.OpenChannelCommand(
                "ch-sm-2", "fundingTxId", "fundingTxHex", 0, List.of(), probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).contains("FUNDING");
    }

    @Test
    void negotiating_rejectsCloseChannel() {
        requestChannel("ch-sm-3");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-sm-3").tell(new ChannelCommand.CloseChannelCommand(
                "ch-sm-3", "settlementHex", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).contains("OPEN");
    }

    // FUNDING state: reject invalid transitions
    @Test
    void funding_rejectsRecordPayment() {
        moveToFunding("ch-sm-4");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-sm-4").tell(new ChannelCommand.RecordPaymentCommand(
                "ch-sm-4", 10000, 90000, 10000, 1,
                "tx", "txId", "sig", "purpose", null, probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).contains("OPEN");
    }

    @Test
    void funding_rejectsReject() {
        moveToFunding("ch-sm-5");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-sm-5").tell(new ChannelCommand.RejectChannelCommand(
                "ch-sm-5", "reason", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).contains("NEGOTIATING");
    }

    // OPEN state: reject invalid transitions
    @Test
    void open_rejectsRefundBuild() {
        moveToOpen("ch-sm-6");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-sm-6").tell(new ChannelCommand.RequestRefundSignatureCommand(
                "ch-sm-6", "refundTx", "sig", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).contains("FUNDING");
    }

    @Test
    void open_rejectsFinalizeClose() {
        moveToOpen("ch-sm-7");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-sm-7").tell(new ChannelCommand.FinalizeCloseCommand(
                "ch-sm-7", "txId", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).contains("CLOSING");
    }

    // CLOSING state: reject invalid transitions
    @Test
    void closing_rejectsRecordPayment() {
        moveToOpen("ch-sm-8");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-sm-8").tell(new ChannelCommand.CloseChannelCommand(
                "ch-sm-8", "settlementHex", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        channelRef("ch-sm-8").tell(new ChannelCommand.RecordPaymentCommand(
                "ch-sm-8", 10000, 90000, 10000, 1,
                "tx", "txId", "sig", "purpose", null, probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).contains("OPEN");
    }

    // CLOSED state: reject further operations
    @Test
    void closed_rejectsCloseChannel() {
        moveToOpen("ch-sm-9");
        TestProbe<ChannelReply> probe = testKit.createTestProbe();
        channelRef("ch-sm-9").tell(new ChannelCommand.CloseChannelCommand(
                "ch-sm-9", "settlementHex", probe.ref()));
        probe.receiveMessage(TIMEOUT);
        channelRef("ch-sm-9").tell(new ChannelCommand.FinalizeCloseCommand(
                "ch-sm-9", "txId", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        channelRef("ch-sm-9").tell(new ChannelCommand.CloseChannelCommand(
                "ch-sm-9", "settlementHex2", probe.ref()));
        ChannelReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(ChannelReply.Failure.class);
        assertThat(((ChannelReply.Failure) reply).reason()).contains("OPEN");
    }
}
