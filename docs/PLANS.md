# Implementation Plan and Status

## Document Role

This file is the source of truth for current implementation status and delivery sequencing.

- `docs/ARCHITECTURE.md` defines the target end-state.
- This file tracks what is implemented now and what remains.
- Snapshot date: 2026-02-13.

## Status Legend

- `DONE`: implemented and verified by tests.
- `PARTIAL`: implemented baseline exists but guarantees, coverage, or hardening are still incomplete.
- `PLANNED`: not implemented beyond trivial scaffolding.
- `BLOCKED`: cannot proceed until prerequisite is done.

## Current Snapshot (2026-02-13)

### Delivered

- Phase 0 foundation is complete (build, Quarkus app, Flyway, PostgreSQL, jOOQ producer).
- M1 stabilization is complete (strict idempotency, atomicity rollback, FACT/EVENT validation contract, lock-timeout config, and API/error contract tests).
- M2 checkpoint/recovery runtime baseline is delivered:
  - Checkpoint serializer/store/service, recovery startup orchestration, replay mode, scheduled/manual checkpoint triggers, and checkpoint REST endpoints are implemented.
  - Recovery determinism and crash-recovery integration tests exist.
  - M2 closeout hardening is complete (structured diagnostics, failure-path coverage, and operations runbook).
- M3 CEP runtime is complete:
  - Event projection persistence (`changeset_events`) and replay-window event rehydration are implemented.
  - Recovery now replays FACT tail and in-window EVENT history deterministically.
  - Event diagnostics APIs (`GET /api/v1/events`, `GET /api/v1/events/{changesetId}`) are implemented.
  - CEP replay-window determinism tests cover restart boundaries and event window filtering.
- M4 foundations exist:
  - Runtime derivation/retraction listener exists (`DerivationTracker`) and is wired into the session.
- M6 foundations exist:
  - Read/query APIs for changesets and facts exist.
  - Base health endpoint support is wired via Quarkus SmallRye Health.

### Remaining gaps for next milestones

1. Provenance is currently transient/in-memory only; no persisted DAG or explanation API.
2. Rule hot swap orchestration is not implemented.
3. API authentication/authorization, SSE operations surface, and graceful shutdown lifecycle are not implemented.

### Execution Plan Files

- Completed baselines:
  - `docs/exec-plans/completed/M2_EXECUTION_PLAN.md`
  - `docs/exec-plans/completed/M2_CLOSEOUT_EXECUTION_PLAN.md`
  - `docs/exec-plans/completed/M3_EXECUTION_PLAN.md`
- Remaining milestones index: `docs/exec-plans/README.md`.
- Active detailed plans:
  - `docs/exec-plans/pending/M4_EXECUTION_PLAN.md`
  - `docs/exec-plans/pending/M5_EXECUTION_PLAN.md`
  - `docs/exec-plans/pending/M6_EXECUTION_PLAN.md`
- Runbook: `docs/RUNBOOK.md`

## Milestones

### M1: Phase 1 Stabilization
Status: DONE

Goal: harden write-path contracts before deeper lifecycle features.

- [x] Implement strict idempotency contract.
- [x] Guarantee changeset atomicity.
- [x] Align validator + DTO + examples for FACT/EVENT contract.
- [x] Align runtime error schema with documented contract.
- [x] Use config-driven lock timeout (remove hardcoded timeout).
- [x] Add regression tests for all above.

Exit criteria:

- Duplicate retry with identical payload returns original result without re-apply.
- Duplicate retry with different payload returns `409`.
- No partial state when apply/logging fails.
- API docs/examples match runtime validation behavior.

### M2: Checkpoints and Recovery
Status: DONE

Goal: deterministic restart from latest checkpoint plus ordered replay, without manual repair.

- [x] Checkpoint schema and domain contract.
- [x] MessagePack+LZ4 serialization for facts/registry.
- [x] Checkpoint store and service (write/read/list/latest + retention hook).
- [x] Recovery bootstrap integrated into startup path.
- [x] Replay-safe apply path (no duplicate logging).
- [x] Manual + scheduled checkpoint creation.
- [x] Checkpoint REST endpoints (create/list/get/latest/by-id).
- [x] Deterministic restart and crash-recovery integration tests.
- [x] Hardening closeout checklist and runbook-level diagnostics.

Exit criteria:

- Restarted engine converges to same logical state as uninterrupted execution for the same stream.
- Recovery replays only finalized rows and respects replay boundary semantics.
- Corrupt/incompatible checkpoint payload fails fast with explicit diagnostics.
- Idempotency contract remains intact after restart.

### M3: CEP Runtime
Status: DONE

Goal: deterministic event-time behavior in live execution and recovery replay.

- [x] EVENT/EMIT entry-point insertion path in changeset processing.
- [x] Pseudo-clock advancement from event timestamps.
- [x] Dedicated clock manager with explicit live/replay mode boundaries.
- [x] Replay-window computation from temporal rule/event contracts.
- [x] Recovery replay semantics for window-bounded events.
- [x] Event query/inspection APIs.
- [x] CEP determinism integration tests (window boundaries, out-of-order events, restart behavior).

Exit criteria:

- Event expiration/window behavior is deterministic in replay and runtime.
- Recovery replays all required in-window events and only required in-window events.

### M4: Provenance and Explanation
Status: PARTIAL

Goal: produce explainable, queryable derivation chains for derived facts.

- [x] Runtime derivation/retraction listener baseline (`DerivationTracker`).
- [ ] Agenda listener + activation context capture.
- [ ] In-memory provenance DAG with stable identifiers and relationship model.
- [ ] Explanation rendering service.
- [ ] Async persistence/export to provenance tables.
- [ ] Provenance REST API.
- [ ] Provenance tests (including CEP-driven derivations).

Exit criteria:

- Explain endpoint returns accurate multi-step derivations and supports persisted history lookup.

### M5: Rule Hot Swap
Status: PARTIAL

Goal: upgrade active rules without downtime and with deterministic rollback.

- [x] Rule version persistence schema exists (`rule_versions`).
- [ ] Candidate compile + compatibility checks.
- [ ] Pre-swap checkpoint and rollback path.
- [ ] Base-fact transfer + in-window event replay into candidate session.
- [ ] Atomic session swap + active version persistence.
- [ ] Hot-swap integration tests.

Exit criteria:

- Rule swap occurs without downtime and with safe rollback on failure.

### M6: API Hardening and Operations
Status: PARTIAL

Goal: secure, operable API surface with lifecycle guarantees.

- [x] Core query/read resources (changesets/facts).
- [x] Base health endpoint support.
- [ ] API key authentication filter.
- [ ] Permission matrix/authorization model.
- [ ] SSE resources for operational streams.
- [ ] Derived fact/event APIs.
- [ ] Graceful shutdown with final checkpoint and flush.
- [ ] Full lifecycle and security contract tests.

Exit criteria:

- Full API surface is secured and operational lifecycle behavior is validated.

## Dependency Graph

```text
M1 (DONE)
  -> M2 closeout
M2 closeout
  -> M3 (CEP completion)
  -> M6 security/ops baseline
M3 + M4
  -> M5 (Hot Swap)
M5 + M6
  -> release readiness
```

## Tracking Notes

- Do not mark a milestone `DONE` unless its exit criteria are covered by automated tests.
- Keep this file aligned with implemented behavior, not planned intent.
