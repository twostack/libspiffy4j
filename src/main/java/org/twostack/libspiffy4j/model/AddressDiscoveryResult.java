package org.twostack.libspiffy4j.model;

import java.util.List;
import java.util.Map;

public record AddressDiscoveryResult(
        List<DiscoveredAddress> receivingAddresses,
        List<DiscoveredAddress> changeAddresses,
        int totalTransactions,
        Map<Boolean, Integer> lastCheckedIndices
) {
}
