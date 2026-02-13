# M3 Execution Plan: CEP Runtime and Replay-Window Determinism

## 1. Objective

Complete CEP runtime behavior so event-time semantics are deterministic in both live processing and recovery replay.

## 2. Current Baseline (2026-02-13)

Implemented:

- EVENT/EMIT contract validation in changeset validator/DTO.
- Event insertion into named entry points from changeset processing.
- Pseudo-clock progression to event timestamp during apply/replay.
- Basic event rule (`Transaction` on `transactions` entry point) and contract tests.

Missing:

- Replay-window computation based on temporal rule semantics.
- Recovery replay that rehydrates required in-window events even when they predate checkpoint sequence.
- Explicit event management/query APIs.
- CEP-focused determinism test matrix.

## 3. Scope

In scope:

- Clock mode management for startup replay vs live processing.
- Replay-window policy and event-tail replay selection.
- Event metadata model needed to avoid full payload scans for every recovery.
- Event inspection APIs (MVP read-only first).
- CEP integration tests across restart boundaries.

Out of scope:

- Full provenance graph enrichment (M4).
- Rule hot swap orchestration (M5).

## 4. Definition of Done

1. Replay window is computed deterministically from configured/event-rule constraints.
2. Recovery replays required event history even when event rows are before checkpoint `sequence_num`.
3. Clock progression behavior is explicit and tested across bootstrap, replay, and live modes.
4. Event APIs expose replay-relevant event history for diagnostics.
5. CEP integration test suite proves deterministic behavior for window boundaries and restarts.

## 5. Core Design Decisions

1. Event replay source:
- Keep `changeset_log` as source of truth.
- Add an event index projection table for efficient replay-window queries.

2. Replay boundary shape:
- Sequence boundary remains valid for base facts.
- Events use a time boundary (`event_timestamp >= replayWindowStart`) and are replayed in deterministic order.

3. Clock authority:
- Introduce a single `EngineClockManager` abstraction.
- For replay: advance to each event timestamp.
- For live: monotonic advance only (never rewind).

4. Window configuration:
- Start with explicit config-driven max replay window duration.
- Optionally add DRL-derived window introspection in a follow-up increment.

## 6. Work Breakdown Structure

### Phase 0: CEP Contract and Config

Deliverables:

- Explicit CEP config section with replay-window controls.

Implementation tasks:

- Extend `EngineConfig` with:
- `event-replay-window`
- `event-replay-enabled`
- `event-entrypoints` (optional allow-list)
- Add defaults in `application.properties`.
- Document config in architecture or runbook docs.

Validation:

- Config mapping tests for defaults and overrides.

### Phase 1: Event Projection Store

Deliverables:

- Efficient query path for replayable events by timestamp and sequence.

Implementation tasks:

- Add migration for `changeset_events` projection table with indexes on:
- `event_timestamp`
- `sequence_num`
- `entry_point`
- Persist event projection rows on finalized changesets containing EVENT/EMIT entries.
- Include `changeset_id`, `sequence_num`, `entry_point`, `fact_type`, `event_timestamp`, serialized event payload.

Validation:

- Repository integration tests verify inserts and index-backed reads.

### Phase 2: Clock Manager and Replay Coordinator

Deliverables:

- Centralized clock behavior for apply and replay flows.

Implementation tasks:

- Introduce `EngineClockManager` in `core/`.
- Refactor `ChangesetProcessor` event timestamp logic to call clock manager.
- Add replay coordinator in `changeset/` that:
- computes replay window start time from checkpoint clock and config
- loads replayable events from projection store
- replays in deterministic order

Validation:

- Unit tests for monotonic clock behavior and replay ordering.

### Phase 3: Recovery Integration

Deliverables:

- Recovery orchestration that replays both fact tail and required event window.

Implementation tasks:

- Update `RecoveryOrchestrator` sequence:
- restore checkpoint snapshot
- replay finalized FACT tail by sequence
- replay in-window EVENT set by timestamp/sequence ordering
- run convergence fire
- Ensure deduplication between fact replay and event replay paths.

Validation:

- Integration tests:
- checkpoint with no event tail
- checkpoint with event tail after boundary
- checkpoint where required events are before boundary but inside window
- checkpoint where old expired events are excluded

### Phase 4: Event APIs

Deliverables:

- Event diagnostics endpoints.

Implementation tasks:

- Add `EventResource` endpoints:
- `GET /api/v1/events`
- `GET /api/v1/events/{changesetId}`
- Filter by `entryPoint`, `from`, `to`, `limit`, `offset`.
- Add DTOs for event summaries.

Validation:

- API contract tests for filtering and pagination.

### Phase 5: CEP Determinism Test Matrix

Deliverables:

- CEP behavior locked by integration coverage.

Implementation tasks:

- Add end-to-end tests for:
- temporal window expiration
- out-of-order event arrival constraints
- replay determinism after restart
- identical stream => identical derived outputs

Validation:

- Determinism tests pass repeatedly in CI without flakiness.

## 7. Concrete File Plan

Likely new files:

- `src/main/resources/db/migration/V00x__changeset_events.sql`
- `src/main/java/com/sky/synome/core/EngineClockManager.java`
- `src/main/java/com/sky/synome/changeset/EventReplayService.java`
- `src/main/java/com/sky/synome/changeset/ChangesetEventStore.java`
- `src/main/java/com/sky/synome/api/EventResource.java`
- `src/main/java/com/sky/synome/api/dto/EventSummaryResponse.java`
- `src/test/java/com/sky/synome/changeset/EventReplayServiceTest.java`
- `src/test/java/com/sky/synome/api/EventApiContractTest.java`

Likely updated files:

- `src/main/java/com/sky/synome/changeset/ChangesetProcessor.java`
- `src/main/java/com/sky/synome/checkpoint/RecoveryOrchestrator.java`
- `src/main/java/com/sky/synome/config/EngineConfig.java`
- `src/main/resources/application.properties`
- `docs/PLANS.md`

## 8. Recommended Delivery Slices

1. PR1: CEP config + projection table + event persistence hooks.
2. PR2: Clock manager + replay service core with unit tests.
3. PR3: Recovery integration and end-to-end replay tests.
4. PR4: Event APIs + contract tests + docs updates.

## 9. Risks and Mitigations

1. Risk: replaying events twice due to overlap between changeset replay and event replay.
- Mitigation: separate fact/event replay phases and explicit dedupe key (`changeset_id`, `entry_point`, `event_timestamp`).

2. Risk: large event history slows startup.
- Mitigation: indexed event projection table and bounded replay window config.

3. Risk: temporal tests flaky due to clock assumptions.
- Mitigation: always use pseudo-clock assertions and deterministic timestamps in tests.

## 10. Exit Checklist

- [ ] Replay-window policy implemented and documented.
- [ ] Event projection store and replay service merged.
- [ ] Recovery uses window-aware event replay.
- [ ] Event APIs and contract tests merged.
- [ ] CEP determinism suite green.
- [ ] `docs/PLANS.md` M3 moved to `DONE`.
