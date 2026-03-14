package org.twostack.libspiffy4j.spv;

import org.twostack.bitcoin4j.Sha256Hash;

import java.util.Arrays;

/**
 * A minimal 80-byte Bitcoin block header.
 * All byte-array fields are in internal (LE) format.
 */
public record BlockHeader(
        long version,
        byte[] prevBlockHash,
        byte[] merkleRoot,
        long timestamp,
        long bits,
        long nonce
) {
    public static final int HEADER_SIZE = 80;

    public BlockHeader {
        if (prevBlockHash == null || prevBlockHash.length != 32) {
            throw new IllegalArgumentException("prevBlockHash must be 32 bytes");
        }
        if (merkleRoot == null || merkleRoot.length != 32) {
            throw new IllegalArgumentException("merkleRoot must be 32 bytes");
        }
        prevBlockHash = prevBlockHash.clone();
        merkleRoot = merkleRoot.clone();
    }

    @Override
    public byte[] prevBlockHash() {
        return prevBlockHash.clone();
    }

    @Override
    public byte[] merkleRoot() {
        return merkleRoot.clone();
    }

    byte[] merkleRootInternal() {
        return merkleRoot;
    }

    public static BlockHeader parse(byte[] data) {
        return parse(data, 0);
    }

    public static BlockHeader parse(byte[] data, int offset) {
        if (data.length - offset < HEADER_SIZE) {
            throw new IllegalArgumentException("Need at least 80 bytes for block header, got " + (data.length - offset));
        }

        long version = readUint32(data, offset);
        byte[] prevHash = new byte[32];
        System.arraycopy(data, offset + 4, prevHash, 0, 32);
        byte[] root = new byte[32];
        System.arraycopy(data, offset + 36, root, 0, 32);
        long ts = readUint32(data, offset + 68);
        long b = readUint32(data, offset + 72);
        long n = readUint32(data, offset + 76);

        return new BlockHeader(version, prevHash, root, ts, b, n);
    }

    public byte[] serialize() {
        byte[] out = new byte[HEADER_SIZE];
        writeUint32(out, 0, version);
        System.arraycopy(prevBlockHash, 0, out, 4, 32);
        System.arraycopy(merkleRoot, 0, out, 36, 32);
        writeUint32(out, 68, timestamp);
        writeUint32(out, 72, bits);
        writeUint32(out, 76, nonce);
        return out;
    }

    /** Double-SHA256 of the 80-byte header, in internal format. */
    public byte[] getHash() {
        return Sha256Hash.hashTwice(serialize());
    }

    /** Block hash in display format (reversed hex). */
    public String getHashHex() {
        byte[] hash = getHash();
        byte[] reversed = new byte[32];
        for (int i = 0; i < 32; i++) {
            reversed[i] = hash[31 - i];
        }
        return toHex(reversed);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockHeader other)) return false;
        return version == other.version
                && Arrays.equals(prevBlockHash, other.prevBlockHash)
                && Arrays.equals(merkleRoot, other.merkleRoot)
                && timestamp == other.timestamp
                && bits == other.bits
                && nonce == other.nonce;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(version);
        result = 31 * result + Arrays.hashCode(prevBlockHash);
        result = 31 * result + Arrays.hashCode(merkleRoot);
        result = 31 * result + Long.hashCode(timestamp);
        result = 31 * result + Long.hashCode(bits);
        result = 31 * result + Long.hashCode(nonce);
        return result;
    }

    private static long readUint32(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    private static void writeUint32(byte[] out, int offset, long value) {
        out[offset] = (byte) (value & 0xFF);
        out[offset + 1] = (byte) ((value >> 8) & 0xFF);
        out[offset + 2] = (byte) ((value >> 16) & 0xFF);
        out[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static String toHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
