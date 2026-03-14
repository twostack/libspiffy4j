package org.twostack.libspiffy4j.aggregate.invoice;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
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
import org.twostack.libspiffy4j.model.InvoiceOutputSpec;
import org.twostack.libspiffy4j.model.InvoiceStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceAggregateTest {

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

        testKit = ActorTestKit.create("invoice-test", config);

        Cluster cluster = Cluster.get(testKit.system());
        cluster.manager().tell(new JoinSeedNodes(
                Collections.singletonList(cluster.selfMember().address())));

        Thread.sleep(2000);

        sharding = ClusterSharding.get(testKit.system());
        sharding.init(Entity.of(InvoiceAggregate.ENTITY_TYPE_KEY, ctx ->
                InvoiceAggregate.create(PersistenceId.of(
                        InvoiceAggregate.ENTITY_TYPE_KEY.name(), ctx.getEntityId()))));
    }

    @AfterAll
    static void teardown() {
        if (testKit != null) {
            testKit.shutdownTestKit();
        }
    }

    private EntityRef<InvoiceCommand> invoiceRef(String id) {
        return sharding.entityRefFor(InvoiceAggregate.ENTITY_TYPE_KEY, id);
    }

    private InvoiceReply.Success createInvoice(String invoiceId) {
        return createInvoice(invoiceId, "wallet-1", List.of("addr1", "addr2"), 100000,
                List.of(new InvoiceOutputSpec.P2PKHOutputSpec("addr1", 100000, "main")),
                "Test Invoice", Instant.now().plusSeconds(3600));
    }

    private InvoiceReply.Success createInvoice(String invoiceId, String walletId,
                                                List<String> addresses, long amountSats,
                                                List<InvoiceOutputSpec> outputs,
                                                String description, Instant expiresAt) {
        TestProbe<InvoiceReply> probe = testKit.createTestProbe();
        invoiceRef(invoiceId).tell(new InvoiceCommand.CreateInvoiceCommand(
                invoiceId, walletId, addresses, amountSats, outputs,
                description, expiresAt, Map.of(), probe.ref()));
        InvoiceReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(InvoiceReply.Success.class);
        return (InvoiceReply.Success) reply;
    }

    // 1. create succeeds
    @Test
    void createInvoice_succeeds() {
        InvoiceReply.Success success = createInvoice("inv-1");
        InvoiceState state = success.state();
        assertThat(state.isCreated()).isTrue();
        assertThat(state.getInvoiceId()).isEqualTo("inv-1");
        assertThat(state.getWalletId()).isEqualTo("wallet-1");
        assertThat(state.getAmountSats()).isEqualTo(100000);
        assertThat(state.getStatus()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(state.getAddresses()).containsExactly("addr1", "addr2");
        assertThat(state.getAddressSet()).containsExactlyInAnyOrder("addr1", "addr2");
    }

    // 2. reject double create
    @Test
    void createInvoice_rejectsDoubleCreate() {
        createInvoice("inv-2");
        TestProbe<InvoiceReply> probe = testKit.createTestProbe();
        invoiceRef("inv-2").tell(new InvoiceCommand.CreateInvoiceCommand(
                "inv-2", "wallet-1", List.of("addr1"), 50000,
                List.of(), "Duplicate", Instant.now().plusSeconds(3600), Map.of(), probe.ref()));
        InvoiceReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(InvoiceReply.Failure.class);
        assertThat(((InvoiceReply.Failure) reply).reason()).isEqualTo("Invoice already exists");
    }

    // 3. command before create rejected
    @Test
    void commandBeforeCreate_rejected() {
        TestProbe<InvoiceReply> probe = testKit.createTestProbe();
        invoiceRef("inv-3-uncreated").tell(new InvoiceCommand.MarkInvoicePaidCommand(
                "inv-3-uncreated", "txid", 100000, "addr1", probe.ref()));
        InvoiceReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(InvoiceReply.Failure.class);
        assertThat(((InvoiceReply.Failure) reply).reason()).isEqualTo("Invoice not created");
    }

    // 4. pay succeeds
    @Test
    void payInvoice_succeeds() {
        createInvoice("inv-4");
        TestProbe<InvoiceReply> probe = testKit.createTestProbe();
        invoiceRef("inv-4").tell(new InvoiceCommand.MarkInvoicePaidCommand(
                "inv-4", "payment-txid-1", 100000, "addr1", probe.ref()));
        InvoiceReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(InvoiceReply.Success.class);
        InvoiceState state = ((InvoiceReply.Success) reply).state();
        assertThat(state.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(state.getPaymentTxid()).isEqualTo("payment-txid-1");
        assertThat(state.getAmountReceivedSats()).isEqualTo(100000);
        assertThat(state.getPaidAt()).isNotNull();
    }

    // 5. pay fails when expired
    @Test
    void payInvoice_failsWhenExpired() {
        createInvoice("inv-5");
        TestProbe<InvoiceReply> probe = testKit.createTestProbe();

        // Expire first
        invoiceRef("inv-5").tell(new InvoiceCommand.ExpireInvoiceCommand("inv-5", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        // Try to pay
        invoiceRef("inv-5").tell(new InvoiceCommand.MarkInvoicePaidCommand(
                "inv-5", "txid", 100000, "addr1", probe.ref()));
        InvoiceReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(InvoiceReply.Failure.class);
        assertThat(((InvoiceReply.Failure) reply).reason()).contains("EXPIRED");
    }

    // 6. pay fails when cancelled
    @Test
    void payInvoice_failsWhenCancelled() {
        createInvoice("inv-6");
        TestProbe<InvoiceReply> probe = testKit.createTestProbe();

        invoiceRef("inv-6").tell(new InvoiceCommand.CancelInvoiceCommand("inv-6", "test", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        invoiceRef("inv-6").tell(new InvoiceCommand.MarkInvoicePaidCommand(
                "inv-6", "txid", 100000, "addr1", probe.ref()));
        InvoiceReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(InvoiceReply.Failure.class);
        assertThat(((InvoiceReply.Failure) reply).reason()).contains("CANCELLED");
    }

    // 7. pay fails with insufficient amount
    @Test
    void payInvoice_failsWithInsufficientAmount() {
        createInvoice("inv-7");
        TestProbe<InvoiceReply> probe = testKit.createTestProbe();
        invoiceRef("inv-7").tell(new InvoiceCommand.MarkInvoicePaidCommand(
                "inv-7", "txid", 50000, "addr1", probe.ref()));
        InvoiceReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(InvoiceReply.Failure.class);
        assertThat(((InvoiceReply.Failure) reply).reason()).contains("Insufficient payment");
    }

    // 8. pay fails with wrong address
    @Test
    void payInvoice_failsWithWrongAddress() {
        createInvoice("inv-8");
        TestProbe<InvoiceReply> probe = testKit.createTestProbe();
        invoiceRef("inv-8").tell(new InvoiceCommand.MarkInvoicePaidCommand(
                "inv-8", "txid", 100000, "wrong-addr", probe.ref()));
        InvoiceReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(InvoiceReply.Failure.class);
        assertThat(((InvoiceReply.Failure) reply).reason()).contains("not associated");
    }

    // 9. cancel succeeds
    @Test
    void cancelInvoice_succeeds() {
        createInvoice("inv-9");
        TestProbe<InvoiceReply> probe = testKit.createTestProbe();
        invoiceRef("inv-9").tell(new InvoiceCommand.CancelInvoiceCommand(
                "inv-9", "Changed mind", probe.ref()));
        InvoiceReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(InvoiceReply.Success.class);
        InvoiceState state = ((InvoiceReply.Success) reply).state();
        assertThat(state.getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
    }

    // 10. cancel fails when paid
    @Test
    void cancelInvoice_failsWhenPaid() {
        createInvoice("inv-10");
        TestProbe<InvoiceReply> probe = testKit.createTestProbe();

        invoiceRef("inv-10").tell(new InvoiceCommand.MarkInvoicePaidCommand(
                "inv-10", "txid", 100000, "addr1", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        invoiceRef("inv-10").tell(new InvoiceCommand.CancelInvoiceCommand(
                "inv-10", "Too late", probe.ref()));
        InvoiceReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(InvoiceReply.Failure.class);
        assertThat(((InvoiceReply.Failure) reply).reason()).contains("Cannot cancel a paid invoice");
    }

    // 11. cancel fails when already cancelled
    @Test
    void cancelInvoice_failsWhenAlreadyCancelled() {
        createInvoice("inv-11");
        TestProbe<InvoiceReply> probe = testKit.createTestProbe();

        invoiceRef("inv-11").tell(new InvoiceCommand.CancelInvoiceCommand("inv-11", "first", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        invoiceRef("inv-11").tell(new InvoiceCommand.CancelInvoiceCommand("inv-11", "second", probe.ref()));
        InvoiceReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(InvoiceReply.Failure.class);
        assertThat(((InvoiceReply.Failure) reply).reason()).contains("already cancelled");
    }

    // 12. expire succeeds
    @Test
    void expireInvoice_succeeds() {
        createInvoice("inv-12");
        TestProbe<InvoiceReply> probe = testKit.createTestProbe();
        invoiceRef("inv-12").tell(new InvoiceCommand.ExpireInvoiceCommand("inv-12", probe.ref()));
        InvoiceReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(InvoiceReply.Success.class);
        InvoiceState state = ((InvoiceReply.Success) reply).state();
        assertThat(state.getStatus()).isEqualTo(InvoiceStatus.EXPIRED);
    }

    // 13. expire fails when paid
    @Test
    void expireInvoice_failsWhenPaid() {
        createInvoice("inv-13");
        TestProbe<InvoiceReply> probe = testKit.createTestProbe();

        invoiceRef("inv-13").tell(new InvoiceCommand.MarkInvoicePaidCommand(
                "inv-13", "txid", 100000, "addr1", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        invoiceRef("inv-13").tell(new InvoiceCommand.ExpireInvoiceCommand("inv-13", probe.ref()));
        InvoiceReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(InvoiceReply.Failure.class);
        assertThat(((InvoiceReply.Failure) reply).reason()).contains("Only PENDING");
    }

    // 14. multi-output invoice
    @Test
    void multiOutputInvoice_succeeds() {
        List<InvoiceOutputSpec> outputs = List.of(
                new InvoiceOutputSpec.P2PKHOutputSpec("addr1", 60000, "payment"),
                new InvoiceOutputSpec.P2PKHOutputSpec("addr2", 40000, "tip")
        );
        InvoiceReply.Success success = createInvoice("inv-14", "wallet-1",
                List.of("addr1", "addr2"), 100000, outputs, "Multi-output", Instant.now().plusSeconds(3600));
        InvoiceState state = success.state();
        assertThat(state.getOutputs()).hasSize(2);
        assertThat(state.getAddressSet()).containsExactlyInAnyOrder("addr1", "addr2");
    }

    // 15. recovery restores state
    @Test
    void recovery_restoresState() {
        String invoiceId = "inv-15-recovery";
        createInvoice(invoiceId);

        PersistenceId pid = PersistenceId.of(InvoiceAggregate.ENTITY_TYPE_KEY.name(), invoiceId);
        ActorRef<InvoiceCommand> recovered = testKit.spawn(InvoiceAggregate.create(pid));

        TestProbe<InvoiceReply> probe = testKit.createTestProbe();
        recovered.tell(new InvoiceCommand.MarkInvoicePaidCommand(
                invoiceId, "recovery-txid", 100000, "addr1", probe.ref()));
        InvoiceReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(InvoiceReply.Success.class);
        InvoiceState state = ((InvoiceReply.Success) reply).state();
        assertThat(state.isCreated()).isTrue();
        assertThat(state.getInvoiceId()).isEqualTo(invoiceId);
        assertThat(state.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }
}
