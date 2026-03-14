-- Fix projection_management table: add projection_key column required by Pekko projection JDBC
ALTER TABLE projection_management DROP CONSTRAINT IF EXISTS projection_management_pkey;
ALTER TABLE projection_management ADD COLUMN IF NOT EXISTS projection_key VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE projection_management ADD PRIMARY KEY (projection_name, projection_key);

CREATE TABLE IF NOT EXISTS wallet_summary (
    wallet_id              VARCHAR(255) NOT NULL PRIMARY KEY,
    name                   VARCHAR(255) NOT NULL,
    root_address           VARCHAR(255),
    wallet_type            VARCHAR(50)  NOT NULL,
    network_type           VARCHAR(50)  NOT NULL,
    confirmed_balance_sats BIGINT       NOT NULL DEFAULT 0,
    unconfirmed_balance_sats BIGINT     NOT NULL DEFAULT 0,
    reserved_balance_sats  BIGINT       NOT NULL DEFAULT 0,
    address_count          INT          NOT NULL DEFAULT 0,
    utxo_count             INT          NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    metadata               JSONB        NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS wallet_address (
    wallet_id   VARCHAR(255) NOT NULL,
    address     VARCHAR(255) NOT NULL,
    script_type VARCHAR(50),
    derivation_path VARCHAR(255),
    derivation_index INT,
    is_change   BOOLEAN NOT NULL DEFAULT FALSE,
    label       VARCHAR(255),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (wallet_id, address)
);

CREATE TABLE IF NOT EXISTS wallet_utxo (
    wallet_id       VARCHAR(255) NOT NULL,
    txid            VARCHAR(255) NOT NULL,
    vout            INT          NOT NULL,
    value_sats      BIGINT       NOT NULL,
    address         VARCHAR(255),
    status          VARCHAR(50)  NOT NULL,
    block_height    INT,
    confirmations   INT,
    reserved_by_tx_id VARCHAR(255),
    reservation_expires_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (wallet_id, txid, vout)
);

CREATE INDEX IF NOT EXISTS idx_wallet_utxo_status ON wallet_utxo (wallet_id, status);

CREATE TABLE IF NOT EXISTS wallet_transaction (
    wallet_id       VARCHAR(255) NOT NULL,
    txid            VARCHAR(255) NOT NULL,
    status          VARCHAR(50)  NOT NULL,
    direction       VARCHAR(50),
    block_height    INT,
    confirmations   INT,
    input_value_sats  BIGINT NOT NULL DEFAULT 0,
    output_value_sats BIGINT NOT NULL DEFAULT 0,
    fee_sats        BIGINT NOT NULL DEFAULT 0,
    net_amount_sats BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (wallet_id, txid)
);

CREATE INDEX IF NOT EXISTS idx_wallet_transaction_created ON wallet_transaction (wallet_id, created_at DESC);

CREATE TABLE IF NOT EXISTS transaction_address_link (
    wallet_id   VARCHAR(255) NOT NULL,
    txid        VARCHAR(255) NOT NULL,
    address     VARCHAR(255) NOT NULL,
    link_type   VARCHAR(50)  NOT NULL,
    PRIMARY KEY (wallet_id, txid, address, link_type)
);
