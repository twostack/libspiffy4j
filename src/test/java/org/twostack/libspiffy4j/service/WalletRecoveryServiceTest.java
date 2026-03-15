package org.twostack.libspiffy4j.service;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.JoinSeedNodes;
import org.apache.pekko.persistence.testkit.PersistenceTestKitPlugin$;
import org.apache.pekko.persistence.testkit.PersistenceTestKitSnapshotPlugin$;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.twostack.libspiffy4j.aggregate.wallet.WalletAggregate;
import org.twostack.libspiffy4j.aggregate.wallet.WalletCommand;
import org.twostack.libspiffy4j.aggregate.wallet.WalletReply;
import org.twostack.libspiffy4j.model.*;
import org.twostack.libspiffy4j.spv.Bump;
import org.twostack.libspiffy4j.spv.BumpLeaf;
import org.twostack.libspiffy4j.spv.BumpLevel;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class WalletRecoveryServiceTest {

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

        testKit = ActorTestKit.create("recovery-test", config);

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

    // --- Stub functions ---

    private static WalletRecoveryService.DiscoveryFunction stubDiscovery(
            List<DiscoveredAddress> receiving, List<DiscoveredAddress> change) {
        return (hdKey, networkType, gapLimit, onProgress) -> {
            List<DiscoveredAddress> all = new ArrayList<>();
            all.addAll(receiving);
            all.addAll(change);
            for (DiscoveredAddress da : all) {
                if (onProgress != null) onProgress.accept(da);
            }
            int totalTx = all.stream().mapToInt(a -> a.transactionIds().size()).sum();
            return new AddressDiscoveryResult(receiving, change, totalTx, Map.of());
        };
    }

    private static WalletRecoveryService.ImportFunction stubImport(List<ImportedTransaction> txs) {
        return txids -> txs.stream()
                .filter(tx -> txids.contains(tx.txid()))
                .toList();
    }

    private static Bump dummyBump() {
        byte[] hash = new byte[32];
        hash[0] = 0x01;
        return new Bump(100000L, List.of(
                new BumpLevel(List.of(new BumpLeaf(0, false, true, hash)))));
    }

    // --- Tests ---

    @Test
    void fullRecovery_withMockedServices() throws Exception {
        String walletId = "recovery-1";

        var addr1 = new DiscoveredAddress("addr1", 0, false, List.of("tx1", "tx2"));
        var addr2 = new DiscoveredAddress("addr2", 1, false, List.of("tx2", "tx3"));

        var itx1 = new ImportedTransaction("tx1", "deadbeef", dummyBump(), 100000, true);
        var itx2 = new ImportedTransaction("tx2", "cafebabe", dummyBump(), 100001, true);
        var itx3 = new ImportedTransaction("tx3", "01020304", dummyBump(), 100002, true);

        // Custom UTXO extractor (since raw hex is fake, we provide test UTXOs directly)
        var utxoExtractor = new java.util.function.BiFunction<ImportedTransaction, Set<String>, List<BitcoinUtxo>>() {
            @Override
            public List<BitcoinUtxo> apply(ImportedTransaction tx, Set<String> addresses) {
                if ("tx1".equals(tx.txid())) {
                    return List.of(new BitcoinUtxo("tx1", 0, 50000, "script", "addr1",
                            UtxoStatus.AVAILABLE, 100000, null, Instant.now(), Instant.now(),
                            null, null, null, null, 0));
                }
                if ("tx3".equals(tx.txid())) {
                    return List.of(new BitcoinUtxo("tx3", 0, 30000, "script", "addr2",
                            UtxoStatus.AVAILABLE, 100002, null, Instant.now(), Instant.now(),
                            null, null, null, null, 1));
                }
                return List.of();
            }
        };

        var service = new WalletRecoveryService(
                sharding,
                (key, net) -> "tb1qroot-recovery",
                stubDiscovery(List.of(addr1, addr2), List.of()),
                stubImport(List.of(itx1, itx2, itx3)),
                TIMEOUT, utxoExtractor);

        List<String> progress = new ArrayList<>();
        CompletionStage<WalletRecoveryResult> resultStage = service.recoverWallet(
                walletId, "Recovery Test", null, NetworkType.TESTNET, 20, progress::add);

        WalletRecoveryResult result = resultStage.toCompletableFuture().get();

        assertThat(result.walletId()).isEqualTo(walletId);
        assertThat(result.addressesDiscovered()).isEqualTo(2);
        assertThat(result.transactionsImported()).isEqualTo(3);
        assertThat(result.utxosRecovered()).isEqualTo(2);
        assertThat(result.totalBalanceSats()).isEqualTo(80000L);
        assertThat(progress).isNotEmpty();

        // Verify wallet state via probe
        TestProbe<WalletReply> probe = testKit.createTestProbe();
        sharding.entityRefFor(WalletAggregate.ENTITY_TYPE_KEY, walletId)
                .tell(new WalletCommand.RecordAddressCommand(walletId,
                        new AddressMetadata("addr-verify", BitcoinScriptType.P2PKH,
                                "m/0", 99, false, null, null, null, null, 0, 0, Instant.now(), false),
                        probe.ref()));
        WalletReply reply = probe.receiveMessage(TIMEOUT);
        // If wallet exists and is created, this should succeed
        assertThat(reply).isInstanceOf(WalletReply.Success.class);
    }

    @Test
    void deduplicatesTxidsAcrossAddresses() throws Exception {
        String walletId = "recovery-2";

        // Both addresses share tx-shared
        var addr1 = new DiscoveredAddress("a1", 0, false, List.of("tx-shared", "tx-only1"));
        var addr2 = new DiscoveredAddress("a2", 0, true, List.of("tx-shared", "tx-only2"));

        var txShared = new ImportedTransaction("tx-shared", "aabb", dummyBump(), 100, true);
        var txOnly1 = new ImportedTransaction("tx-only1", "ccdd", dummyBump(), 101, true);
        var txOnly2 = new ImportedTransaction("tx-only2", "eeff", dummyBump(), 102, true);

        var baseImport = stubImport(List.of(txShared, txOnly1, txOnly2));

        // Track which txids get imported
        List<String> importedTxids = new ArrayList<>();
        WalletRecoveryService.ImportFunction trackingImport = txids -> {
            importedTxids.addAll(txids);
            return baseImport.importBatch(txids);
        };

        var service = new WalletRecoveryService(
                sharding,
                (key, net) -> "tb1qroot-dedup",
                stubDiscovery(List.of(addr1), List.of(addr2)),
                trackingImport,
                TIMEOUT, (tx, addrs) -> List.of());

        service.recoverWallet(walletId, "Dedup Test", null, NetworkType.TESTNET, 20, null)
                .toCompletableFuture().get();

        // tx-shared should appear only once in the batch
        assertThat(importedTxids).containsExactlyInAnyOrder("tx-shared", "tx-only1", "tx-only2");
        assertThat(Collections.frequency(importedTxids, "tx-shared")).isEqualTo(1);
    }

    @Test
    void emptyDiscovery_returnsEmptyResult() throws Exception {
        String walletId = "recovery-3";

        var service = new WalletRecoveryService(
                sharding,
                (key, net) -> "tb1qroot-empty",
                stubDiscovery(List.of(), List.of()),
                stubImport(List.of()),
                TIMEOUT, (tx, addrs) -> List.of());

        WalletRecoveryResult result = service.recoverWallet(
                walletId, "Empty Test", null, NetworkType.TESTNET, 20, null)
                .toCompletableFuture().get();

        assertThat(result.addressesDiscovered()).isZero();
        assertThat(result.transactionsImported()).isZero();
        assertThat(result.utxosRecovered()).isZero();
        assertThat(result.totalBalanceSats()).isZero();
    }
}
