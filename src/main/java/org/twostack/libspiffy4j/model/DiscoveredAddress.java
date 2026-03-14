package org.twostack.libspiffy4j.model;

import java.util.List;

public record DiscoveredAddress(
        String address,
        int derivationIndex,
        boolean isChange,
        List<String> transactionIds
) {
}
