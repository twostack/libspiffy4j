package org.twostack.libspiffy4j.spv;

/**
 * Abstraction over block header storage, allowing different backing implementations
 * (in-memory LRU, persistent database, shared store with P2P node, etc.).
 */
public interface BlockHeaderStore {

    void addHeader(int height, BlockHeader header);

    BlockHeader getHeader(int height);

    int getChainHeight();

    /**
     * Invalidates all headers in the range [fromHeight, toHeight].
     * Default implementation is a no-op for stores that don't support reorgs.
     */
    default void invalidateRange(int fromHeight, int toHeight) {}
}
