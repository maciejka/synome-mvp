# Operations Runbook

## What

Operational guidance for the current prototype runtime:

- startup and readiness,
- checkpoint/recovery diagnostics,
- API auth and permission failures,
- SSE operations streams,
- controlled shutdown behavior.

## Why

The engine is stateful and long-lived. Fast diagnosis requires knowing expected lifecycle and log signatures.

## How

## 1. Startup and readiness checks

1. Verify process health:
- `GET /q/health`
- `GET /q/health/ready`

2. Verify readiness checks are `UP`:
- `datasource-ready`
- `engine-recovery-status`
- `engine-session-ready`
- `checkpoint-scheduler`

3. Verify lifecycle state progression in logs:
- Recovery start: `recovery.lifecycle event=recovery.start`
- Recovery success: `recovery.lifecycle event=recovery.success`
- Or no-checkpoint boot: `recovery.lifecycle event=recovery.skip_no_checkpoint`

4. Verify checkpoint scheduler signals (if enabled):
- `checkpoint.lifecycle event=checkpoint.create.success`

## 2. Security and access checks

Business endpoints require `X-Api-Key`.

Typical failure mapping:

- `401 API_KEY_REQUIRED`: header missing.
- `401 API_KEY_INVALID`: key hash not found.
- `401 API_KEY_INACTIVE`: key row inactive.
- `401 API_KEY_EXPIRED`: `expires_at` is in the past.
- `403 PERMISSION_DENIED`: key authenticated but missing required permission.

Quick probe (dev default key):

```bash
curl -H 'X-Api-Key: dev-local-api-key' http://localhost:8080/api/v1/facts
```

## 3. Checkpoint operations

Endpoints:

- `POST /api/v1/checkpoints`
- `GET /api/v1/checkpoints/latest`
- `GET /api/v1/checkpoints`
- `GET /api/v1/checkpoints/{id}`

Expected checkpoint lifecycle logs:

- `checkpoint.lifecycle event=checkpoint.create.start`
- `checkpoint.lifecycle event=checkpoint.create.success`
- `checkpoint.lifecycle event=checkpoint.create.failure`

Stable checkpoint error detail keys:

- `operation` (`checkpoint_create`)
- `phase` (`acquire_lock`, `snapshot`, `resolve_boundary`, `resolve_rule_version`, `persist`, `prune_retention`)
- `checkpointId`
- `checkpointSequence`
- `reason`

## 4. Recovery diagnostics

Recovery phases in failure details (`operation=recovery_bootstrap`):

- `acquire_lock`
- `cleanup_unfinalized`
- `load_checkpoint`
- `restore_snapshot`
- `replay_tail_facts`
- `replay_window_events`
- `converge`

Expected behavior:

- If no checkpoint: engine starts without restore and logs `recovery.skip_no_checkpoint`.
- If replay/convergence fails after restore: orchestrator attempts to restore checkpoint snapshot before failing startup.

## 5. Failure matrix

| Scenario | Signature | Expected behavior | Action |
|---|---|---|---|
| Checkpoint lock contention | `CHECKPOINT_ERROR`, `details.phase=acquire_lock` | Checkpoint call fails fast; runtime continues | Retry during lower write load |
| Recovery lock contention | `CHECKPOINT_ERROR`, `details.operation=recovery_bootstrap`, `phase=acquire_lock` | Startup fails; service not ready | Remove contention and restart |
| Corrupt checkpoint payload | `CHECKPOINT_ERROR` with deserialize/load message | Startup fails before accepting writes | Investigate checkpoint row; recover from previous valid state |
| Replay failure (FACT tail) | `CHECKPOINT_ERROR`, `phase=replay_tail_facts` | Startup fails | Inspect referenced `sequenceNum` payload in `changeset_log` |
| Replay failure (EVENT window) | `CHECKPOINT_ERROR`, `phase=replay_window_events` | Startup fails | Inspect `changeset_events` rows around failing event |
| Engine shutdown state write | `503 ENGINE_NOT_READY` on `POST /api/v1/changesets` | Writes blocked intentionally | Wait for engine to return `RUNNING` |

## 6. Operations streams (SSE)

Endpoints:

- `/api/v1/ops/stream/changesets`
- `/api/v1/ops/stream/checkpoints`
- `/api/v1/ops/stream/recovery`

Behavior:

- First event is `STREAM_OPENED`.
- Polling cadence is controlled by `engine.ops-stream-poll-interval`.
- Batch size per poll is capped by `engine.ops-stream-max-batch`.

Quick probe:

```bash
curl -N -H 'X-Api-Key: dev-local-api-key' \
  'http://localhost:8080/api/v1/ops/stream/recovery?maxEvents=1'
```

## 7. Controlled shutdown

On shutdown event:

1. Lifecycle moves to `SHUTTING_DOWN`.
2. Service attempts:
- final checkpoint (`reason=shutdown-final`),
- immediate provenance flush.
3. Finalization is bounded by `engine.shutdown-timeout`.
4. Lifecycle is marked `shutdown_complete` before process exit.

## 8. Escalation capture checklist

Capture before escalation:

1. Relevant `checkpoint.lifecycle` and `recovery.lifecycle` log lines.
2. API error envelope (`code`, `message`, `details`, `requestId`).
3. Lifecycle state (`STARTING`, `RECOVERING`, `RUNNING`, `SHUTTING_DOWN`).
4. Failing checkpoint id / sequence, or failing event/changeset sequence.
5. Effective config values for replay window, lock timeout, and scheduler toggles.
