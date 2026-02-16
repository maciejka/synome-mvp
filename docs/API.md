# API Guide (Current Prototype)

## What

The service exposes REST + SSE endpoints under `/api/v1` for:

- changeset write/read,
- facts/events querying,
- checkpoint and recovery operations,
- provenance/explainability,
- rule version lifecycle,
- operational streams.

## Why

This API is the contract surface used to validate the prototype guarantees:

- idempotent writes,
- deterministic replay/recovery,
- explainable derived outcomes,
- safe runtime rule upgrades,
- secure multi-consumer access.

## How

## Authentication and authorization

- All `/api/v1/**` endpoints require `X-Api-Key`.
- Missing/invalid/inactive/expired keys return `401`.
- Missing permission returns `403` with `PERMISSION_DENIED`.
- Permission mapping: `docs/API_PERMISSIONS.md`.

Health endpoints under `/q/health` are not part of `/api/v1/**` and are not API-key gated.

## Error envelope

All business errors use:

```json
{
  "code": "ERROR_CODE",
  "message": "Human readable message",
  "details": {},
  "timestamp": "2026-02-16T12:00:00Z",
  "requestId": "uuid"
}
```

Common codes:

- `VALIDATION_ERROR`
- `DUPLICATE_CHANGESET_PAYLOAD_MISMATCH`
- `LOCK_TIMEOUT`
- `CHECKPOINT_ERROR`
- `CHECKPOINT_NOT_FOUND`
- `RULE_VALIDATION_ERROR`
- `RULE_VERSION_NOT_FOUND`
- `RULE_ACTIVATION_ERROR`
- `ENGINE_NOT_READY`
- `API_KEY_REQUIRED`
- `API_KEY_INVALID`
- `API_KEY_INACTIVE`
- `API_KEY_EXPIRED`
- `PERMISSION_DENIED`
- `INTERNAL_ERROR`

## Core write/read endpoints

### `POST /api/v1/changesets`

Applies one atomic changeset. Returns `201`.

Request:

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "entries": [
    {
      "kind": "FACT",
      "action": "UPSERT",
      "factKey": "customer:C-001",
      "factType": "Customer",
      "data": {
        "customerId": "C-001",
        "name": "Alice",
        "tier": "PREMIUM",
        "balance": 1200
      }
    },
    {
      "kind": "EVENT",
      "action": "EMIT",
      "entryPoint": "transactions",
      "factType": "Transaction",
      "timestamp": "2026-02-16T10:00:00Z",
      "data": {
        "txId": "TX-001",
        "customerId": "C-001",
        "amount": 2500
      }
    }
  ],
  "metadata": {
    "source": "demo-client"
  }
}
```

Response fields:

- `changesetId`, `sequenceNum`, `status`, `rulesFired`, `durationMs`
- `effects` (`factsInserted`, `factsUpdated`, `factsDeleted`, `eventsEmitted`, `derivedFactsCreated`, `derivedFactsRetracted`)
- `newDerivedFacts`

Idempotency behavior:

- same `id` + same payload => returns original stored response,
- same `id` + different payload => `409`.

### `GET /api/v1/changesets`

List persisted changesets.

Query params:

- `limit` (default `50`)
- `offset` (default `0`)

### `GET /api/v1/changesets/{id}`

Fetch one persisted changeset by UUID. Returns `404` if not found.

## Facts and events

### Facts

- `GET /api/v1/facts`
- `GET /api/v1/facts/{factKey}`
- `GET /api/v1/facts/types`
- `GET /api/v1/facts/stats`
- `GET /api/v1/facts/derived`

Notes:

- `/facts` reads base facts from the fact registry.
- `/facts/derived` reads derived-fact summaries from provenance search.

### Events

- `GET /api/v1/events`
- `GET /api/v1/events/{changesetId}`

Query params (both):

- `entryPoint`
- `from` (ISO-8601 instant)
- `to` (ISO-8601 instant)
- `limit` (default `50`, max `500`)
- `offset` (default `0`, min `0`)

## Checkpoints and recovery

- `POST /api/v1/checkpoints`
- `GET /api/v1/checkpoints`
- `GET /api/v1/checkpoints/latest`
- `GET /api/v1/checkpoints/{id}`

`POST` creates a manual checkpoint (`reason=manual` internally).

## Provenance and explainability

- `GET /api/v1/provenance/facts/{factId}`
- `GET /api/v1/provenance/facts/{factId}/explain`
- `GET /api/v1/provenance/facts/{factId}/impact`
- `GET /api/v1/provenance/search`

Useful params:

- `modLimit` for fact modification history,
- `maxDepth` for explain/impact traversals,
- `activeOnly`, `factType`, `factKey`, `ruleName` for search.

## Rule version lifecycle

- `POST /api/v1/rules/validate`
- `POST /api/v1/rules/upload`
- `POST /api/v1/rules/{versionId}/activate`
- `POST /api/v1/rules/{versionId}/rollback`
- `GET /api/v1/rules`
- `GET /api/v1/rules/active`

Validate/upload request supports either `drlFiles` map, inline `drl`, or both:

```json
{
  "versionLabel": "candidate-01",
  "uploadedBy": "dev-user",
  "drlFiles": {
    "com/sky/synome/rules/candidate.drl": "declare ..."
  }
}
```

Activation performs safe runtime swap with pre-swap checkpoint and rollback on failure.

## Operations SSE streams

- `GET /api/v1/ops/stream/changesets`
- `GET /api/v1/ops/stream/checkpoints`
- `GET /api/v1/ops/stream/recovery`

Query param:

- `maxEvents` (`0` means unbounded stream)

Each stream emits `STREAM_OPENED` first, then polled events.
