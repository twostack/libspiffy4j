package org.twostack.libspiffy4j.aggregate.wallet;

import org.twostack.libspiffy4j.model.*;
import org.twostack.libspiffy4j.serialization.SpiffyEvent;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class WalletState implements SpiffyEvent {

    private String walletId;
    private String name;
    private String rootAddress;
    private boolean created;
    private NetworkType networkType;
    private WalletType walletType;
    private Map<String, BitcoinUtxo> utxos = new HashMap<>();
    private Map<String, AddressMetadata> addresses = new HashMap<>();
    private Map<String, BitcoinTransaction> transactions = new HashMap<>();
    private int nextDerivationIndex;
    private long confirmedBalanceSats;
    private long unconfirmedBalanceSats;
    private long reservedBalanceSats;
    private Map<String, Object> metadata = new HashMap<>();
    private long version;
    private Instant lastUpdatedAt;

    public WalletState() {
    }

    // --- Event application methods ---

    public WalletState applyWalletCreated(WalletEvent.WalletCreatedEvent event) {
        this.walletId = event.walletId();
        this.name = event.name();
        this.walletType = event.walletType();
        this.networkType = event.networkType();
        this.rootAddress = event.rootAddress();
        this.metadata = event.metadata() != null ? new HashMap<>(event.metadata()) : new HashMap<>();
        this.created = true;
        this.lastUpdatedAt = event.createdAt();
        this.version++;
        return this;
    }

    public WalletState applyAddressRecorded(WalletEvent.AddressRecordedEvent event) {
        this.addresses.put(event.addressMetadata().address(), event.addressMetadata());
        if (event.derivationIndex() >= this.nextDerivationIndex) {
            this.nextDerivationIndex = event.derivationIndex() + 1;
        }
        this.lastUpdatedAt = event.recordedAt();
        this.version++;
        return this;
    }

    public WalletState applyUtxoReceived(WalletEvent.UtxoReceivedEvent event) {
        BitcoinUtxo utxo = event.utxo();
        this.utxos.put(utxo.key(), utxo);
        if (utxo.confirmations() != null && utxo.confirmations() > 0) {
            this.confirmedBalanceSats += utxo.valueSats();
        } else {
            this.unconfirmedBalanceSats += utxo.valueSats();
        }
        this.lastUpdatedAt = event.receivedAt();
        this.version++;
        return this;
    }

    public WalletState applyUtxoSpent(WalletEvent.UtxoSpentEvent event) {
        BitcoinUtxo utxo = this.utxos.get(event.utxoKey());
        if (utxo != null) {
            if (utxo.status() == UtxoStatus.RESERVED) {
                this.reservedBalanceSats -= utxo.valueSats();
            } else if (utxo.confirmations() != null && utxo.confirmations() > 0) {
                this.confirmedBalanceSats -= utxo.valueSats();
            } else {
                this.unconfirmedBalanceSats -= utxo.valueSats();
            }
            this.utxos.put(event.utxoKey(), utxo.markSpent());
        }
        this.lastUpdatedAt = event.spentAt();
        this.version++;
        return this;
    }

    public WalletState applyUtxoReserved(WalletEvent.UtxoReservedEvent event) {
        BitcoinUtxo utxo = this.utxos.get(event.utxoKey());
        if (utxo != null) {
            if (utxo.confirmations() != null && utxo.confirmations() > 0) {
                this.confirmedBalanceSats -= utxo.valueSats();
            } else {
                this.unconfirmedBalanceSats -= utxo.valueSats();
            }
            this.reservedBalanceSats += utxo.valueSats();
            this.utxos.put(event.utxoKey(), utxo.reserve(
                    event.reservingTxId(), event.expiresAt(), event.priority(), event.reason()));
        }
        this.lastUpdatedAt = event.reservedAt();
        this.version++;
        return this;
    }

    public WalletState applyUtxoReleased(WalletEvent.UtxoReleasedEvent event) {
        BitcoinUtxo utxo = this.utxos.get(event.utxoKey());
        if (utxo != null) {
            this.reservedBalanceSats -= utxo.valueSats();
            BitcoinUtxo released = utxo.releaseReservation();
            if (released.confirmations() != null && released.confirmations() > 0) {
                this.confirmedBalanceSats += released.valueSats();
            } else {
                this.unconfirmedBalanceSats += released.valueSats();
            }
            this.utxos.put(event.utxoKey(), released);
        }
        this.lastUpdatedAt = event.releasedAt();
        this.version++;
        return this;
    }

    public WalletState applyUtxoConfirmationUpdated(WalletEvent.UtxoConfirmationUpdatedEvent event) {
        for (Map.Entry<String, BitcoinUtxo> entry : this.utxos.entrySet()) {
            BitcoinUtxo utxo = entry.getValue();
            if (utxo.txid().equals(event.txid())) {
                boolean wasUnconfirmed = utxo.confirmations() == null || utxo.confirmations() == 0;
                boolean nowConfirmed = event.confirmations() > 0;
                BitcoinUtxo updated = utxo.updateConfirmations(event.confirmations());
                this.utxos.put(entry.getKey(), updated);

                if (wasUnconfirmed && nowConfirmed && utxo.status() != UtxoStatus.RESERVED) {
                    this.unconfirmedBalanceSats -= utxo.valueSats();
                    this.confirmedBalanceSats += utxo.valueSats();
                }
            }
        }
        this.lastUpdatedAt = event.updatedAt();
        this.version++;
        return this;
    }

    public WalletState applyTransactionRecorded(WalletEvent.TransactionRecordedEvent event) {
        this.transactions.put(event.transaction().txid(), event.transaction());
        this.lastUpdatedAt = event.recordedAt();
        this.version++;
        return this;
    }

    public WalletState applyTransactionConfirmed(WalletEvent.TransactionConfirmedEvent event) {
        BitcoinTransaction tx = this.transactions.get(event.txid());
        if (tx != null) {
            BitcoinTransaction updated = new BitcoinTransaction(
                    tx.walletId(), tx.txid(), tx.rawHex(),
                    TransactionStatus.CONFIRMED, tx.direction(),
                    event.blockHeight(), event.confirmations(),
                    tx.inputValueSats(), tx.outputValueSats(), tx.feeSats(), tx.netAmountSats(),
                    tx.sendingAddresses(), tx.receivingAddresses(),
                    tx.createdAt(), event.confirmedAt(), tx.memo(),
                    tx.lockTime(), tx.version()
            );
            this.transactions.put(event.txid(), updated);
        }
        this.lastUpdatedAt = event.confirmedAt();
        this.version++;
        return this;
    }

    // --- Getters and Setters ---

    public String getWalletId() { return walletId; }
    public void setWalletId(String walletId) { this.walletId = walletId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRootAddress() { return rootAddress; }
    public void setRootAddress(String rootAddress) { this.rootAddress = rootAddress; }

    public boolean isCreated() { return created; }
    public void setCreated(boolean created) { this.created = created; }

    public NetworkType getNetworkType() { return networkType; }
    public void setNetworkType(NetworkType networkType) { this.networkType = networkType; }

    public WalletType getWalletType() { return walletType; }
    public void setWalletType(WalletType walletType) { this.walletType = walletType; }

    public Map<String, BitcoinUtxo> getUtxos() { return utxos; }
    public void setUtxos(Map<String, BitcoinUtxo> utxos) { this.utxos = utxos; }

    public Map<String, AddressMetadata> getAddresses() { return addresses; }
    public void setAddresses(Map<String, AddressMetadata> addresses) { this.addresses = addresses; }

    public Map<String, BitcoinTransaction> getTransactions() { return transactions; }
    public void setTransactions(Map<String, BitcoinTransaction> transactions) { this.transactions = transactions; }

    public int getNextDerivationIndex() { return nextDerivationIndex; }
    public void setNextDerivationIndex(int nextDerivationIndex) { this.nextDerivationIndex = nextDerivationIndex; }

    public long getConfirmedBalanceSats() { return confirmedBalanceSats; }
    public void setConfirmedBalanceSats(long confirmedBalanceSats) { this.confirmedBalanceSats = confirmedBalanceSats; }

    public long getUnconfirmedBalanceSats() { return unconfirmedBalanceSats; }
    public void setUnconfirmedBalanceSats(long unconfirmedBalanceSats) { this.unconfirmedBalanceSats = unconfirmedBalanceSats; }

    public long getReservedBalanceSats() { return reservedBalanceSats; }
    public void setReservedBalanceSats(long reservedBalanceSats) { this.reservedBalanceSats = reservedBalanceSats; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }
}
