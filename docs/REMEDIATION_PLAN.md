# Remediation Plan for Architecture Review Findings

## Purpose

This plan addresses findings from the repository architecture review and closes related testing gaps before deeper feature phases continue.

Scope: write-path correctness, contract alignment, error schema consistency, and test reliability.

## Objectives

1. Enforce strict idempotency on `changeset_id`.
2. Enforce all-or-nothing changeset atomicity.
3. Align API contract across docs, DTOs, validator, and processor behavior.
4. Align error envelope and stable error codes.
5. Improve test reliability and close missing regression coverage.

## Workstream A: Strict Idempotency

### A1. Data model changes

- Add response payload persistence to `changeset_log` (JSONB).
- Keep request checksum as canonical payload identity.

Deliverables:

- Flyway migration adding `response_payload`.
- `ChangesetLog` APIs for:
  - lookup by `changeset_id`
  - append with response payload
  - idempotent replay read path

### A2. Processor behavior

- Perform duplicate check before applying entries.
- If duplicate and checksum matches: return stored response.
- If duplicate and checksum differs: raise domain conflict error.

Deliverables:

- Processor preflight branch for duplicate handling.
- New conflict exception type with deterministic code mapping.

### A3. Idempotency tests

- `duplicateSamePayloadReturnsOriginalResponse()`
- `duplicateDifferentPayloadReturns409()`
- `duplicateRetryDoesNotMutateWorkingMemoryAgain()`

## Workstream B: Atomicity Guarantees

### B1. Apply/log coordination

- Ensure apply path cannot acknowledge success without durable log entry.
- Ensure failure cannot leave observable partial commit semantics.

Design options to choose (documented decision required):

1. Two-phase approach with pre-log reservation and finalize.
2. Session action + DB transaction wrapper with compensating rollback strategy.

Deliverables:

- Chosen design documented as a short ADR in `docs/`.
- Processor and log layer refactor implementing chosen design.

### B2. Atomicity tests

- Fault injection in log append path -> verify no partial acknowledged state.
- Fault injection in apply path -> verify no partial registry/session leftovers.

## Workstream C: Contract Alignment (FACT/EVENT)

### C1. DTO and validation contract

- Implement explicit rules:
  - `FACT/UPSERT`: `factKey`, `factType`, `data` required.
  - `FACT/DELETE`: `factKey` required.
  - `EVENT/EMIT`: `entryPoint`, `factType`, ISO timestamp, `data` required.
- Reject invalid `kind/action` combinations.

Deliverables:

- DTO changes for timestamp type and parsing behavior.
- Validator rule matrix implementation.
- Architecture examples updated to exact runtime contract.

### C2. Processor contract

- Honor `kind` and `entryPoint` for events.
- Advance clock from event timestamp in replay/runtime semantics where applicable.

Deliverables:

- Processor branching by `kind/action` contract.
- Event insertion via named entry point.

### C3. Contract tests

- Validation tests for each allowed and forbidden combination.
- API-level tests confirming error details for malformed payloads.

## Workstream D: Error Schema and Codes

### D1. Unified error envelope

- Standardize on one envelope shape and keep it stable.
- Introduce explicit codes for:
  - validation failure
  - lock timeout
  - duplicate payload mismatch
  - internal error

Deliverables:

- Updated `ErrorMapper`.
- Updated `ErrorResponse` DTO and API examples.

### D2. Error contract tests

- Snapshot-style tests for error JSON shape.
- Verify each major failure path maps to expected code and HTTP status.

## Workstream E: Runtime/Plan Consistency

### E1. Session lifecycle parity

- Correct plan status claims to match code reality.
- If `fireUntilHalt()` is target for this milestone, implement with safe write integration.

Deliverables:

- `docs/PLANS.md` reflects real status.
- Either:
  - implement `fireUntilHalt()` + tests, or
  - explicitly defer to later milestone.

### E2. Config consistency

- Replace hardcoded lock timeout with typed config value.

Deliverables:

- Processor uses `EngineConfig.lockTimeoutMs()`.
- Unit test for custom timeout configuration behavior.

## Workstream F: Testing Reliability

### F1. Integration test infra

- Introduce Testcontainers-based PostgreSQL integration profile.
- Remove localhost dependency from integration tests.

Deliverables:

- Testcontainers dependency and reusable DB test setup.
- CI-compatible integration test execution.

### F2. Regression suite additions

Minimum required new tests:

1. Idempotency replay semantics.
2. Duplicate mismatch conflict behavior.
3. Atomicity under injected failures.
4. Event contract validation matrix.
5. Error envelope consistency tests.

## Execution Order

1. Workstream A (idempotency) and D (error code plumbing) in parallel.
2. Workstream B (atomicity) next.
3. Workstream C (contract alignment).
4. Workstream E (lifecycle/config parity).
5. Workstream F (test infra and regression sweep).

## Acceptance Criteria (Milestone Gate)

All must pass:

1. Duplicate identical payload is replayed, not re-applied.
2. Duplicate different payload returns `409` with stable error code.
3. No partial commit behavior on forced failures.
4. API examples and validation behavior are consistent.
5. Error envelope and code mapping are stable and tested.
6. Integration tests run without external localhost PostgreSQL dependency.

## Suggested Task Breakdown

- [x] Task 1: Migration + log layer idempotency read/write APIs.
- [x] Task 2: Processor preflight duplicate semantics.
- [x] Task 3: Conflict exception + error mapper updates.
- Task 4: Atomicity mechanism implementation.
- Task 5: DTO/validator/processor contract alignment for events.
- Task 6: Lock timeout config wiring.
- Task 7: Testcontainers integration profile.
- Task 8: New regression and contract tests.
- Task 9: Final docs synchronization pass.
