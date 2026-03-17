package org.twostack.libspiffy4j.aggregate.wallet;

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
import org.twostack.libspiffy4j.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WalletAggregateTest {

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

        testKit = ActorTestKit.create("wallet-test", config);

        Cluster cluster = Cluster.get(testKit.system());
        cluster.manager().tell(new JoinSeedNodes(
                Collections.singletonList(cluster.selfMember().address())));

        Thread.sleep(2000);

        sharding = ClusterSharding.get(testKit.system());
        sharding.init(Entity.of(WalletAggregate.ENTITY_TYPE_KEY, ctx ->
                WalletAggregate.create(PersistenceId.of(
                        WalletAggregate.ENTITY_TYPE_KEY.name(), ctx.getEntityId()))));
    }

    @AfterAll
    static void teardown() {
        if (testKit != null) {
            testKit.shutdownTestKit();
        }
    }

    private EntityRef<WalletCommand> walletRef(String id) {
        return sharding.entityRefFor(WalletAggregate.ENTITY_TYPE_KEY, id);
    }

    private WalletReply.Success createWallet(String walletId) {
        TestProbe<WalletReply> probe = testKit.createTestProbe();
        walletRef(walletId).tell(new WalletCommand.CreateWalletCommand(
                walletId, "Test Wallet", WalletType.HD, NetworkType.TESTNET,
                "tb1qroot", Map.of(), probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Success.class);
        return (WalletReply.Success) reply;
    }

    private BitcoinUtxo makeUtxo(String txid, int vout, long valueSats, Integer confirmations) {
        return new BitcoinUtxo(txid, vout, valueSats, "76a914...", "tb1qaddr",
                UtxoStatus.AVAILABLE, confirmations != null && confirmations > 0 ? 100 : null,
                confirmations, Instant.now(), Instant.now(),
                null, null, null, null, 0, null, null);
    }

    private AddressMetadata makeAddress(String address, int derivationIndex) {
        return new AddressMetadata(address, BitcoinScriptType.P2PKH, "m/44'/0'/0'/0/" + derivationIndex,
                derivationIndex, false, null, null, null, null, 0, 0, Instant.now(), false);
    }

    private BitcoinTransaction makeTx(String walletId, String txid) {
        return new BitcoinTransaction(walletId, txid, "rawhex",
                TransactionStatus.BROADCAST, TransactionDirection.INCOMING,
                null, 0, 0, 50000, 1000, 49000,
                List.of(), List.of(), Instant.now(), Instant.now(), null, 0, 2);
    }

    // 1. createWallet_succeeds
    @Test
    void createWallet_succeeds() {
        WalletReply.Success success = createWallet("wallet-1");
        assertThat(success.state().isCreated()).isTrue();
        assertThat(success.state().getWalletId()).isEqualTo("wallet-1");
        assertThat(success.state().getName()).isEqualTo("Test Wallet");
        assertThat(success.state().getNetworkType()).isEqualTo(NetworkType.TESTNET);
        assertThat(success.state().getWalletType()).isEqualTo(WalletType.HD);
    }

    // 2. createWallet_rejectsDoubleCreate
    @Test
    void createWallet_rejectsDoubleCreate() {
        createWallet("wallet-2");
        TestProbe<WalletReply> probe = testKit.createTestProbe();
        walletRef("wallet-2").tell(new WalletCommand.CreateWalletCommand(
                "wallet-2", "Duplicate", WalletType.HD, NetworkType.TESTNET,
                "tb1qroot", Map.of(), probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Failure.class);
        assertThat(((WalletReply.Failure) reply).reason()).isEqualTo("Wallet already exists");
    }

    // 3. commandBeforeCreate_rejected
    @Test
    void commandBeforeCreate_rejected() {
        TestProbe<WalletReply> probe = testKit.createTestProbe();
        walletRef("wallet-3-uncreated").tell(new WalletCommand.RecordUtxoCommand(
                "wallet-3-uncreated", makeUtxo("tx1", 0, 50000, 0), probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Failure.class);
        assertThat(((WalletReply.Failure) reply).reason()).isEqualTo("Wallet not created");
    }

    // 4. recordAddress_succeeds
    @Test
    void recordAddress_succeeds() {
        createWallet("wallet-4");
        TestProbe<WalletReply> probe = testKit.createTestProbe();
        AddressMetadata addr = makeAddress("tb1qaddr4", 0);
        walletRef("wallet-4").tell(new WalletCommand.RecordAddressCommand(
                "wallet-4", addr, probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Success.class);
        WalletState state = ((WalletReply.Success) reply).state();
        assertThat(state.getKnownAddresses()).contains("tb1qaddr4");
        assertThat(state.getNextDerivationIndex()).isEqualTo(1);
    }

    // 5. recordAddress_rejectsDuplicate
    @Test
    void recordAddress_rejectsDuplicate() {
        createWallet("wallet-5");
        TestProbe<WalletReply> probe = testKit.createTestProbe();
        AddressMetadata addr = makeAddress("tb1qaddr5", 0);
        walletRef("wallet-5").tell(new WalletCommand.RecordAddressCommand(
                "wallet-5", addr, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        walletRef("wallet-5").tell(new WalletCommand.RecordAddressCommand(
                "wallet-5", addr, probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Failure.class);
        assertThat(((WalletReply.Failure) reply).reason()).isEqualTo("Address already recorded");
    }

    // 6. recordUtxo_succeeds (unconfirmed)
    @Test
    void recordUtxo_succeeds() {
        createWallet("wallet-6");
        TestProbe<WalletReply> probe = testKit.createTestProbe();
        BitcoinUtxo utxo = makeUtxo("tx6", 0, 50000, 0);
        walletRef("wallet-6").tell(new WalletCommand.RecordUtxoCommand(
                "wallet-6", utxo, probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Success.class);
        WalletState state = ((WalletReply.Success) reply).state();
        assertThat(state.getUtxoEntries()).containsKey("tx6:0");
        assertThat(state.getUtxoEntries().get("tx6:0").valueSats()).isEqualTo(50000);
    }

    // 7. recordUtxo_confirmedTracksEntry
    @Test
    void recordUtxo_confirmedTracksEntry() {
        createWallet("wallet-7");
        TestProbe<WalletReply> probe = testKit.createTestProbe();
        BitcoinUtxo utxo = makeUtxo("tx7", 0, 75000, 6);
        walletRef("wallet-7").tell(new WalletCommand.RecordUtxoCommand(
                "wallet-7", utxo, probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Success.class);
        WalletState state = ((WalletReply.Success) reply).state();
        assertThat(state.getUtxoEntries()).containsKey("tx7:0");
        assertThat(state.getUtxoEntries().get("tx7:0").valueSats()).isEqualTo(75000);
        assertThat(state.getUtxoEntries().get("tx7:0").status()).isEqualTo(UtxoStatus.AVAILABLE);
    }

    // 8. reserveUtxo_succeeds
    @Test
    void reserveUtxo_succeeds() {
        createWallet("wallet-8");
        TestProbe<WalletReply> probe = testKit.createTestProbe();
        BitcoinUtxo utxo = makeUtxo("tx8", 0, 60000, 3);
        walletRef("wallet-8").tell(new WalletCommand.RecordUtxoCommand(
                "wallet-8", utxo, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        walletRef("wallet-8").tell(new WalletCommand.ReserveUtxoCommand(
                "wallet-8", "tx8:0", "spending-tx", Instant.now().plusSeconds(3600),
                1, "payment", probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Success.class);
        WalletState state = ((WalletReply.Success) reply).state();
        assertThat(state.getUtxoEntries().get("tx8:0").status()).isEqualTo(UtxoStatus.RESERVED);
    }

    // 9. reserveUtxo_failsWhenSpent
    @Test
    void reserveUtxo_failsWhenSpent() {
        createWallet("wallet-9");
        TestProbe<WalletReply> probe = testKit.createTestProbe();
        BitcoinUtxo utxo = makeUtxo("tx9", 0, 40000, 1);
        walletRef("wallet-9").tell(new WalletCommand.RecordUtxoCommand(
                "wallet-9", utxo, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        walletRef("wallet-9").tell(new WalletCommand.MarkUtxoSpentCommand(
                "wallet-9", "tx9:0", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        walletRef("wallet-9").tell(new WalletCommand.ReserveUtxoCommand(
                "wallet-9", "tx9:0", "tx-attempt", Instant.now().plusSeconds(3600),
                1, "payment", probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Failure.class);
        assertThat(((WalletReply.Failure) reply).reason()).isEqualTo("UTXO is not available");
    }

    // 10. releaseUtxo_succeeds
    @Test
    void releaseUtxo_succeeds() {
        createWallet("wallet-10");
        TestProbe<WalletReply> probe = testKit.createTestProbe();
        BitcoinUtxo utxo = makeUtxo("tx10", 0, 30000, 2);
        walletRef("wallet-10").tell(new WalletCommand.RecordUtxoCommand(
                "wallet-10", utxo, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        walletRef("wallet-10").tell(new WalletCommand.ReserveUtxoCommand(
                "wallet-10", "tx10:0", "spending-tx", Instant.now().plusSeconds(3600),
                1, "payment", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        walletRef("wallet-10").tell(new WalletCommand.ReleaseUtxoCommand(
                "wallet-10", "tx10:0", probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Success.class);
        WalletState state = ((WalletReply.Success) reply).state();
        assertThat(state.getUtxoEntries().get("tx10:0").status()).isEqualTo(UtxoStatus.AVAILABLE);
    }

    // 11. markUtxoSpent_succeeds
    @Test
    void markUtxoSpent_succeeds() {
        createWallet("wallet-11");
        TestProbe<WalletReply> probe = testKit.createTestProbe();
        BitcoinUtxo utxo = makeUtxo("tx11", 0, 20000, 1);
        walletRef("wallet-11").tell(new WalletCommand.RecordUtxoCommand(
                "wallet-11", utxo, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        walletRef("wallet-11").tell(new WalletCommand.MarkUtxoSpentCommand(
                "wallet-11", "tx11:0", probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Success.class);
        WalletState state = ((WalletReply.Success) reply).state();
        assertThat(state.getUtxoEntries().get("tx11:0").status()).isEqualTo(UtxoStatus.SPENT);
    }

    // 12. updateConfirmation_succeeds
    @Test
    void updateConfirmation_succeeds() {
        createWallet("wallet-12");
        TestProbe<WalletReply> probe = testKit.createTestProbe();
        BitcoinUtxo utxo = makeUtxo("tx12", 0, 45000, 0);
        walletRef("wallet-12").tell(new WalletCommand.RecordUtxoCommand(
                "wallet-12", utxo, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        walletRef("wallet-12").tell(new WalletCommand.UpdateConfirmationCommand(
                "wallet-12", "tx12", 6, 800000, probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Success.class);
    }

    // 13. cleanupExpiredReservations_releasesExpired
    @Test
    void cleanupExpiredReservations_releasesExpired() {
        createWallet("wallet-13");
        TestProbe<WalletReply> probe = testKit.createTestProbe();
        BitcoinUtxo utxo = makeUtxo("tx13", 0, 55000, 3);
        walletRef("wallet-13").tell(new WalletCommand.RecordUtxoCommand(
                "wallet-13", utxo, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        // Reserve with already-expired timestamp
        walletRef("wallet-13").tell(new WalletCommand.ReserveUtxoCommand(
                "wallet-13", "tx13:0", "spending-tx", Instant.now().minusSeconds(10),
                1, "payment", probe.ref()));
        probe.receiveMessage(TIMEOUT);

        walletRef("wallet-13").tell(new WalletCommand.CleanupExpiredReservationsCommand(
                "wallet-13", probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Success.class);
        WalletState state = ((WalletReply.Success) reply).state();
        assertThat(state.getUtxoEntries().get("tx13:0").status()).isEqualTo(UtxoStatus.AVAILABLE);
    }

    // 14. recovery_restoresState
    @Test
    void recovery_restoresState() {
        String walletId = "wallet-14-recovery";

        TestProbe<WalletReply> probe = testKit.createTestProbe();
        walletRef(walletId).tell(new WalletCommand.CreateWalletCommand(
                walletId, "Recovery Test", WalletType.HD, NetworkType.TESTNET,
                "tb1qroot", Map.of(), probe.ref()));
        probe.receiveMessage(TIMEOUT);

        BitcoinUtxo utxo = makeUtxo("txRecovery", 0, 100000, 3);
        walletRef(walletId).tell(new WalletCommand.RecordUtxoCommand(
                walletId, utxo, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        // Spawn a separate actor with the same persistence ID to simulate recovery
        PersistenceId pid = PersistenceId.of(WalletAggregate.ENTITY_TYPE_KEY.name(), walletId);
        ActorRef<WalletCommand> recovered = testKit.spawn(WalletAggregate.create(pid));

        // Query the recovered actor
        AddressMetadata addr = makeAddress("tb1qrecovery", 0);
        recovered.tell(new WalletCommand.RecordAddressCommand(walletId, addr, probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        assertThat(reply).isInstanceOf(WalletReply.Success.class);
        WalletState state = ((WalletReply.Success) reply).state();
        assertThat(state.isCreated()).isTrue();
        assertThat(state.getWalletId()).isEqualTo(walletId);
        assertThat(state.getUtxoEntries()).containsKey("txRecovery:0");
    }
}
