package org.twostack.libspiffy4j.storage.postgres;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.twostack.libspiffy4j.model.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class WalletReadModelStorageTest {

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
                "db/libspiffy4j/V007__add_raw_hex_to_wallet_transaction.sql"
        };
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String script : scripts) {
                String sql = new String(
                        WalletReadModelStorageTest.class.getClassLoader()
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
    void upsertWalletSummary_insertsAndUpdates() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletSummary(conn, "w1", "My Wallet", "tb1qroot",
                    WalletType.HD, NetworkType.TESTNET, Map.of("key", "val"), Instant.now());
            conn.commit();
        }

        Optional<WalletSummary> result = storage.findWalletSummary(dataSource, "w1");
        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("My Wallet");
        assertThat(result.get().walletType()).isEqualTo(WalletType.HD);
        assertThat(result.get().metadata()).containsEntry("key", "val");

        // Upsert with new name
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletSummary(conn, "w1", "Updated Wallet", "tb1qroot",
                    WalletType.HD, NetworkType.TESTNET, Map.of(), Instant.now());
            conn.commit();
        }

        result = storage.findWalletSummary(dataSource, "w1");
        assertThat(result.get().name()).isEqualTo("Updated Wallet");
    }

    @Test
    void upsertWalletUtxo_insertsAndQueries() throws Exception {
        createSummary("w2");

        BitcoinUtxo utxo = new BitcoinUtxo("tx1", 0, 50000, "76a914...", "tb1qaddr",
                UtxoStatus.AVAILABLE, 100, 6, Instant.now(), Instant.now(),
                null, null, null, null, 0);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletUtxo(conn, "w2", utxo);
            conn.commit();
        }

        List<BitcoinUtxo> utxos = storage.findUtxosByWalletId(dataSource, "w2");
        assertThat(utxos).hasSize(1);
        assertThat(utxos.get(0).valueSats()).isEqualTo(50000);
        assertThat(utxos.get(0).status()).isEqualTo(UtxoStatus.AVAILABLE);
    }

    @Test
    void findUtxosByStatus_filtersCorrectly() throws Exception {
        createSummary("w3");

        BitcoinUtxo available = new BitcoinUtxo("tx1", 0, 30000, "script", "addr1",
                UtxoStatus.AVAILABLE, 100, 3, Instant.now(), Instant.now(),
                null, null, null, null, 0);
        BitcoinUtxo spent = new BitcoinUtxo("tx2", 0, 20000, "script", "addr2",
                UtxoStatus.AVAILABLE, 100, 3, Instant.now(), Instant.now(),
                null, null, null, null, 0);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletUtxo(conn, "w3", available);
            storage.upsertWalletUtxo(conn, "w3", spent);
            storage.updateUtxoStatus(conn, "w3", "tx2:0", UtxoStatus.SPENT, Instant.now());
            conn.commit();
        }

        List<BitcoinUtxo> avail = storage.findUtxosByStatus(dataSource, "w3", UtxoStatus.AVAILABLE);
        assertThat(avail).hasSize(1);
        assertThat(avail.get(0).txid()).isEqualTo("tx1");

        List<BitcoinUtxo> spentList = storage.findUtxosByStatus(dataSource, "w3", UtxoStatus.SPENT);
        assertThat(spentList).hasSize(1);
    }

    @Test
    void balanceCalculation_aggregatesCorrectly() throws Exception {
        createSummary("w4");

        // Confirmed UTXO (available, confirmations > 0)
        BitcoinUtxo confirmed = new BitcoinUtxo("tx1", 0, 50000, "s", "a1",
                UtxoStatus.AVAILABLE, 100, 6, Instant.now(), Instant.now(),
                null, null, null, null, 0);
        // Unconfirmed UTXO (available, confirmations = 0)
        BitcoinUtxo unconfirmed = new BitcoinUtxo("tx2", 0, 30000, "s", "a2",
                UtxoStatus.AVAILABLE, null, 0, Instant.now(), Instant.now(),
                null, null, null, null, 0);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletUtxo(conn, "w4", confirmed);
            storage.upsertWalletUtxo(conn, "w4", unconfirmed);
            // Reserve the confirmed one
            storage.updateUtxoReserved(conn, "w4", "tx1:0", "spending-tx",
                    Instant.now().plusSeconds(3600), Instant.now());
            storage.updateWalletBalances(conn, "w4");
            conn.commit();
        }

        Optional<WalletSummary> summary = storage.findWalletSummary(dataSource, "w4");
        assertThat(summary).isPresent();
        assertThat(summary.get().confirmedBalanceSats()).isEqualTo(0);
        assertThat(summary.get().unconfirmedBalanceSats()).isEqualTo(30000);
        assertThat(summary.get().reservedBalanceSats()).isEqualTo(50000);

        Optional<WalletBalance> balance = storage.getWalletBalance(dataSource, "w4");
        assertThat(balance).isPresent();
        assertThat(balance.get().availableSats()).isEqualTo(-50000); // confirmed(0) - reserved(50000)
    }

    @Test
    void pagination_worksForWalletSummaries() throws Exception {
        for (int i = 1; i <= 5; i++) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                storage.upsertWalletSummary(conn, "wp" + i, "Wallet " + i, "addr" + i,
                        WalletType.HD, NetworkType.TESTNET, Map.of(), Instant.now());
                conn.commit();
            }
        }

        List<WalletSummary> page1 = storage.listWalletSummaries(dataSource, 2, 0);
        assertThat(page1).hasSize(2);

        List<WalletSummary> page2 = storage.listWalletSummaries(dataSource, 2, 2);
        assertThat(page2).hasSize(2);

        List<WalletSummary> page3 = storage.listWalletSummaries(dataSource, 2, 4);
        assertThat(page3).hasSize(1);
    }

    @Test
    void addressQuery_returnsCorrectAddresses() throws Exception {
        createSummary("w5");

        AddressMetadata addr1 = new AddressMetadata("tb1qaddr1", BitcoinScriptType.P2PKH,
                "m/44'/0'/0'/0/0", 0, false, null, null, null, null, 0, 0, Instant.now(), false);
        AddressMetadata addr2 = new AddressMetadata("tb1qaddr2", BitcoinScriptType.P2PKH,
                "m/44'/0'/0'/0/1", 1, false, null, null, null, null, 0, 0, Instant.now(), false);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletAddress(conn, "w5", addr1, Instant.now());
            storage.upsertWalletAddress(conn, "w5", addr2, Instant.now());
            conn.commit();
        }

        List<String> addresses = storage.findAddressesByWalletId(dataSource, "w5");
        assertThat(addresses).containsExactly("tb1qaddr1", "tb1qaddr2");
    }

    @Test
    void transactionRecordAndConfirm_lifecycle() throws Exception {
        createSummary("w6");

        BitcoinTransaction tx = new BitcoinTransaction("w6", "txABC", "rawhex",
                TransactionStatus.BROADCAST, TransactionDirection.INCOMING,
                null, 0, 0, 50000, 1000, 49000,
                List.of("sender1"), List.of("receiver1"),
                Instant.now(), Instant.now(), null, 0, 2);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletTransaction(conn, "w6", tx);
            conn.commit();
        }

        List<BitcoinTransaction> txs = storage.findTransactionsByWalletId(dataSource, "w6", 10, 0);
        assertThat(txs).hasSize(1);
        assertThat(txs.get(0).status()).isEqualTo(TransactionStatus.BROADCAST);

        // Check address links
        List<String> senders = storage.findAddressesByTransaction(dataSource, "w6", "txABC", "SENDER");
        assertThat(senders).containsExactly("sender1");
        List<String> receivers = storage.findAddressesByTransaction(dataSource, "w6", "txABC", "RECEIVER");
        assertThat(receivers).containsExactly("receiver1");

        // Confirm
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.updateTransactionConfirmed(conn, "w6", "txABC", 6, 800000, Instant.now());
            conn.commit();
        }

        txs = storage.findTransactionsByWalletId(dataSource, "w6", 10, 0);
        assertThat(txs.get(0).status()).isEqualTo(TransactionStatus.CONFIRMED);
    }

    @Test
    void upsertWalletTransaction_persistsAndRetrievesRawHex() throws Exception {
        createSummary("w7");

        BitcoinTransaction tx = new BitcoinTransaction("w7", "txRawHex1", "0100000001abcdef",
                TransactionStatus.BROADCAST, TransactionDirection.OUTGOING,
                null, 0, 0, 50000, 1000, 49000,
                List.of(), List.of(),
                Instant.now(), Instant.now(), null, 0, 2);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletTransaction(conn, "w7", tx);
            conn.commit();
        }

        List<BitcoinTransaction> txs = storage.findTransactionsByWalletId(dataSource, "w7", 10, 0);
        assertThat(txs).hasSize(1);
        assertThat(txs.get(0).rawHex()).isEqualTo("0100000001abcdef");
    }

    @Test
    void findRawHexByTxid_returnsStoredHex() throws Exception {
        createSummary("w8");

        BitcoinTransaction tx = new BitcoinTransaction("w8", "txFindHex1", "deadbeef01020304",
                TransactionStatus.BROADCAST, TransactionDirection.OUTGOING,
                null, 0, 0, 50000, 1000, 49000,
                List.of(), List.of(),
                Instant.now(), Instant.now(), null, 0, 2);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletTransaction(conn, "w8", tx);
            conn.commit();
        }

        Optional<String> rawHex = storage.findRawHexByTxid(dataSource, "txFindHex1");
        assertThat(rawHex).isPresent().hasValue("deadbeef01020304");
    }

    @Test
    void findRawHexByTxid_returnsEmptyWhenNotStored() throws Exception {
        createSummary("w9");

        BitcoinTransaction tx = new BitcoinTransaction("w9", "txNoHex1", null,
                TransactionStatus.BROADCAST, TransactionDirection.OUTGOING,
                null, 0, 0, 50000, 1000, 49000,
                List.of(), List.of(),
                Instant.now(), Instant.now(), null, 0, 2);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletTransaction(conn, "w9", tx);
            conn.commit();
        }

        Optional<String> rawHex = storage.findRawHexByTxid(dataSource, "txNoHex1");
        assertThat(rawHex).isEmpty();
    }

    private void createSummary(String walletId) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            storage.upsertWalletSummary(conn, walletId, "Test", "addr",
                    WalletType.HD, NetworkType.TESTNET, Map.of(), Instant.now());
            conn.commit();
        }
    }
}
