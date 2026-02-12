CREATE TABLE fact_provenance (
    fact_id            TEXT PRIMARY KEY,
    fact_type          TEXT NOT NULL,
    fact_key           TEXT,
    produced_by_rule   TEXT,
    insertion_type     TEXT NOT NULL,
    input_fact_ids     TEXT[] NOT NULL DEFAULT '{}',
    changeset_id       UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    retracted_at       TIMESTAMPTZ,
    retraction_reason  TEXT
);

CREATE INDEX idx_prov_type ON fact_provenance(fact_type);
CREATE INDEX idx_prov_rule ON fact_provenance(produced_by_rule)
    WHERE produced_by_rule IS NOT NULL;
CREATE INDEX idx_prov_key ON fact_provenance(fact_key)
    WHERE fact_key IS NOT NULL;

CREATE TABLE fact_modifications (
    id                  BIGSERIAL PRIMARY KEY,
    fact_id             TEXT NOT NULL REFERENCES fact_provenance(fact_id),
    rule_name           TEXT NOT NULL,
    modified_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    before_state        JSONB,
    after_state         JSONB,
    triggering_facts    TEXT[] NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_mod_fact ON fact_modifications(fact_id);
