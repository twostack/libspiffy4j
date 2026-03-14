package org.twostack.libspiffy4j.service;

import org.twostack.bitcoin4j.ECKey;
import org.twostack.bitcoin4j.PrivateKey;
import org.twostack.bitcoin4j.PublicKey;
import org.twostack.bitcoin4j.Sha256Hash;
import org.twostack.bitcoin4j.Utils;
import org.twostack.bitcoin4j.address.LegacyAddress;
import org.twostack.bitcoin4j.params.NetworkType;
import org.twostack.bitcoin4j.script.Script;
import org.twostack.bitcoin4j.transaction.*;
import org.twostack.libspiffy4j.model.BitcoinUtxo;
import org.twostack.libspiffy4j.model.InvoiceOutputSpec;
import org.twostack.libspiffy4j.model.TransactionBuildConfig;
import org.twostack.libspiffy4j.model.TransactionBuildResult;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.List;

public final class MultisigTransactionService {

    private final TransactionBuildService txBuildService;

    public MultisigTransactionService(TransactionBuildService txBuildService) {
        this.txBuildService = txBuildService;
    }

    public TransactionBuildResult buildFundingTransaction(
            String clientPubKeyHex,
            String serverPubKeyHex,
            long amountSats,
            List<BitcoinUtxo> available,
            TransactionBuildConfig config,
            String changeAddress,
            ECKey signingKey,
            org.twostack.libspiffy4j.model.NetworkType networkType) {

        var multisigOutput = new InvoiceOutputSpec.P2MSOutputSpec(
                List.of(clientPubKeyHex, serverPubKeyHex),
                2,
                amountSats,
                "2-of-2 funding"
        );

        return txBuildService.buildTransaction(
                available,
                List.of(multisigOutput),
                config,
                changeAddress,
                signingKey,
                networkType
        );
    }

    public byte[] signMultisigInput(byte[] rawTx, int inputIndex, ECKey privateKey, long inputAmountSats) {
        try {
            Transaction tx = new Transaction(ByteBuffer.wrap(rawTx));
            TransactionInput input = tx.getInputs().get(inputIndex);

            int sighashType = SigHashType.ALL.value | SigHashType.FORKID.value;

            // Use the input's existing script as the subscript for sighash calculation
            Script subscript = input.getScriptSig();

            SigHash sigHash = new SigHash();
            byte[] hash = sigHash.createHash(tx, sighashType, inputIndex,
                    subscript, BigInteger.valueOf(inputAmountSats));

            Sha256Hash sha256Hash = Sha256Hash.wrap(hash);
            ECKey.ECDSASignature ecSig = privateKey.sign(sha256Hash);

            TransactionSignature txSig = new TransactionSignature(
                    ecSig, SigHashType.ALL, false, true);

            return txSig.encodeToBitcoin();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign multisig input: " + e.getMessage(), e);
        }
    }
}
