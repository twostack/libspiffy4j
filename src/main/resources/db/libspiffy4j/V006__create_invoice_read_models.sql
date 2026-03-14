-- Invoice read model tables

CREATE TABLE IF NOT EXISTS invoice_summary (
    invoice_id          VARCHAR(255) NOT NULL PRIMARY KEY,
    wallet_id           VARCHAR(255) NOT NULL,
    addresses           JSONB        NOT NULL DEFAULT '[]',
    amount_sats         BIGINT       NOT NULL,
    description         TEXT,
    status              VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMP,
    paid_at             TIMESTAMP,
    cancelled_at        TIMESTAMP,
    cancel_reason       TEXT,
    payment_txid        VARCHAR(255),
    amount_received_sats BIGINT,
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    metadata            JSONB        NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_invoice_summary_wallet_id ON invoice_summary (wallet_id);
CREATE INDEX idx_invoice_summary_status ON invoice_summary (status);
CREATE INDEX idx_invoice_summary_pending_expires ON invoice_summary (status, expires_at)
    WHERE status = 'PENDING';

CREATE TABLE IF NOT EXISTS invoice_output (
    invoice_id          VARCHAR(255) NOT NULL,
    output_index        INT          NOT NULL,
    output_type         VARCHAR(50)  NOT NULL,
    address             VARCHAR(255),
    amount_sats         BIGINT,
    label               VARCHAR(255),
    spec_json           JSONB        NOT NULL,
    PRIMARY KEY (invoice_id, output_index),
    FOREIGN KEY (invoice_id) REFERENCES invoice_summary (invoice_id)
);
