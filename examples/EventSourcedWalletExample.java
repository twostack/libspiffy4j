package examples;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef;

import org.twostack.libspiffy4j.LibSpiffy4j;
import org.twostack.libspiffy4j.aggregate.wallet.WalletAggregate;
import org.twostack.libspiffy4j.aggregate.wallet.WalletCommand;
import org.twostack.libspiffy4j.aggregate.wallet.WalletReply;
import org.twostack.libspiffy4j.aggregate.invoice.InvoiceAggregate;
import org.twostack.libspiffy4j.aggregate.invoice.InvoiceCommand;
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
 * Demonstrates the full event-sourced layer: LibSpiffy4j bootstrap, wallet aggregate
 * commands, invoice creation, and read model queries.
 *
 * Requires: PostgreSQL database with Pekko persistence schema.
 *
 * Covers:
 *   - LibSpiffy4j initialization and lifecycle
 *   - Wallet creation via aggregate command
 *   - Address and UTXO recording
 *   - Invoice creation
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
        ClusterSharding sharding = ClusterSharding.get(system);

        var crypto = libSpiffy.cryptoService();
        var encryption = libSpiffy.encryptionService();
        var secureStorage = libSpiffy.secureStorage();

        // ---------------------------------------------------------------
        // 2. Create a Wallet (Aggregate Command)
        // ---------------------------------------------------------------

        // Generate keys
        List<String> mnemonic = crypto.generateMnemonic();
        var hdKey = crypto.mnemonicToHDPrivateKey(mnemonic, "");
        var key0 = crypto.derivePrivateKey(hdKey, 0, 0, 0, false);
        String rootAddress = crypto.generateAddress(key0, NetworkType.MAINNET);

        String walletId = UUID.randomUUID().toString();

        // Get entity reference for this wallet
        EntityRef<WalletCommand> walletRef = sharding.entityRefFor(
            WalletAggregate.ENTITY_TYPE_KEY, walletId
        );

        // Send CreateWalletCommand
        CompletionStage<WalletReply> createReply = walletRef.ask(
            replyTo -> new WalletCommand.CreateWalletCommand(
                walletId,
                "My First Wallet",
                rootAddress,
                WalletType.HD,
                NetworkType.MAINNET,
                Map.of("source", "example"),
                replyTo
            ),
            Duration.ofSeconds(10)
        );

        WalletReply reply = createReply.toCompletableFuture().join();
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
        // 4. Record Addresses
        // ---------------------------------------------------------------

        // Derive and record multiple addresses
        for (int i = 0; i < 5; i++) {
            var key = crypto.derivePrivateKey(hdKey, 0, i, 0, false);
            String address = crypto.generateAddress(key, NetworkType.MAINNET);

            walletRef.ask(
                replyTo -> new WalletCommand.RecordAddressCommand(
                    walletId, address, replyTo
                ),
                Duration.ofSeconds(10)
            ).toCompletableFuture().join();

            System.out.println("Recorded address " + i + ": " + address);
        }

        // ---------------------------------------------------------------
        // 5. Record a UTXO
        // ---------------------------------------------------------------

        var utxo = new BitcoinUtxo(
            "abc123def456...", 0, 100_000L,
            "", rootAddress,
            UtxoStatus.AVAILABLE,
            850_000, 6,
            Instant.now(), Instant.now(),
            null, null, null, null, 0
        );

        walletRef.ask(
            replyTo -> new WalletCommand.RecordUtxoCommand(
                walletId, utxo, replyTo
            ),
            Duration.ofSeconds(10)
        ).toCompletableFuture().join();

        System.out.println("Recorded UTXO: " + utxo.key());

        // ---------------------------------------------------------------
        // 6. Reserve a UTXO (for spending)
        // ---------------------------------------------------------------

        walletRef.ask(
            replyTo -> new WalletCommand.ReserveUtxoCommand(
                walletId,
                utxo.key(),                              // "txid:vout"
                "spending-tx-id",                        // reserving transaction
                Instant.now().plus(Duration.ofMinutes(5)), // expiration
                1,                                       // priority
                "building payment tx",                   // reason
                replyTo
            ),
            Duration.ofSeconds(10)
        ).toCompletableFuture().join();

        System.out.println("Reserved UTXO for spending");

        // ---------------------------------------------------------------
        // 7. Create an Invoice
        // ---------------------------------------------------------------

        String invoiceId = UUID.randomUUID().toString();

        EntityRef<InvoiceCommand> invoiceRef = sharding.entityRefFor(
            InvoiceAggregate.ENTITY_TYPE_KEY, invoiceId
        );

        String paymentAddress = crypto.generateAddress(
            crypto.derivePrivateKey(hdKey, 0, 5, 0, false), NetworkType.MAINNET
        );

        invoiceRef.ask(
            replyTo -> new InvoiceCommand.CreateInvoiceCommand(
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
            Duration.ofSeconds(10)
        ).toCompletableFuture().join();

        System.out.println("Created invoice: " + invoiceId);

        // ---------------------------------------------------------------
        // 8. Query Read Models
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
