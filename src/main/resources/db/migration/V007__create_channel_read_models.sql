-- Payment channel read model
CREATE TABLE IF NOT EXISTS payment_channel (
    channel_id          VARCHAR(255) PRIMARY KEY,
    wallet_id           VARCHAR(255) NOT NULL,
    role                VARCHAR(20)  NOT NULL,
    state               VARCHAR(20)  NOT NULL,
    client_peer_id      VARCHAR(255),
    server_peer_id      VARCHAR(255),
    client_pub_key_hex  VARCHAR(255),
    server_pub_key_hex  VARCHAR(255),
    client_address_b58  VARCHAR(255),
    server_address_b58  VARCHAR(255),
    funding_amount_sats BIGINT       NOT NULL DEFAULT 0,
    lock_time_unix      BIGINT       NOT NULL DEFAULT 0,
    client_balance_sats BIGINT       NOT NULL DEFAULT 0,
    server_balance_sats BIGINT       NOT NULL DEFAULT 0,
    funding_tx_id       VARCHAR(255),
    funding_tx_hex      TEXT,
    funding_output_index INTEGER     NOT NULL DEFAULT 0,
    refund_tx_hex       TEXT,
    refund_client_sig_hex TEXT,
    refund_server_sig_hex TEXT,
    latest_sequence_number INTEGER   NOT NULL DEFAULT 0,
    latest_payment_tx_hex  TEXT,
    latest_payment_tx_id   VARCHAR(255),
    settlement_tx_id    VARCHAR(255),
    context             TEXT,
    error_message       TEXT,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at           TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payment_channel_wallet ON payment_channel (wallet_id);
CREATE INDEX IF NOT EXISTS idx_payment_channel_state  ON payment_channel (state);

-- Channel payment history (unbounded — read model only)
CREATE TABLE IF NOT EXISTS channel_payment_history (
    channel_id          VARCHAR(255) NOT NULL,
    sequence_number     INTEGER      NOT NULL,
    amount_sats         BIGINT       NOT NULL,
    client_balance_sats BIGINT       NOT NULL,
    server_balance_sats BIGINT       NOT NULL,
    payment_tx_hex      TEXT,
    payment_tx_id       VARCHAR(255),
    client_signature_hex TEXT,
    server_signature_hex TEXT,
    purpose             VARCHAR(255),
    invoice_id          VARCHAR(255),
    acknowledged        BOOLEAN      NOT NULL DEFAULT FALSE,
    recorded_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_at     TIMESTAMP,
    PRIMARY KEY (channel_id, sequence_number)
);

CREATE INDEX IF NOT EXISTS idx_channel_payment_channel ON channel_payment_history (channel_id);
