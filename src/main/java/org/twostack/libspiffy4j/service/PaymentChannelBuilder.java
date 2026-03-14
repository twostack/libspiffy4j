package org.twostack.libspiffy4j.service;

import org.twostack.bitcoin4j.ECKey;
import org.twostack.libspiffy4j.model.BitcoinUtxo;
import org.twostack.libspiffy4j.model.NetworkType;
import org.twostack.libspiffy4j.model.TransactionBuildConfig;
import org.twostack.libspiffy4j.model.TransactionBuildResult;

import java.util.List;

public final class PaymentChannelBuilder {

    private final MultisigTransactionService multisigService;

    public PaymentChannelBuilder(MultisigTransactionService multisigService) {
        this.multisigService = multisigService;
    }

    public TransactionBuildResult buildFundingTransaction(
            String clientPubKeyHex,
            String serverPubKeyHex,
            long amountSats,
            List<BitcoinUtxo> availableUtxos,
            TransactionBuildConfig config,
            String changeAddress,
            ECKey signingKey,
            NetworkType networkType) {
        return multisigService.buildFundingTransaction(
                clientPubKeyHex, serverPubKeyHex, amountSats,
                availableUtxos, config, changeAddress, signingKey, networkType);
    }

    public byte[] signMultisigInput(byte[] rawTx, int inputIndex, ECKey privateKey, long inputAmountSats) {
        return multisigService.signMultisigInput(rawTx, inputIndex, privateKey, inputAmountSats);
    }
}
