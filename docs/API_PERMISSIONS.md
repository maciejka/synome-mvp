# API Permission Matrix

## What

This matrix defines authorization requirements for all business endpoints.

## Why

Security filters require valid `X-Api-Key` for `/api/v1/**`, then enforce endpoint permission using `@RequiresPermission`.

## How

## Permission constants

- `CHANGESET_WRITE`: apply changesets.
- `FACT_READ`: read changesets/facts/events.
- `CHECKPOINT_ADMIN`: create/list/read checkpoints.
- `RULE_ADMIN`: validate/upload/activate/rollback/list rule versions.
- `PROVENANCE_READ`: provenance read/explain/search/impact.
- `OPS_STREAM_READ`: operational SSE streams.

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
- `GET /api/v1/provenance/facts/{factId}` -> `PROVENANCE_READ`
- `GET /api/v1/provenance/facts/{factId}/explain` -> `PROVENANCE_READ`
- `GET /api/v1/provenance/facts/{factId}/impact` -> `PROVENANCE_READ`
- `GET /api/v1/provenance/search` -> `PROVENANCE_READ`
- `POST /api/v1/rules/validate` -> `RULE_ADMIN`
- `POST /api/v1/rules/upload` -> `RULE_ADMIN`
- `POST /api/v1/rules/{versionId}/activate` -> `RULE_ADMIN`
- `POST /api/v1/rules/{versionId}/rollback` -> `RULE_ADMIN`
- `GET /api/v1/rules` -> `RULE_ADMIN`
- `GET /api/v1/rules/active` -> `RULE_ADMIN`
- `GET /api/v1/ops/stream/changesets` -> `OPS_STREAM_READ`
- `GET /api/v1/ops/stream/checkpoints` -> `OPS_STREAM_READ`
- `GET /api/v1/ops/stream/recovery` -> `OPS_STREAM_READ`

## Notes

- This matrix applies only to `/api/v1/**`.
- Health endpoints (`/q/health`, `/q/health/ready`) are outside this permission model.
- Authentication errors return `401`; permission errors return `403`.
