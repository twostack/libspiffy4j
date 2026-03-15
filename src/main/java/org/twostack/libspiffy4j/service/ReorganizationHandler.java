package org.twostack.libspiffy4j.service;

import org.twostack.libspiffy4j.model.ReorgResult;
import org.twostack.libspiffy4j.spv.BlockHeader;
import org.twostack.libspiffy4j.spv.BlockHeaderStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Orchestrates the response to a chain reorganization by invalidating old headers,
 * installing replacement headers, and moving affected transactions back to pending.
 */
public final class ReorganizationHandler {

    private final BlockHeaderStore chain;
    private final TransactionImportService importService;
    private final List<ReorgListener> listeners = new CopyOnWriteArrayList<>();

    public ReorganizationHandler(BlockHeaderStore chain, TransactionImportService importService) {
        this.chain = chain;
        this.importService = importService;
    }

    public void addListener(ReorgListener listener) {
        listeners.add(listener);
    }

    /**
     * Handles a chain reorganization.
     *
     * @param invalidFromHeight first height of the invalidated range
     * @param invalidToHeight   last height of the invalidated range
     * @param replacementHeaders new headers keyed by height
     * @return result describing the reorg impact
     */
    public ReorgResult handleReorganization(
            int invalidFromHeight, int invalidToHeight,
            Map<Integer, BlockHeader> replacementHeaders) {

        // 1. Find affected transactions
        Set<String> affectedTxids = importService.getConfirmedTxidsInRange(
                invalidFromHeight, invalidToHeight);

        // 2. Invalidate old headers
        chain.invalidateRange(invalidFromHeight, invalidToHeight);

        // 3. Install replacement headers
        for (var entry : replacementHeaders.entrySet()) {
            chain.addHeader(entry.getKey(), entry.getValue());
        }

        // 4. Move affected txids back to pending
        List<String> invalidatedList = new ArrayList<>(affectedTxids);
        for (String txid : invalidatedList) {
            importService.moveToPending(txid);
        }

        // 5. Build result and notify listeners
        ReorgResult result = new ReorgResult(
                invalidFromHeight, invalidToHeight,
                replacementHeaders.size(), invalidatedList);

        for (ReorgListener listener : listeners) {
            listener.onReorganization(result);
        }

        return result;
    }
}
