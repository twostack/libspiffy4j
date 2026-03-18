package org.twostack.libspiffy4j.coordinator;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.twostack.bitcoin4j.Sha256Hash;
import org.twostack.bitcoin4j.Utils;
import org.twostack.bitcoin4j.crypto.DeterministicKey;
import org.twostack.libspiffy4j.model.BitcoinUtxo;
import org.twostack.libspiffy4j.model.EncryptedKeyRecord;
import org.twostack.libspiffy4j.model.EncryptionResult;
import org.twostack.libspiffy4j.model.NetworkType;
import org.twostack.libspiffy4j.plugin.CallbackTransactionSigner;
import org.twostack.libspiffy4j.service.CryptoService;
import org.twostack.libspiffy4j.service.EncryptionService;
import org.twostack.libspiffy4j.storage.postgres.SecureStorage;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Non-persistent per-wallet actor responsible for all private key operations.
 *
 * <p>Loads HD keys from {@link SecureStorage}, derives per-UTXO child keys,
 * and creates {@link CallbackTransactionSigner} closures. The private key
 * never leaves this actor's context.
 *
 * <p>This actor is deliberately separate from the event-sourced
 * {@link org.twostack.libspiffy4j.aggregate.wallet.WalletAggregate} —
 * signing is a side-effect that doesn't produce persisted events.
 */
public final class WalletSigningActor extends AbstractBehavior<WalletSigningActor.SigningCommand> {

    private static final Logger LOG = Logger.getLogger(WalletSigningActor.class.getName());

    // ── Protocol ──

    public sealed interface SigningCommand permits PrepareSigner {}

    /**
     * Request a {@link CallbackTransactionSigner} and public keys for the given
     * funding UTXOs. The signer closure derives the correct child key per input
     * using the address → derivation index map.
     */
    public record PrepareSigner(
            String walletId,
            List<BitcoinUtxo> fundingUtxos,
            Map<String, Integer> addressToDerivationIndex,
            NetworkType networkType,
            ActorRef<SigningReply> replyTo
    ) implements SigningCommand {}

    public sealed interface SigningReply permits SignerReady, SigningFailure {}

    public record SignerReady(
            CallbackTransactionSigner signer,
            List<String> publicKeyHexes
    ) implements SigningReply {}

    public record SigningFailure(String reason) implements SigningReply {}

    // ── Dependencies ──

    private final SecureStorage secureStorage;
    private final EncryptionService encryptionService;
    private final CryptoService cryptoService;
    private final DataSource dataSource;

    // ── Factory ──

    public static Behavior<SigningCommand> create(
            SecureStorage secureStorage,
            EncryptionService encryptionService,
            CryptoService cryptoService,
            DataSource dataSource) {
        return Behaviors.setup(ctx ->
                new WalletSigningActor(ctx, secureStorage, encryptionService, cryptoService, dataSource));
    }

    private WalletSigningActor(
            ActorContext<SigningCommand> context,
            SecureStorage secureStorage,
            EncryptionService encryptionService,
            CryptoService cryptoService,
            DataSource dataSource) {
        super(context);
        this.secureStorage = secureStorage;
        this.encryptionService = encryptionService;
        this.cryptoService = cryptoService;
        this.dataSource = dataSource;
    }

    @Override
    public Receive<SigningCommand> createReceive() {
        return newReceiveBuilder()
                .onMessage(PrepareSigner.class, this::onPrepareSigner)
                .build();
    }

    // ── Command handlers ──

    private Behavior<SigningCommand> onPrepareSigner(PrepareSigner cmd) {
        try {
            if (encryptionService == null) {
                cmd.replyTo().tell(new SigningFailure("EncryptionService required for signing"));
                return this;
            }

            int coinType = (cmd.networkType() == NetworkType.MAINNET) ? 236 : 1;
            org.twostack.bitcoin4j.params.NetworkType bitcoin4jNetwork = toBitcoin4jNetworkType(cmd.networkType());

            LOG.info("PrepareSigner for wallet " + cmd.walletId()
                    + " — " + cmd.fundingUtxos().size() + " UTXOs, "
                    + cmd.addressToDerivationIndex().size() + " address mappings");

            // Try HD key first
            Optional<EncryptedKeyRecord> hdRecord =
                    secureStorage.loadEncryptedKey(dataSource, cmd.walletId(), "hdkey");
            if (hdRecord.isPresent()) {
                LOG.info("Found HD key for wallet " + cmd.walletId());
                DeterministicKey hdKey = decryptHDKey(hdRecord.get(), cmd.walletId(), bitcoin4jNetwork);
                SignerReady ready = prepareHDSigner(hdKey, cmd, coinType);
                LOG.info("HD signer ready — " + ready.publicKeyHexes().size() + " public keys: "
                        + ready.publicKeyHexes());
                cmd.replyTo().tell(ready);
                return this;
            }

            // Fall back to WIF
            Optional<EncryptedKeyRecord> wifRecord =
                    secureStorage.loadEncryptedKey(dataSource, cmd.walletId(), "wif");
            if (wifRecord.isPresent()) {
                LOG.info("Found WIF key for wallet " + cmd.walletId());
                org.twostack.bitcoin4j.ECKey ecKey = decryptWIFKey(wifRecord.get(), cmd.walletId());
                cmd.replyTo().tell(prepareWIFSigner(ecKey));
                return this;
            }

            LOG.warning("No signing key found for wallet: " + cmd.walletId());
            cmd.replyTo().tell(new SigningFailure("No signing key found for wallet: " + cmd.walletId()));
        } catch (Exception e) {
            cmd.replyTo().tell(new SigningFailure("Signing preparation failed: " + e.getMessage()));
        }
        return this;
    }

