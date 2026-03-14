package org.twostack.libspiffy4j.spv;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory store of block headers, limited to {@link #MAX_HEADERS} entries.
 * Supports lookup by height or hash and basic continuity validation.
 */
public final class BlockHeaderChain {

    public static final int MAX_HEADERS = 2016;

    private final Map<String, BlockHeader> byHash = new LinkedHashMap<>();
    private final Map<Integer, String> heightToHash = new LinkedHashMap<>();
    private int chainHeight = -1;

    public void addHeader(int height, BlockHeader header) {
        String hashHex = header.getHashHex();

        // Evict oldest if at capacity
        if (heightToHash.size() >= MAX_HEADERS && !heightToHash.containsKey(height)) {
            int oldest = heightToHash.keySet().iterator().next();
            String oldHash = heightToHash.remove(oldest);
            byHash.remove(oldHash);
        }

        // Overwrite if same height already stored
        String existing = heightToHash.get(height);
        if (existing != null) {
            byHash.remove(existing);
        }

        byHash.put(hashHex, header);
        heightToHash.put(height, hashHex);

        if (height > chainHeight) {
            chainHeight = height;
        }
    }

    public BlockHeader getHeader(int height) {
        String hash = heightToHash.get(height);
        return hash == null ? null : byHash.get(hash);
    }

    public BlockHeader getHeaderByHash(String hashHex) {
        return byHash.get(hashHex);
    }

    public int getChainHeight() {
        return chainHeight;
    }

    public int size() {
        return heightToHash.size();
    }

    /**
     * Validates that headers exist for every height in [fromHeight, toHeight]
     * and that each header's prevBlockHash matches the hash of the prior header.
     */
    public boolean validateContinuity(int fromHeight, int toHeight) {
        if (fromHeight > toHeight) return false;

        for (int h = fromHeight; h <= toHeight; h++) {
            if (getHeader(h) == null) return false;
        }

        for (int h = fromHeight + 1; h <= toHeight; h++) {
            BlockHeader current = getHeader(h);
            BlockHeader previous = getHeader(h - 1);
            byte[] prevHash = current.prevBlockHash();
            byte[] expectedHash = previous.getHash();
            if (!java.util.Arrays.equals(prevHash, expectedHash)) {
                return false;
            }
        }

        return true;
    }
}
