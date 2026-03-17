package org.twostack.libspiffy4j.projection;

import org.apache.pekko.projection.eventsourced.EventEnvelope;
import org.apache.pekko.projection.jdbc.javadsl.JdbcHandler;
import org.twostack.libspiffy4j.aggregate.wallet.WalletEvent;
import org.twostack.libspiffy4j.model.BitcoinUtxo;
import org.twostack.libspiffy4j.model.UtxoStatus;
import org.twostack.libspiffy4j.plugin.PluginRegistry;
import org.twostack.libspiffy4j.plugin.ScriptPlugin;
import org.twostack.libspiffy4j.storage.postgres.WalletReadModelStorage;

import java.sql.Connection;
import java.util.Map;
import java.util.Optional;

public class WalletProjectionHandler extends JdbcHandler<EventEnvelope<WalletEvent>, SpiffyJdbcSession> {

    private final WalletReadModelStorage storage;
    private final PluginRegistry pluginRegistry;

    public WalletProjectionHandler(WalletReadModelStorage storage, PluginRegistry pluginRegistry) {
        this.storage = storage;
        this.pluginRegistry = pluginRegistry;
    }

    @Override
    public void process(SpiffyJdbcSession session, EventEnvelope<WalletEvent> envelope) throws Exception {
        WalletEvent event = envelope.event();
        session.withConnection(conn -> {
            dispatch(conn, event);
            return null;
        });
    }

    private void dispatch(Connection conn, WalletEvent event) throws Exception {
        switch (event) {
            case WalletEvent.WalletCreatedEvent e -> {
                storage.upsertWalletSummary(conn, e.walletId(), e.name(), e.rootAddress(),
                        e.walletType(), e.networkType(), e.metadata(), e.createdAt());
            }
            case WalletEvent.AddressRecordedEvent e -> {
                storage.upsertWalletAddress(conn, e.walletId(), e.addressMetadata(), e.recordedAt());
            }
            case WalletEvent.UtxoReceivedEvent e -> {
                BitcoinUtxo utxo = enrichWithPluginData(e.utxo());
                storage.upsertWalletUtxo(conn, e.walletId(), utxo);
                storage.updateWalletBalances(conn, e.walletId());
            }
            case WalletEvent.UtxoSpentEvent e -> {
                storage.updateUtxoStatus(conn, e.walletId(), e.utxoKey(), UtxoStatus.SPENT, e.spentAt());
                storage.updateWalletBalances(conn, e.walletId());
            }
            case WalletEvent.UtxoReservedEvent e -> {
                storage.updateUtxoReserved(conn, e.walletId(), e.utxoKey(),
                        e.reservingTxId(), e.expiresAt(), e.reservedAt());
                storage.updateWalletBalances(conn, e.walletId());
            }
            case WalletEvent.UtxoReleasedEvent e -> {
                storage.updateUtxoStatus(conn, e.walletId(), e.utxoKey(), UtxoStatus.AVAILABLE, e.releasedAt());
                storage.updateWalletBalances(conn, e.walletId());
            }
            case WalletEvent.UtxoConfirmationUpdatedEvent e -> {
                storage.updateUtxoConfirmations(conn, e.walletId(), e.txid(),
                        e.confirmations(), e.blockHeight(), e.updatedAt());
                storage.updateWalletBalances(conn, e.walletId());
            }
            case WalletEvent.TransactionRecordedEvent e -> {
                storage.upsertWalletTransaction(conn, e.walletId(), e.transaction());
            }
            case WalletEvent.TransactionConfirmedEvent e -> {
                storage.updateTransactionConfirmed(conn, e.walletId(), e.txid(),
                        e.confirmations(), e.blockHeight(), e.confirmedAt());
            }
            case WalletEvent.WalletConfigurationUpdatedEvent e -> {
                // Could update metadata on wallet_summary if needed
            }
        }
    }

    /**
     * If the UTXO has a scriptPubKey but no pluginId, try all registered plugins
     * to identify and extract token metadata. Returns the original UTXO unchanged
     * if no plugin recognizes the script or if already enriched.
     */
    BitcoinUtxo enrichWithPluginData(BitcoinUtxo utxo) {
        if (utxo.scriptPubKey() == null || utxo.scriptPubKey().isBlank()
                || utxo.pluginId() != null || !pluginRegistry.hasPlugins()) {
            return utxo;
        }

        byte[] scriptBytes = hexToBytes(utxo.scriptPubKey());
        Optional<PluginRegistry.PluginIdentification> identification =
                pluginRegistry.identifyScript(scriptBytes);

        if (identification.isEmpty()) {
            return utxo;
        }

        String pluginId = identification.get().pluginId();
        ScriptPlugin plugin = pluginRegistry.getPlugin(pluginId).orElse(null);
        if (plugin == null) {
            return utxo;
        }

        Map<String, Object> metadata = plugin.extractMetadata(scriptBytes);

        return new BitcoinUtxo(
                utxo.txid(), utxo.vout(), utxo.valueSats(), utxo.scriptPubKey(),
                utxo.address(), utxo.status(), utxo.blockHeight(), utxo.confirmations(),
                utxo.createdAt(), utxo.updatedAt(),
                utxo.reservedByTxId(), utxo.reservationExpiresAt(),
                utxo.reservationPriority(), utxo.reservationReason(),
                utxo.derivationIndex(),
                pluginId, metadata
        );
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
