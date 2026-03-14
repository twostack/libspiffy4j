package org.twostack.libspiffy4j.model;

import java.time.Instant;

public record ChannelUpdate(
        String channelId,
        long newServerBalanceSats,
        int sequenceNumber,
        String clientSignature,
        Instant timestamp,
        String description
) {
}
