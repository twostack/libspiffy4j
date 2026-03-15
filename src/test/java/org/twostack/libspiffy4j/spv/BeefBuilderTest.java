package org.twostack.libspiffy4j.spv;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class BeefBuilderTest {

    @Test
    void roundTrip_buildSerializeParse() {
        Beef original = Beef.parse(BeefTest.BEEF_HEX);

        var builder = new BeefBuilder();

        // Add proven transactions with their BUMPs
        for (int i = 0; i < original.transactionCount(); i++) {
            byte[] rawTx = original.getTransaction(i);
            if (original.hasMerkle().get(i)) {
                Bump bump = original.bumps().get(original.bumpIndex().get(i));
                builder.addProvenTransaction(rawTx, bump);
            } else {
                builder.addUnprovenTransaction(rawTx);
            }
        }

        Beef rebuilt = builder.build();
        byte[] serialized = rebuilt.serialize();
        Beef reparsed = Beef.parse(serialized);

        assertThat(reparsed.version()).isEqualTo(1);
        assertThat(reparsed.bumps()).hasSize(original.bumps().size());
        assertThat(reparsed.transactionCount()).isEqualTo(original.transactionCount());
        assertThat(reparsed.validate()).isTrue();
    }

    @Test
    void rebuildFromTestVector_serializeMatches() {
        byte[] originalBytes = Beef.hexToBytes(BeefTest.BEEF_HEX);
        Beef original = Beef.parse(originalBytes);

        var builder = new BeefBuilder();
        for (int i = 0; i < original.transactionCount(); i++) {
            byte[] rawTx = original.getTransaction(i);
            if (original.hasMerkle().get(i)) {
                Bump bump = original.bumps().get(original.bumpIndex().get(i));
                builder.addProvenTransaction(rawTx, bump);
            } else {
                builder.addUnprovenTransaction(rawTx);
            }
        }

        byte[] rebuilt = builder.build().serialize();
        assertThat(rebuilt).isEqualTo(originalBytes);
    }

    @Test
    void bumpDeduplication_sameBockHeight_oneBump() {
        Beef original = Beef.parse(BeefTest.BEEF_HEX);
        Bump bump = original.bumps().get(0);
        byte[] tx0 = original.getTransaction(0);

        // Use the same bump (same block height) for two different "transactions"
        // We need a second raw tx — just use the same bytes for the test
        byte[] tx1 = tx0.clone();
        tx1[tx1.length - 1] ^= 0xFF; // flip a byte to make it "different"

        var builder = new BeefBuilder();
        builder.addProvenTransaction(tx0, bump);
        builder.addProvenTransaction(tx1, bump);

        Beef beef = builder.build();
        assertThat(beef.bumps()).hasSize(1);
        assertThat(beef.transactionCount()).isEqualTo(2);
        assertThat(beef.hasMerkle().get(0)).isTrue();
        assertThat(beef.hasMerkle().get(1)).isTrue();
        assertThat(beef.bumpIndex().get(0)).isEqualTo(0);
        assertThat(beef.bumpIndex().get(1)).isEqualTo(0);
    }

    @Test
    void mixed_provenAndUnproven_correctFlags() {
        Beef original = Beef.parse(BeefTest.BEEF_HEX);
        Bump bump = original.bumps().get(0);
        byte[] provenTx = original.getTransaction(0);
        byte[] unprovenTx = original.getTransaction(1);

        var builder = new BeefBuilder();
        builder.addProvenTransaction(provenTx, bump);
        builder.addUnprovenTransaction(unprovenTx);

        Beef beef = builder.build();
        assertThat(beef.transactionCount()).isEqualTo(2);

        // Proven tx first
        assertThat(beef.hasMerkle().get(0)).isTrue();
        assertThat(beef.bumpIndex().get(0)).isEqualTo(0);

        // Unproven tx second
        assertThat(beef.hasMerkle().get(1)).isFalse();
        assertThat(beef.bumpIndex().get(1)).isEqualTo(-1);
    }

    @Test
    void emptyBuilder_throwsOnBuild() {
        var builder = new BeefBuilder();
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no transactions");
    }

    @Test
    void addProvenTransaction_nullTx_throws() {
        Beef original = Beef.parse(BeefTest.BEEF_HEX);
        Bump bump = original.bumps().get(0);
        var builder = new BeefBuilder();

        assertThatThrownBy(() -> builder.addProvenTransaction(null, bump))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addProvenTransaction_nullBump_throws() {
        var builder = new BeefBuilder();
        assertThatThrownBy(() -> builder.addProvenTransaction(new byte[]{1, 2, 3}, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addUnprovenTransaction_nullTx_throws() {
        var builder = new BeefBuilder();
        assertThatThrownBy(() -> builder.addUnprovenTransaction(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyUnprovenTransactions_buildsSuccessfully() {
        Beef original = Beef.parse(BeefTest.BEEF_HEX);
        byte[] tx = original.getTransaction(1);

        var builder = new BeefBuilder();
        builder.addUnprovenTransaction(tx);

        Beef beef = builder.build();
        assertThat(beef.bumps()).isEmpty();
        assertThat(beef.transactionCount()).isEqualTo(1);
        assertThat(beef.hasMerkle().get(0)).isFalse();
    }
}
