package org.twostack.libspiffy4j.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.persistence.testkit.PersistenceTestKitPlugin$;
import org.apache.pekko.persistence.testkit.PersistenceTestKitSnapshotPlugin$;
import org.apache.pekko.serialization.Serialization;
import org.apache.pekko.serialization.SerializationExtension;
import org.apache.pekko.serialization.Serializer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CborSerializationTest {

    record TestEvent(String id, byte[] data) implements SpiffyEvent {}

    @Test
    void jacksonCborRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new CBORFactory());
        mapper.registerModule(new ParameterNamesModule());

        TestEvent original = new TestEvent("test-1", new byte[]{1, 2, 3, 4, 5});
        byte[] serialized = mapper.writeValueAsBytes(original);
        TestEvent deserialized = mapper.readValue(serialized, TestEvent.class);

        assertThat(deserialized.id()).isEqualTo(original.id());
        assertThat(deserialized.data()).isEqualTo(original.data());
    }

    @Test
    void serializationExtensionResolvesSpiffyEventToJacksonCbor() throws Exception {
        Config testkitConfig = PersistenceTestKitPlugin$.MODULE$.config()
                .withFallback(PersistenceTestKitSnapshotPlugin$.MODULE$.config());

        Config config = testkitConfig
                .withFallback(ConfigFactory.parseString("""
                        pekko.actor.provider = "cluster"
                        pekko.remote.artery.canonical.hostname = "127.0.0.1"
                        pekko.remote.artery.canonical.port = 0
                        pekko.cluster.downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
                        pekko.actor.allow-java-serialization = on
                        """))
                .withFallback(ConfigFactory.load());

        ActorSystem<Void> system = ActorSystem.create(Behaviors.empty(), "cbor-test", config);
        try {
            Serialization serialization = SerializationExtension.get(system.classicSystem());
            Serializer serializer = serialization.serializerFor(SpiffyEvent.class);

            assertThat(serializer.getClass().getSimpleName()).contains("JacksonCborSerializer");
        } finally {
            system.terminate();
        }
    }
}
