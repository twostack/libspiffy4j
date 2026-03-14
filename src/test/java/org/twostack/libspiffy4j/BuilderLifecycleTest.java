package org.twostack.libspiffy4j;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.persistence.testkit.PersistenceTestKitPlugin$;
import org.apache.pekko.persistence.testkit.PersistenceTestKitSnapshotPlugin$;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuilderLifecycleTest {

    private static final Config TESTKIT_CONFIG = PersistenceTestKitPlugin$.MODULE$.config()
            .withFallback(PersistenceTestKitSnapshotPlugin$.MODULE$.config())
            .withFallback(ConfigFactory.parseString("""
                    pekko.actor.provider = "cluster"
                    pekko.remote.artery.canonical.hostname = "127.0.0.1"
                    pekko.remote.artery.canonical.port = 0
                    pekko.cluster.downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
                    pekko.actor.allow-java-serialization = on
                    """));

    @Test
    void noDataSourceThrows() {
        assertThatThrownBy(() -> LibSpiffy4j.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DataSource is required");
    }

    @Test
    void withDataSourceBuilds() {
        DataSource ds = dummyDataSource();
        try (LibSpiffy4j lib = LibSpiffy4j.builder().dataSource(ds).configOverride(TESTKIT_CONFIG).build()) {
            assertThat(lib).isNotNull();
            assertThat(lib.system()).isNotNull();
        }
    }

    @Test
    void closeCompletes() {
        DataSource ds = dummyDataSource();
        LibSpiffy4j lib = LibSpiffy4j.builder().dataSource(ds).configOverride(TESTKIT_CONFIG).build();
        lib.close();
    }

    @Test
    void closeTwiceIsIdempotent() {
        DataSource ds = dummyDataSource();
        LibSpiffy4j lib = LibSpiffy4j.builder().dataSource(ds).configOverride(TESTKIT_CONFIG).build();
        lib.close();
        lib.close();
    }

    private static DataSource dummyDataSource() {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("toString".equals(method.getName())) return "DummyDataSource";
                    throw new UnsupportedOperationException("dummy");
                });
    }
}
