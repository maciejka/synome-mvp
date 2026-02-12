CREATE TABLE changeset_log (
    sequence_num    BIGSERIAL PRIMARY KEY,
    changeset_id    UUID NOT NULL UNIQUE,
    payload         JSONB NOT NULL,
    checksum        TEXT NOT NULL,
    submitted_by    TEXT,
    applied_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    engine_clock_at BIGINT NOT NULL,
    rules_fired     INTEGER,
    duration_ms     INTEGER,
    error           TEXT
);

CREATE INDEX idx_changeset_applied ON changeset_log(applied_at);
CREATE INDEX idx_changeset_clock ON changeset_log(engine_clock_at);
