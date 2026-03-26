ALTER TABLE wallet_summary ADD COLUMN target_lifecycle_steps INT NOT NULL DEFAULT 5;
ALTER TABLE wallet_summary ADD COLUMN low_utxo_threshold INT NOT NULL DEFAULT 2;
ALTER TABLE wallet_summary ADD COLUMN auto_provision_enabled BOOLEAN NOT NULL DEFAULT TRUE;
