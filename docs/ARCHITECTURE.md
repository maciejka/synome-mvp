# Decision Engine Architecture (Target End-State)

## Document Intent

This document defines the target end-state architecture.

- It describes what the system must look like when all planned phases are complete.
- It does not claim everything here is implemented today.
- Current implementation status is tracked in `docs/PLANS.md`.

## 1. Goals and Constraints

### Product goals

- Long-lived stateful rules engine with deterministic behavior.
- Single write path through replayable changesets.
- CEP support (temporal operators, sliding windows, expiring events).
- Explainable derived facts with provenance graph traversal.
- Zero-downtime rule upgrades.
- REST-first access for internal and external consumers.

### Hard constraints

- Working memory target: 100K to 1M base facts.
- Single writer ordering for changesets.
- PostgreSQL as system of record for log/checkpoints/provenance/rules/auth.
- Deterministic recovery from checkpoint + replay.

## 2. Architecture Overview

### Runtime shape

- One engine instance hosts one logical `KieSession` (single tenant for MVP).
- APIs fan in to changeset processor, query layer, provenance API, and operations API.
- Engine writes an ordered changeset log using reservation/finalization (with cancellation on failure) and periodic checkpoints.
- Provenance persistence is asynchronous and never blocks rule execution.

### Component boundaries

- `core/`: session lifecycle, clock control, rule compilation, hot swap control.
- `changeset/`: input contract, validation, idempotency, application, log/replay.
- `checkpoint/`: fact serialization and checkpoint persistence/recovery.
- `provenance/`: derivation graph, explanation, export, async persistence.
- `api/`: REST resources, DTOs, auth filters, error mapping.
- `config/`: typed config and infrastructure producers.

## 3. Data Model in Working Memory

Working memory is partitioned into three categories:

1. Base facts
- Inserted only by changesets.
- Addressable by stable `factKey`.
- Included in checkpoints.
- Transferred on rule hot swap.

2. Events
- Inserted by `EVENT/EMIT` entries through named entry points.
- Declared with `@role(event)` and temporal metadata.
- Not checkpointed.
- Replayed from log within replay window.

3. Derived facts
- Inserted only by rule RHS via `insertLogical()`.
- Auto-retracted by TMS when justifications disappear.
- Not checkpointed.
- Re-derived after recovery and hot swap.

## 4. Changeset Contract (Authoritative)

