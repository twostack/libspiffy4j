package org.twostack.libspiffy4j.model;

import java.util.List;

public record CdnManifest(
        String network,
        int chunkSize,
        List<CdnChunkDescriptor> chunks
) {
}
