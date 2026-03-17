ALTER TABLE wallet_utxo
    ADD COLUMN IF NOT EXISTS script_pub_key TEXT;
