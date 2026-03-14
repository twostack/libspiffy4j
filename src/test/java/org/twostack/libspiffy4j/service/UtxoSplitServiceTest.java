package org.twostack.libspiffy4j.service;

import org.junit.jupiter.api.Test;
import org.twostack.libspiffy4j.model.InvoiceOutputSpec;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UtxoSplitServiceTest {

    private final UtxoSplitService service = new UtxoSplitService();

    @Test
    void benfordSplit_sumsToTotal() {
        var addresses = List.of("addr1", "addr2", "addr3", "addr4", "addr5");
        long total = 1_000_000;

        var result = service.generateBenfordSplit(total, addresses, 1000);

        long sum = result.stream().mapToLong(InvoiceOutputSpec.P2PKHOutputSpec::amountSats).sum();
        assertThat(sum).isEqualTo(total);
    }

    @Test
    void benfordSplit_outputCountMatchesAddresses() {
        var addresses = List.of("addr1", "addr2", "addr3");

        var result = service.generateBenfordSplit(100_000, addresses, 500);

        assertThat(result).hasSize(3);
    }

    @Test
    void benfordSplit_minOutputEnforced() {
        var addresses = List.of("addr1", "addr2", "addr3", "addr4", "addr5",
                "addr6", "addr7", "addr8", "addr9");
        long minOutput = 5000;

        var result = service.generateBenfordSplit(500_000, addresses, minOutput);

        for (var spec : result) {
            assertThat(spec.amountSats()).isGreaterThanOrEqualTo(minOutput);
        }
    }

    @Test
    void benfordSplit_addressesPreserved() {
        var addresses = List.of("1ABC", "1DEF", "1GHI");

        var result = service.generateBenfordSplit(100_000, addresses, 100);

        assertThat(result.stream().map(InvoiceOutputSpec.P2PKHOutputSpec::address).toList())
                .containsExactlyElementsOf(addresses);
    }

    @Test
    void benfordSplit_singleAddress() {
        var result = service.generateBenfordSplit(50_000, List.of("addr1"), 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).amountSats()).isEqualTo(50_000);
    }

    @Test
    void benfordSplit_emptyAddresses_throwsException() {
        assertThatThrownBy(() -> service.generateBenfordSplit(10_000, List.of(), 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void benfordSplit_insufficientForMinOutputs_throwsException() {
        var addresses = List.of("addr1", "addr2", "addr3");

        assertThatThrownBy(() -> service.generateBenfordSplit(100, addresses, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insufficient");
    }

    @Test
    void benfordSplit_distributionFollowsBenfordPattern() {
        var addresses = IntStream.rangeClosed(1, 9).mapToObj(i -> "addr" + i).toList();
        long total = 10_000_000;

        var result = service.generateBenfordSplit(total, addresses, 100);

        // First output should be largest (Benford digit 1 has highest probability)
        long first = result.get(0).amountSats();
        long last = result.get(result.size() - 1).amountSats();
        assertThat(first).isGreaterThan(last);
    }

    @Test
    void benfordSplit_zeroTotal_throwsException() {
        assertThatThrownBy(() -> service.generateBenfordSplit(0, List.of("addr1"), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
