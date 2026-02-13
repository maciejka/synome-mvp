# M2 Closeout Execution Plan: Checkpoints and Recovery Hardening

## 1. Objective

Close the remaining M2 gaps so checkpoint/recovery can be promoted from `PARTIAL` to `DONE` in `docs/PLANS.md`.

This closeout plan is additive to `docs/exec-plans/completed/M2_EXECUTION_PLAN.md` and focuses on diagnostics hardening, failure-path clarity, and operational runbook quality.

## 2. Current Baseline (2026-02-13)

Implemented:

- Checkpoint create/read/list/latest flow (store + API + scheduler).
- Startup recovery orchestration with replay after checkpoint boundary.
- Replay mode that avoids duplicate `changeset_log` writes.
- Integration coverage for deterministic restart behavior and reservation cleanup.

Still missing for M2 completion:

- Tightened operational diagnostics and failure playbook artifacts.
- Recovery failure-path test coverage for operator-facing scenarios.
- Final QA closeout evidence tied to M2 exit criteria.

## 3. Scope

In scope:

- Structured logs with stable field keys for recovery diagnostics.
- Error contract hardening for checkpoint/recovery failures.
- Tests that assert key failure-path diagnostics and expected failure behavior.
- Docs updates for runbook-level troubleshooting.
- M2 completion checklist and closeout evidence.

Out of scope:

- Explicit observability metrics (deferred for now).
- CEP replay window semantics (covered by M3).
- Provenance DAG/persistence (covered by M4).
- Rule hot swap runtime (covered by M5).

## 4. Definition of Done

1. Recovery and checkpoint logs include stable diagnostic fields for checkpoint id, sequence, replay count, and duration.
2. Recovery failure paths produce actionable error messages with context (no silent partial boot).
3. Runbook documents common failure signatures and concrete operator actions.
4. `./gradlew qa` passes with no new QA regressions.
5. `docs/PLANS.md` M2 can be switched to `DONE` without caveats.

## 5. Design Decisions

1. Recovery failure policy:
- Keep current fail-fast startup semantics.
- Improve diagnostics and operator guidance, not startup behavior.

2. Log structure:
- Use stable keys in fixed order for machine parsing and incident diffing.
- Prefer one summary line per lifecycle phase (start, success, failure).

3. Error envelope stability:
- Keep API error codes stable.
- Add details fields only when deterministic and low-cardinality.

4. Metrics deferral:
- Do not gate M2 completion on metrics instrumentation.
- Capture metrics as optional follow-up scope after M2 closeout.

## 6. Work Breakdown Structure

### Phase 0: Failure Matrix and Closeout Checklist

Deliverables:

- M2 hardening checklist and failure matrix document.

Implementation tasks:

- Define expected behavior for scenarios:
- lock timeout during recovery/checkpoint
- checkpoint payload corruption
- registry/fact consistency mismatch
- replay deserialization failure
- no checkpoint found on startup
- Map each scenario to:
- expected log signature
- expected API/startup behavior
- operator action

Validation:

- Checklist reviewed and linked from runbook section.

### Phase 1: Structured Diagnostics Hardening

Deliverables:

- Stable log field keys for checkpoint/recovery lifecycle events.

Implementation tasks:

- Normalize recovery log messages and include:
- `checkpointId`
- `checkpointSequence`
- `replayedChangesets`
- `convergenceRules`
- `durationMs`
- Normalize checkpoint creation logs with consistent key/value ordering.
- Improve exception text for corrupted payload and lock-timeout paths.

Validation:

- Log assertion tests for key lifecycle messages (where practical).

### Phase 2: Error Contract Hardening

Deliverables:

- Error payloads remain stable and provide clear checkpoint/recovery diagnostics.

Implementation tasks:

- Review `ErrorMapper` mapping for checkpoint/recovery exceptions.
- Ensure `CHECKPOINT_ERROR` and `CHECKPOINT_NOT_FOUND` responses remain stable.
- Add deterministic detail fields only when they add operator value.

Validation:

- API contract tests for checkpoint failure shapes remain green.

### Phase 3: Failure-Path Test Hardening

Deliverables:

- Additional tests for critical recovery failure branches.

Implementation tasks:

- Add/extend integration tests for:
- corrupted checkpoint payload aborts startup
- mismatched checkpoint payload contract fails fast
- replay tail failure aborts startup and leaves no partial state
- checkpoint create lock-timeout failure behavior

Validation:

- New tests pass consistently and are non-flaky.

### Phase 4: Runbook and Operational Docs

Deliverables:

- Recovery/checkpoint runbook section with actionable diagnostics.

Implementation tasks:

- Add runbook content:
- first-check log lines and endpoints
- failure signature -> action mapping
- safe restart procedure
- data capture checklist for escalation
- Link runbook from `docs/PLANS.md` or execution-plan index.

Validation:

- Manual walk-through of runbook steps against integration test scenarios.

### Phase 5: Final QA Closeout

Deliverables:

- M2 closeout evidence package.

Implementation tasks:

- Execute `./gradlew qa`.
- Capture and reference green test evidence in PR description.
- Update `docs/PLANS.md` M2 to `DONE` only when all criteria are satisfied.

Validation:

- CI and local QA pass.

## 7. Concrete File Plan

Likely new files:

- `docs/RUNBOOK.md` (or recovery section in existing operations docs)

Likely updated files:

- `src/main/java/com/sky/synome/checkpoint/CheckpointService.java`
- `src/main/java/com/sky/synome/checkpoint/RecoveryOrchestrator.java`
- `src/main/java/com/sky/synome/api/ErrorMapper.java`
- `src/test/java/com/sky/synome/checkpoint/*.java`
- `src/test/java/com/sky/synome/api/CheckpointApiContractTest.java`
- `docs/PLANS.md`

## 8. Recommended Delivery Slices

1. PR1: Failure matrix + structured diagnostics normalization.
2. PR2: Error contract hardening + failure-path integration tests.
3. PR3: Runbook docs + full QA + M2 status update.

## 9. Risks and Mitigations

1. Risk: log message churn breaks operator scripts.
- Mitigation: use stable keys and document any changed fields.

2. Risk: failure-path tests are brittle.
- Mitigation: assert invariant outcomes and signature fragments, not full free-text logs.

3. Risk: hardening is declared complete without enough operator guidance.
- Mitigation: require runbook review against failure matrix as an exit gate.

## 10. Exit Checklist

- [x] Failure matrix and hardening checklist implemented.
- [x] Structured diagnostics validated.
- [x] Error contract hardening merged with tests.
- [x] Runbook diagnostics section published.
- [x] `./gradlew qa` green.
- [x] `docs/PLANS.md` M2 moved to `DONE`.
