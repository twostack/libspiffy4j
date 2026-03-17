package org.twostack.libspiffy4j.projection;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.twostack.libspiffy4j.aggregate.wallet.WalletEvent;
import org.twostack.libspiffy4j.model.*;
import org.twostack.libspiffy4j.plugin.PluginLockSpec;
import org.twostack.libspiffy4j.plugin.PluginRegistry;
import org.twostack.libspiffy4j.plugin.PluginUnlockSpec;
import org.twostack.libspiffy4j.plugin.ScriptPlugin;
import org.twostack.libspiffy4j.storage.postgres.WalletReadModelStorage;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the projection layer correctly enriches UTXOs with plugin metadata
 * when a scriptPubKey is present and recognized by a registered plugin.
 */
@Testcontainers
class WalletProjectionEnrichmentTest {

    @Container
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("libspiffy4j_test")
                    .withUsername("test")
                    .withPassword("test");

    private static PGSimpleDataSource dataSource;
    private final WalletReadModelStorage storage = new WalletReadModelStorage();

    @BeforeAll
    static void setupSchema() throws Exception {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(PG.getJdbcUrl());
        dataSource.setUser(PG.getUsername());
        dataSource.setPassword(PG.getPassword());

        String[] scripts = {
                "db/libspiffy4j/V003__create_projection_offset.sql",
                "db/libspiffy4j/V004__create_wallet_read_models.sql",
                "db/libspiffy4j/V007__add_raw_hex_to_wallet_transaction.sql",
                "db/libspiffy4j/V008__add_plugin_fields.sql",
                "db/libspiffy4j/V009__add_script_pub_key_to_wallet_utxo.sql"
        };
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String script : scripts) {
                String sql = new String(
                        WalletProjectionEnrichmentTest.class.getClassLoader()
                                .getResourceAsStream(script).readAllBytes(),
                        StandardCharsets.UTF_8);
                stmt.execute(sql);
            }
        }
    }

    @BeforeEach
    void cleanTables() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM transaction_address_link");
            stmt.execute("DELETE FROM wallet_transaction");
            stmt.execute("DELETE FROM wallet_utxo");
            stmt.execute("DELETE FROM wallet_address");
            stmt.execute("DELETE FROM wallet_summary");
        }
    }

    @Test
    void enrichment_populatesPluginIdAndMetadata_whenPluginRecognizesScript() throws Exception {
        createSummary("w1");

        // Register a mock plugin that recognizes scripts starting with "aa"
        PluginRegistry registry = new PluginRegistry();
        registry.register(new StubTokenPlugin());

        WalletProjectionHandler handler = new WalletProjectionHandler(storage, registry);

        // UTXO has scriptPubKey but no pluginId — projection should enrich it
        BitcoinUtxo utxo = new BitcoinUtxo("tx1", 0, 10000, "aabb01020304", "addr1",
                UtxoStatus.AVAILABLE, null, 0, Instant.now(), Instant.now(),
                null, null, null, null, null,
                null, null);

        WalletEvent.UtxoReceivedEvent event =
                new WalletEvent.UtxoReceivedEvent("w1", utxo, Instant.now());

        // Simulate what the projection handler does: dispatch within a connection
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletUtxo(conn, "w1",
                    invokeEnrichment(handler, event));
            storage.updateWalletBalances(conn, "w1");
            conn.commit();
        }

        List<BitcoinUtxo> utxos = storage.findUtxosByWalletId(dataSource, "w1");
        assertThat(utxos).hasSize(1);

        BitcoinUtxo stored = utxos.get(0);
        assertThat(stored.pluginId()).isEqualTo("stub-token");
        assertThat(stored.pluginMetadata()).isNotNull();
        assertThat(stored.pluginMetadata()).containsEntry("tokenId", "T001");
        assertThat(stored.pluginMetadata()).containsEntry("ownerAddress", "addr1");
        assertThat(stored.scriptPubKey()).isEqualTo("aabb01020304");
    }

    @Test
    void enrichment_skipped_whenNoPluginRecognizesScript() throws Exception {
        createSummary("w2");

        PluginRegistry registry = new PluginRegistry();
        registry.register(new StubTokenPlugin());

        WalletProjectionHandler handler = new WalletProjectionHandler(storage, registry);

        // Script "ff..." not recognized by StubTokenPlugin
        BitcoinUtxo utxo = new BitcoinUtxo("tx2", 0, 20000, "ff0102030405", "addr2",
                UtxoStatus.AVAILABLE, null, 0, Instant.now(), Instant.now(),
                null, null, null, null, null,
                null, null);

        WalletEvent.UtxoReceivedEvent event =
                new WalletEvent.UtxoReceivedEvent("w2", utxo, Instant.now());

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletUtxo(conn, "w2",
                    invokeEnrichment(handler, event));
            conn.commit();
        }

        List<BitcoinUtxo> utxos = storage.findUtxosByWalletId(dataSource, "w2");
        assertThat(utxos).hasSize(1);
        assertThat(utxos.get(0).pluginId()).isNull();
        assertThat(utxos.get(0).pluginMetadata()).isNull();
        assertThat(utxos.get(0).scriptPubKey()).isEqualTo("ff0102030405");
    }

    @Test
    void enrichment_skipped_whenPluginIdAlreadySet() throws Exception {
        createSummary("w3");

        PluginRegistry registry = new PluginRegistry();
        registry.register(new StubTokenPlugin());

        WalletProjectionHandler handler = new WalletProjectionHandler(storage, registry);

        // UTXO already has pluginId set — should NOT be overwritten
        BitcoinUtxo utxo = new BitcoinUtxo("tx3", 0, 30000, "aabb01020304", "addr3",
                UtxoStatus.AVAILABLE, null, 0, Instant.now(), Instant.now(),
                null, null, null, null, null,
                "pre-existing-plugin", Map.of("custom", "data"));

        WalletEvent.UtxoReceivedEvent event =
                new WalletEvent.UtxoReceivedEvent("w3", utxo, Instant.now());

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletUtxo(conn, "w3",
                    invokeEnrichment(handler, event));
            conn.commit();
        }

        List<BitcoinUtxo> utxos = storage.findUtxosByWalletId(dataSource, "w3");
        assertThat(utxos).hasSize(1);
        assertThat(utxos.get(0).pluginId()).isEqualTo("pre-existing-plugin");
        assertThat(utxos.get(0).pluginMetadata()).containsEntry("custom", "data");
    }

    @Test
    void enrichment_skipped_whenNoScriptPubKey() throws Exception {
        createSummary("w4");

        PluginRegistry registry = new PluginRegistry();
        registry.register(new StubTokenPlugin());

        WalletProjectionHandler handler = new WalletProjectionHandler(storage, registry);

        // No scriptPubKey — enrichment should be skipped
        BitcoinUtxo utxo = new BitcoinUtxo("tx4", 0, 40000, null, "addr4",
                UtxoStatus.AVAILABLE, null, 0, Instant.now(), Instant.now(),
                null, null, null, null, null,
                null, null);

        WalletEvent.UtxoReceivedEvent event =
                new WalletEvent.UtxoReceivedEvent("w4", utxo, Instant.now());

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletUtxo(conn, "w4",
                    invokeEnrichment(handler, event));
            conn.commit();
        }

        List<BitcoinUtxo> utxos = storage.findUtxosByWalletId(dataSource, "w4");
        assertThat(utxos).hasSize(1);
        assertThat(utxos.get(0).pluginId()).isNull();
    }

    /**
     * Calls the handler's enrichWithPluginData via the same code path as dispatch,
     * but without needing a full Pekko projection pipeline.
     */
    private BitcoinUtxo invokeEnrichment(WalletProjectionHandler handler,
                                          WalletEvent.UtxoReceivedEvent event) {
        // Use reflection-free approach: the handler enriches then passes to storage.
        // We replicate just the enrichment by creating a handler and calling the
        // enrichment logic through the public path — but since enrichWithPluginData
        // is private, we test it through the storage round-trip (the assert checks
        // that pluginId/metadata were set before storage.upsertWalletUtxo was called).
        //
        // The handler is tested indirectly: we construct the same UTXO, and the
        // fact that storage receives enriched data proves the handler enriched it.
        //
        // For a direct test, we extract the enrichment logic.
        // Since enrichWithPluginData is private, we test via a package-private accessor.

        // Actually, we need to invoke the handler's enrichment. Let's use the same
        // approach: create handler, get the enriched UTXO.
        // We'll make enrichWithPluginData package-private for testability.
        return handler.enrichWithPluginData(event.utxo());
    }

    private void createSummary(String walletId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletSummary(conn, walletId, "Test", "addr",
                    WalletType.HD, NetworkType.TESTNET, Map.of(), Instant.now());
            conn.commit();
        }
    }

    /**
     * Stub plugin that recognizes any script starting with 0xAA.
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
            if (scriptPubKey != null && scriptPubKey.length > 0
                    && (scriptPubKey[0] & 0xFF) == 0xAA) {
                return "stub_ft";
            }
            return null;
        }

        @Override
        public Map<String, Object> extractMetadata(byte[] scriptPubKey) {
            return Map.of(
                    "tokenId", "T001",
                    "ownerAddress", "addr1",
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
