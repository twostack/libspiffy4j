package org.twostack.libspiffy4j.spv;

import org.twostack.bitcoin4j.Sha256Hash;
import org.twostack.bitcoin4j.VarInt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * BSV Unified Merkle Path (BUMP) — proves transaction inclusion in a block
 * via a compact merkle proof.
 */
public final class Bump {

    private final long blockHeight;
    private final List<BumpLevel> path;

    public Bump(long blockHeight, List<BumpLevel> path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("BUMP path must not be empty");
        }
        this.blockHeight = blockHeight;
        this.path = List.copyOf(path);
    }

    public long blockHeight() {
        return blockHeight;
    }

    public List<BumpLevel> path() {
        return path;
    }

    public int treeHeight() {
        return path.size();
    }

    // ── Parsing ──────────────────────────────────────────────────────────

    public static Bump parse(byte[] data) {
        int[] consumed = new int[1];
        return parse(data, 0, consumed);
    }

    public static Bump parse(byte[] data, int offset, int[] bytesConsumed) {
        int start = offset;

        // Block height
        var heightVi = new VarInt(data, offset);
        long blockHeight = heightVi.longValue();
        offset += heightVi.getOriginalSizeInBytes();

        // Tree height (1 byte)
        int treeHeight = data[offset++] & 0xFF;

        List<BumpLevel> levels = new ArrayList<>(treeHeight);
        for (int level = 0; level < treeHeight; level++) {
            var nLeavesVi = new VarInt(data, offset);
            int nLeaves = nLeavesVi.intValue();
            offset += nLeavesVi.getOriginalSizeInBytes();

            List<BumpLeaf> leaves = new ArrayList<>(nLeaves);
            for (int i = 0; i < nLeaves; i++) {
                var offsetVi = new VarInt(data, offset);
                long leafOffset = offsetVi.longValue();
                offset += offsetVi.getOriginalSizeInBytes();

                int flags = data[offset++] & 0xFF;
                boolean duplicate = (flags & 0x01) != 0;
                boolean isTxid = (flags & 0x02) != 0;

                byte[] hash = null;
                if (!duplicate) {
                    hash = new byte[32];
                    System.arraycopy(data, offset, hash, 0, 32);
                    offset += 32;
                }

                leaves.add(new BumpLeaf(leafOffset, duplicate, isTxid, hash));
            }
            levels.add(new BumpLevel(leaves));
        }

        if (bytesConsumed != null) {
            bytesConsumed[0] = offset - start;
        }
        return new Bump(blockHeight, levels);
    }

    // ── Serialization ────────────────────────────────────────────────────

    public byte[] serialize() {
        try {
            var out = new ByteArrayOutputStream();

            out.write(new VarInt(blockHeight).encode());
            out.write((byte) path.size());

            for (BumpLevel level : path) {
                out.write(new VarInt(level.leaves().size()).encode());
                for (BumpLeaf leaf : level.leaves()) {
                    out.write(new VarInt(leaf.offset()).encode());
                    int flags = 0;
                    if (leaf.duplicate()) flags |= 0x01;
                    if (leaf.isTxid()) flags |= 0x02;
                    out.write((byte) flags);
                    if (!leaf.duplicate()) {
                        out.write(leaf.hashInternal());
                    }
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Unexpected I/O error during serialization", e);
        }
    }

    // ── Merkle root computation ──────────────────────────────────────────

    /**
     * Computes the merkle root for a given txid by walking the BUMP path.
     *
     * @param txid 32-byte txid in internal (raw SHA-256) format
     * @return 32-byte merkle root in internal format
     */
    public byte[] computeMerkleRoot(byte[] txid) {
        if (txid == null || txid.length != 32) {
            throw new IllegalArgumentException("txid must be 32 bytes");
        }

        // Find the txid leaf at level 0
        BumpLeaf txidLeaf = null;
        for (BumpLeaf leaf : path.get(0).leaves()) {
            if (leaf.isTxid()) {
                byte[] leafHash = leaf.hashInternal();
                if (leafHash != null && java.util.Arrays.equals(leafHash, txid)) {
                    txidLeaf = leaf;
                    break;
                }
            }
        }
        if (txidLeaf == null) {
            throw new IllegalArgumentException("txid not found in BUMP level 0");
        }

        byte[] currentHash = txid.clone();
        long currentOffset = txidLeaf.offset();

        for (int level = 0; level < path.size(); level++) {
            long siblingOffset = (currentOffset % 2 == 0) ? currentOffset + 1 : currentOffset - 1;

            byte[] siblingHash = findHashAtLevel(level, siblingOffset, currentHash);

            byte[] left, right;
            if (currentOffset % 2 == 0) {
                left = currentHash;
                right = siblingHash;
            } else {
                left = siblingHash;
                right = currentHash;
            }

            currentHash = Sha256Hash.hashTwice(left, right);
            currentOffset = currentOffset / 2;
        }

        return currentHash;
    }

    /**
     * Validates that a complete merkle path exists for the given txid.
     *
     * @param txid 32-byte txid in internal format
     * @return true if the merkle path is structurally valid
     */
    public boolean validateMerklePath(byte[] txid) {
        try {
            byte[] root = computeMerkleRoot(txid);
            return root != null && root.length == 32;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] findHashAtLevel(int level, long offset, byte[] currentHash) {
        for (BumpLeaf leaf : path.get(level).leaves()) {
            if (leaf.offset() == offset) {
                if (leaf.duplicate()) {
                    return currentHash;
                }
                return leaf.hashInternal();
            }
        }
        throw new IllegalArgumentException(
                "Missing sibling at level " + level + ", offset " + offset);
    }

    @Override
    public String toString() {
        return "Bump[blockHeight=" + blockHeight + ", treeHeight=" + path.size() + "]";
    }
}
