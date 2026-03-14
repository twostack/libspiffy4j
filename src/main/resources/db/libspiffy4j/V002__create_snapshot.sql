CREATE TABLE IF NOT EXISTS snapshot (
    persistence_id      VARCHAR(255) NOT NULL,
    sequence_number     BIGINT       NOT NULL,
    created             BIGINT       NOT NULL,
    snapshot_ser_id     INTEGER      NOT NULL,
    snapshot_ser_manifest VARCHAR(255) NOT NULL,
    snapshot_payload    BYTEA        NOT NULL,
    meta_ser_id         INTEGER,
    meta_ser_manifest   VARCHAR(255),
    meta_payload        BYTEA,
    PRIMARY KEY (persistence_id, sequence_number)
);
