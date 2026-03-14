package org.twostack.libspiffy4j.spv;

import java.util.List;

/**
 * A single level in a BUMP, containing one or more leaves.
 *
 * @param leaves immutable list of leaves at this merkle tree level
 */
public record BumpLevel(List<BumpLeaf> leaves) {

    public BumpLevel {
        if (leaves == null || leaves.isEmpty()) {
            throw new IllegalArgumentException("BumpLevel requires at least one leaf");
        }
        leaves = List.copyOf(leaves);
    }
}
