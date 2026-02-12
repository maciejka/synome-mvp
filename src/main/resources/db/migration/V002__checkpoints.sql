CREATE TABLE checkpoints (
    checkpoint_id     UUID PRIMARY KEY,
    sequence_num      BIGINT NOT NULL,
    rule_version_id   UUID NOT NULL,
    clock_millis      BIGINT NOT NULL,
    fact_count        INTEGER NOT NULL,
    blob_format       TEXT NOT NULL DEFAULT 'msgpack+lz4',
    fact_blob         BYTEA NOT NULL,
    fact_registry     BYTEA NOT NULL,
    engine_metadata   JSONB NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    size_bytes        BIGINT NOT NULL
);

CREATE INDEX idx_checkpoint_seq ON checkpoints(sequence_num DESC);
