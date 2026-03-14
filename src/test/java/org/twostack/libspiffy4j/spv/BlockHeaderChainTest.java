package org.twostack.libspiffy4j.spv;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BlockHeaderChainTest {

    private BlockHeader dummyHeader(int seed) {
        byte[] prevHash = new byte[32];
        byte[] merkleRoot = new byte[32];
        prevHash[0] = (byte) seed;
        merkleRoot[0] = (byte) (seed + 1);
        return new BlockHeader(1L, prevHash, merkleRoot, seed, 0x1d00ffffL, seed);
    }

    @Test
    void addAndGet_singleHeader() {
        var chain = new BlockHeaderChain();
        BlockHeader h = dummyHeader(1);
        chain.addHeader(100, h);

        assertThat(chain.getHeader(100)).isEqualTo(h);
        assertThat(chain.getChainHeight()).isEqualTo(100);
        assertThat(chain.size()).isEqualTo(1);
    }

    @Test
    void getHeader_unknownHeight_returnsNull() {
        var chain = new BlockHeaderChain();
        assertThat(chain.getHeader(999)).isNull();
    }

    @Test
    void getHeaderByHash_returnsCorrectHeader() {
        var chain = new BlockHeaderChain();
        BlockHeader h = dummyHeader(42);
        chain.addHeader(500, h);

        String hashHex = h.getHashHex();
        assertThat(chain.getHeaderByHash(hashHex)).isEqualTo(h);
    }

    @Test
    void chainHeight_tracksHighestAdded() {
        var chain = new BlockHeaderChain();
        chain.addHeader(10, dummyHeader(10));
        chain.addHeader(20, dummyHeader(20));
        chain.addHeader(15, dummyHeader(15));

        assertThat(chain.getChainHeight()).isEqualTo(20);
    }

    @Test
    void eviction_beyondMaxHeaders() {
        var chain = new BlockHeaderChain();

        // Fill to capacity
        for (int i = 0; i < BlockHeaderChain.MAX_HEADERS; i++) {
            chain.addHeader(i, dummyHeader(i));
        }
        assertThat(chain.size()).isEqualTo(BlockHeaderChain.MAX_HEADERS);

        // Add one more — oldest (height 0) should be evicted
        chain.addHeader(BlockHeaderChain.MAX_HEADERS, dummyHeader(BlockHeaderChain.MAX_HEADERS));
        assertThat(chain.size()).isEqualTo(BlockHeaderChain.MAX_HEADERS);
        assertThat(chain.getHeader(0)).isNull();
        assertThat(chain.getHeader(BlockHeaderChain.MAX_HEADERS)).isNotNull();
    }

    @Test
    void overwrite_sameHeight_replacesHeader() {
        var chain = new BlockHeaderChain();
        BlockHeader h1 = dummyHeader(1);
        BlockHeader h2 = dummyHeader(2);

        chain.addHeader(100, h1);
        chain.addHeader(100, h2);

        assertThat(chain.getHeader(100)).isEqualTo(h2);
        assertThat(chain.size()).isEqualTo(1);
    }

    @Test
    void validateContinuity_connectedChain_returnsTrue() {
        var chain = new BlockHeaderChain();

        // Build a connected chain: each header's prevBlockHash = previous header's hash
        BlockHeader h0 = dummyHeader(100);
        chain.addHeader(0, h0);

        byte[] prevHash = h0.getHash();
        for (int i = 1; i <= 3; i++) {
            byte[] merkleRoot = new byte[32];
            merkleRoot[0] = (byte) (i + 50);
            BlockHeader h = new BlockHeader(1L, prevHash, merkleRoot, i * 1000L, 0x1d00ffffL, i);
            chain.addHeader(i, h);
            prevHash = h.getHash();
        }

        assertThat(chain.validateContinuity(0, 3)).isTrue();
    }

    @Test
    void validateContinuity_brokenChain_returnsFalse() {
        var chain = new BlockHeaderChain();

        // Add disconnected headers (prevBlockHash doesn't match)
        chain.addHeader(0, dummyHeader(1));
        chain.addHeader(1, dummyHeader(2));

        assertThat(chain.validateContinuity(0, 1)).isFalse();
    }

    @Test
    void validateContinuity_missingHeight_returnsFalse() {
        var chain = new BlockHeaderChain();
        chain.addHeader(0, dummyHeader(1));
        // Skip height 1
        chain.addHeader(2, dummyHeader(3));

        assertThat(chain.validateContinuity(0, 2)).isFalse();
    }

    @Test
    void validateContinuity_singleHeight_returnsTrue() {
        var chain = new BlockHeaderChain();
        chain.addHeader(5, dummyHeader(5));

        assertThat(chain.validateContinuity(5, 5)).isTrue();
    }

    @Test
    void initialChainHeight_isNegativeOne() {
        var chain = new BlockHeaderChain();
        assertThat(chain.getChainHeight()).isEqualTo(-1);
    }
}
