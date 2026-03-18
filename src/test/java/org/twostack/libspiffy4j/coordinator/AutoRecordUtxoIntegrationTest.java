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

    // Fixed 20-byte hash160 and the corresponding testnet address
    private static final byte[] TEST_HASH160 = Utils.HEX.decode("89abcdefabbaabbaabbaabbaabbaabbaabbaabba");
    private static final String TEST_ADDRESS;
    static {
        TEST_ADDRESS = LegacyAddress.fromPubKeyHash(NetworkAddressType.TEST_PKH, TEST_HASH160).toBase58();
    }

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
                .registerPlugin(new StubTokenPlugin(TEST_ADDRESS))
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

    @Test
    void recordTransaction_autoRecordsP2PKHOutputs() throws Exception {
        String walletId = "auto-p2pkh-1";
        ActorRef<CoordinatorCommand> coordinator = lib.coordinator();
        TestProbe<CoordinatorReply> probe = ActorTestKit.create(lib.system()).createTestProbe();

        // 1. Create wallet
        coordinator.tell(new CoordinatorCommand.CreateWallet(
                walletId, "Auto P2PKH", WalletType.HD, NetworkType.TESTNET,
                TEST_ADDRESS, Map.of(), probe.ref()));
        probe.receiveMessage(TIMEOUT);

        // 2. Register the wallet address
        AddressMetadata addr = new AddressMetadata(TEST_ADDRESS, BitcoinScriptType.P2PKH,
                "m/44'/0'/0'/0/0", 0, false, null, null, null, null, 0, 0, Instant.now(), false);
        coordinator.tell(new CoordinatorCommand.RecordAddress(walletId, addr, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        // Wait for address to be projected
        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<String> addresses = storage.findAddressesByWalletId(dataSource, walletId);
            assertThat(addresses).contains(TEST_ADDRESS);
        });

        // 3. Build a raw transaction with a P2PKH output to the wallet address
        Script p2pkhScript = ScriptBuilder.createP2PKHOutputScript(TEST_HASH160);
        Transaction tx = new Transaction();
        // Dummy input (coinbase-like)
        tx.addInput(new TransactionInput(
                new byte[32], 0xFFFFFFFFL, 0xFFFFFFFFL,
                new DefaultUnlockBuilder()));
        // P2PKH output to wallet address
        tx.addOutput(new TransactionOutput(BigInteger.valueOf(50000), p2pkhScript));

        byte[] rawBytes = tx.serialize();
        String rawHex = Utils.HEX.encode(rawBytes);
        String txid = Utils.HEX.encode(Sha256Hash.hashTwice(rawBytes));

        // 4. Record the transaction via coordinator
        BitcoinTransaction btx = new BitcoinTransaction(walletId, txid, rawHex,
                TransactionStatus.BROADCAST, TransactionDirection.INCOMING,
                null, 0, 0, 50000, 0, 50000,
                List.of(), List.of(TEST_ADDRESS),
                Instant.now(), Instant.now(), null, 0, 1);
        coordinator.tell(new CoordinatorCommand.RecordTransaction(walletId, btx, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        // 5. Verify: UTXO auto-recorded and visible in read model
        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<BitcoinUtxo> utxos = storage.findUtxosByWalletId(dataSource, walletId);
            assertThat(utxos).hasSizeGreaterThanOrEqualTo(1);

            BitcoinUtxo autoRecorded = utxos.stream()
                    .filter(u -> u.txid().equals(txid))
                    .findFirst().orElse(null);
            assertThat(autoRecorded).isNotNull();
            assertThat(autoRecorded.valueSats()).isEqualTo(50000);
            assertThat(autoRecorded.address()).isEqualTo(TEST_ADDRESS);
            assertThat(autoRecorded.scriptPubKey()).isNotNull();
            assertThat(autoRecorded.scriptPubKey()).isNotBlank();
        });
    }

    @Test
    void recordTransaction_autoRecordsPluginIdentifiedOutputs() throws Exception {
        String walletId = "auto-plugin-1";
        ActorRef<CoordinatorCommand> coordinator = lib.coordinator();
        TestProbe<CoordinatorReply> probe = ActorTestKit.create(lib.system()).createTestProbe();

        // 1. Create wallet
        coordinator.tell(new CoordinatorCommand.CreateWallet(
                walletId, "Auto Plugin", WalletType.HD, NetworkType.TESTNET,
                TEST_ADDRESS, Map.of(), probe.ref()));
        probe.receiveMessage(TIMEOUT);

        // 2. Register wallet address
        AddressMetadata addr = new AddressMetadata(TEST_ADDRESS, BitcoinScriptType.P2PKH,
                "m/44'/0'/0'/0/0", 0, false, null, null, null, null, 0, 0, Instant.now(), false);
        coordinator.tell(new CoordinatorCommand.RecordAddress(walletId, addr, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<String> addresses = storage.findAddressesByWalletId(dataSource, walletId);
            assertThat(addresses).contains(TEST_ADDRESS);
        });

        // 3. Build transaction with a non-standard (plugin) output
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

        // 4. Record transaction
        BitcoinTransaction btx = new BitcoinTransaction(walletId, txid, rawHex,
                TransactionStatus.BROADCAST, TransactionDirection.INCOMING,
                null, 0, 0, 10000, 0, 10000,
                List.of(), List.of(),
                Instant.now(), Instant.now(), null, 0, 1);
        coordinator.tell(new CoordinatorCommand.RecordTransaction(walletId, btx, probe.ref()));
        probe.receiveMessage(TIMEOUT);

        // 5. Verify: plugin-identified UTXO auto-recorded and enriched by projection
        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<BitcoinUtxo> utxos = storage.findUtxosByWalletId(dataSource, walletId);

            BitcoinUtxo pluginUtxo = utxos.stream()
                    .filter(u -> u.txid().equals(txid))
                    .findFirst().orElse(null);
            assertThat(pluginUtxo).isNotNull();
            assertThat(pluginUtxo.valueSats()).isEqualTo(10000);
            assertThat(pluginUtxo.scriptPubKey()).isNotNull();
            // Projection should have enriched with plugin data
            assertThat(pluginUtxo.pluginId()).isEqualTo("stub-token");
            assertThat(pluginUtxo.pluginMetadata()).containsEntry("tokenId", "T001");
            assertThat(pluginUtxo.pluginMetadata()).containsEntry("ownerAddress", TEST_ADDRESS);
        });
    }

    @Test
    void askPattern_createWallet_thenRecordAddress_works() throws Exception {
        String walletId = "ask-pattern-test-1";
        ActorRef<CoordinatorCommand> coordinator = lib.coordinator();

        // Use AskPattern (same as Monocelo's WalletProvisioningService)
        CoordinatorReply createReply = org.apache.pekko.actor.typed.javadsl.AskPattern
                .<CoordinatorCommand, CoordinatorReply>ask(
                        coordinator,
                        replyTo -> new CoordinatorCommand.CreateWallet(
                                walletId, "AskPattern Test", WalletType.HD, NetworkType.TESTNET,
                                TEST_ADDRESS, Map.of(), replyTo),
                        TIMEOUT, lib.system().scheduler()
                ).toCompletableFuture().get(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

        assertThat(createReply).isInstanceOf(CoordinatorReply.WalletCreated.class);

        // Immediately follow with RecordAddress via AskPattern
        AddressMetadata addr = new AddressMetadata(TEST_ADDRESS, BitcoinScriptType.P2PKH,
                null, null, false, "root", "merchant-root", null, null, 0, 0, Instant.now(), true);
        try {
            CoordinatorReply addrReply = org.apache.pekko.actor.typed.javadsl.AskPattern
                    .<CoordinatorCommand, CoordinatorReply>ask(
                            coordinator,
                            replyTo -> new CoordinatorCommand.RecordAddress(walletId, addr, replyTo),
                            TIMEOUT, lib.system().scheduler()
                    ).toCompletableFuture().get(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            assertThat(addrReply)
                    .describedAs("RecordAddress reply was: %s", addrReply)
                    .isInstanceOf(CoordinatorReply.CommandAccepted.class);
        } catch (java.util.concurrent.TimeoutException te) {
            throw new AssertionError("RecordAddress timed out after " + TIMEOUT + " — AskPattern reply never received", te);
        } catch (java.util.concurrent.ExecutionException ee) {
            throw new AssertionError("RecordAddress ExecutionException: " + ee.getCause(), ee);
        }

        // Verify address projected
        await().atMost(PROJECTION_TIMEOUT).untilAsserted(() -> {
            List<String> addresses = storage.findAddressesByWalletId(dataSource, walletId);
            assertThat(addresses).contains(TEST_ADDRESS);
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
                        AutoRecordUtxoIntegrationTest.class.getClassLoader()
                                .getResourceAsStream(script).readAllBytes(),
                        StandardCharsets.UTF_8);
                stmt.execute(sql);
            }
        }
    }

    /**
     * Stub plugin that recognizes scripts starting with 0xAA.
     * Returns ownerAddress matching the wallet's test address.
     */
    static class StubTokenPlugin implements ScriptPlugin {

        private final String ownerAddress;

        StubTokenPlugin(String ownerAddress) {
            this.ownerAddress = ownerAddress;
        }

        @Override
        public String pluginId() { return "stub-token"; }

        @Override
        public String displayName() { return "Stub Token"; }

        @Override
        public List<String> scriptTypes() { return List.of("stub_ft"); }

        @Override
        public String identifyScript(byte[] scriptPubKey) {
            // Recognize OP_RETURN scripts containing 0xAA marker in data payload
            if (scriptPubKey != null && scriptPubKey.length > 2
                    && (scriptPubKey[0] & 0xFF) == 0x6a) { // OP_RETURN
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
                    "ownerAddress", ownerAddress,
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
