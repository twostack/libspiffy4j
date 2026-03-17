ALTER TABLE wallet_utxo
    ADD COLUMN IF NOT EXISTS plugin_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS plugin_metadata JSONB;

CREATE INDEX IF NOT EXISTS idx_wallet_utxo_plugin
    ON wallet_utxo (wallet_id, plugin_id)
    WHERE plugin_id IS NOT NULL;
