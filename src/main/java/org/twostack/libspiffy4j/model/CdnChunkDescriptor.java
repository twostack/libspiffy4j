package org.twostack.libspiffy4j.model;

public record CdnChunkDescriptor(
        int startHeight,
        int endHeight,
        String url,
        String sha256
) {
}
