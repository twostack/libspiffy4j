package org.twostack.libspiffy4j.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentChannelTest {

    private PaymentChannel channel(PaymentChannelState state, PaymentChannelRole role) {
        return new PaymentChannel("ch1", "w1", role,
            "peer1", "peer2", "pub1", "pub2", "a1", "a2",
            100000, 0, state, 60000, 40000,
            null, null, 0, null, null, null,
            0, null, null, null, List.of(), null,
            Instant.now(), null, null);
    }

    @Test void isOpen() {
        assertThat(channel(PaymentChannelState.OPEN, PaymentChannelRole.CLIENT).isOpen()).isTrue();
        assertThat(channel(PaymentChannelState.CLOSED, PaymentChannelRole.CLIENT).isOpen()).isFalse();
    }

    @Test void isExpired() {
        assertThat(channel(PaymentChannelState.EXPIRED, PaymentChannelRole.CLIENT).isExpired()).isTrue();
        assertThat(channel(PaymentChannelState.OPEN, PaymentChannelRole.CLIENT).isExpired()).isFalse();
    }

    @Test void isClosed() {
        assertThat(channel(PaymentChannelState.CLOSED, PaymentChannelRole.SERVER).isClosed()).isTrue();
    }

    @Test void isActive() {
        assertThat(channel(PaymentChannelState.OPEN, PaymentChannelRole.CLIENT).isActive()).isTrue();
        assertThat(channel(PaymentChannelState.EXPIRED, PaymentChannelRole.CLIENT).isActive()).isFalse();
    }

    @Test void isClient() {
        assertThat(channel(PaymentChannelState.OPEN, PaymentChannelRole.CLIENT).isClient()).isTrue();
        assertThat(channel(PaymentChannelState.OPEN, PaymentChannelRole.SERVER).isClient()).isFalse();
    }

    @Test void isServer() {
        assertThat(channel(PaymentChannelState.OPEN, PaymentChannelRole.SERVER).isServer()).isTrue();
    }
}
