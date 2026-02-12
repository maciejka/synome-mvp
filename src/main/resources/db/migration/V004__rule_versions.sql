CREATE TABLE rule_versions (
    version_id       UUID PRIMARY KEY,
    version_label    TEXT NOT NULL,
    drl_files        JSONB NOT NULL,
    checksum         TEXT NOT NULL,
    uploaded_by      TEXT,
    uploaded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at     TIMESTAMPTZ,
    deactivated_at   TIMESTAMPTZ,
    is_active        BOOLEAN NOT NULL DEFAULT false,
    compilation_log  TEXT
);

CREATE UNIQUE INDEX idx_rule_active
    ON rule_versions(is_active) WHERE is_active = true;
