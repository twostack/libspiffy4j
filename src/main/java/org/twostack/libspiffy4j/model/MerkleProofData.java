package org.twostack.libspiffy4j.model;

import org.twostack.libspiffy4j.spv.Bump;

public record MerkleProofData(
        Bump bump,
        long blockHeight
) {
}
