package org.twostack.libspiffy4j.aggregate.wallet;

import org.twostack.libspiffy4j.model.*;
import org.twostack.libspiffy4j.serialization.SpiffyEvent;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class WalletState implements SpiffyEvent {

    public record UtxoEntry(UtxoStatus status, long valueSats, Instant reservationExpiresAt, String txid) {}

    private String walletId;
    private String name;
    private String rootAddress;
    private boolean created;
    private NetworkType networkType;
    private WalletType walletType;
    private Map<String, UtxoEntry> utxoEntries = new HashMap<>();
    private Map<String, Integer> addressDerivationIndices = new HashMap<>();
    private Set<String> knownTxids = new HashSet<>();
    private int nextDerivationIndex;
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
        this.addressDerivationIndices.put(event.addressMetadata().address(), event.derivationIndex());
        if (event.derivationIndex() >= this.nextDerivationIndex) {
            this.nextDerivationIndex = event.derivationIndex() + 1;
        }
        this.lastUpdatedAt = event.recordedAt();
        this.version++;
        return this;
    }

    public WalletState applyUtxoReceived(WalletEvent.UtxoReceivedEvent event) {
        BitcoinUtxo utxo = event.utxo();
        this.utxoEntries.put(utxo.key(), new UtxoEntry(utxo.status(), utxo.valueSats(), null, utxo.txid()));
        this.lastUpdatedAt = event.receivedAt();
        this.version++;
        return this;
    }

    public WalletState applyUtxoSpent(WalletEvent.UtxoSpentEvent event) {
        UtxoEntry entry = this.utxoEntries.get(event.utxoKey());
        if (entry != null) {
            this.utxoEntries.put(event.utxoKey(),
                    new UtxoEntry(UtxoStatus.SPENT, entry.valueSats(), null, entry.txid()));
        }
        this.lastUpdatedAt = event.spentAt();
        this.version++;
        return this;
    }

    public WalletState applyUtxoReserved(WalletEvent.UtxoReservedEvent event) {
        UtxoEntry entry = this.utxoEntries.get(event.utxoKey());
        if (entry != null) {
            this.utxoEntries.put(event.utxoKey(),
                    new UtxoEntry(UtxoStatus.RESERVED, entry.valueSats(), event.expiresAt(), entry.txid()));
        }
        this.lastUpdatedAt = event.reservedAt();
        this.version++;
        return this;
    }

    public WalletState applyUtxoReleased(WalletEvent.UtxoReleasedEvent event) {
        UtxoEntry entry = this.utxoEntries.get(event.utxoKey());
        if (entry != null) {
            this.utxoEntries.put(event.utxoKey(),
                    new UtxoEntry(UtxoStatus.AVAILABLE, entry.valueSats(), null, entry.txid()));
        }
        this.lastUpdatedAt = event.releasedAt();
        this.version++;
        return this;
    }

    public WalletState applyUtxoConfirmationUpdated(WalletEvent.UtxoConfirmationUpdatedEvent event) {
        // Minimal state: no confirmation tracking needed for command validation
        this.lastUpdatedAt = event.updatedAt();
        this.version++;
        return this;
    }

    public WalletState applyTransactionRecorded(WalletEvent.TransactionRecordedEvent event) {
        this.knownTxids.add(event.transaction().txid());
        this.lastUpdatedAt = event.recordedAt();
        this.version++;
        return this;
    }

    public WalletState applyTransactionConfirmed(WalletEvent.TransactionConfirmedEvent event) {
        // Minimal state: transaction confirmation details live in read model
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

    public Map<String, UtxoEntry> getUtxoEntries() { return utxoEntries; }
    public void setUtxoEntries(Map<String, UtxoEntry> utxoEntries) { this.utxoEntries = utxoEntries; }

    public Map<String, Integer> getAddressDerivationIndices() { return addressDerivationIndices; }
    public void setAddressDerivationIndices(Map<String, Integer> addressDerivationIndices) { this.addressDerivationIndices = addressDerivationIndices; }

    public Set<String> getKnownAddresses() { return addressDerivationIndices.keySet(); }

    public Set<String> getKnownTxids() { return knownTxids; }
    public void setKnownTxids(Set<String> knownTxids) { this.knownTxids = knownTxids; }

    public int getNextDerivationIndex() { return nextDerivationIndex; }
    public void setNextDerivationIndex(int nextDerivationIndex) { this.nextDerivationIndex = nextDerivationIndex; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }
}
