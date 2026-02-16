# Configuration Reference

This document covers runtime properties used by the current prototype.

## What

Primary settings are under the `engine.*` prefix (`EngineConfig`).

## Why

Most runtime guarantees (replay scope, lock behavior, scheduler cadence, security posture) are config-driven.

## How

## Core engine and locking

- `engine.rules-path` (default: `rules`)
- `engine.default-rules-file` (default: `bootstrap-rules.drl`)
- `engine.lock-timeout-ms` (default: `5000`)

`engine.lock-timeout-ms` is used by write-path, recovery, checkpoint creation, and rule swap lock acquisition.

## Checkpoint and recovery

- `engine.checkpoint-enabled` (default: `true`)
- `engine.checkpoint-scheduler-enabled` (default: `true`)
- `engine.checkpoint-interval` (default: `10m`)
- `engine.checkpoint-retain-count` (default: `20`)
- `engine.recovery-enabled` (default: `true`)

Behavior:

- Scheduled checkpoints run only when checkpointing and scheduler are both enabled.
- Retention pruning keeps newest `checkpoint-retain-count` entries.
- Recovery bootstrap runs at startup only when enabled.

## Event replay / CEP

- `engine.event-replay-enabled` (default: `true`)
- `engine.event-replay-window` (default: `30m`)
- `engine.event-entrypoints` (default: `transactions`)

Behavior:

- `EVENT/EMIT` entry points must be listed in `engine.event-entrypoints`.
- Recovery and hot swap replay events only inside the replay window.

## Provenance async pipeline

- `engine.provenance-queue-capacity` (default: `2000`)
- `engine.provenance-batch-size` (default: `100`)
- `engine.provenance-flush-interval` (default: `500ms`)
- `engine.provenance-retry-backoff` (default: `200ms`)

Behavior:

- Writes never block on provenance persistence.
- Queue overflow drops captures and logs an error.
- Flush retries once after backoff before dropping failed batch.

## Security

- `engine.security-enabled` (default: `true`)
- `engine.security-bootstrap-key` (default: empty)
- `engine.security-bootstrap-name` (default: `bootstrap-admin`)
- `engine.security-bootstrap-permissions` (default: all permissions)

Behavior:

- On startup, if security is enabled and bootstrap key is non-blank, key row is inserted/updated in `api_keys`.
- API keys are hashed with SHA-256 before persistence.

Profile defaults in `application.properties`:

- `%dev.engine.security-bootstrap-key=dev-local-api-key`
- `%test.engine.security-bootstrap-key=test-local-api-key`

## Operations and lifecycle

- `engine.shutdown-timeout` (default: `15s`)
- `engine.ops-stream-poll-interval` (default: `1s`)
- `engine.ops-stream-max-batch` (default: `50`)

Behavior:

- Shutdown finalization (checkpoint + provenance flush) is bounded by `engine.shutdown-timeout`.
- SSE streams poll DB/lifecycle state on the configured interval.

## Non-engine Quarkus settings used by the prototype

- `quarkus.flyway.migrate-at-start=true`
- `quarkus.smallrye-health.root-path=/q/health`
- `%dev` PostgreSQL datasource points to `localhost:5432/decision_engine`
- `%prod` enables JSON logging
