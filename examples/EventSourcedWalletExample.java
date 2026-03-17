package examples;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;

import org.twostack.libspiffy4j.LibSpiffy4j;
import org.twostack.libspiffy4j.coordinator.CoordinatorCommand;
import org.twostack.libspiffy4j.coordinator.CoordinatorReply;
import org.twostack.libspiffy4j.model.*;
import org.twostack.libspiffy4j.service.CryptoService;
import org.twostack.libspiffy4j.service.EncryptionService;
import org.twostack.libspiffy4j.storage.postgres.WalletReadModelStorage;
import org.twostack.libspiffy4j.storage.postgres.InvoiceReadModelStorage;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Demonstrates the Coordinator API: LibSpiffy4j bootstrap, wallet and invoice
 * operations via the WalletCoordinator, and read model queries.
 *
 * The Coordinator API ({@code libSpiffy.coordinator()}) is the recommended way
 * to interact with the event-sourced layer. It provides a unified command/reply
 * interface that handles aggregate routing, UTXO reservation, and transaction
 * building internally.
 *
 * Requires: PostgreSQL database with Pekko persistence schema.
 *
 * Covers:
 *   - LibSpiffy4j initialization and lifecycle
 *   - Wallet creation via the Coordinator API
 *   - Address and UTXO recording via the Coordinator API
 *   - Invoice creation via the Coordinator API
 *   - Balance query via the Coordinator API
 *   - Read model queries (WalletReadModelStorage, InvoiceReadModelStorage)
 *   - Secure key storage
 */
public class EventSourcedWalletExample {

