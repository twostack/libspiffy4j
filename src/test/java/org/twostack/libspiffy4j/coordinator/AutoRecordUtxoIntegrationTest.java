package org.twostack.libspiffy4j.coordinator;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.twostack.bitcoin4j.Sha256Hash;
import org.twostack.bitcoin4j.Utils;
import org.twostack.bitcoin4j.address.LegacyAddress;
import org.twostack.bitcoin4j.params.NetworkAddressType;
import org.twostack.bitcoin4j.script.Script;
import org.twostack.bitcoin4j.script.ScriptBuilder;
import org.twostack.bitcoin4j.script.ScriptOpCodes;
import org.twostack.bitcoin4j.transaction.*;
import org.twostack.libspiffy4j.LibSpiffy4j;
import org.twostack.libspiffy4j.model.*;
import org.twostack.libspiffy4j.plugin.PluginLockSpec;
import org.twostack.libspiffy4j.plugin.PluginUnlockSpec;
import org.twostack.libspiffy4j.plugin.ScriptPlugin;
import org.twostack.libspiffy4j.service.EncryptionService;
import org.twostack.libspiffy4j.storage.postgres.WalletReadModelStorage;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test verifying that RecordTransaction with rawHex automatically
 * creates UTXOs for wallet-owned outputs (both standard P2PKH and plugin-identified).
 */
