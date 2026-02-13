CREATE TABLE changeset_events (
    event_id         BIGSERIAL PRIMARY KEY,
    changeset_id     UUID NOT NULL,
    sequence_num     BIGINT NOT NULL,
    entry_point      TEXT NOT NULL,
    fact_type        TEXT NOT NULL,
    event_timestamp  TIMESTAMPTZ NOT NULL,
    payload          JSONB NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_changeset_events_event_timestamp ON changeset_events(event_timestamp);
CREATE INDEX idx_changeset_events_sequence_num ON changeset_events(sequence_num);
CREATE INDEX idx_changeset_events_entry_point ON changeset_events(entry_point);
CREATE INDEX idx_changeset_events_changeset_id ON changeset_events(changeset_id);