### Request envelope

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
        "tier": "PREMIUM"
      }
    },
    {
      "kind": "FACT",
      "action": "DELETE",
      "factKey": "customer:C-099"
    },
    {
      "kind": "EVENT",
      "action": "EMIT",
      "entryPoint": "transactions",
      "factType": "Transaction",
      "timestamp": "2026-02-12T14:30:00Z",
      "data": {
        "txId": "TX-001",
        "customerId": "C-001",
        "amount": 15000.0
      }
    }
  ],
  "metadata": {
    "source": "crm-service",
    "correlationId": "req-abc-123"
  }
}
```

### Entry rules

- `FACT/UPSERT`: requires `factKey`, `factType`, `data`.
- `FACT/DELETE`: requires `factKey`; `factType` optional but validated if present.
- `EVENT/EMIT`: requires `entryPoint`, `factType`, `timestamp` (ISO-8601 UTC), `data`.
- `kind/action` combinations outside contract are rejected with validation error.

### Strict idempotency semantics

`changeset_id` is the idempotency key.

- If `changeset_id` is unseen: apply once, persist result, return created response.
- If `changeset_id` exists and payload checksum matches: do not reapply; return original stored result.
- If `changeset_id` exists and checksum differs: return `409 CONFLICT` with deterministic error code.

Implication: network retries are safe and side effects execute at most once.

### Atomicity semantics

A changeset is all-or-nothing.

- Validation runs before mutation.
- Reserve a `changeset_log` row before applying (`changeset_id`, payload, checksum).
- Snapshot current base-fact state from the fact registry/session.
- Apply entries and fire rules.
- Finalize the reserved row with success metadata and replay payload.
- If apply or finalization fails: rebuild session from the pre-apply snapshot, cancel the reserved row, and rethrow the original failure.
- Success is acknowledged only after row finalization; failures return explicit error with no partially committed engine state.

## 5. Session and Concurrency Model

- Exactly one writer applies changesets at a time (`SessionLock`).
- Rule execution model is `STREAM` + `EQUALITY`.
- Pseudo clock is used for replay/recovery; production mode uses real-time progression.
- Long-lived session runs continuously; write operations integrate with running agenda safely.

## 6. Persistence Model

### PostgreSQL tables

- `changeset_log`: ordered source of truth for finalized (applied) changesets, with reservation records for in-flight/failed attempts.
- `changeset_events`: projected event index for replay-window recovery and diagnostics.
- `checkpoints`: serialized base-fact snapshots and engine metadata.
- `fact_provenance`, `fact_modifications`: provenance records and history.
- `rule_versions`: uploaded and active/inactive DRL versions.
- `api_keys`: key hashes and permissions.

### Changeset log requirements

- Unique `changeset_id`.
- Support reservation -> finalization/cancellation state transitions.
- Persist request payload checksum.
- Persist reservation data before in-memory apply begins.
- Persist response payload required for strict idempotent replay.
- Persist timing and rule-fire metadata.
- Replay reads only finalized rows; canceled reservations are excluded from replay.

## 7. Recovery and Checkpointing

### Checkpoint contents

Included:
- Base facts.
- Fact registry map (`factKey -> handle metadata`).
- Clock state.
- Active rule version metadata.

Excluded:
- Events.
- Derived facts.

### Recovery sequence

1. Run migrations.
2. Load active rule version and compile `KieBase`.
3. Create session and wire listeners.
4. Restore latest checkpoint.
5. Compute replay window and replay required changesets/events in order.
6. Fire rules for convergence.
7. Enter running mode and accept traffic.

Target invariant: recovered state equals pre-shutdown logical state.

## 8. CEP and Replay Window

- Events are inserted via named entry points.
- Replay window is derived from max of event expiration and temporal windows.
- During replay, pseudo clock advances to event timestamps.
- After replay, engine transitions to real-time mode.
- Replay controls are config-driven:
  - `engine.event-replay-enabled`
  - `engine.event-replay-window`
  - `engine.event-entrypoints`

## 9. Provenance and Explainability

- Track rule firing context and object lifecycle.
- Maintain derivation DAG from derived fact to contributing base facts/events.
- Persist provenance asynchronously in bounded batches.
- Explanation endpoint renders human-readable multi-step reasoning.

## 10. Rule Hot Swap

Target procedure:

1. Compile and validate candidate DRL.
2. Acquire write lock.
3. Create pre-swap checkpoint.
4. Build new session.
5. Transfer base facts.
6. Replay in-window events.
7. Rewire listeners.
8. Fire for convergence.
9. Atomically swap active session.
10. Persist rule version activation and release lock.

Rollback source of truth is pre-swap checkpoint.

## 11. API Surface (Target)

### Core

- `POST /api/v1/changesets`
- `GET /api/v1/changesets`
- `GET /api/v1/changesets/{id}`
- `GET /api/v1/facts`
- `GET /api/v1/facts/{factKey}`
- `GET /api/v1/facts/types`
- `GET /api/v1/facts/stats`
- `GET /api/v1/events`
- `GET /api/v1/events/{changesetId}`

### Advanced

- Derived facts, events, named queries.
- Provenance tree/explain/graph/impact endpoints.
- Rule validation/upload/rollback endpoints.
- Checkpoint control endpoints.
- SSE streams for dashboard consumers.
- Health/readiness endpoints.

### Auth

All business endpoints require `X-Api-Key`.

## 12. Error Model (Target)

Standard error envelope:

```json
{
  "code": "DUPLICATE_CHANGESET_PAYLOAD_MISMATCH",
  "message": "changeset_id already exists with different payload",
  "details": {
    "changesetId": "550e8400-e29b-41d4-a716-446655440000"
  },
  "timestamp": "2026-02-13T12:00:00Z",
  "requestId": "req-123"
}
```

Error codes are stable API contract.

## 13. Testing Strategy (Target)

- Unit tests for validation, coercion, idempotency logic, and listeners.
- Integration tests against real PostgreSQL via Testcontainers.
- Lifecycle tests: checkpoint, crash recovery, replay determinism.
- CEP tests: expiration/window behavior and replay window edges.
- Hot swap tests: compatibility failures, success path, rollback path.
- Contract tests: duplicate retry semantics, reservation/finalization failure behavior, and error schema.

## 14. Key Invariants

1. Changesets are the only write path.
2. Rule RHS uses `insertLogical()` for derived facts.
3. `changeset_id` provides strict idempotency.
4. Changeset apply is atomic via reservation/finalization plus compensating rollback.
5. Checkpoint + replay is deterministic.
6. Events are replayed, not checkpointed.
7. Hot swap preserves base facts and re-derives conclusions.
8. Provenance persistence never blocks rule execution.
9. Single writer ordering is enforced.
