package org.twostack.libspiffy4j.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = InvoiceOutputSpec.P2PKHOutputSpec.class, name = "p2pkh"),
    @JsonSubTypes.Type(value = InvoiceOutputSpec.P2MSOutputSpec.class, name = "p2ms"),
    @JsonSubTypes.Type(value = InvoiceOutputSpec.OPReturnOutputSpec.class, name = "op_return")
})
public sealed interface InvoiceOutputSpec {

    record P2PKHOutputSpec(String address, long amountSats, String label) implements InvoiceOutputSpec {}

    record P2MSOutputSpec(List<String> publicKeys, int threshold, long amountSats, String label) implements InvoiceOutputSpec {
        public P2MSOutputSpec {
            publicKeys = publicKeys == null ? List.of() : List.copyOf(publicKeys);
            if (threshold <= 0) throw new IllegalArgumentException("threshold must be > 0");
            if (threshold > publicKeys.size()) throw new IllegalArgumentException("threshold must be <= number of public keys");
            if (publicKeys.size() > 16) throw new IllegalArgumentException("at most 16 public keys allowed");
        }
    }

    record OPReturnOutputSpec(List<byte[]> dataChunks, boolean separateOutputs) implements InvoiceOutputSpec {
        public OPReturnOutputSpec {
            dataChunks = dataChunks == null ? List.of() : List.copyOf(dataChunks);
            long totalSize = dataChunks.stream().mapToLong(c -> c.length).sum();
            if (totalSize > 99_000) throw new IllegalArgumentException("total data size must be <= 99,000 bytes");
        }
    }
}
