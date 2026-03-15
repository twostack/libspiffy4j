package org.twostack.libspiffy4j.model;

import java.util.List;

public record ReorgResult(
        int fromHeight,
        int toHeight,
        int headersReplaced,
        List<String> invalidatedTxids
) {
    public ReorgResult {
        invalidatedTxids = List.copyOf(invalidatedTxids);
    }
}
