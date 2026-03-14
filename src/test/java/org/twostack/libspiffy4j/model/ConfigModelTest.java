package org.twostack.libspiffy4j.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ConfigModelTest {

    @Test void arcServiceConfig_taalTestnet() {
        var config = ArcServiceConfig.taalTestnet("key123");
        assertThat(config.baseUrl()).isEqualTo("https://arc-test.taal.com");
        assertThat(config.apiKey()).isEqualTo("key123");
        assertThat(config.defaultCallbackUrl()).isNull();
    }

    @Test void arcServiceConfig_taalMainnet() {
        var config = ArcServiceConfig.taalMainnet();
        assertThat(config.baseUrl()).isEqualTo("https://arc.taal.com");
    }

    @Test void arcServiceConfig_custom() {
        var config = ArcServiceConfig.custom("http://localhost", "key", "http://cb");
        assertThat(config.baseUrl()).isEqualTo("http://localhost");
        assertThat(config.defaultCallbackUrl()).isEqualTo("http://cb");
    }

    @Test void transactionBuildConfig_standard() {
        var config = TransactionBuildConfig.standard();
        assertThat(config.feePerKb()).isEqualTo(100);
        assertThat(config.selectionStrategy()).isEqualTo(UtxoSelectionStrategy.OPTIMAL_CHANGE);
        assertThat(config.performSanityChecks()).isTrue();
    }

    @Test void transactionBuildConfig_partial() {
        var config = TransactionBuildConfig.partial();
        assertThat(config.selectionStrategy()).isEqualTo(UtxoSelectionStrategy.SMALLEST_FIRST);
        assertThat(config.performSanityChecks()).isFalse();
    }

    @Test void walletConfig_metadataDefensiveCopy() {
        var mutable = new HashMap<String, Object>();
        mutable.put("k", "v");
        var config = new WalletConfig("w1", "name", "root", WalletType.HD,
            "mainnet", mutable, Instant.now());
        mutable.put("k2", "v2");
        assertThat(config.metadata()).doesNotContainKey("k2");
    }

    @Test void walletConfig_nullMetadataBecomesEmpty() {
        var config = new WalletConfig("w1", "name", "root", WalletType.HD,
            "mainnet", null, Instant.now());
        assertThat(config.metadata()).isEmpty();
    }
}
