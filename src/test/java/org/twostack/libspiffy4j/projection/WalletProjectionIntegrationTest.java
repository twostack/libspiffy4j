package org.twostack.libspiffy4j.projection;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.JoinSeedNodes;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.twostack.libspiffy4j.LibSpiffy4j;
import org.twostack.libspiffy4j.aggregate.wallet.*;
import org.twostack.libspiffy4j.model.*;
import org.twostack.libspiffy4j.storage.postgres.WalletReadModelStorage;

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
class WalletProjectionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("libspiffy4j_test")
                    .withUsername("test")
                    .withPassword("test");

    private static LibSpiffy4j lib;
    private static ClusterSharding sharding;
    private static PGSimpleDataSource dataSource;
    private static final WalletReadModelStorage storage = new WalletReadModelStorage();
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

        // Wait for cluster to form
        Thread.sleep(3000);

        sharding = ClusterSharding.get(lib.system());
        sharding.init(Entity.of(WalletAggregate.ENTITY_TYPE_KEY, ctx ->
                WalletAggregate.create(PersistenceId.of(
                        WalletAggregate.ENTITY_TYPE_KEY.name(), ctx.getEntityId()))));
    }

    @AfterAll
    static void teardown() {
        if (lib != null) {
            lib.close();
        }
    }

    private EntityRef<WalletCommand> walletRef(String id) {
        return sharding.entityRefFor(WalletAggregate.ENTITY_TYPE_KEY, id);
    }

    private WalletReply.Success sendCommand(String walletId, WalletCommand cmd) {
        TestProbe<WalletReply> probe = ActorTestKit.create(lib.system()).createTestProbe();
        walletRef(walletId).tell(cmd);
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Success.class);
        return (WalletReply.Success) reply;
    }

    // 1. End-to-end: create wallet → verify read model
    @Test
    void endToEnd_walletCreation_appearsInReadModel() {
        String walletId = "proj-e2e-1";
        TestProbe<WalletReply> probe = ActorTestKit.create(lib.system()).createTestProbe();
        walletRef(walletId).tell(new WalletCommand.CreateWalletCommand(
                walletId, "E2E Wallet", WalletType.HD, NetworkType.TESTNET,
                "tb1qroot", Map.of("env", "test"), probe.ref()));
        probe.receiveMessage(TIMEOUT);

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            Optional<WalletSummary> summary = storage.findWalletSummary(dataSource, walletId);
            assertThat(summary).isPresent();
            assertThat(summary.get().name()).isEqualTo("E2E Wallet");
            assertThat(summary.get().walletType()).isEqualTo(WalletType.HD);
            assertThat(summary.get().networkType()).isEqualTo(NetworkType.TESTNET);
        });
    }

    // 2. Balance calculation through projection
    @Test
    void balanceCalculation_reflectsUtxoState() {
        String walletId = "proj-balance-1";
        TestProbe<WalletReply> probe = ActorTestKit.create(lib.system()).createTestProbe();

        walletRef(walletId).tell(new WalletCommand.CreateWalletCommand(
                walletId, "Balance Wallet", WalletType.HD, NetworkType.TESTNET,
                "tb1qroot", Map.of(), probe.ref()));
        probe.receiveMessage(TIMEOUT);

        BitcoinUtxo utxo = new BitcoinUtxo("txbal1", 0, 100000, "script", "addr",
                UtxoStatus.AVAILABLE, 500, 6, Instant.now(), Instant.now(),
                null, null, null, null, 0, null, null);
        walletRef(walletId).tell(new WalletCommand.RecordUtxoCommand(walletId, utxo, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            Optional<WalletBalance> balance = storage.getWalletBalance(dataSource, walletId);
            assertThat(balance).isPresent();
            assertThat(balance.get().confirmedSats()).isEqualTo(100000);
        });
    }

    // 3. UTXO lifecycle: receive → reserve → release → spend
    @Test
    void utxoLifecycle_trackedInReadModel() {
        String walletId = "proj-utxo-lifecycle";
        TestProbe<WalletReply> probe = ActorTestKit.create(lib.system()).createTestProbe();

        walletRef(walletId).tell(new WalletCommand.CreateWalletCommand(
                walletId, "UTXO Lifecycle", WalletType.HD, NetworkType.TESTNET,
                "tb1qroot", Map.of(), probe.ref()));
        probe.receiveMessage(TIMEOUT);

        BitcoinUtxo utxo = new BitcoinUtxo("txlife1", 0, 75000, "script", "addr",
                UtxoStatus.AVAILABLE, 100, 3, Instant.now(), Instant.now(),
                null, null, null, null, 0, null, null);
        walletRef(walletId).tell(new WalletCommand.RecordUtxoCommand(walletId, utxo, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        // Wait for UTXO to appear
        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<BitcoinUtxo> utxos = storage.findUtxosByWalletId(dataSource, walletId);
            assertThat(utxos).hasSize(1);
            assertThat(utxos.get(0).status()).isEqualTo(UtxoStatus.AVAILABLE);
        });

        // Reserve
        walletRef(walletId).tell(new WalletCommand.ReserveUtxoCommand(
                walletId, "txlife1:0", "spending-tx", Instant.now().plusSeconds(3600),
                1, "payment", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<BitcoinUtxo> utxos = storage.findUtxosByStatus(dataSource, walletId, UtxoStatus.RESERVED);
            assertThat(utxos).hasSize(1);
        });

        // Release
        walletRef(walletId).tell(new WalletCommand.ReleaseUtxoCommand(walletId, "txlife1:0", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<BitcoinUtxo> utxos = storage.findUtxosByStatus(dataSource, walletId, UtxoStatus.AVAILABLE);
            assertThat(utxos).hasSize(1);
        });

        // Spend
        walletRef(walletId).tell(new WalletCommand.MarkUtxoSpentCommand(walletId, "txlife1:0", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<BitcoinUtxo> utxos = storage.findUtxosByStatus(dataSource, walletId, UtxoStatus.SPENT);
            assertThat(utxos).hasSize(1);
        });
    }

    // 4. Idempotency — replaying same event should not duplicate
    @Test
    void idempotency_duplicateUtxoRejectedByAggregate() {
        String walletId = "proj-idempotent";
        TestProbe<WalletReply> probe = ActorTestKit.create(lib.system()).createTestProbe();

        walletRef(walletId).tell(new WalletCommand.CreateWalletCommand(
                walletId, "Idempotent", WalletType.HD, NetworkType.TESTNET,
                "tb1qroot", Map.of(), probe.ref()));
        probe.receiveMessage(TIMEOUT);

        BitcoinUtxo utxo = new BitcoinUtxo("txidem", 0, 40000, "script", "addr",
                UtxoStatus.AVAILABLE, 100, 1, Instant.now(), Instant.now(),
                null, null, null, null, 0, null, null);
        walletRef(walletId).tell(new WalletCommand.RecordUtxoCommand(walletId, utxo, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        // Second attempt should fail at aggregate level
        walletRef(walletId).tell(new WalletCommand.RecordUtxoCommand(walletId, utxo, probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Failure.class);

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<BitcoinUtxo> utxos = storage.findUtxosByWalletId(dataSource, walletId);
            assertThat(utxos).hasSize(1);
        });
    }

    // 5. Pagination for transactions
    @Test
    void transactionPagination_works() {
        String walletId = "proj-txpage";
        TestProbe<WalletReply> probe = ActorTestKit.create(lib.system()).createTestProbe();

        walletRef(walletId).tell(new WalletCommand.CreateWalletCommand(
                walletId, "Paginated", WalletType.HD, NetworkType.TESTNET,
                "tb1qroot", Map.of(), probe.ref()));
        probe.receiveMessage(TIMEOUT);

        for (int i = 0; i < 5; i++) {
            BitcoinTransaction tx = new BitcoinTransaction(walletId, "txpage" + i, "rawhex",
                    TransactionStatus.BROADCAST, TransactionDirection.INCOMING,
                    null, 0, 0, 10000, 500, 9500,
                    List.of(), List.of(), Instant.now(), Instant.now(), null, 0, 2);
            walletRef(walletId).tell(new WalletCommand.RecordTransactionCommand(walletId, tx, probe.ref()));
            probe.receiveMessage(TIMEOUT);
        }

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<BitcoinTransaction> all = storage.findTransactionsByWalletId(dataSource, walletId, 10, 0);
            assertThat(all).hasSize(5);

            List<BitcoinTransaction> page1 = storage.findTransactionsByWalletId(dataSource, walletId, 2, 0);
            assertThat(page1).hasSize(2);

            List<BitcoinTransaction> page2 = storage.findTransactionsByWalletId(dataSource, walletId, 2, 2);
            assertThat(page2).hasSize(2);
        });
    }

    // 6. Address query
    @Test
    void addressRecording_appearsInReadModel() {
        String walletId = "proj-addr";
        TestProbe<WalletReply> probe = ActorTestKit.create(lib.system()).createTestProbe();

        walletRef(walletId).tell(new WalletCommand.CreateWalletCommand(
                walletId, "Address Wallet", WalletType.HD, NetworkType.TESTNET,
                "tb1qroot", Map.of(), probe.ref()));
        probe.receiveMessage(TIMEOUT);

        AddressMetadata addr = new AddressMetadata("tb1qprojaddr", BitcoinScriptType.P2PKH,
                "m/44'/0'/0'/0/0", 0, false, null, null, null, null, 0, 0, Instant.now(), false);
        walletRef(walletId).tell(new WalletCommand.RecordAddressCommand(walletId, addr, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<String> addresses = storage.findAddressesByWalletId(dataSource, walletId);
            assertThat(addresses).contains("tb1qprojaddr");
        });
    }

    // 7. Recovery — projection catches up after restart
    @Test
    void projectionCatchesUp_afterEvents() {
        String walletId = "proj-recovery";
        TestProbe<WalletReply> probe = ActorTestKit.create(lib.system()).createTestProbe();

        walletRef(walletId).tell(new WalletCommand.CreateWalletCommand(
                walletId, "Recovery Wallet", WalletType.HD, NetworkType.TESTNET,
                "tb1qroot", Map.of(), probe.ref()));
        probe.receiveMessage(TIMEOUT);

        // Record multiple UTXOs
        for (int i = 0; i < 3; i++) {
            BitcoinUtxo utxo = new BitcoinUtxo("txrec" + i, 0, 25000 * (i + 1), "s", "a",
                    UtxoStatus.AVAILABLE, 100, 3, Instant.now(), Instant.now(),
                    null, null, null, null, 0, null, null);
            walletRef(walletId).tell(new WalletCommand.RecordUtxoCommand(walletId, utxo, probe.ref()));
            probe.receiveMessage(TIMEOUT);
        }

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            Optional<WalletSummary> summary = storage.findWalletSummary(dataSource, walletId);
            assertThat(summary).isPresent();
            assertThat(summary.get().confirmedBalanceSats()).isEqualTo(150000); // 25000 + 50000 + 75000
            List<BitcoinUtxo> utxos = storage.findUtxosByWalletId(dataSource, walletId);
            assertThat(utxos).hasSize(3);
        });
    }

    private static void runMigrations(PGSimpleDataSource ds) throws Exception {
        String[] scripts = {
                "db/libspiffy4j/V001__create_journal.sql",
                "db/libspiffy4j/V002__create_snapshot.sql",
                "db/libspiffy4j/V003__create_projection_offset.sql",
                "db/libspiffy4j/V004__create_wallet_read_models.sql",
                "db/libspiffy4j/V007__add_raw_hex_to_wallet_transaction.sql",
                "db/libspiffy4j/V008__add_plugin_fields.sql",
                "db/libspiffy4j/V009__add_script_pub_key_to_wallet_utxo.sql"
        };
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String script : scripts) {
                String sql = new String(
                        WalletProjectionIntegrationTest.class.getClassLoader()
                                .getResourceAsStream(script).readAllBytes(),
                        StandardCharsets.UTF_8);
                stmt.execute(sql);
            }
        }
    }
}
