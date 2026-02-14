# API Permission Matrix

All business endpoints under `/api/v1/**` require `X-Api-Key`.

## Permission constants

- `CHANGESET_WRITE` - changeset write operations.
- `FACT_READ` - fact, event, and changeset read/query operations.
- `CHECKPOINT_ADMIN` - checkpoint lifecycle operations.
- `RULE_ADMIN` - rule validation, upload, activation, rollback, and list operations.
- `PROVENANCE_READ` - provenance query and explainability endpoints.
- `OPS_STREAM_READ` - server-sent-event operations streams.

## Endpoint mapping

- `POST /api/v1/changesets` -> `CHANGESET_WRITE`
- `GET /api/v1/changesets` -> `FACT_READ`
- `GET /api/v1/changesets/{id}` -> `FACT_READ`
- `GET /api/v1/facts` -> `FACT_READ`
- `GET /api/v1/facts/{factKey}` -> `FACT_READ`
- `GET /api/v1/facts/types` -> `FACT_READ`
- `GET /api/v1/facts/stats` -> `FACT_READ`
- `GET /api/v1/facts/derived` -> `FACT_READ`
- `GET /api/v1/events` -> `FACT_READ`
- `GET /api/v1/events/{changesetId}` -> `FACT_READ`
- `POST /api/v1/checkpoints` -> `CHECKPOINT_ADMIN`
- `GET /api/v1/checkpoints` -> `CHECKPOINT_ADMIN`
- `GET /api/v1/checkpoints/latest` -> `CHECKPOINT_ADMIN`
- `GET /api/v1/checkpoints/{id}` -> `CHECKPOINT_ADMIN`
- `GET /api/v1/provenance/**` -> `PROVENANCE_READ`
- `POST /api/v1/rules/validate` -> `RULE_ADMIN`
- `POST /api/v1/rules/upload` -> `RULE_ADMIN`
- `POST /api/v1/rules/{versionId}/activate` -> `RULE_ADMIN`
- `POST /api/v1/rules/{versionId}/rollback` -> `RULE_ADMIN`
- `GET /api/v1/rules` -> `RULE_ADMIN`
- `GET /api/v1/rules/active` -> `RULE_ADMIN`
- `GET /api/v1/ops/stream/changesets` -> `OPS_STREAM_READ`
- `GET /api/v1/ops/stream/checkpoints` -> `OPS_STREAM_READ`
- `GET /api/v1/ops/stream/recovery` -> `OPS_STREAM_READ`
