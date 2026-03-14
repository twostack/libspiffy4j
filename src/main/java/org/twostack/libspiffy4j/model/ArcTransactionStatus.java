package org.twostack.libspiffy4j.model;

/**
 * ARC network-level transaction statuses.
 * These map ARC's REST API status codes to a typed enum.
 */
public enum ArcTransactionStatus {
    UNKNOWN(0),
    QUEUED(1),
    RECEIVED(2),
    STORED(3),
    ANNOUNCED_TO_NETWORK(4),
    REQUESTED_BY_NETWORK(5),
    SENT_TO_NETWORK(6),
    ACCEPTED_BY_NETWORK(7),
    SEEN_ON_NETWORK(8),
    MINED(9),
    CONFIRMED(108),
    REJECTED(109),
    SEEN_IN_ORPHAN_MEMPOOL(110),
    DOUBLE_SPEND_ATTEMPTED(461);

    private final int code;

    ArcTransactionStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ArcTransactionStatus fromCode(int code) {
        for (ArcTransactionStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return UNKNOWN;
    }

    /**
     * Maps this ARC-level status to the wallet-level {@link TransactionStatus}.
     */
    public TransactionStatus toTransactionStatus() {
        return switch (this) {
            case QUEUED, RECEIVED, STORED, ANNOUNCED_TO_NETWORK,
                 REQUESTED_BY_NETWORK, SENT_TO_NETWORK -> TransactionStatus.BROADCAST;
            case ACCEPTED_BY_NETWORK, SEEN_ON_NETWORK -> TransactionStatus.PENDING;
            case MINED, CONFIRMED -> TransactionStatus.CONFIRMED;
            case REJECTED, DOUBLE_SPEND_ATTEMPTED -> TransactionStatus.FAILED;
            case SEEN_IN_ORPHAN_MEMPOOL -> TransactionStatus.PENDING;
            case UNKNOWN -> TransactionStatus.CREATED;
        };
    }
}
