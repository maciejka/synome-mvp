# Current Status and Near-Term Plan

## Document Role

This file is the source of truth for current implementation status and the immediate backlog.

Snapshot date: 2026-02-16.

## Current Prototype Status

### Implemented

- Stateful single-session engine with single-writer lock.
- Strict idempotent changeset processing with atomic rollback on failure.
- FACT and EVENT contract validation with configured entry-point allowlist.
- PostgreSQL-backed changeset log and event projection (`changeset_events`).
- Checkpoint creation, retention, restore, and startup recovery orchestration.
- Replay of finalized FACT tail and in-window events.
- Provenance capture, explainability graph traversal, and async persistence.
- Rule version validate/upload/list/activate/rollback with safe hot swap.
- API key authentication and permission-based authorization for `/api/v1/**`.
- Operational SSE streams (changesets/checkpoints/recovery).
- Readiness checks and controlled shutdown finalization.

### Known constraints (prototype)

- Single logical tenant and single engine runtime instance.
- API key management is DB-driven; no external IAM integration.
- Provenance persistence is best-effort async (queue overflow drops captures).
- Event replay is bounded by fixed configured window, not rule-introspection derived.

## Near-Term Plan

1. Documentation hardening
- Keep architecture/API/config/runbook aligned with code in every behavior change.
- Keep historical execution plans archived but out of primary onboarding flow.

2. Productization backlog
- Multi-tenant/runtime partitioning strategy.
- Externalized key management and key rotation workflows.
- Expanded operational telemetry and SLO-oriented dashboards.
- Throughput/performance characterization under sustained load.

## Tracking Rules

- Do not mark behavior as implemented without automated test coverage.
- Update docs in the same PR as API/config/schema/runtime changes.
- Historical implementation plans remain in `docs/exec-plans/completed/` for reference only.