    // ── Signer factories ──

    private SignerReady prepareHDSigner(
            DeterministicKey hdKey, PrepareSigner cmd, int coinType) {

        List<BitcoinUtxo> fundingUtxos = cmd.fundingUtxos();
        Map<String, Integer> addressToIndex = cmd.addressToDerivationIndex();

        // Build signer closure — derives the correct child key per input
        CallbackTransactionSigner signer = (sighash, inputIndex) -> {
            LOG.info("Signer called for inputIndex=" + inputIndex
                    + " sighash=" + Utils.HEX.encode(sighash).substring(0, 16) + "...");
            String address = fundingUtxos.get(inputIndex).address();
            int derivIdx = addressToIndex.getOrDefault(address, 0);
            LOG.info("  address=" + address + " derivIdx=" + derivIdx);
            DeterministicKey childKey = cryptoService.derivePrivateKey(hdKey, 0, derivIdx, coinType, false);
            org.twostack.bitcoin4j.ECKey ecKey =
                    org.twostack.bitcoin4j.ECKey.fromPrivate(childKey.getPrivKeyBytes(), true);
            LOG.info("  pubKey=" + Utils.HEX.encode(ecKey.getPubKey()));
            org.twostack.bitcoin4j.ECKey.ECDSASignature sig =
                    ecKey.sign(Sha256Hash.wrap(sighash));
            LOG.info("  signature produced, DER length=" + sig.encodeToDER().length);
            return sig.encodeToDER();
        };

        // Derive public keys for each funding UTXO
        List<String> publicKeyHexes = new ArrayList<>();
        for (BitcoinUtxo utxo : fundingUtxos) {
            int derivIdx = addressToIndex.getOrDefault(utxo.address(), 0);
            DeterministicKey childKey = cryptoService.derivePrivateKey(hdKey, 0, derivIdx, coinType, false);
            org.twostack.bitcoin4j.ECKey ecKey =
                    org.twostack.bitcoin4j.ECKey.fromPrivate(childKey.getPrivKeyBytes(), true);
            publicKeyHexes.add(Utils.HEX.encode(ecKey.getPubKey()));
        }

        return new SignerReady(signer, publicKeyHexes);
    }

    private SignerReady prepareWIFSigner(org.twostack.bitcoin4j.ECKey ecKey) {
        CallbackTransactionSigner signer = (sighash, inputIndex) -> {
            org.twostack.bitcoin4j.ECKey.ECDSASignature sig =
                    ecKey.sign(Sha256Hash.wrap(sighash));
            return sig.encodeToDER();
        };
        List<String> publicKeyHexes = List.of(Utils.HEX.encode(ecKey.getPubKey()));
        return new SignerReady(signer, publicKeyHexes);
    }

    // ── Key decryption ──

    private DeterministicKey decryptHDKey(
            EncryptedKeyRecord record, String walletId,
            org.twostack.bitcoin4j.params.NetworkType bitcoin4jNetwork) {
        String context = "wallet:" + walletId + ":hdkey";
        byte[] decrypted = encryptionService.decrypt(record.encryptedKey(), record.nonce(), context);
        return DeterministicKey.deserialize(bitcoin4jNetwork, decrypted);
    }

    private org.twostack.bitcoin4j.ECKey decryptWIFKey(EncryptedKeyRecord record, String walletId) throws Exception {
        String context = "wallet:" + walletId;
        byte[] decrypted = encryptionService.decrypt(record.encryptedKey(), record.nonce(), context);
        String wif = new String(decrypted, StandardCharsets.UTF_8);
        return cryptoService.privateKeyFromWIF(wif);
    }

    private static org.twostack.bitcoin4j.params.NetworkType toBitcoin4jNetworkType(NetworkType networkType) {
        return switch (networkType) {
            case MAINNET -> org.twostack.bitcoin4j.params.NetworkType.MAIN;
            case TESTNET -> org.twostack.bitcoin4j.params.NetworkType.TEST;
            case REGTEST -> org.twostack.bitcoin4j.params.NetworkType.REGTEST;
        };
    }
}
