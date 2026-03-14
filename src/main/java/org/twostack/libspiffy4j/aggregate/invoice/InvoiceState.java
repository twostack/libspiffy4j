package org.twostack.libspiffy4j.aggregate.invoice;

import org.twostack.libspiffy4j.model.InvoiceOutputSpec;
import org.twostack.libspiffy4j.model.InvoiceStatus;
import org.twostack.libspiffy4j.serialization.SpiffyEvent;

import java.time.Instant;
import java.util.*;

public class InvoiceState implements SpiffyEvent {

    private String invoiceId;
    private boolean created;
    private String walletId;
    private List<String> addresses = List.of();
    private Set<String> addressSet = new HashSet<>();
    private long amountSats;
    private List<InvoiceOutputSpec> outputs = List.of();
    private String description;
    private InvoiceStatus status;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant paidAt;
    private String paymentTxid;
    private long amountReceivedSats;
    private Map<String, Object> metadata = new HashMap<>();
    private long version;
    private Instant lastUpdatedAt;

    public InvoiceState() {
    }

    // --- Event application methods ---

    public InvoiceState applyInvoiceCreated(InvoiceEvent.InvoiceCreatedEvent event) {
        this.invoiceId = event.invoiceId();
        this.walletId = event.walletId();
        this.addresses = event.addresses() != null ? List.copyOf(event.addresses()) : List.of();
        this.addressSet = new HashSet<>(this.addresses);
        this.amountSats = event.amountSats();
        this.outputs = event.outputs() != null ? List.copyOf(event.outputs()) : List.of();
        this.description = event.description();
        this.status = InvoiceStatus.PENDING;
        this.createdAt = event.createdAt();
        this.expiresAt = event.expiresAt();
        this.metadata = event.metadata() != null ? new HashMap<>(event.metadata()) : new HashMap<>();
        this.created = true;
        this.lastUpdatedAt = event.createdAt();
        this.version++;
        return this;
    }

    public InvoiceState applyInvoicePaid(InvoiceEvent.InvoicePaidEvent event) {
        this.status = InvoiceStatus.PAID;
        this.paymentTxid = event.paymentTxid();
        this.amountReceivedSats = event.amountReceivedSats();
        this.paidAt = event.paidAt();
        this.lastUpdatedAt = event.paidAt();
        this.version++;
        return this;
    }

    public InvoiceState applyInvoiceExpired(InvoiceEvent.InvoiceExpiredEvent event) {
        this.status = InvoiceStatus.EXPIRED;
        this.lastUpdatedAt = event.expiredAt();
        this.version++;
        return this;
    }

    public InvoiceState applyInvoiceCancelled(InvoiceEvent.InvoiceCancelledEvent event) {
        this.status = InvoiceStatus.CANCELLED;
        this.lastUpdatedAt = event.cancelledAt();
        this.version++;
        return this;
    }

    // --- Getters and Setters ---

    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }

    public boolean isCreated() { return created; }
    public void setCreated(boolean created) { this.created = created; }

    public String getWalletId() { return walletId; }
    public void setWalletId(String walletId) { this.walletId = walletId; }

    public List<String> getAddresses() { return addresses; }
    public void setAddresses(List<String> addresses) {
        this.addresses = addresses;
        this.addressSet = new HashSet<>(addresses);
    }

    public Set<String> getAddressSet() { return addressSet; }
    public void setAddressSet(Set<String> addressSet) { this.addressSet = addressSet; }

    public long getAmountSats() { return amountSats; }
    public void setAmountSats(long amountSats) { this.amountSats = amountSats; }

    public List<InvoiceOutputSpec> getOutputs() { return outputs; }
    public void setOutputs(List<InvoiceOutputSpec> outputs) { this.outputs = outputs; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public InvoiceStatus getStatus() { return status; }
    public void setStatus(InvoiceStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }

    public String getPaymentTxid() { return paymentTxid; }
    public void setPaymentTxid(String paymentTxid) { this.paymentTxid = paymentTxid; }

    public long getAmountReceivedSats() { return amountReceivedSats; }
    public void setAmountReceivedSats(long amountReceivedSats) { this.amountReceivedSats = amountReceivedSats; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }
}
