# Execution Plans Index

This directory tracks detailed execution plans for each milestone in `docs/PLANS.md`.

## Completed

- `docs/exec-plans/completed/M2_EXECUTION_PLAN.md` - historical implementation plan used to deliver the M2 runtime baseline.
- `docs/exec-plans/completed/M2_CLOSEOUT_EXECUTION_PLAN.md` - hardening closeout plan that completed M2 diagnostics, failure-path tests, and runbook deliverables.
- `docs/exec-plans/completed/M3_EXECUTION_PLAN.md` - delivered CEP replay-window determinism, event projection replay, and event diagnostics APIs.
- `docs/exec-plans/completed/M4_EXECUTION_PLAN.md` - delivered full provenance graph, async persistence, explanation traversal, and provenance APIs.
- `docs/exec-plans/completed/M5_EXECUTION_PLAN.md` - delivered rule version lifecycle APIs plus safe runtime hot swap with rollback.
- `docs/exec-plans/completed/M6_EXECUTION_PLAN.md` - delivered API-key authz, ops streams, readiness/shutdown lifecycle controls, and security/lifecycle tests.

## Pending / Active

- `none`

## Usage Guidance

- Keep status authority in `docs/PLANS.md`; keep implementation detail in these plan files.
- Update plan checklists when scope shifts; never silently drop scope.
- Do not move a milestone to `DONE` without matching automated coverage and passing `./gradlew qa`.
