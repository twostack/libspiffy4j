package org.twostack.libspiffy4j.spv;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BeefTest {

    static final String BEEF_HEX =
            "0100beef01fe636d0c0007021400fe507c0c7aa754cef1f7889d5fd395cf1f785dd7de98eed895dbedfe4e5bc70d1502ac4e164f5bc16746bb0868404292ac8318bbac3800e4aad13a014da427adce3e010b00bc4ff395efd11719b277694cface5aa50d085a0bb81f613f70313acd28cf4557010400574b2d9142b8d28b61d88e3b2c3f44d858411356b49a28a4643b6d1a6a092a5201030051a05fc84d531b5d250c23f4f886f6812f9fe3f402d61607f977b4ecd2701c19010000fd781529d58fc2523cf396a7f25440b409857e7e221766c57214b1d38c7b481f01010062f542f45ea3660f86c013ced80534cb5fd4c19d66c56e7e8c5d4bf2d40acc5e010100b121e91836fd7cd5102b654e9f72f3cf6fdbfd0b161c53a9c54b12c841126331020100000001cd4e4cac3c7b56920d1e7655e7e260d31f29d9a388d04910f1bbd72304a79029010000006b483045022100e75279a205a547c445719420aa3138bf14743e3f42618e5f86a19bde14bb95f7022064777d34776b05d816daf1699493fcdf2ef5a5ab1ad710d9c97bfb5b8f7cef3641210263e2dee22b1ddc5e11f6fab8bcd2378bdd19580d640501ea956ec0e786f93e76ffffffff013e660000000000001976a9146bfd5c7fbe21529d45803dbcf0c87dd3c71efbc288ac0000000001000100000001ac4e164f5bc16746bb0868404292ac8318bbac3800e4aad13a014da427adce3e000000006a47304402203a61a2e931612b4bda08d541cfb980885173b8dcf64a3471238ae7abcd368d6402204cbf24f04b9aa2256d8901f0ed97866603d2be8324c2bfb7a37bf8fc90edd5b441210263e2dee22b1ddc5e11f6fab8bcd2378bdd19580d640501ea956ec0e786f93e76ffffffff013c660000000000001976a9146bfd5c7fbe21529d45803dbcf0c87dd3c71efbc288ac0000000000";

    @Test
    void parse_validBeef_extractsCorrectCounts() {
        Beef beef = Beef.parse(BEEF_HEX);

        assertThat(beef.version()).isEqualTo(1);
        assertThat(beef.bumps()).hasSize(1);
        assertThat(beef.transactionCount()).isEqualTo(2);
    }

    @Test
    void parse_validBeef_extractsBumpBlockHeight() {
        Beef beef = Beef.parse(BEEF_HEX);

        assertThat(beef.bumps().get(0).blockHeight()).isEqualTo(814435L);
    }

    @Test
    void parse_validBeef_firstTxHasMerkle() {
        Beef beef = Beef.parse(BEEF_HEX);

        assertThat(beef.hasMerkle().get(0)).isTrue();
        assertThat(beef.bumpIndex().get(0)).isEqualTo(0);
    }

    @Test
    void parse_validBeef_secondTxHasNoMerkle() {
        Beef beef = Beef.parse(BEEF_HEX);

        assertThat(beef.hasMerkle().get(1)).isFalse();
        assertThat(beef.bumpIndex().get(1)).isEqualTo(-1);
    }

    @Test
    void roundTrip_serializeMatchesOriginal() {
        byte[] original = Beef.hexToBytes(BEEF_HEX);
        Beef beef = Beef.parse(original);
        byte[] serialized = beef.serialize();

        assertThat(serialized).isEqualTo(original);
    }

    @Test
    void calculateTxid_returnsConsistentResult() {
        Beef beef = Beef.parse(BEEF_HEX);
        byte[] tx0 = beef.getTransaction(0);

        byte[] txid1 = Beef.calculateTxid(tx0);
        byte[] txid2 = Beef.calculateTxid(tx0);

        assertThat(txid1).hasSize(32);
        assertThat(txid1).isEqualTo(txid2);
    }

    @Test
    void findTransactionByTxid_existingTx_returnsRawBytes() {
        Beef beef = Beef.parse(BEEF_HEX);
        byte[] tx0 = beef.getTransaction(0);
        byte[] txid = Beef.calculateTxid(tx0);

        byte[] found = beef.findTransactionByTxid(txid);

        assertThat(found).isEqualTo(tx0);
    }

    @Test
    void findTransactionByTxid_unknownTxid_returnsNull() {
        Beef beef = Beef.parse(BEEF_HEX);
        byte[] fakeTxid = new byte[32];

        assertThat(beef.findTransactionByTxid(fakeTxid)).isNull();
    }

    @Test
    void validateTransaction_provenTx_returnsTrue() {
        Beef beef = Beef.parse(BEEF_HEX);
        byte[] tx0 = beef.getTransaction(0);
        byte[] txid = Beef.calculateTxid(tx0);

        assertThat(beef.validateTransaction(txid)).isTrue();
    }

    @Test
    void validateTransaction_unprovenTx_returnsFalse() {
        Beef beef = Beef.parse(BEEF_HEX);
        // Second tx has no BUMP proof
        byte[] tx1 = beef.getTransaction(1);
        byte[] txid = Beef.calculateTxid(tx1);

        assertThat(beef.validateTransaction(txid)).isFalse();
    }

    @Test
    void validate_allProvenTxsValid_returnsTrue() {
        Beef beef = Beef.parse(BEEF_HEX);

        assertThat(beef.validate()).isTrue();
    }

    @Test
    void validateTransactionWithBlockHeader_matchingRoot_returnsTrue() {
        Beef beef = Beef.parse(BEEF_HEX);
        byte[] tx0 = beef.getTransaction(0);
        byte[] txid = Beef.calculateTxid(tx0);

        // Compute the actual merkle root from the BUMP
        byte[] merkleRoot = beef.bumps().get(0).computeMerkleRoot(txid);

        // Create a block header with that merkle root
        BlockHeader header = new BlockHeader(
                1L, new byte[32], merkleRoot, 0L, 0L, 0L);

        assertThat(beef.validateTransactionWithBlockHeader(txid, header)).isTrue();
    }

    @Test
    void validateTransactionWithBlockHeader_wrongRoot_returnsFalse() {
        Beef beef = Beef.parse(BEEF_HEX);
        byte[] tx0 = beef.getTransaction(0);
        byte[] txid = Beef.calculateTxid(tx0);

        BlockHeader header = new BlockHeader(
                1L, new byte[32], new byte[32], 0L, 0L, 0L);

        assertThat(beef.validateTransactionWithBlockHeader(txid, header)).isFalse();
    }

    @Test
    void parse_emptyData_throws() {
        assertThatThrownBy(() -> Beef.parse(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void parse_badMagic_throws() {
        byte[] bad = {0x00, 0x00, 0x00, 0x00, 0x01};
        assertThatThrownBy(() -> Beef.parse(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("magic");
    }

    @Test
    void parse_truncatedData_throws() {
        // Valid magic but truncated before any BUMP data
        byte[] truncated = {0x01, 0x00, (byte) 0xBE, (byte) 0xEF};
        assertThatThrownBy(() -> Beef.parse(truncated))
                .isInstanceOf(Exception.class);
    }
}
