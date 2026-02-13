# ADR-0001: Changeset Atomicity via Reservation + Compensating Rollback

## Status

Accepted (2026-02-13)

## Context

The engine applies changesets to a long-lived in-memory Drools session and persists the outcome to
`changeset_log`. A failure in log persistence after in-memory apply can leave observable partial
state. This violates the remediation requirement for all-or-nothing changeset behavior.

## Decision

Use a reservation/finalization write protocol in `changeset_log` with a compensating rollback in
the engine session:

1. Reserve a log row before applying a changeset (`changeset_id`, payload, checksum).
2. Snapshot current base facts from the fact registry/session.
3. Apply entries and fire rules.
4. Finalize the reserved row with success metadata and replay payload.
5. If apply/finalize fails:
   - rebuild the in-memory session from the pre-apply snapshot,
   - cancel the reserved log row,
   - rethrow the original failure.

## Consequences

- Success cannot be acknowledged without a durable reserved log row.
- Failures in apply/finalization do not leave persistent partial-success semantics.
- Recovery behavior is deterministic for the base-fact state managed by the registry snapshot.
- Cost: extra snapshot/rebuild overhead on each write and additional log-layer protocol complexity.
