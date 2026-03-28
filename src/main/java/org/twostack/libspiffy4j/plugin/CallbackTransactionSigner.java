package org.twostack.libspiffy4j.plugin;

/**
 * Secure signing interface that isolates private keys from plugin code.
 *
 * <p>The coordinator creates an instance by closing over the private key in a lambda.
 * Plugins call {@link #sign} to produce signatures but cannot extract the key itself.
 *
 * <pre>{@code
 * // Created internally by the coordinator:
 * CallbackTransactionSigner signer = (sighash, inputIndex) -> {
 *     ECKey.ECDSASignature sig = privateKey.sign(Sha256Hash.wrap(sighash));
 *     return sig.encodeToDER();
 * };
 *
 * // Plugin uses the signer without key access:
 * byte[] signature = signer.sign(sighashBytes, 0);
 * }</pre>
 */
@FunctionalInterface
public interface CallbackTransactionSigner {

    /**
     * Sign the given sighash for the specified input index.
     *
     * @param sighash the sighash bytes to sign
     * @param inputIndex the transaction input index being signed
     * @return DER-encoded signature bytes
     */
    byte[] sign(byte[] sighash, int inputIndex);

    /**
     * Sign the given sighash, using the locking script of the output being spent
     * to resolve the owner address and derive the correct signing key.
     *
     * <p>Default implementation ignores the script for backward compatibility.
     *
     * @param sighash        the sighash bytes to sign
     * @param inputIndex     the transaction input index being signed
     * @param scriptPubKey   the locking script of the output being spent
     * @return DER-encoded signature bytes
     */
    default byte[] sign(byte[] sighash, int inputIndex, byte[] scriptPubKey) {
        return sign(sighash, inputIndex);
    }
}
