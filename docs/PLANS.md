# Implementation Plan and Status

## Document Role

This file is the source of truth for current implementation status and delivery sequencing.

- `docs/ARCHITECTURE.md` defines the target end-state.
- This file tracks what is implemented now and what is next.
- Snapshot date: 2026-02-13.

## Status Legend

- `DONE`: implemented and verified by tests.
- `PARTIAL`: implemented but missing contract guarantees/tests.
- `PLANNED`: not implemented.
- `BLOCKED`: cannot proceed until prerequisite is done.

## Current Snapshot (2026-02-13)

### Delivered

- Phase 0 foundation is complete (build, Quarkus app, Flyway, PostgreSQL, jOOQ producer).
- Phase 1 stabilization hardening is delivered:
  - Engine session bootstraps with DRL compilation.
  - Strict idempotency implemented (`changeset_id` replay + payload mismatch conflict).
  - Changeset atomicity implemented with reservation/finalize + compensating rollback.
  - FACT/EVENT contract enforcement implemented in DTO/validator/processor.
  - Stable conflict code mapped (`DUPLICATE_CHANGESET_PAYLOAD_MISMATCH`).
  - Lock timeout reads typed config (`engine.lock-timeout-ms`).
  - Regression + API contract coverage added for idempotency, atomicity, validation, and errors.
  - Test resource bootstraps PostgreSQL via Testcontainers, with localhost fallback when Docker API
    compatibility prevents container startup.

### Remaining gaps for next milestones

1. `fireUntilHalt()` is still not part of the active write-path runtime.
2. Recovery/checkpoint runtime (beyond DB schema), CEP replay window, and provenance phases remain unimplemented.

## Milestones

### M1: Phase 1 Stabilization (Must complete before Phase 2)
Status: DONE

Goal: harden existing write path and contracts before checkpoint/replay work.

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

### M2: Checkpoints and Recovery (Phase 2)
Status: PARTIAL (core runtime and API implemented; hardening and full QA gate still pending)

Goal: deterministic restart from latest checkpoint plus ordered replay, without manual repair.
Detailed execution plan: `docs/exec-plans/completed/M2_EXECUTION_PLAN.md`.

- [x] Checkpoint schema groundwork exists (`V002__checkpoints.sql`).
- [x] Define checkpoint domain contract (payload schema version + metadata contract + consistency validation).
- [x] Fact serializer/deserializer (MessagePack + LZ4) for base facts and fact registry payloads.
- [x] Checkpoint store (write, latest-read, point-read, retention pruning hook).
- [x] Recovery bootstrap sequence integrated into startup path.
- [x] Changeset replay after checkpoint (`sequence_num > checkpoint.sequence_num`, strict ordering).
- [x] Recovery-safe apply path (replay mode that does not re-log already finalized changesets).
- [x] Checkpoint trigger strategy (manual endpoint plus configurable periodic checkpointing).
- [x] Checkpoint API endpoints (create/list/get/latest/by-id).
- [ ] Recovery observability (add explicit metrics; logs are in place).
- [x] Round-trip, deterministic restart, and crash-recovery integration tests.

Exit criteria:

- Restarted engine converges to same logical state as uninterrupted execution for the same changeset stream.
- Recovery replays only finalized rows and only rows after the checkpoint sequence boundary.
- Checkpoint payload excludes events and derived facts; those are replayed/re-derived correctly.
- Corrupt/incompatible checkpoint payload fails fast with explicit diagnostics.
- Idempotency contract remains intact after restart (duplicate same payload replays stored response, mismatch returns `409`).

### M3: CEP Runtime (Phase 3)
Status: PLANNED

- [ ] Clock manager and event timestamp progression.
- [ ] Event entry-point insertion path.
- [ ] Replay window computation.
- [ ] Replay semantics for event windows.
- [ ] Event APIs.
- [ ] CEP integration tests.

Exit criteria:

- Event expiration/window behavior is deterministic in replay and runtime.

### M4: Provenance and Explanation (Phase 4)
Status: PLANNED

- [ ] Agenda + runtime provenance listeners.
- [ ] In-memory derivation graph.
- [ ] Explanation rendering.
- [ ] Async persistence and export.
- [ ] Provenance REST API.
- [ ] Provenance tests (including CEP context).

Exit criteria:

- Explain endpoint returns accurate multi-step derivations.

### M5: Rule Hot Swap (Phase 5)
Status: PLANNED

- [ ] Candidate compile + compatibility checks.
- [ ] Pre-swap checkpoint and rollback path.
- [ ] Base fact transfer + event replay into new session.
- [ ] Atomic session swap and version persistence.
- [ ] Hot swap integration tests.

Exit criteria:

- Rule swap occurs without downtime and with safe rollback.

### M6: API Hardening and Operations (Phase 6)
Status: PLANNED

- [ ] API key auth filter + permission matrix.
- [ ] Query and SSE resources.
- [ ] Health/readiness endpoints.
- [ ] Derived fact APIs.
- [ ] Graceful shutdown with final checkpoint and flush.
- [ ] Full lifecycle test.

Exit criteria:

- Full API surface secured and operational lifecycle validated.

## Dependency Graph

```text
M1 (Phase 1 Stabilization)
  -> M2 (Checkpoints/Recovery)
  -> M3 (CEP)
  -> M4 (Provenance)
M2 + M3 + M4
  -> M5 (Hot Swap)
M5
  -> M6 (API Hardening/Operations)
```

## Tracking Notes

- Do not mark an item `DONE` unless covered by automated tests.
- Keep this file in sync with actual code behavior, not intended behavior.
