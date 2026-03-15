package examples;

import org.twostack.libspiffy4j.model.*;
import org.twostack.libspiffy4j.service.*;
import org.twostack.libspiffy4j.spv.*;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Demonstrates SPV validation, ARC integration, header sync, and BEEF construction.
 * No Pekko actor system or database required.
 *
 * Covers:
 *   - ARC transaction broadcast and query
 *   - CDN header sync (block header bootstrap)
 *   - Transaction import with SPV validation
 *   - BEEF construction and parsing
 *   - Pending transaction tracking
 */
public class SpvValidationExample {

    public static void main(String[] args) throws Exception {

        // ---------------------------------------------------------------
        // 1. ARC Service Setup
        // ---------------------------------------------------------------

        // Pre-configured for TAAL mainnet
        var arc = new ArcService(ArcServiceConfig.taalMainnet());

        // Or custom endpoint
        // var arc = new ArcService(ArcServiceConfig.custom(
        //     "https://arc.example.com", "api-key", "https://myapp.com/callback"
        // ));

        // ---------------------------------------------------------------
        // 2. Block Header Store & CDN Sync
        // ---------------------------------------------------------------

        // In-memory LRU store (max 2016 headers)
        BlockHeaderChain headerStore = new BlockHeaderChain();

        // Bootstrap headers from CDN
        var cdnConfig = new CdnHeaderSyncConfig(
            "https://headers.example.com",
            "mainnet",
            4,                          // parallel threads
            Duration.ofSeconds(30),     // request timeout
            false,                      // verifyHeaders
            false,                      // downloadAll
            null,                       // fromHeight (null = auto)
            3                           // maxRetries
        );

        var cdnSync = new CdnHeaderSyncService(cdnConfig, headerStore);

        // Uncomment to run actual sync:
        // CdnSyncResult syncResult = cdnSync.synchronize();
        // System.out.println("Synced to height: " + headerStore.getChainHeight());

        // ---------------------------------------------------------------
        // 3. Transaction Import with SPV Validation
        // ---------------------------------------------------------------

        var importService = new TransactionImportService(arc, headerStore);

        // Import a confirmed transaction — fetches tx + merkle proof from ARC,
        // validates the merkle path against local block headers
        // String confirmedTxid = "abc123...";
        // ImportedTransaction imported = importService.importTransaction(confirmedTxid);
        // System.out.println("SPV valid: " + imported.spvValid());
        // System.out.println("Block height: " + imported.blockHeight());

        // Batch import
        // List<ImportedTransaction> batch = importService.importTransactionBatch(
        //     List.of(txid1, txid2, txid3)
        // );

        // ---------------------------------------------------------------
        // 4. Pending Transaction Tracking
        // ---------------------------------------------------------------

        // Track an unconfirmed transaction
        String pendingTxid = "pending_txid_example";
        importService.trackPendingTransaction(pendingTxid);

        System.out.println("Pending txids: " + importService.getPendingTxids());

        // When a new block is detected, attempt to confirm all pending txs
        // List<ImportedTransaction> confirmed = importService.onNewBlock(850_001L);
        // for (var tx : confirmed) {
        //     System.out.println("Confirmed: " + tx.txid() + " SPV=" + tx.spvValid());
        // }

        // ---------------------------------------------------------------
        // 5. Manual SPV Verification (BUMP)
        // ---------------------------------------------------------------

        // If you have raw BUMP data and want to verify manually:
        System.out.println("\n--- Manual SPV Verification ---");

        // byte[] bumpData = ...; // From ARC or BEEF
        // Bump bump = Bump.parse(bumpData);
        //
        // System.out.println("Block height: " + bump.blockHeight());
        // System.out.println("Tree height:  " + bump.treeHeight());
        //
        // // Compute merkle root from txid
        // byte[] txid = ...; // 32-byte txid
        // byte[] computedRoot = bump.computeMerkleRoot(txid);
        //
        // // Compare against the block header's merkle root
        // BlockHeader header = headerStore.getHeader((int) bump.blockHeight());
        // boolean spvValid = Arrays.equals(computedRoot, header.merkleRoot());
        // System.out.println("SPV valid: " + spvValid);

        // ---------------------------------------------------------------
        // 6. BEEF Construction (BeefBuilder)
        // ---------------------------------------------------------------

        System.out.println("\n--- BEEF Construction ---");

        // BEEF bundles transactions with their merkle proofs into a single
        // portable container that a recipient can verify offline.

        // var beefBuilder = new BeefBuilder();
        //
        // // Add ancestor transactions with merkle proofs
        // beefBuilder.addProvenTransaction(ancestorRawTx, ancestorBump);
        //
        // // Add the new transaction (unproven — not yet in a block)
        // beefBuilder.addUnprovenTransaction(newRawTx);
        //
        // Beef beef = beefBuilder.build();
        // byte[] serialized = beef.serialize();
        // System.out.println("BEEF size: " + serialized.length + " bytes");
        // System.out.println("Transactions: " + beef.transactionCount());

        // ---------------------------------------------------------------
        // 7. BEEF Parsing and Validation
        // ---------------------------------------------------------------

        System.out.println("\n--- BEEF Validation ---");

        // Beef received = Beef.parse(serializedBeef);
        //
        // // Validate all proven transactions in the BEEF
        // boolean allValid = received.validate();
        // System.out.println("All proven txs valid: " + allValid);
        //
        // // Validate a specific transaction
        // boolean txValid = received.validateTransaction(specificTxid);
        //
        // // Validate against a specific block header
        // boolean headerValid = received.validateTransactionWithBlockHeader(
        //     specificTxid, blockHeader
        // );
        //
        // // Find a transaction within the BEEF by txid
        // byte[] foundTx = received.findTransactionByTxid(specificTxid);

        // ---------------------------------------------------------------
        // 8. Chain Tip Tracking
        // ---------------------------------------------------------------

        System.out.println("\n--- Chain Tip Tracking ---");

        var tracker = new ChainTipTracker(arc, 6); // 6-confirmation threshold

        // Update chain tip when you learn of a new block
        tracker.updateNetworkHeight(850_000L, "00000000000000000002a7...");

        System.out.println("Network height: " + tracker.getNetworkHeight());

        // Track confirmation progress for a transaction
        tracker.trackTransaction("some_txid", (update) -> {
            System.out.println("Confirmations: " + update.confirmations());
        });

        System.out.println("Is confirmed: " + tracker.isConfirmed("some_txid"));

        System.out.println("\nSPV validation example complete.");
    }
}
