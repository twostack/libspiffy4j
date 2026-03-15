package org.twostack.libspiffy4j.service;

import org.twostack.libspiffy4j.model.ArcTransactionResponse;
import org.twostack.libspiffy4j.model.ImportedTransaction;
import org.twostack.libspiffy4j.model.MerkleProofData;
import org.twostack.libspiffy4j.spv.BlockHeader;
import org.twostack.libspiffy4j.spv.BlockHeaderStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Fetches transactions and merkle proofs from ARC, validates SPV against the local
 * {@link BlockHeaderStore}.
 */
public final class TransactionImportService {

    private final ArcService arcService;
    private final BlockHeaderStore chain;
    private final Set<String> pendingTxids = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> confirmedTxHeights = new ConcurrentHashMap<>();

    public TransactionImportService(ArcService arcService, BlockHeaderStore chain) {
        this.arcService = arcService;
        this.chain = chain;
    }

    /**
     * Imports a single transaction by fetching its data and merkle proof from ARC,
     * then validating SPV against the local block header chain.
     */
    public ImportedTransaction importTransaction(String txid) {
        ArcTransactionResponse txResponse = arcService.queryTransaction(txid);
        MerkleProofData proofData = arcService.getMerkleProof(txid);

        boolean spvValid = validateSpv(txid, proofData, txResponse.blockHeight());

        if (spvValid) {
            confirmedTxHeights.put(txid, txResponse.blockHeight());
        }

        return new ImportedTransaction(
                txid,
                txResponse.merklePath(),
                proofData.bump(),
                txResponse.blockHeight(),
                spvValid
        );
    }

    /**
     * Imports multiple transactions in parallel using virtual threads.
     */
    public List<ImportedTransaction> importTransactionBatch(List<String> txids) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ImportedTransaction>> futures = txids.stream()
                    .map(txid -> executor.submit(() -> importTransaction(txid)))
                    .toList();

            return futures.stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new RuntimeException("Batch import failed for transaction", e);
                        }
                    })
                    .toList();
        }
    }

    /**
     * Tracks a transaction that was broadcast via ARC and is awaiting confirmation.
     * When {@link #onNewBlock(long)} is called, merkle proofs will be fetched for
     * all pending transactions.
     */
    public void trackPendingTransaction(String txid) {
        pendingTxids.add(txid);
    }

    /**
     * Returns the current set of pending (unconfirmed) transaction IDs.
     */
    public Set<String> getPendingTxids() {
        return Set.copyOf(pendingTxids);
    }

    /**
     * Called when a new block is announced. Attempts to fetch merkle proofs for all
     * pending transactions, validates SPV, and returns any that were successfully confirmed.
     * Confirmed transactions are removed from the pending set.
     */
    public List<ImportedTransaction> onNewBlock(long blockHeight) {
        if (pendingTxids.isEmpty()) {
            return List.of();
        }

        List<String> snapshot = new ArrayList<>(pendingTxids);
        List<ImportedTransaction> confirmed = new ArrayList<>();

        for (String txid : snapshot) {
            try {
                ImportedTransaction imported = importTransaction(txid);
                if (imported.spvValid()) {
                    pendingTxids.remove(txid);
                    confirmed.add(imported);
                }
            } catch (Exception e) {
                // Transaction not yet mined or proof not available — keep in pending set
            }
        }

        return confirmed;
    }

    /**
     * Returns txids confirmed at heights within [fromHeight, toHeight].
     */
    public Set<String> getConfirmedTxidsInRange(int fromHeight, int toHeight) {
        Set<String> result = ConcurrentHashMap.newKeySet();
        for (var entry : confirmedTxHeights.entrySet()) {
            long h = entry.getValue();
            if (h >= fromHeight && h <= toHeight) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * Moves a confirmed transaction back to pending status (used during reorgs).
     */
    public void moveToPending(String txid) {
        confirmedTxHeights.remove(txid);
        pendingTxids.add(txid);
    }

    private boolean validateSpv(String txid, MerkleProofData proofData, long blockHeight) {
        BlockHeader header = chain.getHeader((int) blockHeight);
        if (header == null) {
            return false;
        }

        try {
            // Convert txid hex to internal byte format (reverse the display-format hex)
            byte[] txidBytes = reversedHexToBytes(txid);

            // Compute merkle root from the BUMP proof
            byte[] computedRoot = proofData.bump().computeMerkleRoot(txidBytes);

            // Compare with the merkle root in the block header
            byte[] headerMerkleRoot = header.merkleRoot();
            return Arrays.equals(computedRoot, headerMerkleRoot);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] reversedHexToBytes(String hex) {
        byte[] bytes = HexFormat.of().parseHex(hex);
        byte[] reversed = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            reversed[i] = bytes[bytes.length - 1 - i];
        }
        return reversed;
    }
}
