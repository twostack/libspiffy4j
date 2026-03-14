package org.twostack.libspiffy4j.projection;

import org.apache.pekko.projection.eventsourced.EventEnvelope;
import org.apache.pekko.projection.jdbc.javadsl.JdbcHandler;
import org.twostack.libspiffy4j.aggregate.wallet.WalletEvent;
import org.twostack.libspiffy4j.model.UtxoStatus;
import org.twostack.libspiffy4j.storage.postgres.WalletReadModelStorage;

import java.sql.Connection;

public class WalletProjectionHandler extends JdbcHandler<EventEnvelope<WalletEvent>, SpiffyJdbcSession> {

    private final WalletReadModelStorage storage;

    public WalletProjectionHandler(WalletReadModelStorage storage) {
        this.storage = storage;
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
                storage.upsertWalletUtxo(conn, e.walletId(), e.utxo());
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
}
