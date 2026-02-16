# Decision Engine Architecture (Current Prototype)

## Document Intent

This document describes the architecture that is implemented now (not a future target state).

Snapshot date: 2026-02-16.

## What

### Runtime shape

- Single Quarkus service process.
- Single logical Drools `KieSession` (single-tenant prototype).
- Single writer for mutations enforced by `SessionLock`.
- PostgreSQL is the system of record.
- `KieSession` uses a pseudo clock.

### Core components

- `core/`: session lifecycle, fact materialization, lock, clock manager.
- `changeset/`: validation, idempotent log reservation/finalization, apply/replay.
- `checkpoint/`: checkpoint creation, retention, restore, startup recovery.
- `provenance/`: capture, in-memory graph, async persistence, explanation queries.
- `rules/`: validate/upload/list/activate/rollback, hot swap orchestration.
- `security/`: API key authentication and permission authorization.
- `ops/`: readiness model, SSE streams, controlled shutdown.
- `api/`: REST resources + error mapping.

## Why

This prototype architecture is optimized for deterministic behavior and safe iteration:

- Changesets are the only write path.
- All writes are idempotent by `changeset_id`.
- Write failures do not leave partial in-memory state.
- Recovery reproduces logical state from checkpoint + replay.
- Rule upgrades can happen without full service restart.
- Decision outputs remain inspectable through provenance APIs.

## How

### Data model in memory

1. Base facts
- Inserted/updated/deleted via `FACT` entries.
- Indexed by `factKey` in `FactRegistry`.
- Included in checkpoints.

2. Events
- Inserted via `EVENT/EMIT` to named entry points.
- Persisted in `changeset_events` for replay window recovery.
- Not included in checkpoints.

3. Derived facts
- Created by rules through `insertLogical()`.
- Not stored in checkpoints.
- Re-derived after replay/convergence.

### Changeset contract

- `FACT/UPSERT`: `factKey`, `factType`, `data` required.
- `FACT/DELETE`: `factKey` required; optional `factType` validated when provided.
- `EVENT/EMIT`: `entryPoint`, `factType`, `timestamp`, `data` required.
- `entryPoint` must be enabled by `engine.event-entrypoints`.
- `factType` must exist in package `com.sky.synome.rules`.

### Idempotency and atomicity

Idempotency key: `changeset.id`.

- First seen ID: reserve log row, apply, finalize with response payload.
- Same ID + same checksum: return stored response payload.
- Same ID + different checksum: `409` with code `DUPLICATE_CHANGESET_PAYLOAD_MISMATCH`.

Atomicity behavior:

- Before apply, snapshot base-fact runtime state.
- On failure after reservation, restore snapshot, cancel reservation row, remove projected events.
- Success is acknowledged only after `changeset_log` finalization.

### Persistence model

Primary tables:

- `changeset_log`: ordered changesets + checksum + response payload.
- `changeset_events`: projected `EVENT/EMIT` rows for replay/query.
- `checkpoints`: serialized base facts + registry + metadata.
- `fact_provenance`, `fact_modifications`: explainability history.
- `rule_versions`: versioned DRL sources and active state.
- `api_keys`: hashed keys + permissions + state.

### Recovery sequence

Startup path when `engine.recovery-enabled=true`:

1. Mark lifecycle `RECOVERING`.
2. Acquire writer lock.
3. Remove unfinalized `changeset_log` reservations.
4. Load latest checkpoint (if any).
5. Restore checkpoint base facts/clock.
6. Replay finalized FACT tail (`changeset_log` after checkpoint sequence).
7. Replay in-window events from `changeset_events`.
8. Fire rules for convergence.
9. Mark lifecycle `RUNNING`.

If no checkpoint exists, startup continues in empty-session mode.

### Event-time behavior

- Replay window: `checkpointClockMillis - engine.event-replay-window`.
- Event replay source: `changeset_events` filtered by window and configured entry points.
- Clock advancement is monotonic (`EngineClockManager` ignores backward moves).

### Rule hot swap behavior

Activation path:

1. Validate/compile candidate rules.
2. Acquire writer lock.
3. Create pre-swap checkpoint.
4. Create candidate session.
5. Transfer current base facts.
6. Replay in-window events.
7. Fire rules for convergence.
8. Swap runtime session/base/registry atomically.
9. Persist active version change.

On failure after swap, runtime is rolled back to previous session.

### Security and lifecycle controls

- All `/api/v1/**` endpoints require `X-Api-Key`.
- AuthN/AuthZ is enforced by request filters and `@RequiresPermission`.
- Engine writes are accepted only in lifecycle `RUNNING`.
- Controlled shutdown attempts final checkpoint + provenance flush within timeout.

## Current Invariants

1. Changesets are the only mutation path.
2. Exactly one writer mutates runtime state at a time.
3. `changeset_id` enforces strict idempotency.
4. Changeset apply is atomic from API perspective.
5. Checkpoint + replay is deterministic for the same persisted stream.
6. Event replay is bounded by configured replay window and entry points.
7. Provenance persistence is asynchronous and best-effort (non-blocking write path).
8. Business APIs are permission-gated by API keys.
