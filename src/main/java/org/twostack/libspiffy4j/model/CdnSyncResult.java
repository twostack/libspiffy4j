package org.twostack.libspiffy4j.model;

import java.time.Duration;

public record CdnSyncResult(
        int headersImported,
        int finalHeight,
        Duration elapsed
) {
}
