package org.twostack.libspiffy4j.spv;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BumpTest {

    // Full BEEF hex — the BUMP is embedded after the 4-byte magic + 1-byte nBumps VarInt
    static final String BEEF_HEX =
            "0100beef01fe636d0c0007021400fe507c0c7aa754cef1f7889d5fd395cf1f785dd7de98eed895dbedfe4e5bc70d1502ac4e164f5bc16746bb0868404292ac8318bbac3800e4aad13a014da427adce3e010b00bc4ff395efd11719b277694cface5aa50d085a0bb81f613f70313acd28cf4557010400574b2d9142b8d28b61d88e3b2c3f44d858411356b49a28a4643b6d1a6a092a5201030051a05fc84d531b5d250c23f4f886f6812f9fe3f402d61607f977b4ecd2701c19010000fd781529d58fc2523cf396a7f25440b409857e7e221766c57214b1d38c7b481f01010062f542f45ea3660f86c013ced80534cb5fd4c19d66c56e7e8c5d4bf2d40acc5e010100b121e91836fd7cd5102b654e9f72f3cf6fdbfd0b161c53a9c54b12c841126331020100000001cd4e4cac3c7b56920d1e7655e7e260d31f29d9a388d04910f1bbd72304a79029010000006b483045022100e75279a205a547c445719420aa3138bf14743e3f42618e5f86a19bde14bb95f7022064777d34776b05d816daf1699493fcdf2ef5a5ab1ad710d9c97bfb5b8f7cef3641210263e2dee22b1ddc5e11f6fab8bcd2378bdd19580d640501ea956ec0e786f93e76ffffffff013e660000000000001976a9146bfd5c7fbe21529d45803dbcf0c87dd3c71efbc288ac0000000001000100000001ac4e164f5bc16746bb0868404292ac8318bbac3800e4aad13a014da427adce3e000000006a47304402203a61a2e931612b4bda08d541cfb980885173b8dcf64a3471238ae7abcd368d6402204cbf24f04b9aa2256d8901f0ed97866603d2be8324c2bfb7a37bf8fc90edd5b441210263e2dee22b1ddc5e11f6fab8bcd2378bdd19580d640501ea956ec0e786f93e76ffffffff013c660000000000001976a9146bfd5c7fbe21529d45803dbcf0c87dd3c71efbc288ac0000000000";

    /** Extract just the BUMP bytes from BEEF_HEX (skip 4-byte magic + 1-byte VarInt nBumps). */
    private byte[] bumpBytes() {
        byte[] beef = Beef.hexToBytes(BEEF_HEX);
        // magic(4) + nBumps VarInt(1) = 5 bytes to skip
        int bumpStart = 5;
        int[] consumed = new int[1];
        Bump.parse(beef, bumpStart, consumed);
        byte[] bump = new byte[consumed[0]];
        System.arraycopy(beef, bumpStart, bump, 0, consumed[0]);
        return bump;
    }

    @Test
    void parse_extractsBlockHeightAndTreeHeight() {
        byte[] data = bumpBytes();
        Bump bump = Bump.parse(data);

        assertThat(bump.blockHeight()).isEqualTo(814435L);
        assertThat(bump.treeHeight()).isEqualTo(7);
    }

    @Test
    void parse_extractsCorrectLeafCounts() {
        Bump bump = Bump.parse(bumpBytes());

        // Level 0 has 2 leaves, levels 1-6 each have 1 leaf
        assertThat(bump.path().get(0).leaves()).hasSize(2);
        for (int i = 1; i < 7; i++) {
            assertThat(bump.path().get(i).leaves()).hasSize(1);
        }
    }

    @Test
    void parse_level0_hasTxidLeaf() {
        Bump bump = Bump.parse(bumpBytes());
        var leaves = bump.path().get(0).leaves();

        // Second leaf at offset 21 should be the txid
        BumpLeaf txidLeaf = leaves.get(1);
        assertThat(txidLeaf.offset()).isEqualTo(21);
        assertThat(txidLeaf.isTxid()).isTrue();
        assertThat(txidLeaf.duplicate()).isFalse();
        assertThat(txidLeaf.hash()).hasSize(32);
    }

    @Test
    void roundTrip_serializeMatchesOriginalBytes() {
        byte[] original = bumpBytes();
        Bump bump = Bump.parse(original);
        byte[] serialized = bump.serialize();

        assertThat(serialized).isEqualTo(original);
    }

    @Test
    void computeMerkleRoot_withEmbeddedTxid_succeeds() {
        Bump bump = Bump.parse(bumpBytes());

        // The txid is the isTxid leaf's hash at level 0
        BumpLeaf txidLeaf = bump.path().get(0).leaves().get(1);
        byte[] txid = txidLeaf.hash();

        byte[] root = bump.computeMerkleRoot(txid);
        assertThat(root).hasSize(32);
    }

    @Test
    void computeMerkleRoot_isDeterministic() {
        Bump bump = Bump.parse(bumpBytes());
        BumpLeaf txidLeaf = bump.path().get(0).leaves().get(1);
        byte[] txid = txidLeaf.hash();

        byte[] root1 = bump.computeMerkleRoot(txid);
        byte[] root2 = bump.computeMerkleRoot(txid);

        assertThat(root1).isEqualTo(root2);
    }

    @Test
    void validateMerklePath_validTxid_returnsTrue() {
        Bump bump = Bump.parse(bumpBytes());
        BumpLeaf txidLeaf = bump.path().get(0).leaves().get(1);

        assertThat(bump.validateMerklePath(txidLeaf.hash())).isTrue();
    }

    @Test
    void validateMerklePath_invalidTxid_returnsFalse() {
        Bump bump = Bump.parse(bumpBytes());
        byte[] fakeTxid = new byte[32]; // all zeros

        assertThat(bump.validateMerklePath(fakeTxid)).isFalse();
    }

    @Test
    void parse_withOffset_reportsBytesConsumed() {
        byte[] beef = Beef.hexToBytes(BEEF_HEX);
        int[] consumed = new int[1];

        Bump bump = Bump.parse(beef, 5, consumed);

        assertThat(consumed[0]).isPositive();
        assertThat(bump.blockHeight()).isEqualTo(814435L);
    }

    @Test
    void parse_emptyData_throws() {
        assertThatThrownBy(() -> Bump.parse(new byte[0]))
                .isInstanceOf(ArrayIndexOutOfBoundsException.class);
    }
}
