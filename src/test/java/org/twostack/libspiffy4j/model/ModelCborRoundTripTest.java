package org.twostack.libspiffy4j.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCborRoundTripTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = new ObjectMapper(new CBORFactory())
            .registerModule(new ParameterNamesModule())
            .registerModule(new JavaTimeModule());
    }

    private <T> T roundTrip(T value, Class<T> type) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(value);
        return mapper.readValue(bytes, type);
    }

    @Test void bitcoinUtxo() throws Exception {
        var utxo = new BitcoinUtxo("txid1", 0, 50000, "script", "addr",
            UtxoStatus.AVAILABLE, 100, 6,
            Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z"),
            null, null, null, null, 0, null, null);
        var result = roundTrip(utxo, BitcoinUtxo.class);
        assertThat(result).isEqualTo(utxo);
    }

    @Test void addressMetadata() throws Exception {
        var meta = new AddressMetadata("1Addr", BitcoinScriptType.P2PKH, "m/44'/0'/0'/0/0",
            0, false, "main", "receive",
            Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-06-01T00:00:00Z"),
            5, 100000, Instant.parse("2024-12-01T00:00:00Z"), true);
        var result = roundTrip(meta, AddressMetadata.class);
        assertThat(result).isEqualTo(meta);
    }

    @Test void walletConfig() throws Exception {
        var config = new WalletConfig("w1", "My Wallet", "1Root", WalletType.HD,
            "mainnet", Map.of("key", "value"), Instant.parse("2025-01-01T00:00:00Z"));
        var result = roundTrip(config, WalletConfig.class);
        assertThat(result).isEqualTo(config);
    }

    @Test void bitcoinTransaction() throws Exception {
        var tx = new BitcoinTransaction("w1", "txid", "rawhex",
            TransactionStatus.CONFIRMED, TransactionDirection.INCOMING,
            100, 6, 50000, 49000, 1000, 49000,
            List.of("addr1"), List.of("addr2"),
            Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z"),
            "memo", 0, 2);
        var result = roundTrip(tx, BitcoinTransaction.class);
        assertThat(result).isEqualTo(tx);
    }

    @Test void transactionAddressLink() throws Exception {
        var link = new TransactionAddressLink("addr1", "output", 5000, 0, null);
        var result = roundTrip(link, TransactionAddressLink.class);
        assertThat(result).isEqualTo(link);
    }

    @Test void invoiceOutputSpec_polymorphic() throws Exception {
        InvoiceOutputSpec spec = new InvoiceOutputSpec.P2PKHOutputSpec("addr", 1000, "label");
        byte[] bytes = mapper.writeValueAsBytes(spec);
        InvoiceOutputSpec result = mapper.readValue(bytes, InvoiceOutputSpec.class);
        assertThat(result).isEqualTo(spec);
        assertThat(result).isInstanceOf(InvoiceOutputSpec.P2PKHOutputSpec.class);
    }

    @Test void invoiceOutputSpec_p2ms() throws Exception {
        InvoiceOutputSpec spec = new InvoiceOutputSpec.P2MSOutputSpec(
            List.of("k1", "k2"), 2, 5000, null);
        byte[] bytes = mapper.writeValueAsBytes(spec);
        InvoiceOutputSpec result = mapper.readValue(bytes, InvoiceOutputSpec.class);
        assertThat(result).isEqualTo(spec);
    }

    @Test void invoice() throws Exception {
        var invoice = new Invoice("inv1", "w1", List.of("addr1"),
            10000, List.of(new InvoiceOutputSpec.P2PKHOutputSpec("addr1", 10000, null)),
            "test invoice", InvoiceStatus.PENDING,
            Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-02-01T00:00:00Z"),
            null, null, null, Map.of("ref", "order123"));
        var result = roundTrip(invoice, Invoice.class);
        assertThat(result.invoiceId()).isEqualTo("inv1");
        assertThat(result.outputs()).hasSize(1);
        assertThat(result.metadata()).containsEntry("ref", "order123");
    }

    @Test void paymentChannel() throws Exception {
        var channel = new PaymentChannel("ch1", "w1", PaymentChannelRole.CLIENT,
            "peer1", "peer2", "pubhex1", "pubhex2", "addr1", "addr2",
            100000, 1700000000L, PaymentChannelState.OPEN,
            60000, 40000, "ftxid", "ftxhex", 0,
            null, null, null, 5, "ptxhex", "ptxid", null,
            List.of("anc1"), "ctx",
            Instant.parse("2025-01-01T00:00:00Z"), null, null);
        var result = roundTrip(channel, PaymentChannel.class);
        assertThat(result.channelId()).isEqualTo("ch1");
        assertThat(result.isOpen()).isTrue();
        assertThat(result.isClient()).isTrue();
    }

    @Test void channelUpdate() throws Exception {
        var update = new ChannelUpdate("ch1", 50000, 3, "sig", Instant.parse("2025-01-01T00:00:00Z"), "payment");
        var result = roundTrip(update, ChannelUpdate.class);
        assertThat(result).isEqualTo(update);
    }

    @Test void paymentChannelResult() throws Exception {
        var pcr = PaymentChannelResult.success(null, "txhex", "beefhex");
        var result = roundTrip(pcr, PaymentChannelResult.class);
        assertThat(result.success()).isTrue();
        assertThat(result.transactionHex()).isEqualTo("txhex");
    }

    @Test void arcServiceConfig() throws Exception {
        var config = ArcServiceConfig.taalTestnet("mykey");
        var result = roundTrip(config, ArcServiceConfig.class);
        assertThat(result).isEqualTo(config);
    }

    @Test void transactionBuildConfig() throws Exception {
        var config = TransactionBuildConfig.standard();
        var result = roundTrip(config, TransactionBuildConfig.class);
        assertThat(result).isEqualTo(config);
    }

    @Test void cdnHeaderSyncConfig() throws Exception {
        var config = new CdnHeaderSyncConfig("https://cdn.example.com", "mainnet",
            4, Duration.ofSeconds(30), true, true, "/tmp/cache", 3);
        var result = roundTrip(config, CdnHeaderSyncConfig.class);
        assertThat(result).isEqualTo(config);
    }

    @Test void nullOptionalFields() throws Exception {
        var utxo = new BitcoinUtxo("txid1", 0, 0, null, null,
            UtxoStatus.PENDING, null, null, null, null,
            null, null, null, null, null, null, null);
        var result = roundTrip(utxo, BitcoinUtxo.class);
        assertThat(result.blockHeight()).isNull();
        assertThat(result.address()).isNull();
    }

    @Test void enumRoundTrip() throws Exception {
        for (var status : UtxoStatus.values()) {
            byte[] bytes = mapper.writeValueAsBytes(status);
            assertThat(mapper.readValue(bytes, UtxoStatus.class)).isEqualTo(status);
        }
    }
}
