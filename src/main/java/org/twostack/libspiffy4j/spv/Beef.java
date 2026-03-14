package org.twostack.libspiffy4j.spv;

import org.twostack.bitcoin4j.Sha256Hash;
import org.twostack.bitcoin4j.VarInt;
import org.twostack.bitcoin4j.transaction.Transaction;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Background Evaluation Extended Format (BEEF) — bundles transactions
 * with their BUMP merkle proofs for SPV validation.
 */
public final class Beef {

    /** BEEF magic marker: version 1 (0x0100BEEF as 4 LE bytes: 01 00 BE EF). */
    private static final byte[] MAGIC = {0x01, 0x00, (byte) 0xBE, (byte) 0xEF};

    private final int version;
    private final List<Bump> bumps;
    private final List<byte[]> transactions;
    private final List<Boolean> hasMerkle;
    private final List<Integer> bumpIndex;

    public Beef(int version, List<Bump> bumps, List<byte[]> transactions,
                List<Boolean> hasMerkle, List<Integer> bumpIndex) {
        this.version = version;
        this.bumps = List.copyOf(bumps);
        this.transactions = transactions.stream().map(byte[]::clone).toList();
        this.hasMerkle = List.copyOf(hasMerkle);
        this.bumpIndex = List.copyOf(bumpIndex);
    }

    public int version() { return version; }
    public List<Bump> bumps() { return bumps; }
    public int transactionCount() { return transactions.size(); }
    public List<Boolean> hasMerkle() { return hasMerkle; }
    public List<Integer> bumpIndex() { return bumpIndex; }

    /** Returns a defensive copy of the raw transaction at the given index. */
    public byte[] getTransaction(int index) {
        return transactions.get(index).clone();
    }

    // ── Parsing ──────────────────────────────────────────────────────────

    public static Beef parse(String hex) {
        return parse(hexToBytes(hex));
    }

    public static Beef parse(byte[] data) {
        if (data == null || data.length < 4) {
            throw new IllegalArgumentException("BEEF data too short");
        }

        // Verify magic
        if (data[0] != MAGIC[0] || data[1] != MAGIC[1]
                || data[2] != MAGIC[2] || data[3] != MAGIC[3]) {
            throw new IllegalArgumentException("Invalid BEEF magic bytes");
        }

        int offset = 4;

        // Parse BUMPs
        var nBumpsVi = new VarInt(data, offset);
        int nBumps = nBumpsVi.intValue();
        offset += nBumpsVi.getOriginalSizeInBytes();

        List<Bump> bumps = new ArrayList<>(nBumps);
        for (int i = 0; i < nBumps; i++) {
            int[] consumed = new int[1];
            bumps.add(Bump.parse(data, offset, consumed));
            offset += consumed[0];
        }

        // Parse transactions using InputStream
        var is = new ByteArrayInputStream(data, offset, data.length - offset);
        try {
            var nTxVi = VarInt.fromStream(is);
            int nTx = nTxVi.intValue();

            List<byte[]> txList = new ArrayList<>(nTx);
            List<Boolean> hasMerkleList = new ArrayList<>(nTx);
            List<Integer> bumpIndexList = new ArrayList<>(nTx);

            for (int i = 0; i < nTx; i++) {
                Transaction tx = Transaction.fromStream(is);
                txList.add(tx.serialize());

                int flag = is.read();
                if (flag < 0) {
                    throw new IllegalArgumentException("Unexpected end of BEEF data at tx " + i);
                }
                boolean hasBump = flag != 0;
                hasMerkleList.add(hasBump);

                if (hasBump) {
                    var biVi = VarInt.fromStream(is);
                    bumpIndexList.add(biVi.intValue());
                } else {
                    bumpIndexList.add(-1);
                }
            }

            return new Beef(1, bumps, txList, hasMerkleList, bumpIndexList);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to parse BEEF transactions", e);
        }
    }

    // ── Serialization ────────────────────────────────────────────────────

    public byte[] serialize() {
        try {
            var out = new ByteArrayOutputStream();
            out.write(MAGIC);

            out.write(new VarInt(bumps.size()).encode());
            for (Bump bump : bumps) {
                out.write(bump.serialize());
            }

            out.write(new VarInt(transactions.size()).encode());
            for (int i = 0; i < transactions.size(); i++) {
                out.write(transactions.get(i));

                out.write(hasMerkle.get(i) ? 1 : 0);
                if (hasMerkle.get(i)) {
                    out.write(new VarInt(bumpIndex.get(i)).encode());
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Unexpected I/O error during serialization", e);
        }
    }

    // ── Txid calculation ─────────────────────────────────────────────────

    /**
     * Computes the txid (double-SHA256) of a raw transaction.
     *
     * @param rawTx the raw transaction bytes
     * @return 32-byte txid in internal format
     */
    public static byte[] calculateTxid(byte[] rawTx) {
        return Sha256Hash.hashTwice(rawTx);
    }

    // ── Transaction lookup ───────────────────────────────────────────────

    /**
     * Finds a raw transaction by txid.
     *
     * @param txid 32-byte txid in internal format
     * @return raw transaction bytes, or null if not found
     */
    public byte[] findTransactionByTxid(byte[] txid) {
        for (byte[] tx : transactions) {
            if (Arrays.equals(calculateTxid(tx), txid)) {
                return tx.clone();
            }
        }
        return null;
    }

    // ── Validation ───────────────────────────────────────────────────────

    /**
     * Validates all transactions that have BUMP proofs.
     *
     * @return true if every proven transaction has a valid merkle path
     */
    public boolean validate() {
        for (int i = 0; i < transactions.size(); i++) {
            if (hasMerkle.get(i)) {
                byte[] txid = calculateTxid(transactions.get(i));
                int bi = bumpIndex.get(i);
                if (bi < 0 || bi >= bumps.size()) return false;
                if (!bumps.get(bi).validateMerklePath(txid)) return false;
            }
        }
        return true;
    }

    /**
     * Validates a specific transaction's merkle path.
     *
     * @param txid 32-byte txid in internal format
     * @return true if the transaction is found and its merkle path is valid
     */
    public boolean validateTransaction(byte[] txid) {
        for (int i = 0; i < transactions.size(); i++) {
            byte[] computed = calculateTxid(transactions.get(i));
            if (Arrays.equals(computed, txid) && hasMerkle.get(i)) {
                int bi = bumpIndex.get(i);
                return bi >= 0 && bi < bumps.size()
                        && bumps.get(bi).validateMerklePath(txid);
            }
        }
        return false;
    }

    /**
     * Validates a transaction's merkle path against a block header's merkle root.
     *
     * @param txid   32-byte txid in internal format
     * @param header block header containing the expected merkle root
     * @return true if the computed merkle root matches the header's merkle root
     */
    public boolean validateTransactionWithBlockHeader(byte[] txid, BlockHeader header) {
        for (int i = 0; i < transactions.size(); i++) {
            byte[] computed = calculateTxid(transactions.get(i));
            if (Arrays.equals(computed, txid) && hasMerkle.get(i)) {
                int bi = bumpIndex.get(i);
                if (bi < 0 || bi >= bumps.size()) return false;
                try {
                    byte[] root = bumps.get(bi).computeMerkleRoot(txid);
                    return Arrays.equals(root, header.merkleRootInternal());
                } catch (IllegalArgumentException e) {
                    return false;
                }
            }
        }
        return false;
    }

    // ── Utilities ────────────────────────────────────────────────────────

    static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex string");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Beef[version=" + version
                + ", bumps=" + bumps.size()
                + ", transactions=" + transactions.size() + "]";
    }
}
