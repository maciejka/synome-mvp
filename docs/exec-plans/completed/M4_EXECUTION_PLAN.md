# M4 Execution Plan: Provenance Graph and Explanation APIs

## 1. Objective

Deliver persisted, queryable provenance with accurate multi-step explanations for derived facts.

## 2. Current Baseline (2026-02-13)

Implemented:

- Runtime listener (`DerivationTracker`) captures inserted/retracted facts during changeset apply.
- Changeset response returns lightweight derived fact summaries.
- Provenance tables exist in schema (`fact_provenance`, `fact_modifications`).

Missing:

- Stable provenance identity model.
- Full activation context (agenda + rule inputs).
- Persisted derivation DAG and asynchronous write path.
- Explanation service and provenance REST endpoints.
- CEP-aware provenance tests.

## 3. Scope

In scope:

- Provenance capture from runtime and agenda listeners.
- In-memory graph model and persistence pipeline.
- Explain/query APIs with deterministic traversal.
- Backfill behavior for restart/replay boundaries (best-effort in MVP constraints).

Out of scope:

- UI rendering for provenance graphs.
- Cross-instance distributed provenance federation.

## 4. Definition of Done

1. Every derived fact has a stable provenance record with producing rule and input links.
2. Retractions/updates are persisted with causal context.
3. Explain API returns deterministic multi-hop chain from derived output to base facts/events.
4. Persistence is asynchronous and bounded; rule execution path is not blocked by DB writes.
5. Provenance behavior is covered by integration tests, including event-driven derivations.

## 5. Core Design Decisions

1. Stable IDs:
- `fact_id` is generated deterministically for base facts (`factKey`) and UUID-based for derived/event objects.
- Keep explicit mapping from `FactHandle` to `fact_id` in memory.

2. Capture strategy:
- Combine `RuleRuntimeEventListener` and `AgendaEventListener`.
- Use agenda activation data to resolve rule input fact IDs.

3. Graph model:
- Directed acyclic graph in MVP scope:
- node types: BASE_FACT, EVENT, DERIVED_FACT, RULE_ACTIVATION
- edge types: PRODUCED_BY, DEPENDS_ON, MODIFIED_BY, RETRACTED_BY

4. Persistence strategy:
- Append-only bounded queue with batched async flush.
- Backpressure policy: drop with error metric/log (never block changeset apply thread).

## 6. Work Breakdown Structure

### Phase 0: Provenance Contract and IDs

Deliverables:

- Provenance identity contract and node/edge schema mapping document.

Implementation tasks:

- Define provenance domain records in `provenance/` package.
- Add in-memory mapping service for `FactHandle -> fact_id`.
- Document deterministic id rules for base facts and replay behavior.

Validation:

- Unit tests for identity generation and mapping lifecycle.

### Phase 1: Listener Enrichment

Deliverables:

- Listener layer captures activation and object lifecycle context.

Implementation tasks:

- Add agenda listener to capture matched facts and rule activation ids.
- Extend runtime listener capture to include:
- insertion cause
- retraction cause
- update before/after snapshots
- Replace or evolve `DerivationTracker` into provenance event collector with explicit event types.

Validation:

- Unit tests asserting capture correctness for insert/update/delete/retract cases.

### Phase 2: In-Memory DAG and Explanation Core

Deliverables:

- Provenance graph service and traversal engine.

Implementation tasks:

- Implement DAG storage and lookup:
- node insert/upsert
- edge append
- chain traversal with cycle guards
- Implement explanation renderer:
- textual explanation
- structured chain payload for API consumers

Validation:

- Graph tests for branching derivations, shared dependencies, and retraction propagation.

### Phase 3: Async Persistence Pipeline

Deliverables:

- Batched non-blocking persistence to `fact_provenance` and `fact_modifications`.

Implementation tasks:

- Build asynchronous writer with configurable batch size and flush interval.
- Map in-memory provenance events to SQL writes via jOOQ.
- Add recovery-safe startup behavior for pending queue drain/clear policy.

Validation:

- Integration tests for:
- batch flush success
- transient DB failure and retry/backoff behavior
- bounded queue overflow policy

### Phase 4: Provenance REST API

Deliverables:

- Explain and query endpoints.

Implementation tasks:

- Add `ProvenanceResource` endpoints:
- `GET /api/v1/provenance/facts/{factId}`
- `GET /api/v1/provenance/facts/{factId}/explain`
- `GET /api/v1/provenance/facts/{factId}/impact`
- `GET /api/v1/provenance/search`
- Add DTOs for graph node/edge and explanation payloads.

Validation:

- API contract tests for success, not-found, and traversal depth controls.

### Phase 5: CEP-Aware Provenance Tests

Deliverables:

- End-to-end provenance assertions for event-derived facts.

Implementation tasks:

- Add integration scenarios combining:
- base facts + events
- temporal rule firing
- retraction after window expiration
- restart and replay consistency checks for persisted provenance snapshots

Validation:

- Provenance integration suite green in CI.

## 7. Concrete File Plan

Likely new files:

- `src/main/java/com/sky/synome/provenance/ProvenanceCollector.java`
- `src/main/java/com/sky/synome/provenance/ProvenanceGraph.java`
- `src/main/java/com/sky/synome/provenance/ProvenancePersistenceService.java`
- `src/main/java/com/sky/synome/provenance/ExplanationService.java`
- `src/main/java/com/sky/synome/api/ProvenanceResource.java`
- `src/main/java/com/sky/synome/api/dto/Provenance*.java`
- `src/test/java/com/sky/synome/provenance/*.java`
- `src/test/java/com/sky/synome/api/ProvenanceApiContractTest.java`

Likely updated files:

- `src/main/java/com/sky/synome/core/EngineSession.java`
- `src/main/java/com/sky/synome/core/DerivationTracker.java` (or replacement)
- `src/main/resources/application.properties`
- `docs/PLANS.md`

## 8. Recommended Delivery Slices

1. PR1: Provenance identity contract + listener enrichment.
2. PR2: In-memory DAG + explanation engine + unit tests.
3. PR3: Async persistence service + integration tests.
4. PR4: Provenance APIs + contract tests + docs updates.

## 9. Risks and Mitigations

1. Risk: high write volume overwhelms async persistence queue.
- Mitigation: bounded queue + batch flush tuning + overflow metrics and alerts.

2. Risk: inaccurate causal links when activation context is incomplete.
- Mitigation: agenda listener correlation ids and strict test fixtures for multi-rule chains.

3. Risk: graph traversal performance degrades with depth.
- Mitigation: traversal depth limits, pagination, and indexed persistence reads.

## 10. Exit Checklist

- [x] Provenance node/edge identity contract implemented.
- [x] Listener capture includes activation context.
- [x] In-memory graph + explanation service merged.
- [x] Async persistence and retry behavior verified.
- [x] Provenance API contract tests green.
- [x] CEP-aware provenance tests green.
- [x] `docs/PLANS.md` M4 moved to `DONE`.
