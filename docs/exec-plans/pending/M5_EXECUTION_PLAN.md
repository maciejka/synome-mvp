# M5 Execution Plan: Rule Hot Swap With Safe Rollback

## 1. Objective

Enable zero-downtime rule upgrades by atomically swapping the active session to a validated candidate ruleset with deterministic rollback.

## 2. Current Baseline (2026-02-13)

Implemented:

- Rule compiler exists and compiles current DRL at startup.
- `rule_versions` persistence schema exists.
- Checkpoint/recovery primitives exist and can be reused for rollback safety.
- Session lock provides single-writer guard.

Missing:

- Rule version lifecycle service (upload/validate/activate/deactivate).
- Candidate compatibility checks against current working-memory model.
- Hot-swap coordinator (build candidate, transfer state, converge, commit or rollback).
- APIs and tests for swap lifecycle.

## 3. Scope

In scope:

- Rule version CRUD + activation flow.
- Candidate compile and compatibility validation.
- Safe swap orchestration using pre-swap checkpoint.
- Rollback semantics on any failed stage.
- Integration tests for uninterrupted changeset service through swap.

Out of scope:

- Multi-tenant rule versioning.
- Blue/green multi-instance distributed rollouts.

## 4. Definition of Done

1. Candidate ruleset is compiled and validated before lock acquisition where possible.
2. Swap acquires session lock, creates pre-swap checkpoint, and performs atomic session replacement.
3. Base facts are transferred and in-window events replayed into candidate session.
4. On failure, runtime reverts to pre-swap checkpoint/session with no partial activation state.
5. Rule version activation state is persisted transactionally with swap outcome.

## 5. Core Design Decisions

1. Swap transaction boundary:
- Use orchestration-level transaction semantics:
- persist candidate metadata
- perform runtime swap
- persist activation/deactivation state
- if runtime swap fails, keep prior version active

2. Compatibility checks:
- Validate required fact types, fields, and entry points against current changeset contract.
- Fail activation for incompatible schemas.

3. State transfer:
- Transfer base facts from registry snapshot.
- Replay in-window events using M3 replay services.
- Re-fire rules for convergence before cutover.

4. Cutover model:
- Build candidate `KieSession` side-by-side.
- Perform pointer swap in `EngineSession` under lock.

## 6. Work Breakdown Structure

### Phase 0: Rule Version Service

Deliverables:

- Persistence service for rule version lifecycle.

Implementation tasks:

- Add `RuleVersionStore` and `RuleVersionService`.
- Support:
- upload candidate
- list/get versions
- mark active/inactive
- store compile logs and checksums

Validation:

- Repository/service tests for version state transitions.

### Phase 1: Candidate Validation Pipeline

Deliverables:

- Compile and compatibility checks with deterministic diagnostics.

Implementation tasks:

- Implement candidate compile workflow using existing `RuleCompiler`.
- Add compatibility validator for:
- expected fact types
- expected fields
- required event entry points
- Emit structured validation report in DB and API response.

Validation:

- Unit tests for compile failure and compatibility mismatch cases.

### Phase 2: Hot-Swap Coordinator

Deliverables:

- Runtime orchestrator that executes safe swap protocol.

Implementation tasks:

- Add `RuleHotSwapService` with stages:
- acquire lock
- create pre-swap checkpoint
- build candidate session
- transfer base facts
- replay in-window events
- convergence fire
- atomic swap + persist active version
- Implement rollback path:
- restore previous session/checkpoint on any stage failure
- keep previous version active

Validation:

- Integration tests for successful swap and forced failures at each stage.

### Phase 3: API Surface

Deliverables:

- Rule version and hot-swap endpoints.

Implementation tasks:

- Add `RuleVersionResource` endpoints:
- `POST /api/v1/rules/validate`
- `POST /api/v1/rules/upload`
- `POST /api/v1/rules/{versionId}/activate`
- `POST /api/v1/rules/{versionId}/rollback`
- `GET /api/v1/rules`
- `GET /api/v1/rules/active`
- Add DTOs for validation report and swap outcome.

Validation:

- API contract tests for happy path and failure path payloads.

### Phase 4: Operational Hardening and QA

Deliverables:

- End-to-end safety proof for swap flow.

Implementation tasks:

- Add lifecycle tests:
- concurrent write attempts during swap lock
- swap under active event traffic
- rollback under injected failures
- restart after swap with recovery consistency
- Run `./gradlew qa`.

Validation:

- CI green with repeatable swap lifecycle tests.

## 7. Concrete File Plan

Likely new files:

- `src/main/java/com/sky/synome/rules/RuleVersionStore.java`
- `src/main/java/com/sky/synome/rules/RuleVersionService.java`
- `src/main/java/com/sky/synome/rules/RuleCompatibilityValidator.java`
- `src/main/java/com/sky/synome/rules/RuleHotSwapService.java`
- `src/main/java/com/sky/synome/api/RuleVersionResource.java`
- `src/main/java/com/sky/synome/api/dto/Rule*.java`
- `src/test/java/com/sky/synome/rules/*.java`
- `src/test/java/com/sky/synome/api/RuleVersionApiContractTest.java`

Likely updated files:

- `src/main/java/com/sky/synome/core/EngineSession.java`
- `src/main/java/com/sky/synome/checkpoint/CheckpointService.java`
- `src/main/resources/application.properties`
- `docs/PLANS.md`

## 8. Recommended Delivery Slices

1. PR1: Rule version persistence/service + compile report API.
2. PR2: Compatibility validator + validation contract tests.
3. PR3: Hot-swap coordinator + rollback integration tests.
4. PR4: Activation/rollback APIs + lifecycle hardening.

## 9. Risks and Mitigations

1. Risk: swap lock holds too long and impacts write throughput.
- Mitigation: precompile candidate outside lock and keep lock section minimal.

2. Risk: candidate rules compile but diverge semantically.
- Mitigation: add smoke simulation tests on copied base state before cutover.

3. Risk: rollback path is under-tested.
- Mitigation: failure-injection tests at each swap stage as release gate.

## 10. Exit Checklist

- [ ] Rule version lifecycle service merged.
- [ ] Candidate compile/compatibility checks merged.
- [ ] Hot-swap coordinator with rollback merged.
- [ ] Rule APIs and contract tests merged.
- [ ] Full swap lifecycle tests and `./gradlew qa` green.
- [ ] `docs/PLANS.md` M5 moved to `DONE`.