@Testcontainers
class AutoRecordUtxoIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("libspiffy4j_test")
                    .withUsername("test")
                    .withPassword("test");

    private static LibSpiffy4j lib;
    private static PGSimpleDataSource dataSource;
    private static final WalletReadModelStorage storage = new WalletReadModelStorage();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PROJECTION_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Mutable — set after the first wallet is created so the StubTokenPlugin
     * can return the correct ownerAddress.
     */
    private static volatile String discoveredAddress;

    @BeforeAll
    static void setup() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(PG.getJdbcUrl());
        dataSource.setUser(PG.getUsername());
        dataSource.setPassword(PG.getPassword());

        runMigrations(dataSource);

        Config config = ConfigFactory.parseString("""
                pekko.actor.provider = "cluster"
                pekko.remote.artery.canonical.hostname = "127.0.0.1"
                pekko.remote.artery.canonical.port = 0
                pekko.cluster.downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
                pekko.actor.allow-java-serialization = on
                """);

        lib = LibSpiffy4j.builder()
                .dataSource(dataSource)
                .configOverride(config)
                .encryptionMasterKey(EncryptionService.generateMasterKey())
                .registerPlugin(new StubTokenPlugin())
                .build();

        // Wait for cluster to form
        Thread.sleep(3000);
    }

    @AfterAll
    static void teardown() {
        if (lib != null) {
            lib.close();
        }
    }

    /**
     * Helper: create a wallet via the coordinator and discover its root address
     * from the read model. The coordinator generates HD keys, derives the root
     * address, and registers it internally.
     */
    private String createWalletAndDiscoverAddress(String walletId, String name) throws Exception {
        ActorRef<CoordinatorCommand> coordinator = lib.coordinator();
        TestProbe<CoordinatorReply> probe = ActorTestKit.create(lib.system()).createTestProbe();

        coordinator.tell(new CoordinatorCommand.CreateWallet(
                walletId, name, WalletType.HD, NetworkType.TESTNET,
                null, null, null, Map.of(), probe.ref()));
        CoordinatorReply createReply = probe.receiveMessage(TIMEOUT);
        assertThat(createReply)
                .describedAs("CreateWallet reply for '%s'", walletId)
                .isInstanceOf(CoordinatorReply.WalletCreated.class);

        // Wait for the root address to be projected
        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<String> addresses = storage.findAddressesByWalletId(dataSource, walletId);
            assertThat(addresses).isNotEmpty();
        });

        List<String> addresses = storage.findAddressesByWalletId(dataSource, walletId);
        String address = addresses.get(0);
        discoveredAddress = address;
        return address;
    }

    @Test
    void recordTransaction_autoRecordsP2PKHOutputs() throws Exception {
        String walletId = "auto-p2pkh-1";
        ActorRef<CoordinatorCommand> coordinator = lib.coordinator();
        TestProbe<CoordinatorReply> probe = ActorTestKit.create(lib.system()).createTestProbe();

        String walletAddress = createWalletAndDiscoverAddress(walletId, "Auto P2PKH");

        // Build a raw transaction with a P2PKH output to the wallet address
        byte[] hash160 = LegacyAddress.fromBase58(
                org.twostack.bitcoin4j.params.NetworkType.TEST, walletAddress).getHash();
        Script p2pkhScript = ScriptBuilder.createP2PKHOutputScript(hash160);
        Transaction tx = new Transaction();
        tx.addInput(new TransactionInput(
                new byte[32], 0xFFFFFFFFL, 0xFFFFFFFFL,
                new DefaultUnlockBuilder()));
        tx.addOutput(new TransactionOutput(BigInteger.valueOf(50000), p2pkhScript));

        byte[] rawBytes = tx.serialize();
        String rawHex = Utils.HEX.encode(rawBytes);
        String txid = Utils.HEX.encode(Sha256Hash.hashTwice(rawBytes));

        // Record the transaction via coordinator
        BitcoinTransaction btx = new BitcoinTransaction(walletId, txid, rawHex,
                TransactionStatus.BROADCAST, TransactionDirection.INCOMING,
                null, 0, 0, 50000, 0, 50000,
                List.of(), List.of(walletAddress),
                Instant.now(), Instant.now(), null, 0, 1);
        coordinator.tell(new CoordinatorCommand.RecordTransaction(walletId, btx, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        // Verify: UTXO auto-recorded and visible in read model
        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<BitcoinUtxo> utxos = storage.findUtxosByWalletId(dataSource, walletId);
            assertThat(utxos).hasSizeGreaterThanOrEqualTo(1);

            BitcoinUtxo autoRecorded = utxos.stream()
                    .filter(u -> u.txid().equals(txid))
                    .findFirst().orElse(null);
            assertThat(autoRecorded).isNotNull();
            assertThat(autoRecorded.valueSats()).isEqualTo(50000);
            assertThat(autoRecorded.address()).isEqualTo(walletAddress);
            assertThat(autoRecorded.scriptPubKey()).isNotNull();
            assertThat(autoRecorded.scriptPubKey()).isNotBlank();
        });
    }

    @Test
    void recordTransaction_autoRecordsPluginIdentifiedOutputs() throws Exception {
        String walletId = "auto-plugin-1";
        ActorRef<CoordinatorCommand> coordinator = lib.coordinator();
        TestProbe<CoordinatorReply> probe = ActorTestKit.create(lib.system()).createTestProbe();

        String walletAddress = createWalletAndDiscoverAddress(walletId, "Auto Plugin");

        // Build transaction with a non-standard (plugin) output
        // OP_RETURN + data starting with 0xAA — recognized by StubTokenPlugin
        byte[] pluginData = new byte[]{(byte) 0xAA, 0x01, 0x02, 0x03, 0x04};
        Script pluginScript = new ScriptBuilder()
                .op(ScriptOpCodes.OP_RETURN)
                .data(pluginData)
                .build();

        Transaction tx = new Transaction();
        tx.addInput(new TransactionInput(
                new byte[32], 0xFFFFFFFFL, 0xFFFFFFFFL,
                new DefaultUnlockBuilder()));
        tx.addOutput(new TransactionOutput(BigInteger.valueOf(10000), pluginScript));

        byte[] rawBytes = tx.serialize();
        String rawHex = Utils.HEX.encode(rawBytes);
        String txid = Utils.HEX.encode(Sha256Hash.hashTwice(rawBytes));

        // Record transaction
        BitcoinTransaction btx = new BitcoinTransaction(walletId, txid, rawHex,
                TransactionStatus.BROADCAST, TransactionDirection.INCOMING,
                null, 0, 0, 10000, 0, 10000,
                List.of(), List.of(),
                Instant.now(), Instant.now(), null, 0, 1);
        coordinator.tell(new CoordinatorCommand.RecordTransaction(walletId, btx, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        // Verify: plugin-identified UTXO auto-recorded and enriched by projection
        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<BitcoinUtxo> utxos = storage.findUtxosByWalletId(dataSource, walletId);

            BitcoinUtxo pluginUtxo = utxos.stream()
                    .filter(u -> u.txid().equals(txid))
                    .findFirst().orElse(null);
            assertThat(pluginUtxo).isNotNull();
            assertThat(pluginUtxo.valueSats()).isEqualTo(10000);
            assertThat(pluginUtxo.scriptPubKey()).isNotNull();
            assertThat(pluginUtxo.pluginId()).isEqualTo("stub-token");
            assertThat(pluginUtxo.pluginMetadata()).containsEntry("tokenId", "T001");
            assertThat(pluginUtxo.pluginMetadata()).containsEntry("ownerAddress", walletAddress);
        });
    }

    @Test
    void deriveAddress_returnsNewAddress() throws Exception {
        String walletId = "derive-addr-1";
        ActorRef<CoordinatorCommand> coordinator = lib.coordinator();

        String rootAddress = createWalletAndDiscoverAddress(walletId, "DeriveAddress Test");

        // Derive a second address
        CoordinatorReply deriveReply = org.apache.pekko.actor.typed.javadsl.AskPattern
                .<CoordinatorCommand, CoordinatorReply>ask(
                        coordinator,
                        replyTo -> new CoordinatorCommand.DeriveAddress(walletId, replyTo),
                        TIMEOUT, lib.system().scheduler()
                ).toCompletableFuture().get(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

        assertThat(deriveReply).isInstanceOf(CoordinatorReply.AddressDerived.class);
        CoordinatorReply.AddressDerived derived = (CoordinatorReply.AddressDerived) deriveReply;
        assertThat(derived.address()).isNotBlank();
        assertThat(derived.address()).isNotEqualTo(rootAddress);
        assertThat(derived.index()).isEqualTo(1);

        // Verify address is projected
        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<String> addresses = storage.findAddressesByWalletId(dataSource, walletId);
            assertThat(addresses).contains(rootAddress, derived.address());
        });
    }

    private static void runMigrations(PGSimpleDataSource ds) throws Exception {
        String[] scripts = {
                "db/libspiffy4j/V001__create_journal.sql",
                "db/libspiffy4j/V002__create_snapshot.sql",
                "db/libspiffy4j/V003__create_projection_offset.sql",
                "db/libspiffy4j/V004__create_wallet_read_models.sql",
                "db/libspiffy4j/V005__create_secure_storage.sql",
                "db/libspiffy4j/V007__add_raw_hex_to_wallet_transaction.sql",
                "db/libspiffy4j/V008__add_plugin_fields.sql",
                "db/libspiffy4j/V009__add_script_pub_key_to_wallet_utxo.sql",
                "db/libspiffy4j/V010__add_merkle_proof_to_wallet_transaction.sql",
                "db/libspiffy4j/V011__add_utxo_policy_to_wallet_summary.sql"
        };
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String script : scripts) {
                String sql = new String(
                        AutoRecordUtxoIntegrationTest.class.getClassLoader()
                                .getResourceAsStream(script).readAllBytes(),
                        StandardCharsets.UTF_8);
                stmt.execute(sql);
            }
        }
    }

    /**
     * Stub plugin that recognizes scripts starting with 0xAA.
     * Returns ownerAddress matching the wallet's discovered address.
     */
    static class StubTokenPlugin implements ScriptPlugin {

        @Override
        public String pluginId() { return "stub-token"; }

        @Override
        public String displayName() { return "Stub Token"; }

        @Override
        public List<String> scriptTypes() { return List.of("stub_ft"); }

        @Override
        public String identifyScript(byte[] scriptPubKey) {
            if (scriptPubKey != null && scriptPubKey.length > 2
                    && (scriptPubKey[0] & 0xFF) == 0x6a) {
                for (int i = 2; i < scriptPubKey.length; i++) {
                    if ((scriptPubKey[i] & 0xFF) == 0xAA) return "stub_ft";
                }
            }
            return null;
        }

        @Override
        public Map<String, Object> extractMetadata(byte[] scriptPubKey) {
            return Map.of(
                    "tokenId", "T001",
                    "ownerAddress", discoveredAddress != null ? discoveredAddress : "",
                    "amount", 1000
            );
        }

        @Override
        public byte[] createLockingScript(PluginLockSpec spec) {
            return new byte[0];
        }

        @Override
        public byte[] createUnlockingScript(PluginUnlockSpec spec) {
            return new byte[0];
        }
    }
}
