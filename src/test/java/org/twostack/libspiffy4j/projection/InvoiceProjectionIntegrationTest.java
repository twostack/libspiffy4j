package org.twostack.libspiffy4j.projection;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.twostack.libspiffy4j.LibSpiffy4j;
import org.twostack.libspiffy4j.aggregate.invoice.*;
import org.twostack.libspiffy4j.model.Invoice;
import org.twostack.libspiffy4j.model.InvoiceOutputSpec;
import org.twostack.libspiffy4j.model.InvoiceStatus;
import org.twostack.libspiffy4j.storage.postgres.InvoiceReadModelStorage;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class InvoiceProjectionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("libspiffy4j_test")
                    .withUsername("test")
                    .withPassword("test");

    private static LibSpiffy4j lib;
    private static ClusterSharding sharding;
    private static PGSimpleDataSource dataSource;
    private static final InvoiceReadModelStorage storage = new InvoiceReadModelStorage();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PROJECTION_TIMEOUT = Duration.ofSeconds(30);

    @BeforeAll
    static void setup() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(PG.getJdbcUrl());
        dataSource.setUser(PG.getUsername());
        dataSource.setPassword(PG.getPassword());

        runMigrations(dataSource);

        Config jdbcConfig = ConfigFactory.parseString("""
                pekko.actor.provider = "cluster"
                pekko.remote.artery.canonical.hostname = "127.0.0.1"
                pekko.remote.artery.canonical.port = 0
                pekko.cluster.downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
                pekko.actor.allow-java-serialization = on
                """);

        lib = LibSpiffy4j.builder()
                .dataSource(dataSource)
                .configOverride(jdbcConfig)
                .build();

        Thread.sleep(3000);

        sharding = ClusterSharding.get(lib.system());
        sharding.init(Entity.of(InvoiceAggregate.ENTITY_TYPE_KEY, ctx ->
                InvoiceAggregate.create(PersistenceId.of(
                        InvoiceAggregate.ENTITY_TYPE_KEY.name(), ctx.getEntityId()))));
    }

    @AfterAll
    static void teardown() {
        if (lib != null) {
            lib.close();
        }
    }

    private EntityRef<InvoiceCommand> invoiceRef(String id) {
        return sharding.entityRefFor(InvoiceAggregate.ENTITY_TYPE_KEY, id);
    }

    private void createInvoice(String invoiceId, String walletId, List<String> addresses,
                                long amountSats, List<InvoiceOutputSpec> outputs, Instant expiresAt) {
        TestProbe<InvoiceReply> probe = ActorTestKit.create(lib.system()).createTestProbe();
        invoiceRef(invoiceId).tell(new InvoiceCommand.CreateInvoiceCommand(
                invoiceId, walletId, addresses, amountSats, outputs,
                "Test Invoice", expiresAt, Map.of("env", "test"), probe.ref()));
        InvoiceReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(InvoiceReply.Success.class);
    }

    // 1. Creation appears in read model
    @Test
    void endToEnd_invoiceCreation_appearsInReadModel() {
        String invoiceId = "proj-inv-1";
        createInvoice(invoiceId, "wallet-1", List.of("addr1", "addr2"), 100000,
                List.of(new InvoiceOutputSpec.P2PKHOutputSpec("addr1", 100000, "main")),
                Instant.now().plusSeconds(3600));

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            Optional<Invoice> invoice = storage.findInvoice(dataSource, invoiceId);
            assertThat(invoice).isPresent();
            assertThat(invoice.get().walletId()).isEqualTo("wallet-1");
            assertThat(invoice.get().amountSats()).isEqualTo(100000);
            assertThat(invoice.get().status()).isEqualTo(InvoiceStatus.PENDING);
        });
    }

    // 2. Paid updates read model
    @Test
    void paidInvoice_updatesReadModel() {
        String invoiceId = "proj-inv-2";
        createInvoice(invoiceId, "wallet-1", List.of("addr1"), 50000,
                List.of(new InvoiceOutputSpec.P2PKHOutputSpec("addr1", 50000, "pay")),
                Instant.now().plusSeconds(3600));

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            Optional<Invoice> inv = storage.findInvoice(dataSource, invoiceId);
            assertThat(inv).isPresent();
            assertThat(inv.get().status()).isEqualTo(InvoiceStatus.PENDING);
        });

        TestProbe<InvoiceReply> probe = ActorTestKit.create(lib.system()).createTestProbe();
        invoiceRef(invoiceId).tell(new InvoiceCommand.MarkInvoicePaidCommand(
                invoiceId, "payment-tx-1", 50000, "addr1", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            Optional<Invoice> inv = storage.findInvoice(dataSource, invoiceId);
            assertThat(inv).isPresent();
            assertThat(inv.get().status()).isEqualTo(InvoiceStatus.PAID);
            assertThat(inv.get().paymentTxid()).isEqualTo("payment-tx-1");
            assertThat(inv.get().amountReceivedSats()).isEqualTo(50000L);
        });
    }

    // 3. Cancelled updates read model
    @Test
    void cancelledInvoice_updatesReadModel() {
        String invoiceId = "proj-inv-3";
        createInvoice(invoiceId, "wallet-1", List.of("addr1"), 75000,
                List.of(), Instant.now().plusSeconds(3600));

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            Optional<Invoice> inv = storage.findInvoice(dataSource, invoiceId);
            assertThat(inv).isPresent();
        });

        TestProbe<InvoiceReply> probe = ActorTestKit.create(lib.system()).createTestProbe();
        invoiceRef(invoiceId).tell(new InvoiceCommand.CancelInvoiceCommand(
                invoiceId, "Not needed", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            Optional<Invoice> inv = storage.findInvoice(dataSource, invoiceId);
            assertThat(inv).isPresent();
            assertThat(inv.get().status()).isEqualTo(InvoiceStatus.CANCELLED);
        });
    }

    // 4. Expired updates read model
    @Test
    void expiredInvoice_updatesReadModel() {
        String invoiceId = "proj-inv-4";
        createInvoice(invoiceId, "wallet-1", List.of("addr1"), 60000,
                List.of(), Instant.now().plusSeconds(3600));

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            Optional<Invoice> inv = storage.findInvoice(dataSource, invoiceId);
            assertThat(inv).isPresent();
        });

        TestProbe<InvoiceReply> probe = ActorTestKit.create(lib.system()).createTestProbe();
        invoiceRef(invoiceId).tell(new InvoiceCommand.ExpireInvoiceCommand(invoiceId, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            Optional<Invoice> inv = storage.findInvoice(dataSource, invoiceId);
            assertThat(inv).isPresent();
            assertThat(inv.get().status()).isEqualTo(InvoiceStatus.EXPIRED);
        });
    }

    // 5. List invoices with status filter
    @Test
    void listInvoices_withStatusFilter() {
        String walletId = "wallet-filter";
        createInvoice("proj-inv-5a", walletId, List.of("addr1"), 10000,
                List.of(), Instant.now().plusSeconds(3600));
        createInvoice("proj-inv-5b", walletId, List.of("addr1"), 20000,
                List.of(), Instant.now().plusSeconds(3600));

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<Invoice> all = storage.listInvoices(dataSource, walletId, null, 10, 0);
            assertThat(all).hasSize(2);
        });

        // Cancel one
        TestProbe<InvoiceReply> probe = ActorTestKit.create(lib.system()).createTestProbe();
        invoiceRef("proj-inv-5a").tell(new InvoiceCommand.CancelInvoiceCommand(
                "proj-inv-5a", "test", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<Invoice> pending = storage.listInvoices(dataSource, walletId, InvoiceStatus.PENDING, 10, 0);
            assertThat(pending).hasSize(1);
            assertThat(pending.get(0).invoiceId()).isEqualTo("proj-inv-5b");

            List<Invoice> cancelled = storage.listInvoices(dataSource, walletId, InvoiceStatus.CANCELLED, 10, 0);
            assertThat(cancelled).hasSize(1);
        });
    }

    // 6. Find expired invoices
    @Test
    void findExpiredInvoices_works() {
        Instant pastExpiry = Instant.now().minusSeconds(60);
        createInvoice("proj-inv-6", "wallet-expired", List.of("addr1"), 30000,
                List.of(), pastExpiry);

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<Invoice> expired = storage.findExpiredInvoices(dataSource, Instant.now());
            assertThat(expired).anyMatch(inv -> inv.invoiceId().equals("proj-inv-6"));
        });
    }

    // 7. Multi-output invoice outputs persisted
    @Test
    void multiOutputInvoice_outputsPersisted() {
        String invoiceId = "proj-inv-7";
        List<InvoiceOutputSpec> outputs = List.of(
                new InvoiceOutputSpec.P2PKHOutputSpec("addr1", 60000, "payment"),
                new InvoiceOutputSpec.P2PKHOutputSpec("addr2", 40000, "tip")
        );
        createInvoice(invoiceId, "wallet-multi", List.of("addr1", "addr2"), 100000,
                outputs, Instant.now().plusSeconds(3600));

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<InvoiceOutputSpec> storedOutputs = storage.findInvoiceOutputs(dataSource, invoiceId);
            assertThat(storedOutputs).hasSize(2);
            assertThat(storedOutputs.get(0)).isInstanceOf(InvoiceOutputSpec.P2PKHOutputSpec.class);
            InvoiceOutputSpec.P2PKHOutputSpec first = (InvoiceOutputSpec.P2PKHOutputSpec) storedOutputs.get(0);
            assertThat(first.address()).isEqualTo("addr1");
            assertThat(first.amountSats()).isEqualTo(60000);
        });
    }

    private static void runMigrations(PGSimpleDataSource ds) throws Exception {
        String[] scripts = {
                "db/libspiffy4j/V001__create_journal.sql",
                "db/libspiffy4j/V002__create_snapshot.sql",
                "db/libspiffy4j/V003__create_projection_offset.sql",
                "db/libspiffy4j/V004__create_wallet_read_models.sql",
                "db/libspiffy4j/V005__create_secure_storage.sql",
                "db/libspiffy4j/V006__create_invoice_read_models.sql"
        };
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String script : scripts) {
                String sql = new String(
                        InvoiceProjectionIntegrationTest.class.getClassLoader()
                                .getResourceAsStream(script).readAllBytes(),
                        StandardCharsets.UTF_8);
                stmt.execute(sql);
            }
        }
    }
}
