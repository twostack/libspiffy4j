CREATE TABLE IF NOT EXISTS secure_storage (
    wallet_id    VARCHAR(255) NOT NULL,
    key_type     VARCHAR(50)  NOT NULL DEFAULT 'MASTER_HD_KEY',
    encrypted_key BYTEA       NOT NULL,
    nonce        BYTEA        NOT NULL,
    key_version  INT          NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (wallet_id, key_type)
);
