# Operations Runbook

## M2 Recovery and Checkpoint Diagnostics

This runbook documents operator actions for checkpoint/recovery behavior delivered in M2 closeout.

### First Checks

1. Check health: `GET /q/health`.
2. Check latest checkpoint: `GET /api/v1/checkpoints/latest`.
3. Inspect startup logs for `checkpoint.lifecycle` and `recovery.lifecycle` events.

Expected lifecycle signatures:

- `checkpoint.lifecycle event=checkpoint.create.start`
- `checkpoint.lifecycle event=checkpoint.create.success`
- `recovery.lifecycle event=recovery.start`
- `recovery.lifecycle event=recovery.success`

Stable recovery fields:

- `checkpointId`
- `checkpointSequence`
- `replayedChangesets`
- `convergenceRules`
- `durationMs`

### Failure Matrix

| Scenario | Log/Error Signature | Expected Behavior | Operator Action |
|---|---|---|---|
| Lock timeout during checkpoint creation | `CHECKPOINT_ERROR`, `details.operation=checkpoint_create`, `details.phase=acquire_lock` | Request fails fast, no checkpoint row written | Retry during lower write load; verify no long-running changeset lock holders |
| Lock timeout during recovery bootstrap | Startup error with `phase=acquire_lock` and `operation=recovery_bootstrap` | Startup fails fast; engine does not boot partially | Restart process after contention clears; confirm no concurrent bootstrap attempt |
| Corrupted checkpoint payload (`fact_blob` / `fact_registry`) | `CHECKPOINT_ERROR` containing `Failed to deserialize checkpoint payload ...` and `phase=load_checkpoint` | Startup fails fast before accepting traffic | Identify checkpoint id from logs, inspect checkpoint row integrity, restore from previous valid checkpoint if needed |
| Snapshot contract mismatch (`fact_count` / registry mismatch) | `CHECKPOINT_ERROR` containing `fact_count mismatch` or registry mismatch and `phase=load_checkpoint` | Startup fails fast | Investigate checkpoint write pipeline and storage integrity; quarantine corrupted checkpoint row and restart |
| Replay tail deserialization/validation failure | `CHECKPOINT_ERROR` with `phase=replay_tail` | Startup fails; in-memory state is restored to checkpoint snapshot before abort | Inspect corrupted `changeset_log.payload` row by sequence from diagnostics; repair data or roll forward with corrected row |
| No checkpoint found on startup | `recovery.lifecycle event=recovery.skip_no_checkpoint` | Engine starts from empty session and continues live processing | No action required for fresh deployments; create manual checkpoint after baseline data load |

### Safe Restart Procedure

1. Verify database is reachable and migrations are current.
2. Confirm latest checkpoint is readable via `GET /api/v1/checkpoints/latest`.
3. Restart a single engine instance.
4. Verify startup logs show either `recovery.success` or `recovery.skip_no_checkpoint`.
5. Confirm health endpoint is `UP`.
6. Trigger a manual checkpoint with `POST /api/v1/checkpoints` after stabilization.

### Escalation Data Capture

Collect the following before escalation:

1. Full `checkpoint.lifecycle` and `recovery.lifecycle` log lines for the failing boot.
2. API error payload (`code`, `message`, `details`, `requestId`) if failure is from checkpoint endpoint.
3. `checkpointId` and `checkpointSequence` from diagnostics.
4. Affected `changeset_log.sequence_num` values for replay failures.
5. Timestamp of restart attempts and deployment version.

### M2 Closeout Checklist

- [x] Failure matrix and hardening checklist implemented.
- [x] Structured diagnostics validated in tests.
- [x] Checkpoint/recovery error contract hardened with stable detail fields.
- [x] Recovery failure-path tests added (corruption, mismatch, replay-tail failure, lock timeout).
- [x] Full QA gate required before merge (`./gradlew qa`).
