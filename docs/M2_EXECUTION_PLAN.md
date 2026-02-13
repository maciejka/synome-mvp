# M2 Execution Plan: Checkpoints and Recovery

## 1. Objective

Deliver deterministic engine restart by restoring the latest persisted checkpoint and replaying only the required finalized changesets.

## 2. Scope for M2

In scope:
- Checkpoint serialization/deserialization for base facts and fact registry.
- Checkpoint persistence and retrieval.
- Startup recovery orchestration (restore + replay + converge).
- Manual and scheduled checkpoint creation.
- Checkpoint REST endpoints.
- Determinism and crash-recovery test coverage.

Out of scope:
- CEP replay-window trimming (M3).
- Provenance replay/export (M4).
- Rule hot-swap orchestration (M5).

## 3. Current Baseline (2026-02-13)

- `checkpoints` table exists via `src/main/resources/db/migration/V002__checkpoints.sql`.
- `ChangesetProcessor` and `ChangesetLog` already enforce idempotency and atomic write semantics.
- Engine startup currently compiles DRL and creates a fresh `KieSession`; no checkpoint recovery path exists.
- No checkpoint API, scheduler, serializer, or recovery integration tests exist yet.

## 4. Definition of Done

1. Recovery path is automatic on startup: latest checkpoint restore, then ordered replay of `changeset_log` rows where `sequence_num > checkpoint.sequence_num`.
2. Recovered state is equivalent to uninterrupted state for the same input stream.
3. Checkpoints contain only base facts + fact registry + engine metadata + clock state.
4. Replay path does not append duplicate log rows or break strict idempotency semantics.
5. API endpoints for checkpoint creation and inspection are present and covered by contract tests.
6. Crash/corruption scenarios fail explicitly and do not start in a silent partial state.

## 5. Design Decisions (Implement First)

1. Serialization format:
- Store blobs as MessagePack compressed with LZ4 (`blob_format = msgpack+lz4`).
- Include `schemaVersion` in `engine_metadata` to support future migrations.

2. Checkpoint boundary:
- Capture `max(sequence_num)` from `changeset_log` at checkpoint time as the replay boundary.

3. Replay semantics:
- Reapply entries from logged payloads in strict `sequence_num ASC`.
- Use a dedicated replay apply mode that bypasses duplicate preflight and logging writes.

4. Rule version reference:
- Resolve active rule version from `rule_versions`.
- If no active rule version exists, fail checkpoint creation with explicit error until bootstrap strategy is defined.

5. Startup failure policy:
- If checkpoint restore or replay fails, abort startup (no degraded mode in M2).

## 6. Work Breakdown Structure

### Phase 0: Contracts and Configuration

Deliverables:
- Checkpoint domain model and metadata contract.
- Config flags for scheduling/retention/recovery behavior.

Implementation tasks:
- Add checkpoint config in `src/main/java/com/sky/synome/config/EngineConfig.java`:
  - `checkpoint-enabled`
  - `checkpoint-interval`
  - `checkpoint-retain-count`
  - `recovery-enabled`
- Add matching defaults in `src/main/resources/application.properties`.
- Define checkpoint DTO/domain classes in `src/main/java/com/sky/synome/checkpoint/`.

Validation:
- Unit test for config mapping defaults and overrides.

### Phase 1: Serializer and Snapshot Model

Deliverables:
- Serializer abstraction and MessagePack+LZ4 implementation.
- Stable snapshot model for base facts/fact registry.

Implementation tasks:
- Add dependencies in `gradle/libs.versions.toml` and `build.gradle.kts`:
  - Jackson MessagePack module.
  - LZ4 Java library.
- Introduce:
  - `CheckpointSerializer` interface.
  - `MessagePackLz4CheckpointSerializer` implementation.
  - Snapshot records for facts/registry/metadata.
- Ensure deterministic serialization order (sort by `factKey` before encoding).

Validation:
- Round-trip unit tests:
  - empty snapshot
  - mixed fact types
  - large payload
  - corrupted blob handling

### Phase 2: Checkpoint Persistence Layer

Deliverables:
- Repository/service for create/read/list/latest.

Implementation tasks:
- Add `CheckpointStore` using jOOQ in `src/main/java/com/sky/synome/checkpoint/CheckpointStore.java`.
- Implement:
  - `create(...)`
  - `findLatest()`
  - `findById(UUID checkpointId)`
  - `list(limit, offset)`
  - retention pruning helper (keep N newest)
- Persist `size_bytes` from compressed blob length and metadata checksum.
- Add schema migration only if contract requires extra columns (e.g., checksum/indexes).

Validation:
- Repository integration tests against PostgreSQL.
- Verify index usage for latest lookup and sequence filtering.

### Phase 3: Replay Service and Recovery Apply Mode

Deliverables:
- Ordered replay service decoupled from live request processing.
- Replay-safe changeset apply path.

Implementation tasks:
- Add `ChangesetReplayService` under `src/main/java/com/sky/synome/changeset/`.
- Query `changeset_log` where `sequence_num > boundary` ordered ascending.
- Deserialize logged payload to `Changeset`.
- Refactor `ChangesetProcessor`:
  - extract core apply logic used by both live mode and replay mode.
  - replay mode must skip idempotency preflight and all `changeset_log` writes.
- Preserve event clock progression behavior during replay.

Validation:
- Unit tests for replay ordering and boundary correctness.
- Integration test proving replay does not create extra `changeset_log` rows.

