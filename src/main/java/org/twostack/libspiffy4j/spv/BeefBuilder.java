package org.twostack.libspiffy4j.spv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Constructs a BEEF bundle from proven transactions (with BUMPs) and unproven
 * transactions. De-duplicates BUMPs by block height so multiple transactions
 * from the same block share a single BUMP entry.
 */
public final class BeefBuilder {

    private final Map<Long, Integer> heightToBumpIndex = new LinkedHashMap<>();
    private final List<Bump> bumps = new ArrayList<>();
    private final List<byte[]> provenTxs = new ArrayList<>();
    private final List<Integer> provenBumpIndices = new ArrayList<>();
    private final List<byte[]> unprovenTxs = new ArrayList<>();

    /**
     * Adds a transaction that has a BUMP merkle proof.
     *
     * @param rawTx raw transaction bytes
     * @param bump  the BUMP proof for this transaction
     * @return this builder for chaining
     */
    public BeefBuilder addProvenTransaction(byte[] rawTx, Bump bump) {
        if (rawTx == null || rawTx.length == 0) {
            throw new IllegalArgumentException("rawTx must not be null or empty");
        }
        if (bump == null) {
            throw new IllegalArgumentException("bump must not be null");
        }

        int bumpIdx = heightToBumpIndex.computeIfAbsent(bump.blockHeight(), h -> {
            bumps.add(bump);
            return bumps.size() - 1;
        });

        provenTxs.add(rawTx.clone());
        provenBumpIndices.add(bumpIdx);
        return this;
    }

    /**
     * Adds an unproven transaction (e.g. the new tx being sent).
     *
     * @param rawTx raw transaction bytes
     * @return this builder for chaining
     */
    public BeefBuilder addUnprovenTransaction(byte[] rawTx) {
        if (rawTx == null || rawTx.length == 0) {
            throw new IllegalArgumentException("rawTx must not be null or empty");
        }
        unprovenTxs.add(rawTx.clone());
        return this;
    }

    /**
     * Builds the BEEF bundle. Proven transactions appear first, then unproven.
     *
     * @return the assembled Beef object
     * @throws IllegalStateException if no transactions have been added
     */
    public Beef build() {
        if (provenTxs.isEmpty() && unprovenTxs.isEmpty()) {
            throw new IllegalStateException("Cannot build BEEF with no transactions");
        }

        List<byte[]> allTxs = new ArrayList<>(provenTxs.size() + unprovenTxs.size());
        List<Boolean> hasMerkle = new ArrayList<>(allTxs.size());
        List<Integer> bumpIndex = new ArrayList<>(allTxs.size());

        for (int i = 0; i < provenTxs.size(); i++) {
            allTxs.add(provenTxs.get(i));
            hasMerkle.add(true);
            bumpIndex.add(provenBumpIndices.get(i));
        }

        for (byte[] tx : unprovenTxs) {
            allTxs.add(tx);
            hasMerkle.add(false);
            bumpIndex.add(-1);
        }

        return new Beef(1, bumps, allTxs, hasMerkle, bumpIndex);
    }
}
