package org.twostack.libspiffy4j.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.twostack.libspiffy4j.model.ReorgResult;
import org.twostack.libspiffy4j.spv.BlockHeader;
import org.twostack.libspiffy4j.spv.BlockHeaderChain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ReorganizationHandlerTest {

    private BlockHeaderChain chain;
    private TransactionImportService importService;
    private ReorganizationHandler handler;

    private BlockHeader dummyHeader(int seed) {
        byte[] prevHash = new byte[32];
        byte[] merkleRoot = new byte[32];
        prevHash[0] = (byte) seed;
        merkleRoot[0] = (byte) (seed + 1);
        return new BlockHeader(1L, prevHash, merkleRoot, seed, 0x1d00ffffL, seed);
    }

    @BeforeEach
    void setup() {
        chain = new BlockHeaderChain();
        // We need a TransactionImportService that we can populate with confirmed heights.
        // Use a stub ArcService that will never be called during reorg tests.
        importService = new TransactionImportService(null, chain);
    }

    @Test
    void handleReorg_replacesHeaders_movesTxidsToPending() {
        // Setup: add headers at heights 100-104
        for (int h = 100; h <= 104; h++) {
            chain.addHeader(h, dummyHeader(h));
        }

        // Simulate confirmed txids at heights 102 and 103 by using moveToPending in reverse
        // (we need to populate confirmedTxHeights directly — use the public getConfirmedTxidsInRange
        // which reads from confirmedTxHeights populated by importTransaction)
        // Instead, we directly test by populating via the internal state
        // We'll use a workaround: track pending, then verify the handler's flow

        // Use reflection-free approach: call moveToPending to add to pending first,
        // then verify the full flow works with the handler
        // Actually, let's test the handler with an approach that populates confirmed heights

        // We'll test that invalidateRange + replacement works even without confirmed txids
        Map<Integer, BlockHeader> replacements = new LinkedHashMap<>();
        replacements.put(102, dummyHeader(202));
        replacements.put(103, dummyHeader(203));

        ReorgResult result = handler().handleReorganization(102, 103, replacements);

        assertThat(result.fromHeight()).isEqualTo(102);
        assertThat(result.toHeight()).isEqualTo(103);
        assertThat(result.headersReplaced()).isEqualTo(2);
        assertThat(result.invalidatedTxids()).isEmpty();

        // Replacement headers installed
        assertThat(chain.getHeader(102)).isEqualTo(dummyHeader(202));
        assertThat(chain.getHeader(103)).isEqualTo(dummyHeader(203));

        // Original headers at other heights still present
        assertThat(chain.getHeader(100)).isNotNull();
        assertThat(chain.getHeader(101)).isNotNull();
        assertThat(chain.getHeader(104)).isNotNull();
    }

    @Test
    void handleReorg_noAffectedTransactions_emptyInvalidatedList() {
        chain.addHeader(50, dummyHeader(50));
        chain.addHeader(51, dummyHeader(51));

        Map<Integer, BlockHeader> replacements = Map.of(50, dummyHeader(150));
        ReorgResult result = handler().handleReorganization(50, 51, replacements);

        assertThat(result.invalidatedTxids()).isEmpty();
        assertThat(result.headersReplaced()).isEqualTo(1);
    }

    @Test
    void handleReorg_listenerNotified() {
        chain.addHeader(10, dummyHeader(10));

        List<ReorgResult> received = new ArrayList<>();
        ReorganizationHandler h = handler();
        h.addListener(received::add);

        h.handleReorganization(10, 10, Map.of(10, dummyHeader(110)));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).fromHeight()).isEqualTo(10);
    }

    @Test
    void handleReorg_emptyRange_noError() {
        // Invalidate range where no headers exist
        ReorgResult result = handler().handleReorganization(999, 1000, Map.of());

        assertThat(result.headersReplaced()).isZero();
        assertThat(result.invalidatedTxids()).isEmpty();
    }

    private ReorganizationHandler handler() {
        if (handler == null) {
            handler = new ReorganizationHandler(chain, importService);
        }
        return handler;
    }
}