### Phase 4: Startup Recovery Orchestrator

Deliverables:
- Recovery integrated into startup lifecycle.

Implementation tasks:
- Add `RecoveryOrchestrator` in `src/main/java/com/sky/synome/checkpoint/`.
- Wire startup flow in `src/main/java/com/sky/synome/core/EngineSession.java`:
  1. Compile/load rules and create session.
  2. Load latest checkpoint if present.
  3. Restore base facts + fact registry + clock.
  4. Replay required changesets in order.
  5. Fire rules for convergence.
  6. Log recovery summary and mark ready.
- Ensure session lock prevents concurrent writes during replay.

Validation:
- Integration tests:
  - no checkpoint path
  - checkpoint with zero replay
  - checkpoint with replay tail
  - corrupted checkpoint aborts startup

### Phase 5: Checkpoint Creation Triggers

Deliverables:
- Manual checkpoint command and optional periodic scheduler.

Implementation tasks:
- Add `CheckpointService#createCheckpoint(reason)` API.
- Add scheduler (if enabled) that calls service on fixed interval.
- Prune old checkpoints based on retain count.
- Emit structured logs for checkpoint start/success/failure.

Validation:
- Scheduler test with short interval in test profile.
- Retention test verifies pruning behavior and ordering.

### Phase 6: REST API

Deliverables:
- Checkpoint management endpoints and DTOs.

Implementation tasks:
- Add resource class `src/main/java/com/sky/synome/api/CheckpointResource.java`.
- Add DTOs in `src/main/java/com/sky/synome/api/dto/`:
  - `CreateCheckpointResponse`
  - `CheckpointSummaryResponse`
  - `CheckpointDetailResponse`
- Endpoints:
  - `POST /api/v1/checkpoints`
  - `GET /api/v1/checkpoints`
  - `GET /api/v1/checkpoints/latest`
  - `GET /api/v1/checkpoints/{id}`
- Extend `ErrorMapper` for checkpoint-specific failures.

Validation:
- API contract tests for success and failure paths.

### Phase 7: Determinism, Crash Recovery, and QA Gate

Deliverables:
- End-to-end proof that recovery is deterministic and safe.

Implementation tasks:
- Add lifecycle integration test suite:
  - apply N changesets
  - create checkpoint at sequence S
  - apply tail changesets S+1..N
  - restart/rehydrate and compare final state against uninterrupted baseline
- Add failure-injection tests:
  - serializer corruption
  - replay deserialization error
  - missing active rule version for checkpoint creation
- Compare:
  - fact registry keys/types/data
  - selected derived outputs
  - idempotency duplicate behavior post-restart
- Run full quality gate: `./gradlew qa`.

Validation:
- M2 cannot be marked done until all tests above are green in CI.

## 7. Concrete File Plan

Likely new files:
- `src/main/java/com/sky/synome/checkpoint/CheckpointService.java`
- `src/main/java/com/sky/synome/checkpoint/CheckpointStore.java`
- `src/main/java/com/sky/synome/checkpoint/CheckpointSerializer.java`
- `src/main/java/com/sky/synome/checkpoint/MessagePackLz4CheckpointSerializer.java`
- `src/main/java/com/sky/synome/checkpoint/RecoveryOrchestrator.java`
- `src/main/java/com/sky/synome/changeset/ChangesetReplayService.java`
- `src/main/java/com/sky/synome/api/CheckpointResource.java`
- `src/main/java/com/sky/synome/api/dto/*Checkpoint*.java`
- `src/test/java/com/sky/synome/checkpoint/*.java`
- `src/test/java/com/sky/synome/api/CheckpointApiContractTest.java`

Likely updated files:
- `src/main/java/com/sky/synome/core/EngineSession.java`
- `src/main/java/com/sky/synome/changeset/ChangesetProcessor.java`
- `src/main/java/com/sky/synome/config/EngineConfig.java`
- `src/main/resources/application.properties`
- `gradle/libs.versions.toml`
- `build.gradle.kts`
- `docs/PLANS.md`

## 8. Execution Order (Recommended PR Slices)

1. PR1: Config + checkpoint contracts + serializer + unit tests.
2. PR2: CheckpointStore + DB adjustments + repository tests.
3. PR3: Replay mode refactor in `ChangesetProcessor` + replay service tests.
4. PR4: Recovery orchestrator wired into startup + integration tests.
5. PR5: API endpoints + contract tests.
6. PR6: Scheduler/retention + deterministic lifecycle suite + docs/status update.

## 9. Risks and Mitigations

1. Risk: replay path diverges from live apply logic.
- Mitigation: single shared apply core with mode flags and dedicated parity tests.

2. Risk: non-deterministic serialization order breaks equivalence checks.
- Mitigation: canonical sort + stable schema version + round-trip golden tests.

3. Risk: startup time regression on long replay tails.
- Mitigation: checkpoint frequency config, replay metrics, and bounded retention.

4. Risk: `rule_version_id` dependency blocks checkpoint writes.
- Mitigation: explicitly fail with actionable error until bootstrap active-version policy is implemented.

## 10. Exit Checklist

- [ ] All Phase 0-7 deliverables merged.
- [ ] `./gradlew qa` green.
- [ ] `docs/PLANS.md` M2 switched to `DONE` only after automated coverage is in place.
- [ ] Operational runbook includes recovery failure diagnostics.
