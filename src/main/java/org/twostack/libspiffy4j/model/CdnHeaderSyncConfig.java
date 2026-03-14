package org.twostack.libspiffy4j.model;

import java.time.Duration;

public record CdnHeaderSyncConfig(
        String baseUrl,
        String network,
        int concurrentDownloads,
        Duration downloadTimeout,
        boolean validateProofOfWork,
        boolean verifyCheckpoints,
        String cacheDirectory,
        int maxRetries
) {
}
