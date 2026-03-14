package org.twostack.libspiffy4j.spv;

import java.util.Arrays;

/**
 * A single leaf in a BUMP (BSV Unified Merkle Path) level.
 *
 * @param offset    position in the merkle tree at this level
 * @param duplicate true if this leaf's hash duplicates its sibling (no hash stored)
 * @param isTxid    true if this leaf represents a txid being proven
 * @param hash      32-byte hash in internal (LE) format; null when duplicate is true
 */
public record BumpLeaf(long offset, boolean duplicate, boolean isTxid, byte[] hash) {

    public BumpLeaf {
        if (duplicate && hash != null) {
            throw new IllegalArgumentException("Duplicate leaf must not carry a hash");
        }
        if (!duplicate && hash == null) {
            throw new IllegalArgumentException("Non-duplicate leaf requires a 32-byte hash");
        }
        if (hash != null) {
            if (hash.length != 32) {
                throw new IllegalArgumentException("Hash must be exactly 32 bytes, got " + hash.length);
            }
            hash = hash.clone();
        }
    }

    @Override
    public byte[] hash() {
        return hash == null ? null : hash.clone();
    }

    byte[] hashInternal() {
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BumpLeaf other)) return false;
        return offset == other.offset
                && duplicate == other.duplicate
                && isTxid == other.isTxid
                && Arrays.equals(hash, other.hash);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(offset);
        result = 31 * result + Boolean.hashCode(duplicate);
        result = 31 * result + Boolean.hashCode(isTxid);
        result = 31 * result + Arrays.hashCode(hash);
        return result;
    }

    @Override
    public String toString() {
        return "BumpLeaf[offset=" + offset
                + ", duplicate=" + duplicate
                + ", isTxid=" + isTxid
                + ", hash=" + (hash == null ? "null" : toHex(hash))
                + "]";
    }

    private static String toHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