    public static void main(String[] args) throws Exception {

        // ---------------------------------------------------------------
        // 1. Initialize LibSpiffy4j
        // ---------------------------------------------------------------

        // You must provide a PostgreSQL DataSource (e.g., HikariCP)
        DataSource dataSource = createDataSource();

        var libSpiffy = LibSpiffy4j.builder()
            .dataSource(dataSource)
            .objectMapper(new ObjectMapper())
            .encryptionMasterKey(EncryptionService.generateMasterKey())
            .build();

        ActorSystem<Void> system = libSpiffy.system();
        ActorRef<CoordinatorCommand> coordinator = libSpiffy.coordinator();

        var crypto = libSpiffy.cryptoService();
        var encryption = libSpiffy.encryptionService();
        var secureStorage = libSpiffy.secureStorage();

        // ---------------------------------------------------------------
        // 2. Create a Wallet (Coordinator API)
        // ---------------------------------------------------------------

        // Generate keys
        List<String> mnemonic = crypto.generateMnemonic();
        var hdKey = crypto.mnemonicToHDPrivateKey(mnemonic, "");
        var key0 = crypto.derivePrivateKey(hdKey, 0, 0, 0, false);
        String rootAddress = crypto.generateAddress(key0, NetworkType.MAINNET);

        String walletId = UUID.randomUUID().toString();

        // Create the wallet via the coordinator
        CompletionStage<CoordinatorReply> createReply = AskPattern.ask(
            coordinator,
            replyTo -> new CoordinatorCommand.CreateWallet(
                walletId,
                "My First Wallet",
                WalletType.HD,
                NetworkType.MAINNET,
                rootAddress,
                Map.of("source", "example"),
                replyTo
            ),
            Duration.ofSeconds(10),
            system.scheduler()
        );

        CoordinatorReply reply = createReply.toCompletableFuture().join();
        System.out.println("Create wallet reply: " + reply);

        // ---------------------------------------------------------------
        // 3. Store Encrypted Key (SecureStorage)
        // ---------------------------------------------------------------

        // Encrypt the HD key for persistent storage
        byte[] hdKeyBytes = hdKey.serializePrivate();
        var encrypted = encryption.encrypt(hdKeyBytes, "wallet:" + walletId + ":hdkey");

        // Store requires a JDBC Connection (for transaction control)
        try (var conn = dataSource.getConnection()) {
            secureStorage.storeEncryptedKey(
                conn, walletId, "hdkey",
                encrypted.ciphertext(), encrypted.nonce(), 1
            );
            conn.commit();
        }

        // ---------------------------------------------------------------
        // 4. Record Addresses (Coordinator API)
        // ---------------------------------------------------------------

        // Derive and record multiple addresses
        for (int i = 0; i < 5; i++) {
            var key = crypto.derivePrivateKey(hdKey, 0, i, 0, false);
            String address = crypto.generateAddress(key, NetworkType.MAINNET);

            // RecordAddress takes AddressMetadata, not a plain String
            var addressMetadata = new AddressMetadata(address, i, false);

            AskPattern.ask(
                coordinator,
                replyTo -> new CoordinatorCommand.RecordAddress(
                    walletId, addressMetadata, replyTo
                ),
                Duration.ofSeconds(10),
                system.scheduler()
            ).toCompletableFuture().join();

            System.out.println("Recorded address " + i + ": " + address);
        }

        // ---------------------------------------------------------------
        // 5. Record a UTXO (Coordinator API)
        // ---------------------------------------------------------------

        var utxo = new BitcoinUtxo(
            "abc123def456...", 0, 100_000L,
            "", rootAddress,
            UtxoStatus.AVAILABLE,
            850_000, 6,
            Instant.now(), Instant.now(),
            null, null, null, null, 0,
            null, null
        );

        AskPattern.ask(
            coordinator,
            replyTo -> new CoordinatorCommand.RecordUtxo(
                walletId, utxo, replyTo
            ),
            Duration.ofSeconds(10),
            system.scheduler()
        ).toCompletableFuture().join();

        System.out.println("Recorded UTXO: " + utxo.key());

        // ---------------------------------------------------------------
        // 6. Create an Invoice (Coordinator API)
        // ---------------------------------------------------------------

        String invoiceId = UUID.randomUUID().toString();

        String paymentAddress = crypto.generateAddress(
            crypto.derivePrivateKey(hdKey, 0, 5, 0, false), NetworkType.MAINNET
        );

        AskPattern.ask(
            coordinator,
            replyTo -> new CoordinatorCommand.CreateInvoice(
                invoiceId,
                walletId,
                List.of(paymentAddress),
                50_000L,
                List.of(new InvoiceOutputSpec.P2PKHOutputSpec(paymentAddress, 50_000L, "order-456")),
                "Payment for Order #456",
                Instant.now().plus(Duration.ofHours(24)),
                Map.of("orderId", "456"),
                replyTo
            ),
            Duration.ofSeconds(10),
            system.scheduler()
        ).toCompletableFuture().join();

        System.out.println("Created invoice: " + invoiceId);

        // ---------------------------------------------------------------
        // 7. Query Balance via Coordinator
        // ---------------------------------------------------------------

        CompletionStage<CoordinatorReply> balanceReply = AskPattern.ask(
            coordinator,
            replyTo -> new CoordinatorCommand.GetBalance(walletId, replyTo),
            Duration.ofSeconds(10),
            system.scheduler()
        );

        CoordinatorReply balanceResult = balanceReply.toCompletableFuture().join();
        if (balanceResult instanceof CoordinatorReply.BalanceResult br) {
            System.out.println("Coordinator balance: " + br.balance());
        }

        // ---------------------------------------------------------------
        // 8. Query Read Models (direct storage access)
        // ---------------------------------------------------------------

        // Allow a moment for projections to update the read models
        Thread.sleep(1000);

        var walletStorage = new WalletReadModelStorage();
        var invoiceStorage = new InvoiceReadModelStorage();

        // Wallet summary
        Optional<WalletSummary> summary = walletStorage.findWalletSummary(dataSource, walletId);
        summary.ifPresent(s -> {
            System.out.println("\nWallet summary:");
            System.out.println("  Name:      " + s.name());
            System.out.println("  Type:      " + s.walletType());
            System.out.println("  Confirmed: " + s.confirmedBalanceSats() + " sats");
            System.out.println("  UTXOs:     " + s.utxoCount());
            System.out.println("  Addresses: " + s.addressCount());
        });

        // List UTXOs by status
        List<BitcoinUtxo> available = walletStorage.findUtxosByStatus(
            dataSource, walletId, UtxoStatus.AVAILABLE
        );
        System.out.println("Available UTXOs: " + available.size());

        // Wallet balance
        walletStorage.getWalletBalance(dataSource, walletId).ifPresent(b -> {
            System.out.println("Balance: " + b);
        });

        // Invoice queries
        Optional<Invoice> invoice = invoiceStorage.findInvoice(dataSource, invoiceId);
        invoice.ifPresent(inv -> {
            System.out.println("\nInvoice: " + inv.invoiceId());
            System.out.println("  Status:  " + inv.status());
            System.out.println("  Amount:  " + inv.amountSats() + " sats");
            System.out.println("  Expires: " + inv.expiresAt());
        });

        // List pending invoices
        List<Invoice> pendingInvoices = invoiceStorage.listInvoices(
            dataSource, walletId, InvoiceStatus.PENDING, 50, 0
        );
        System.out.println("Pending invoices: " + pendingInvoices.size());

        // ---------------------------------------------------------------
        // 9. Cleanup
        // ---------------------------------------------------------------

        libSpiffy.close();
        System.out.println("\nLibSpiffy4j shut down.");
    }

    /** Placeholder — replace with your actual DataSource (e.g., HikariDataSource). */
    private static DataSource createDataSource() {
        // Example with HikariCP:
        //
        // var config = new HikariConfig();
        // config.setJdbcUrl("jdbc:postgresql://localhost:5432/spiffy");
        // config.setUsername("spiffy");
        // config.setPassword("spiffy");
        // config.setAutoCommit(false);
        // return new HikariDataSource(config);

        throw new UnsupportedOperationException(
            "Replace this method with your actual PostgreSQL DataSource"
        );
    }
}
