package org.twostack.libspiffy4j.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class InvoiceOutputSpecTest {

    @Test void p2ms_rejectsThresholdZero() {
        assertThatThrownBy(() -> new InvoiceOutputSpec.P2MSOutputSpec(
            List.of("key1", "key2"), 0, 1000, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void p2ms_rejectsThresholdGreaterThanKeys() {
        assertThatThrownBy(() -> new InvoiceOutputSpec.P2MSOutputSpec(
            List.of("key1"), 2, 1000, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void p2ms_rejectsMoreThan16Keys() {
        var keys = java.util.stream.IntStream.range(0, 17)
            .mapToObj(i -> "key" + i).toList();
        assertThatThrownBy(() -> new InvoiceOutputSpec.P2MSOutputSpec(
            keys, 2, 1000, null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void p2ms_acceptsValidConfig() {
        var spec = new InvoiceOutputSpec.P2MSOutputSpec(
            List.of("key1", "key2", "key3"), 2, 5000, "multisig"
        );
        assertThat(spec.threshold()).isEqualTo(2);
        assertThat(spec.publicKeys()).hasSize(3);
    }

    @Test void opReturn_rejectsOversizedData() {
        byte[] big = new byte[99_001];
        assertThatThrownBy(() -> new InvoiceOutputSpec.OPReturnOutputSpec(
            List.of(big), false
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void opReturn_acceptsExactly99KB() {
        byte[] exact = new byte[99_000];
        var spec = new InvoiceOutputSpec.OPReturnOutputSpec(List.of(exact), false);
        assertThat(spec.dataChunks()).hasSize(1);
    }

    @Test void exhaustiveSwitch() {
        InvoiceOutputSpec spec = new InvoiceOutputSpec.P2PKHOutputSpec("addr", 1000, null);
        String result = switch (spec) {
            case InvoiceOutputSpec.P2PKHOutputSpec p -> "p2pkh";
            case InvoiceOutputSpec.P2MSOutputSpec p -> "p2ms";
            case InvoiceOutputSpec.OPReturnOutputSpec p -> "op_return";
            case InvoiceOutputSpec.PluginOutputSpec p -> "plugin";
        };
        assertThat(result).isEqualTo("p2pkh");
    }
}
