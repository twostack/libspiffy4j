package org.twostack.libspiffy4j.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.twostack.bitcoin4j.Sha256Hash;
import org.twostack.libspiffy4j.model.ArcTransactionResponse;
import org.twostack.libspiffy4j.model.ArcTransactionStatus;
import org.twostack.libspiffy4j.model.ImportedTransaction;
import org.twostack.libspiffy4j.model.MerkleProofData;
import org.twostack.libspiffy4j.spv.BlockHeader;
import org.twostack.libspiffy4j.spv.BlockHeaderChain;
import org.twostack.libspiffy4j.spv.Bump;
import org.twostack.libspiffy4j.spv.BumpLeaf;
import org.twostack.libspiffy4j.spv.BumpLevel;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionImportServiceTest {

    private BlockHeaderChain chain;

    @BeforeEach
    void setUp() {
        chain = new BlockHeaderChain();
    }

    @Test
    void importTransaction_validSpv() {
        // Create a fake txid (internal format)
        byte[] txidInternal = new byte[32];
        txidInternal[0] = 0x01;

        // Create a sibling hash
        byte[] siblingHash = new byte[32];
        siblingHash[0] = 0x02;

        // Build a simple 1-level BUMP: txid at offset 0, sibling at offset 1
        BumpLeaf txidLeaf = new BumpLeaf(0, false, true, txidInternal);
        BumpLeaf siblingLeaf = new BumpLeaf(1, false, false, siblingHash);
        BumpLevel level0 = new BumpLevel(List.of(txidLeaf, siblingLeaf));
        Bump bump = new Bump(100, List.of(level0));

        // Compute the expected merkle root
        byte[] expectedRoot = Sha256Hash.hashTwice(txidInternal, siblingHash);

        // Create a block header with this merkle root
        byte[] prevHash = new byte[32];
        BlockHeader header = new BlockHeader(1, prevHash, expectedRoot, 1700000000L, 0x1d00ffff, 0);
        chain.addHeader(100, header);

        // txid in display format (reversed hex)
        byte[] txidReversed = new byte[32];
        for (int i = 0; i < 32; i++) txidReversed[i] = txidInternal[31 - i];
        String txidHex = HexFormat.of().formatHex(txidReversed);

        // Create stub ArcService
        ArcService stubArc = new StubArcService(
                new ArcTransactionResponse(txidHex, ArcTransactionStatus.MINED, 100, "blockhash", "2026-01-01T00:00:00Z", "rawdata"),
                new MerkleProofData(bump, 100)
        );

        TransactionImportService importService = new TransactionImportService(stubArc, chain);
        ImportedTransaction result = importService.importTransaction(txidHex);

        assertThat(result.txid()).isEqualTo(txidHex);
        assertThat(result.spvValid()).isTrue();
        assertThat(result.blockHeight()).isEqualTo(100);
    }

    @Test
    void importTransaction_missingHeader_spvInvalid() {
        byte[] txidInternal = new byte[32];
        txidInternal[0] = 0x01;

        byte[] siblingHash = new byte[32];
        BumpLeaf txidLeaf = new BumpLeaf(0, false, true, txidInternal);
        BumpLeaf siblingLeaf = new BumpLeaf(1, false, false, siblingHash);
        Bump bump = new Bump(999, List.of(new BumpLevel(List.of(txidLeaf, siblingLeaf))));

        byte[] txidReversed = new byte[32];
        for (int i = 0; i < 32; i++) txidReversed[i] = txidInternal[31 - i];
        String txidHex = HexFormat.of().formatHex(txidReversed);

        // No header at height 999 in the chain
        ArcService stubArc = new StubArcService(
                new ArcTransactionResponse(txidHex, ArcTransactionStatus.MINED, 999, "blockhash", "2026-01-01T00:00:00Z", null),
                new MerkleProofData(bump, 999)
        );

        TransactionImportService importService = new TransactionImportService(stubArc, chain);
        ImportedTransaction result = importService.importTransaction(txidHex);

        assertThat(result.spvValid()).isFalse();
    }

    @Test
    void importTransactionBatch_importsMultiple() {
        // Create two distinct txids with valid SPV
        List<String> txids = List.of(
                createTxWithValidSpv((byte) 0x01, 100),
                createTxWithValidSpv((byte) 0x02, 101)
        );

        // Create stub that returns appropriate data for each txid
        ArcService stubArc = new BatchStubArcService(chain);

        TransactionImportService importService = new TransactionImportService(stubArc, chain);
        List<ImportedTransaction> results = importService.importTransactionBatch(txids);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(ImportedTransaction::spvValid);
    }

    private String createTxWithValidSpv(byte marker, int height) {
        byte[] txidInternal = new byte[32];
        txidInternal[0] = marker;

        byte[] siblingHash = new byte[32];
        siblingHash[0] = (byte) (marker + 0x10);

        byte[] expectedRoot = Sha256Hash.hashTwice(txidInternal, siblingHash);

        BlockHeader header = new BlockHeader(1, new byte[32], expectedRoot, 1700000000L, 0x1d00ffff, 0);
        chain.addHeader(height, header);

        byte[] txidReversed = new byte[32];
        for (int i = 0; i < 32; i++) txidReversed[i] = txidInternal[31 - i];
        return HexFormat.of().formatHex(txidReversed);
    }

    /**
     * Simple stub ArcService for single-transaction tests.
     */
    private static class StubArcService extends ArcService {
        private final ArcTransactionResponse txResponse;
        private final MerkleProofData proofData;

        StubArcService(ArcTransactionResponse txResponse, MerkleProofData proofData) {
            super(new org.twostack.libspiffy4j.model.ArcServiceConfig("http://stub", null, null));
            this.txResponse = txResponse;
            this.proofData = proofData;
        }

        @Override
        public ArcTransactionResponse queryTransaction(String txid) {
            return txResponse;
        }

        @Override
        public MerkleProofData getMerkleProof(String txid) {
            return proofData;
        }
    }

    /**
     * Stub for batch tests that constructs responses based on txid.
     */
    private static class BatchStubArcService extends ArcService {
        private final BlockHeaderChain chain;

        BatchStubArcService(BlockHeaderChain chain) {
            super(new org.twostack.libspiffy4j.model.ArcServiceConfig("http://stub", null, null));
            this.chain = chain;
        }

        @Override
        public ArcTransactionResponse queryTransaction(String txid) {
            byte[] txidBytes = reversedHexToBytes(txid);
            // Determine height based on marker byte
            int height = (txidBytes[0] == 0x01) ? 100 : 101;
            return new ArcTransactionResponse(txid, ArcTransactionStatus.MINED, height, "hash", null, null);
        }

        @Override
        public MerkleProofData getMerkleProof(String txid) {
            byte[] txidBytes = reversedHexToBytes(txid);
            int height = (txidBytes[0] == 0x01) ? 100 : 101;

            byte[] siblingHash = new byte[32];
            siblingHash[0] = (byte) (txidBytes[0] + 0x10);

            BumpLeaf txidLeaf = new BumpLeaf(0, false, true, txidBytes);
            BumpLeaf siblingLeaf = new BumpLeaf(1, false, false, siblingHash);
            Bump bump = new Bump(height, List.of(new BumpLevel(List.of(txidLeaf, siblingLeaf))));
            return new MerkleProofData(bump, height);
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
}
